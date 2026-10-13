# Copyright (c) 2026 Tory_zy Tu

"""SQLAlchemy 数据模型"""
from datetime import datetime

from sqlalchemy import (
    Column, Integer, String, Boolean, Float, DateTime, Text, LargeBinary, ForeignKey, JSON
)
from sqlalchemy.orm import relationship

from database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    # 用户id：注册时自动生成的唯一数字编号，登录用（用户名可重复，用户id不可重复）
    user_code = Column(String(12), unique=True, index=True, nullable=True)
    # 用户名：显示名，允许重复（非登录标识）
    username = Column(String(20), index=True, nullable=True)
    avatar_url = Column(String(500), default="")
    # 登录密码：PBKDF2-HMAC-SHA256，盐 + 哈希（hex）
    password_salt = Column(String(64), nullable=True)
    password_hash = Column(String(128), nullable=True)
    # 家人控制：是否开启 / 是否仅允许查看
    family_control_enabled = Column(Boolean, default=False)
    family_control_view_only = Column(Boolean, default=False)
    # 端到端加密：盐与验证子（验证子用于家属无登录鉴权，绝非数据密钥）
    enc_salt = Column(String(64), nullable=True)
    enc_verifier = Column(String(128), nullable=True)
    # 家人控制密码包裹的「登录密码派生数据/媒体密钥」（数据恒用登录密码加密，家属凭家人密码解包）
    enc_wrapped_family_key = Column(String(500), nullable=True)
    # 本人设备屏幕宽高比（宽/高），家属远程裁剪视频时按此比例，避免按家属自己的屏比裁错
    screen_aspect = Column(Float, nullable=True)
    # 本人设备最近一次心跳时间；家属控制据此判断目标设备当前是否连接服务器
    last_seen_at = Column(DateTime, nullable=True)
    # 按时服药阈值（分钟）：本人本地设置上传服务器，家属端远程服药时按同一标准判定
    threshold_minutes = Column(Integer, nullable=False, default=60)
    # 本人设备时区（IANA 标识，如 Asia/Shanghai）：家属远程控制时按目标用户本地时区显示和处理时间
    timezone = Column(String(64), nullable=True)
    # 本人设备时钟相对服务器时间的偏移（毫秒）：家属端据此还原目标设备当前的时间读数，远程操作时间戳以目标端为准
    clock_offset_ms = Column(Integer, nullable=False, default=0)
    enc_enabled = Column(Boolean, default=False)
    # 单设备登录：每次登录递增版本号，token 携带此版本，旧 token 因版本不匹配而失效
    token_version = Column(Integer, default=0)
    created_at = Column(DateTime, default=datetime.utcnow)

    medicines = relationship("Medicine", back_populates="user", cascade="all, delete-orphan")
    records = relationship("MedicationRecord", back_populates="user", cascade="all, delete-orphan")


class Medicine(Base):
    __tablename__ = "medicines"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    name = Column(String(200), nullable=False)
    dosage = Column(String(100), default="")
    frequency = Column(String(50), default="daily")  # daily / weekly / custom
    time_slots = Column(JSON, default=list)          # ["08:00", "12:00", "18:00"]
    stock = Column(Integer, default=0)
    notes = Column(String(500), default="")
    is_active = Column(Boolean, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)

    user = relationship("User", back_populates="medicines")
    records = relationship("MedicationRecord", back_populates="medicine", cascade="all, delete-orphan")


class MedicationRecord(Base):
    __tablename__ = "medication_records"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    medicine_id = Column(Integer, ForeignKey("medicines.id"), nullable=False)
    scheduled_time = Column(DateTime, nullable=False)
    taken_time = Column(DateTime, nullable=True)
    status = Column(String(20), default="pending")  # pending / taken / missed / skipped
    created_at = Column(DateTime, default=datetime.utcnow)

    user = relationship("User", back_populates="records")
    medicine = relationship("Medicine", back_populates="records")


class FamilyBinding(Base):
    __tablename__ = "family_bindings"

    id = Column(Integer, primary_key=True, index=True)
    senior_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    family_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    relation = Column(String(50), default="")
    is_accepted = Column(Boolean, default=False)
    created_at = Column(DateTime, default=datetime.utcnow)


class EncryptedBlob(Base):
    """端到端加密的数据 blob（药品/记录整块密文，服务器不解析字段）。

    ciphertext = base64(nonce(12) || ciphertext || tag(16))，由客户端用 dataKey 加解密。
    """

    __tablename__ = "encrypted_blobs"

    id = Column(Integer, primary_key=True, index=True)
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    kind = Column(String(20), nullable=False)  # "medicines" / "records"
    ciphertext = Column(Text, nullable=True)
    version = Column(Integer, default=0)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    updated_by = Column(String(20), nullable=True)  # "owner" / "family"


class EncryptedMedia(Base):
    """端到端加密的媒体文件（自定义视频/铃声），用 mediaKey 加解密。"""

    __tablename__ = "encrypted_media"

    id = Column(Integer, primary_key=True, index=True)
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    media_id = Column(String(36), nullable=False, index=True)  # UUID
    ciphertext = Column(LargeBinary, nullable=True)
    content_type = Column(String(100), default="application/octet-stream")
    filename = Column(String(255), default="")
    size = Column(Integer, default=0)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class Admin(Base):
    """后端管理网站管理员账号：与 App 用户账号体系完全隔离，仅保护管理网站。"""

    __tablename__ = "admins"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(64), unique=True, index=True, nullable=False)
    password_hash = Column(String(128), nullable=False)  # bcrypt
    token_version = Column(Integer, nullable=False, default=0)  # 改密/退出递增，旧会话即时失效
    is_default_password = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime, default=datetime.utcnow)


class PairingCode(Base):
    """局域网配对码：CLI 进程生成、服务器进程校验，故持久化到数据库。

    只存派生密钥（code_key_sha256 = SHA-256(域分离标签 || 配对码)），不存配对码本身；
    单码单次使用，超尝试次数或过期即删除。
    """

    __tablename__ = "pairing_codes"

    id = Column(Integer, primary_key=True, index=True)
    code_key_sha256 = Column(String(64), nullable=False)
    attempts = Column(Integer, nullable=False, default=0)
    expires_at = Column(DateTime, nullable=False)
    created_at = Column(DateTime, default=datetime.utcnow)
