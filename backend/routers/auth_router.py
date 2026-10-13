# Copyright (c) 2026 Tory_zy Tu

"""认证相关接口：明文密码永不上传——客户端本地派生验证材料，
登录/改密以 HMAC 证明 + 一次性挑战完成校验"""
import base64
import secrets
import time
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from database import get_db
from schemas import (
    UserProfile, UserUpdate, ApiResponse,
    RegisterRequest, LoginChallengeRequest, LoginRequest, ChangePasswordRequest,
    ScreenAspectRequest, ThresholdRequest, TimezoneRequest, ClockOffsetRequest,
)
from auth import (
    get_current_user, issue_owner_token,
    register_user, login_user, change_login_password,
)
from models import User, FamilyBinding, EncryptedBlob, EncryptedMedia

router = APIRouter(prefix="/api/auth", tags=["认证"])

# 登录挑战（内存存储，开发阶段；生产应入 Redis/DB）：用户id -> {challenge, expires_at}
_login_challenges: dict = {}
LOGIN_CHALLENGE_TTL_SECONDS = 300


def _take_challenge(user_code: str) -> str:
    """取出并消费一次性登录挑战（Base64）；缺失或过期抛 400"""
    record = _login_challenges.get(user_code)
    if not record or datetime.utcnow() > record["expires_at"]:
        _login_challenges.pop(user_code, None)
        raise HTTPException(status_code=400, detail="挑战已过期，请重试")
    _login_challenges.pop(user_code, None)
    return base64.b64encode(record["challenge"]).decode("ascii")


@router.post("/login-challenge", response_model=ApiResponse)
def login_challenge(body: LoginChallengeRequest, db: Session = Depends(get_db)):
    """登录挑战：返回密码盐与一次性挑战。用户不存在时返回随机盐+挑战（与正常流程无差别，防枚举）。"""
    user = db.query(User).filter(User.user_code == body.user_code.strip()).first()
    salt = (user.password_salt if user and user.password_salt else secrets.token_hex(16))
    challenge = secrets.token_bytes(16)
    _login_challenges[body.user_code.strip()] = {
        "challenge": challenge,
        "expires_at": datetime.utcnow() + timedelta(seconds=LOGIN_CHALLENGE_TTL_SECONDS),
    }
    return ApiResponse(data={
        "challenge": base64.b64encode(challenge).decode("ascii"),
        "salt": salt,
    })


