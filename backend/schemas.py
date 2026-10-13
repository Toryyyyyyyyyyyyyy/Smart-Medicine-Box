# Copyright (c) 2026 Tory_zy Tu

"""Pydantic 请求/响应模型"""
from datetime import datetime
from typing import Optional, List
from pydantic import BaseModel, Field


# ==================== 用户 ====================

class RegisterRequest(BaseModel):
    """用户名+密码注册请求：客户端本地派生密码验证材料（PBKDF2 哈希），明文密码不上传服务器"""
    username: str = Field(..., min_length=2, max_length=20, pattern=r"^\S{2,20}$", description="用户名（显示名）")
    password_salt: str = Field(..., min_length=32, max_length=32, pattern=r"^[0-9a-fA-F]{32}$", description="客户端生成的密码盐（hex，16 字节）")
    password_hash: str = Field(..., min_length=64, max_length=64, pattern=r"^[0-9a-fA-F]{64}$", description="PBKDF2-SHA256 密码哈希（hex，32 字节）")


class LoginChallengeRequest(BaseModel):
    """登录挑战请求：获取盐与一次性挑战，客户端据此计算登录证明"""
    user_code: str = Field(..., min_length=1, max_length=20, description="用户id")


class LoginRequest(BaseModel):
    """用户id+登录证明登录请求：proof = HMAC-SHA256(密码哈希, challenge)"""
    user_code: str = Field(..., min_length=1, max_length=20, description="用户id")
    proof: str = Field(..., min_length=16, max_length=256, description="登录证明（Base64）")


class ChangePasswordRequest(BaseModel):
    """更改登录密码（仅旧密码方式）：旧密码以证明提交，新密码以客户端派生的盐+哈希提交"""
    old_proof: str = Field(..., min_length=16, max_length=256, description="旧密码证明（Base64）")
    new_password_salt: str = Field(..., min_length=32, max_length=32, pattern=r"^[0-9a-fA-F]{32}$", description="新密码盐（hex）")
    new_password_hash: str = Field(..., min_length=64, max_length=64, pattern=r"^[0-9a-fA-F]{64}$", description="新密码 PBKDF2 哈希（hex）")


class ScreenAspectRequest(BaseModel):
    """上传本人设备屏幕宽高比（宽/高），供家属远程裁剪视频时按此比例"""
    aspect: float = Field(..., gt=0, description="屏幕宽高比（宽/高）")


class ThresholdRequest(BaseModel):
    """上传本人设备的按时服药阈值（分钟），供家属端远程服药时按同一标准判定"""
    threshold_minutes: int = Field(..., ge=1, le=1440, description="按时服药阈值（分钟）")


class TimezoneRequest(BaseModel):
    """上传本人设备时区（IANA 标识），供家属端远程控制时按目标用户本地时区显示和处理时间"""
    timezone: str = Field(..., min_length=1, max_length=64, description="IANA 时区标识（如 Asia/Shanghai）")


class ClockOffsetRequest(BaseModel):
    """上传本人设备时钟相对服务器时间的偏移（毫秒），供家属端还原目标设备当前时间读数"""
    clock_offset_ms: int = Field(..., description="本人设备时钟 - 服务器时间（毫秒）")


class UserUpdate(BaseModel):
    username: Optional[str] = None
    avatar_url: Optional[str] = None


class UserProfile(BaseModel):
    id: int
    user_code: Optional[str] = None
    username: Optional[str] = None
    avatar_url: str = ""
    family_control_enabled: bool = False
    family_control_view_only: bool = False
    enc_enabled: bool = False
    enc_salt: Optional[str] = None
    enc_wrapped_family_key: Optional[str] = None
    screen_aspect: Optional[float] = None
    threshold_minutes: int = 60
    created_at: datetime

    class Config:
        from_attributes = True


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserProfile


# ==================== 药品 ====================

class TimeSlot(BaseModel):
    """单个服药时间点"""
    time: str  # "08:00"
    enabled: bool = True


class MedicineCreate(BaseModel):
    name: str = Field(..., min_length=1, max_length=200, description="药品名称")
    dosage: str = Field(default="", max_length=100, description="剂量")
    frequency: str = Field(default="daily", description="服用频率")
    time_slots: List[str] = Field(default=[], description="服用时间点")
    stock: int = Field(default=0, ge=0, description="剩余数量")
    notes: str = Field(default="", max_length=500, description="备注")


class MedicineUpdate(BaseModel):
    name: Optional[str] = None
    dosage: Optional[str] = None
    frequency: Optional[str] = None
    time_slots: Optional[List[str]] = None
    stock: Optional[int] = None
    notes: Optional[str] = None
    is_active: Optional[bool] = None


class MedicineResponse(BaseModel):
    id: int
    user_id: int
    name: str
    dosage: str
    frequency: str
    time_slots: List[str]
    stock: int
    notes: str
    is_active: bool
    created_at: datetime

    class Config:
        from_attributes = True


# ==================== 服药记录 ====================

class RecordCreate(BaseModel):
    medicine_id: int
    scheduled_time: str  # ISO datetime


