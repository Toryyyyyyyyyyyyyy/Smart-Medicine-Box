# Copyright (c) 2026 Tory_zy Tu

"""JWT 认证与用户名密码登录工具"""
import base64
import hashlib
import hmac
import secrets
from datetime import datetime, timedelta
from typing import Optional

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from jose import JWTError, jwt
from sqlalchemy import update
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from config import (
    SECRET_KEY, ALGORITHM, ACCESS_TOKEN_EXPIRE_DAYS,
    PASSWORD_HASH_ITERATIONS, FAMILY_TOKEN_EXPIRE_MINUTES,
)
from database import get_db
from models import User
from services import family_control_service

security = HTTPBearer()


def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    """生成 JWT token"""
    to_encode = data.copy()
    expire = datetime.utcnow() + (expires_delta or timedelta(days=ACCESS_TOKEN_EXPIRE_DAYS))
    to_encode.update({"exp": expire})
    return jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)


def verify_token(token: str) -> Optional[dict]:
    """验证 JWT token，返回 payload 或 None"""
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        return payload
    except JWTError:
        return None


def issue_owner_token(db: Session, user: User) -> str:
    """登录/注册成功后签发主人 token；递增 token_version 使旧 token 失效（单设备登录）。

    用「比较并交换」原子递增（UPDATE ... WHERE token_version = 旧值）：并发登录时，
    先提交的那次登录的 token 会在后提交者递增后立即失效，任意时刻至多一个设备会话
    有效。家属控制令牌走 create_family_token，不受此版本号约束。
    """
    for _ in range(5):
        try:
            current = db.query(User.token_version).filter(User.id == user.id).scalar() or 0
            result = db.execute(
                update(User)
                .where(User.id == user.id, User.token_version == current)
                .values(token_version=current + 1)
            )
            db.commit()
        except OperationalError:
            # SQLite 写锁竞争（并发登录）：回滚后重试
            db.rollback()
            continue
        if result.rowcount == 1:
            return create_access_token({
                "user_id": user.id,
                "user_code": user.user_code,
                "token_version": current + 1,
            })
    raise HTTPException(status_code=500, detail="登录冲突，请重试")


async def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: Session = Depends(get_db),
) -> User:
    """FastAPI 依赖：从 token 获取当前用户"""
    token = credentials.credentials
    payload = verify_token(token)
    if payload is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="无效的认证凭证",
        )
    user_id: int = payload.get("user_id")
    if user_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="无效的认证凭证")

    user = db.query(User).filter(User.id == user_id).first()
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="用户不存在")
    # 老 token 无 token_version 字段，按 0 处理，避免升级后旧会话被误判失效
    if payload.get("token_version", 0) != (user.token_version or 0):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="登录已失效，请重新登录")
    return user


# ==================== 用户名密码注册 / 登录（密码验证材料协议） ====================
# 明文密码永不上传：客户端在本地派生 PBKDF2 哈希（注册时提交盐+哈希；
# 登录/改密时提交 HMAC(哈希, 一次性挑战) 证明），服务器只持有密码哈希并做证明校验。


def verify_login_proof(user: User, challenge_b64: str, proof_b64: str) -> bool:
    """校验登录证明：proof == HMAC-SHA256(存储的密码哈希, challenge)，常量时间比较"""
    try:
        challenge = base64.b64decode(challenge_b64)
        proof = base64.b64decode(proof_b64)
        stored = bytes.fromhex(user.password_hash or "")
    except (ValueError, TypeError):
        return False
    if len(stored) == 0:
        return False
    expected = hmac.new(stored, challenge, hashlib.sha256).digest()
    return hmac.compare_digest(expected, proof)


def _generate_user_code(db: Session) -> str:
    """生成不重复的 8 位数字用户id（登录用）"""
    while True:
        code = str(10000000 + secrets.randbelow(90000000))
        if not db.query(User).filter(User.user_code == code).first():
            return code


