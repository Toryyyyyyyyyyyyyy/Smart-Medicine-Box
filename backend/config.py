# Copyright (c) 2026 Tory_zy Tu

"""应用配置：环境变量（.env）优先，关键秘密自动生成并持久化"""
import os
import secrets

from dotenv import load_dotenv

load_dotenv()

# 数据库配置
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATABASE_URL = f"sqlite:///{os.path.join(BASE_DIR, 'medicine_box.db')}"


# JWT 签名密钥：环境变量优先；否则读取/生成持久化密钥文件（切勿提交到版本库）。
# 轮换密钥会使所有已签发令牌（用户/家属/管理员）立即失效。
def _load_or_create_secret_key():
    env_key = os.getenv("SECRET_KEY")
    if env_key:
        return env_key, "env"
    key_file = os.path.join(BASE_DIR, ".secret_key")
    if os.path.exists(key_file):
        with open(key_file, "r", encoding="utf-8") as f:
            return f.read().strip(), "file"
    key = secrets.token_hex(32)
    with open(key_file, "w", encoding="utf-8") as f:
        f.write(key)
    return key, "generated"


SECRET_KEY, SECRET_KEY_SOURCE = _load_or_create_secret_key()

# JWT 配置
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_DAYS = 30
FAMILY_TOKEN_EXPIRE_MINUTES = 60  # 家属控制短期令牌有效期

# 密码哈希（PBKDF2-HMAC-SHA256，标准库 hashlib）
PASSWORD_HASH_ITERATIONS = 200_000

# 部署模式：lan = 局域网直连（自签 TLS + 配对）；public = 公网（回环监听，Caddy 终结 TLS）
DEPLOY_MODE = os.getenv("DEPLOY_MODE", "lan")
if DEPLOY_MODE not in ("lan", "public"):
    raise RuntimeError(f"DEPLOY_MODE 只能是 lan 或 public，当前值: {DEPLOY_MODE!r}")

# 服务配置：公网模式默认只监听回环（由 Caddy 反代），局域网模式监听所有网卡
HOST = os.getenv("HOST") or ("127.0.0.1" if DEPLOY_MODE == "public" else "0.0.0.0")
PORT = int(os.getenv("PORT", "8000"))

# 管理员初始凭据：仅在数据库中「没有任何管理员记录」时生效（首次启动播种）。
# 修改这里的值不会改变已初始化的管理员密码——改密请在管理网站进行，
# 忘记密码请用 python cli.py reset-admin 在服务器本机重置。
ADMIN_USERNAME = os.getenv("ADMIN_USERNAME", "Admin")
ADMIN_PASSWORD = os.getenv("ADMIN_PASSWORD", "123456")
ADMIN_TOKEN_EXPIRE_HOURS = int(os.getenv("ADMIN_TOKEN_EXPIRE_HOURS", "12"))
# 严格模式：未修改默认密码前，管理网站的用户管理功能不可用（公网部署建议开启）
ADMIN_FORCE_PASSWORD_CHANGE = os.getenv("ADMIN_FORCE_PASSWORD_CHANGE", "false").lower() in ("1", "true", "yes")

# 服务器身份密钥与自签证书（局域网 TLS + 配对的身份锚点；公网模式不使用）
IDENTITY_DIR = os.path.join(BASE_DIR, "server_identity")
TLS_CERT_FILE = os.getenv("TLS_CERT_FILE") or os.path.join(IDENTITY_DIR, "identity.crt")
TLS_KEY_FILE = os.getenv("TLS_KEY_FILE") or os.path.join(IDENTITY_DIR, "identity.key")
