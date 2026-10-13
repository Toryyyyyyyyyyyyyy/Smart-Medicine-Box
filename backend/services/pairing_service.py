# Copyright (c) 2026 Tory_zy Tu

"""局域网配对：一次性配对码 + 证书指纹证明（防 MITM/重放/爆破）。

协议：配对码由服务器终端显示（可信本地渠道），App 经 TLS 握手取得服务器
自签证书后提交 proof = HMAC-SHA256(SHA-256(域分离标签 || 码), 服务器 SPKI 指纹)。
服务器用自身证书指纹重算比对：
  - 中间人换证书 → HMAC 对不上（没有配对码）→ 拒绝；
  - 中间人透传   → 客户端锁定的仍是真服务器证书；
  - 单码单次使用 + 5 次尝试上限 + IP 限速 → 防重放与在线爆破；
  - 离线爆破配对码无价值：verify 成功不返回任何令牌或数据，仅确认 pin 安装。
"""
import base64
import hashlib
import hmac
import secrets
import time
from datetime import datetime, timedelta

from sqlalchemy.orm import Session

from models import PairingCode
from services import server_identity

CODE_LENGTH = 8
PAIR_TTL_SECONDS = 600          # 配对码 10 分钟有效
MAX_ATTEMPTS = 5                # 单码最多 5 次尝试
IP_RATE_MAX = 10                # 每 IP 限速
IP_RATE_WINDOW_SECONDS = 600
DOMAIN_SEP = b"smart-medicine-box-pairing-v1"

# 每 IP 滑动窗口（内存；单进程部署，重启清零——码本身在数据库中持久化）
_ip_windows = {}


class PairingError(Exception):
    """配对失败：status_code + 用户可读原因"""

    def __init__(self, status_code: int, detail: str):
        self.status_code = status_code
        self.detail = detail


def _rate_limit(ip: str) -> bool:
    """滑动窗口限速；超限返回 True"""
    now = time.monotonic()
    window = [t for t in _ip_windows.get(ip, []) if now - t < IP_RATE_WINDOW_SECONDS]
    _ip_windows[ip] = window
    if len(window) >= IP_RATE_MAX:
        return True
    window.append(now)
    return False


def derive_code_key(code: str) -> bytes:
    """配对码派生密钥（域分离；码是低熵一次性秘密，在线限速是主防线）"""
    return hashlib.sha256(DOMAIN_SEP + code.encode("ascii")).digest()


def new_pairing_code(db: Session) -> str:
    """生成新的 8 位配对码（覆盖旧码），返回明文码（仅在服务器终端显示一次）"""
    db.query(PairingCode).delete()
    code = f"{secrets.randbelow(10 ** CODE_LENGTH):0{CODE_LENGTH}d}"
    db.add(PairingCode(
        code_key_sha256=derive_code_key(code).hex(),
        attempts=0,
        expires_at=datetime.utcnow() + timedelta(seconds=PAIR_TTL_SECONDS),
    ))
    db.commit()
    return code


def verify_pairing(db: Session, proof_b64: str, fingerprint_hex: str, client_ip: str) -> str:
    """校验配对证明。成功返回服务器指纹；失败抛 PairingError。"""
    if _rate_limit(client_ip):
        raise PairingError(429, "请求过于频繁，请稍后再试")
    code_row = db.query(PairingCode).order_by(PairingCode.id.desc()).first()
    if not code_row or datetime.utcnow() > code_row.expires_at:
        raise PairingError(400, "配对码无效或已过期")
    if code_row.attempts >= MAX_ATTEMPTS:
        db.delete(code_row)
        db.commit()
        raise PairingError(400, "尝试次数过多，请重新生成配对码")
    try:
        proof = base64.b64decode(proof_b64)
    except Exception:
        proof = b""
    server_fp = server_identity.get_fingerprint()
    expected = hmac.new(
        bytes.fromhex(code_row.code_key_sha256),
        server_fp.encode("ascii"),
        hashlib.sha256,
    ).digest()
    # 指纹必须与服务器自身证书一致（App 捕获的证书与服务器实际证书必须同一张）
    if not hmac.compare_digest(expected, proof) or fingerprint_hex != server_fp:
        code_row.attempts += 1
        db.commit()
        raise PairingError(400, "配对失败，请核对配对码")
    # 成功：单次使用，立即消费
    db.delete(code_row)
    db.commit()
    return server_fp