def register_user(db: Session, username: str, password_salt: str, password_hash: str) -> User:
    """注册新用户：用户名可重复（显示名），自动生成唯一用户id；
    密码盐与 PBKDF2 哈希由客户端本地派生提交，明文密码不上传服务器"""
    username = username.strip()
    user_code = _generate_user_code(db)
    user = User(
        user_code=user_code,
        username=username or f"用户{user_code[-4:]}",
        password_salt=password_salt,
        password_hash=password_hash,
        # 端到端加密盐在注册时即生成：默认状态用登录密码派生 dataKey/mediaKey
        enc_salt=base64.b64encode(secrets.token_bytes(16)).decode("ascii"),
        enc_enabled=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user


def login_user(db: Session, user_code: str, challenge_b64: str, proof_b64: str) -> User:
    """用户id+登录证明登录：校验通过返回用户，否则抛 400（不存在与证明错误同文案，防枚举）"""
    user = db.query(User).filter(User.user_code == user_code.strip()).first()
    if not user or not user.password_hash or not verify_login_proof(user, challenge_b64, proof_b64):
        raise HTTPException(status_code=400, detail="用户id或密码错误")
    return user


def change_login_password(
    db: Session, user: User,
    challenge_b64: str, old_proof_b64: str,
    new_password_salt: str, new_password_hash: str,
) -> bool:
    """更改登录密码：校验旧密码证明，存储客户端本地派生的新盐与新哈希。返回是否成功。"""
    if not verify_login_proof(user, challenge_b64, old_proof_b64):
        return False
    user.password_salt = new_password_salt
    user.password_hash = new_password_hash
    db.commit()
    db.refresh(user)
    return True


def create_family_token(senior_id: int, view_only: bool = False) -> str:
    """生成家属控制短期令牌（无需登录，凭密码 proof 换取）"""
    return create_access_token(
        {
            "senior_id": senior_id,
            "sub_type": "family_control",
            "view_only": view_only,
        },
        expires_delta=timedelta(minutes=FAMILY_TOKEN_EXPIRE_MINUTES),
    )


async def get_family_control_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: Session = Depends(get_db),
) -> tuple:
    """FastAPI 依赖：从家属控制令牌解析被控制的用户。

    返回 (senior_user, view_only)。view_only 每次都从 DB 重读，保证开关即时生效。
    """
    payload = verify_token(credentials.credentials)
    if payload is None or payload.get("sub_type") != "family_control":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="无效的家属控制凭证",
        )
    senior_id: int = payload.get("senior_id")
    if senior_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="无效的家属控制凭证")

    senior = db.query(User).filter(User.id == senior_id).first()
    if senior is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="被控用户不存在")
    if not senior.family_control_enabled:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="该用户未开启家人控制")
    if not family_control_service.is_connected(senior):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="控制对象未连接服务器")

    return senior, bool(senior.family_control_view_only)


async def get_vault_access(
    credentials: HTTPAuthorizationCredentials = Depends(security),
    db: Session = Depends(get_db),
) -> dict:
    """FastAPI 依赖：解析 vault 访问者（普通 token → 本用户；家属 token → 被控用户）。

    返回 {"owner_id": int, "view_only": bool}。写操作由路由按 view_only 拒绝；
    view_only 每次都从 DB 重读，保证开关即时生效。
    """
    payload = verify_token(credentials.credentials)
    if payload is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="无效的认证凭证")

    if payload.get("sub_type") == "family_control":
        senior_id: int = payload.get("senior_id")
        if senior_id is None:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="无效的家属控制凭证")
        senior = db.query(User).filter(User.id == senior_id).first()
        if senior is None or not senior.family_control_enabled:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="该用户未开启家人控制")
        if not family_control_service.is_connected(senior):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="控制对象未连接服务器")
        return {"owner_id": senior.id, "view_only": bool(senior.family_control_view_only)}

    user_id: int = payload.get("user_id")
    if user_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="无效的认证凭证")
    user = db.query(User).filter(User.id == user_id).first()
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="用户不存在")
    # 老 token 无 token_version 字段，按 0 处理，避免升级后旧会话被误判失效
    if payload.get("token_version", 0) != (user.token_version or 0):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="登录已失效，请重新登录")
    return {"owner_id": user.id, "view_only": False}