@router.post("/register", response_model=ApiResponse)
def register(body: RegisterRequest, db: Session = Depends(get_db)):
    """用户名+密码注册（客户端已本地派生密码盐与 PBKDF2 哈希），成功后直接返回 token（即注册即登录）"""
    try:
        user = register_user(db, body.username, body.password_salt, body.password_hash)
        token = issue_owner_token(db, user)
        return ApiResponse(
            data={
                "access_token": token,
                "user": UserProfile.model_validate(user),
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"注册失败: {str(e)}")


@router.post("/login", response_model=ApiResponse)
def login(body: LoginRequest, db: Session = Depends(get_db)):
    """用户id+登录证明登录：服务器用存储的密码哈希校验 HMAC 证明"""
    try:
        user_code = body.user_code.strip()
        challenge_b64 = _take_challenge(user_code)
        user = login_user(db, user_code, challenge_b64, body.proof)
        token = issue_owner_token(db, user)
        return ApiResponse(
            data={
                "access_token": token,
                "user": UserProfile.model_validate(user),
            }
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"登录失败: {str(e)}")


@router.put("/password", response_model=ApiResponse)
def change_password(
    body: ChangePasswordRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """更改登录密码（仅旧密码方式）：旧密码以证明校验，新密码以客户端本地派生的盐+哈希存储；客户端按需重加密数据"""
    challenge_b64 = _take_challenge(current_user.user_code or "")
    ok = change_login_password(
        db, current_user, challenge_b64, body.old_proof,
        body.new_password_salt, body.new_password_hash,
    )
    if not ok:
        raise HTTPException(status_code=400, detail="旧密码错误")
    return ApiResponse(message="密码已更改")


@router.get("/profile", response_model=ApiResponse)
def get_profile(current_user: User = Depends(get_current_user)):
    """获取当前用户信息"""
    return ApiResponse(data=UserProfile.model_validate(current_user))


@router.put("/screen-aspect", response_model=ApiResponse)
def update_screen_aspect(
    body: ScreenAspectRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """上传本人设备屏幕宽高比（宽/高），供家属远程裁剪视频时按此比例"""
    current_user.screen_aspect = body.aspect
    db.commit()
    db.refresh(current_user)
    return ApiResponse(message="更新成功", data={"screen_aspect": current_user.screen_aspect})


@router.put("/threshold", response_model=ApiResponse)
def update_threshold(
    body: ThresholdRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """上传本人设备的按时服药阈值（分钟），供家属端远程服药时按同一标准判定"""
    current_user.threshold_minutes = body.threshold_minutes
    db.commit()
    db.refresh(current_user)
    return ApiResponse(message="更新成功", data={"threshold_minutes": current_user.threshold_minutes})


@router.put("/timezone", response_model=ApiResponse)
def update_timezone(
    body: TimezoneRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """上传本人设备时区（IANA 标识），供家属端远程控制时按目标用户本地时区显示和处理时间"""
    current_user.timezone = body.timezone
    db.commit()
    db.refresh(current_user)
    return ApiResponse(message="更新成功", data={"timezone": current_user.timezone})


@router.get("/time", response_model=ApiResponse)
def get_server_time(current_user: User = Depends(get_current_user)):
    """返回服务器当前时间（毫秒）：本人端据此计算自身时钟偏移并上传"""
    return ApiResponse(data={"server_time": int(time.time() * 1000)})


@router.put("/clock-offset", response_model=ApiResponse)
def update_clock_offset(
    body: ClockOffsetRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """上传本人设备时钟相对服务器时间的偏移（毫秒），供家属端还原目标设备当前时间读数"""
    current_user.clock_offset_ms = body.clock_offset_ms
    db.commit()
    db.refresh(current_user)
    return ApiResponse(message="更新成功", data={"clock_offset_ms": current_user.clock_offset_ms})


@router.post("/heartbeat", response_model=ApiResponse)
def heartbeat(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """本人设备心跳：刷新最近连接时间（家属控制据此判断目标设备当前是否连接服务器）"""
    current_user.last_seen_at = datetime.utcnow()
    db.commit()
    return ApiResponse(message="ok")


@router.put("/profile", response_model=ApiResponse)
def update_profile(
    body: UserUpdate,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """更新用户信息"""
    update_data = body.model_dump(exclude_unset=True)
    for key, value in update_data.items():
        setattr(current_user, key, value)
    db.commit()
    db.refresh(current_user)
    return ApiResponse(message="更新成功", data=UserProfile.model_validate(current_user))


@router.delete("/account", response_model=ApiResponse)
def delete_account(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """注销账号：删除本人及其药品、记录、家属绑定与端到端加密的 vault 密文/媒体（不留残留）"""
    # 删除与本人相关的家属绑定（FamilyBinding 只有裸外键，无 relationship，需手动删）
    db.query(FamilyBinding).filter(
        (FamilyBinding.senior_id == current_user.id) | (FamilyBinding.family_id == current_user.id)
    ).delete(synchronize_session=False)
    # 删除端到端加密的 vault 密文 blob 与加密媒体（无 relationship，需手动删）
    db.query(EncryptedBlob).filter(EncryptedBlob.owner_id == current_user.id).delete(synchronize_session=False)
    db.query(EncryptedMedia).filter(EncryptedMedia.owner_id == current_user.id).delete(synchronize_session=False)
    # 删除用户本身；medicines/records 通过 relationship 的 cascade 级联删除
    db.delete(current_user)
    db.commit()
    return ApiResponse(message="账户已注销")
