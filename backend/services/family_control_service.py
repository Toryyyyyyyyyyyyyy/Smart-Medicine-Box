# Copyright (c) 2026 Tory_zy Tu

"""家人控制业务逻辑：验证子存储 / 无登录鉴权证明

安全模型说明：
  dataKey  = PBKDF2(密码, salt || "data")   —— 仅存在于客户端，用于 AES-GCM 加密数据
  authVerifier = PBKDF2(家人密码, salt || "auth") —— 由客户端本地派生后上传，服务器只存验证子
明文密码（登录密码与家人密码）在任何时刻都不上传服务器。这能阻止
「诚实但好奇」的服务器管理员直接读取明文；剩余风险是主动作恶者离线爆破
存储的验证子（标准 verifier 模型，非严格 OPAQUE）。
"""
import base64
import hashlib
import hmac
import secrets
from datetime import datetime, timedelta

from sqlalchemy.orm import Session

from models import User

# 目标设备视为「已连接服务器」的心跳窗口；超过该时长无心跳即视为未连接
CONNECTED_WINDOW_MINUTES = 45


def is_connected(user: User) -> bool:
    """目标用户设备当前是否连接服务器：最近一次心跳必须在窗口期内。"""
    if not user.last_seen_at:
        return False
    return datetime.utcnow() - user.last_seen_at < timedelta(minutes=CONNECTED_WINDOW_MINUTES)


def set_family_control(db: Session, user: User, auth_verifier_b64: str, view_only: bool) -> None:
    """开启/设置家人控制：验证子由客户端用家人密码本地派生上传（明文密码永不上传服务器）。

    盐必须保持稳定：密文 blob 用 dataKey = PBKDF2(登录密码, salt||"data") 加密，
    若每次开启都换盐，已加密数据会因密钥漂移而无法解密（家属端表现为 BAD_DECRYPT）。
    注册时盐已生成；此处仅为历史数据兜底。
    """
    if not user.enc_salt:
        user.enc_salt = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
    user.enc_verifier = auth_verifier_b64
    user.family_control_enabled = True
    user.family_control_view_only = view_only
    db.commit()
    db.refresh(user)


def verify_family_proof(enc_verifier_b64: str, challenge: bytes, proof_b64: str) -> bool:
    """验证家属证明：expected = HMAC(authVerifier, challenge)，常量时间比对。"""
    try:
        verifier = base64.b64decode(enc_verifier_b64)
        proof = base64.b64decode(proof_b64)
    except Exception:
        return False
    expected = hmac.new(verifier, challenge, hashlib.sha256).digest()
    return hmac.compare_digest(expected, proof)


def new_challenge() -> bytes:
    """生成随机 challenge"""
    return secrets.token_bytes(16)