class RecordUpdate(BaseModel):
    status: Optional[str] = None  # taken / skipped / missed


class RecordResponse(BaseModel):
    id: int
    user_id: int
    medicine_id: int
    medicine_name: str
    dosage: str
    scheduled_time: datetime
    taken_time: Optional[datetime]
    status: str
    created_at: datetime

    class Config:
        from_attributes = True


class RecordStatsResponse(BaseModel):
    period: str  # "week" / "month"
    total: int
    taken: int
    missed: int
    skipped: int
    adherence_rate: float  # 0.0 ~ 1.0


# ==================== 家属绑定 ====================

class FamilyBindRequest(BaseModel):
    senior_user_code: str = Field(..., min_length=1, max_length=20, description="控制对象的用户id")


class BindingResponse(BaseModel):
    id: int
    senior_id: int
    senior_name: str
    family_id: int
    family_name: str
    relation: str
    is_accepted: bool
    created_at: datetime

    class Config:
        from_attributes = True


class SeniorInfo(BaseModel):
    id: int
    username: Optional[str] = None
    avatar_url: str = ""


# ==================== 家人控制 ====================

class FamilyControlSetRequest(BaseModel):
    """开启/更新家人控制设置"""
    # 家属鉴权验证子：客户端用家人密码本地派生上传（PBKDF2 域分离 salt||"auth"），
    # 明文家人密码永不上传服务器（与登录密码链路同级）
    auth_verifier: Optional[str] = Field(
        default=None, min_length=43, max_length=44, pattern=r"^[A-Za-z0-9+/]{43}=$",
        description="家属鉴权验证子（base64，32 字节）"
    )
    view_only: Optional[bool] = Field(default=None, description="仅允许查看")
    enabled: Optional[bool] = Field(default=None, description="是否开启家人控制")
    # 客户端用家人密码包裹的登录密钥（数据恒用登录密码加密；关闭时传 None 清除）
    wrapped_family_key: Optional[str] = Field(default=None, description="包裹后的家属密钥")


class FamilyAccessInitRequest(BaseModel):
    """家属无登录访问初始化请求"""
    user_code: str = Field(..., min_length=1, max_length=20, description="目标用户id")


class FamilyAccessVerifyRequest(BaseModel):
    """家属无登录访问验证请求"""
    user_code: str = Field(..., min_length=1, max_length=20, description="目标用户id")
    proof: str = Field(..., description="HMAC(authVerifier, challenge) 的 base64")


# ==================== 端到端加密 vault ====================

class VaultBlobResponse(BaseModel):
    """单个加密 blob 的响应"""
    kind: str
    ciphertext: Optional[str] = None
    version: int = 0
    updated_at: Optional[datetime] = None


class VaultBlobUpdateRequest(BaseModel):
    """上传加密 blob（乐观锁：expected_version 不匹配返回 409）"""
    ciphertext: str = Field(..., description="base64(nonce||ciphertext||tag)")
    expected_version: int = Field(..., ge=0, description="期望的当前版本")


class VaultMediaMeta(BaseModel):
    """加密媒体元数据（不含密文二进制）"""
    media_id: str
    size: int
    content_type: str
    filename: str
    updated_at: Optional[datetime] = None


class EncryptionEnabledRequest(BaseModel):
    """切换端到端加密开关（迁移完成后置 true）"""
    enabled: bool


# ==================== 管理网站（与 App 用户账号体系完全隔离） ====================

class AdminLoginRequest(BaseModel):
    """管理员登录请求"""
    username: str = Field(..., min_length=1, max_length=64)
    password: str = Field(..., min_length=1, max_length=64)


class AdminChangePasswordRequest(BaseModel):
    """管理员修改密码（旧密码校验，改后旧会话全部失效）"""
    old_password: str = Field(..., min_length=1, max_length=64)
    new_password: str = Field(..., min_length=8, max_length=64)


class AdminUserRow(BaseModel):
    """管理网站用户列表行：仅安全元数据，绝不含验证子/包裹密钥/密文"""
    id: int
    user_code: Optional[str] = None
    username: Optional[str] = None
    family_control_enabled: bool = False
    family_control_view_only: bool = False
    enc_enabled: bool = False
    last_seen_at: Optional[datetime] = None
    created_at: Optional[datetime] = None
    token_version: int = 0

    class Config:
        from_attributes = True


class AdminDashboardData(BaseModel):
    """管理网站仪表盘：仅统计聚合"""
    username: str
    is_default_password: bool
    user_count: int
    medicine_count: int
    record_count: int
    blob_count: int
    media_count: int


# ==================== 局域网配对 ====================

class PairVerifyRequest(BaseModel):
    """配对证明：proof = HMAC-SHA256(配对码派生密钥, 服务器 SPKI 指纹) 的 base64"""
    proof: str = Field(..., min_length=16, max_length=128)
    fingerprint: str = Field(..., pattern=r"^[0-9a-f]{64}$", description="服务器证书 SPKI 指纹（SHA-256 hex）")


# ==================== 通用 ====================

class ApiResponse(BaseModel):
    """统一响应格式"""
    code: int = 0
    message: str = "ok"
    data: Optional[object] = None
