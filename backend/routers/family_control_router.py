# Copyright (c) 2026 Tory_zy Tu

"""家人控制接口：设置/改密/无登录访问 + 家属对控制对象数据的读写（阶段2明文通道）"""
import base64
import time
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from database import get_db
from auth import get_current_user, get_family_control_user, create_family_token
from models import User
from schemas import (
    ApiResponse,
    FamilyControlSetRequest,
    FamilyAccessInitRequest,
    FamilyAccessVerifyRequest,
)
from services import family_control_service

router = APIRouter(tags=["家人控制"])

# 无登录家属访问的 challenge（内存存储，开发阶段；生产应入 Redis/DB）
_family_challenges: dict = {}
CHALLENGE_TTL_SECONDS = 300


def _family_flags(user: User) -> dict:
    return {
        "family_control_enabled": user.family_control_enabled,
        "family_control_view_only": user.family_control_view_only,
    }


# ==================== 设置 / 改密（需登录） ====================

@router.get("/api/auth/family-control", response_model=ApiResponse)
def get_family_control(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """本人取回家人控制状态与盐（盐用于本地派生 dataKey/mediaKey，不含验证子）"""
    return ApiResponse(data={
        **_family_flags(current_user),
        "salt": current_user.enc_salt,
        "wrapped_family_key": current_user.enc_wrapped_family_key,
    })


@router.put("/api/auth/family-control", response_model=ApiResponse)
def set_family_control(
    body: FamilyControlSetRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """开启/更新家人控制设置"""
    # 关闭家人控制
    if body.enabled is False:
        current_user.family_control_enabled = False
        current_user.enc_wrapped_family_key = None
        db.commit()
        db.refresh(current_user)
        return ApiResponse(message="已关闭家人控制", data=_family_flags(current_user))

    # 设置/更新密码（开启时需验证子；验证子由客户端本地派生上传，明文密码永不上传服务器）
    if body.auth_verifier:
        view_only = body.view_only if body.view_only is not None else current_user.family_control_view_only
        # 客户端已用家人密码包裹登录密钥；服务器只存包裹后的密文，不含明文密钥
        current_user.enc_wrapped_family_key = body.wrapped_family_key
        family_control_service.set_family_control(db, current_user, body.auth_verifier, view_only)
        # 返回盐，供本人客户端本地派生 dataKey/mediaKey（服务器不存 dataKey）
        return ApiResponse(message="家人控制已开启", data={
            **_family_flags(current_user),
            "salt": current_user.enc_salt,
        })

    # 无密码：仅更新开关/仅查看（重新开启需已有密码）
    if body.enabled is True and not current_user.enc_verifier:
        raise HTTPException(status_code=400, detail="开启家人控制需要先设置密码")
    if body.enabled is True:
        current_user.family_control_enabled = True
    if body.view_only is not None:
        current_user.family_control_view_only = body.view_only
    # 改登录密码/迁移时仅更新包裹密钥（无密码、无开关变化）
    if body.wrapped_family_key is not None:
        current_user.enc_wrapped_family_key = body.wrapped_family_key
    db.commit()
    db.refresh(current_user)
    return ApiResponse(message="更新成功", data=_family_flags(current_user))


# ==================== 无登录家属访问 ====================

@router.post("/api/family/access/init", response_model=ApiResponse)
def family_access_init(body: FamilyAccessInitRequest, db: Session = Depends(get_db)):
    """家属无登录访问第一步：取盐与 challenge（需对方已开启家人控制）"""
    senior = db.query(User).filter(User.user_code == body.user_code.strip()).first()
    if not senior:
        raise HTTPException(status_code=404, detail="未找到该用户id的用户")
    if not senior.family_control_enabled:
        raise HTTPException(status_code=403, detail="该用户未开启远程控制功能")
    # 目标设备必须当前连接服务器（最近心跳在窗口期内），否则不允许家属进入控制
    if not family_control_service.is_connected(senior):
        raise HTTPException(status_code=403, detail="控制对象未连接服务器")
    if not senior.enc_salt:
        raise HTTPException(status_code=500, detail="该用户的家人控制未正确初始化")

    challenge = family_control_service.new_challenge()
    _family_challenges[body.user_code.strip()] = {
        "challenge": challenge,
        "expires_at": datetime.utcnow() + timedelta(seconds=CHALLENGE_TTL_SECONDS),
    }
    return ApiResponse(data={
        "senior_id": senior.id,
        "username": senior.username,
        "salt": senior.enc_salt,
        "challenge": base64.b64encode(challenge).decode("ascii"),
        "wrapped_family_key": senior.enc_wrapped_family_key,
        "screen_aspect": senior.screen_aspect,
    })


@router.post("/api/family/access/verify", response_model=ApiResponse)
def family_access_verify(body: FamilyAccessVerifyRequest, db: Session = Depends(get_db)):
    """家属无登录访问第二步：验证 HMAC 证明，换取家属控制令牌"""
    user_code = body.user_code.strip()
    senior = db.query(User).filter(User.user_code == user_code).first()
    if not senior or not senior.enc_verifier:
        raise HTTPException(status_code=400, detail="访问失败")
    # 目标设备必须当前连接服务器（与 init 一致；断连后即使 challenge 未过期也不发令牌）
    if not family_control_service.is_connected(senior):
        raise HTTPException(status_code=403, detail="控制对象未连接服务器")

    record = _family_challenges.get(user_code)
    if not record or datetime.utcnow() > record["expires_at"]:
        _family_challenges.pop(user_code, None)
        raise HTTPException(status_code=400, detail="挑战已过期，请重试")

    challenge = record["challenge"]
    if not family_control_service.verify_family_proof(senior.enc_verifier, challenge, body.proof):
        raise HTTPException(status_code=400, detail="密码错误")

    _family_challenges.pop(user_code, None)
    token = create_family_token(senior.id, senior.family_control_view_only)
    return ApiResponse(data={
        "access_token": token,
        "senior_id": senior.id,
        "username": senior.username,
        "view_only": senior.family_control_view_only,
    })


# ==================== 家属拉取控制对象设置 ====================

@router.get("/api/family/control/settings", response_model=ApiResponse)
def family_get_settings(ctx: tuple = Depends(get_family_control_user)):
    """家属拉取控制对象设置（按时服药阈值等）；连接校验由 get_family_control_user 完成"""
    senior, _ = ctx
    return ApiResponse(data={
        "threshold_minutes": senior.threshold_minutes,
        "screen_aspect": senior.screen_aspect,
        "timezone": senior.timezone,
        "server_time": int(time.time() * 1000),
        "clock_offset_ms": senior.clock_offset_ms,
    })
