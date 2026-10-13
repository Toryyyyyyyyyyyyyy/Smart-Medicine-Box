# Copyright (c) 2026 Tory_zy Tu

"""管理网站认证：bcrypt 密码 + 登录限速 + 版本化会话（CAS 撤销）。

Admin 账号与 App 用户账号体系完全隔离：admin 会话令牌携带 sub_type="admin"
且不含 user_id——现有用户/家属认证依赖天然拒绝它；反向亦然。
"""
import time
from datetime import timedelta

import bcrypt
from fastapi import HTTPException
from sqlalchemy import update
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

import config
from auth import create_access_token
from models import Admin, User

ADMIN_PASSWORD_MIN = 8
ADMIN_PASSWORD_MAX = 64

# 登录限速（内存；单进程部署，重启清零——管理员密码为 bcrypt 哈希，离线爆破仍是主防线）
_login_window = {}      # ip -> 最近登录尝试时间戳列表（滑动窗口）
_fail_count = {}        # ip -> 连续失败次数
_locked_until = {}      # ip -> 锁定期结束时间
LOGIN_WINDOW_SECONDS = 300
LOGIN_WINDOW_MAX = 10
LOCKOUT_FAILS = 5
LOCKOUT_SECONDS = 60


def ensure_default_admin(db: Session) -> None:
    """首次启动播种管理员账号。env 配置只在「没有任何管理员记录」时生效；
    之后修改 env 不会改变已初始化的管理员密码（改密在管理网站进行，
    忘记密码用 python cli.py reset-admin 在服务器本机重置）。"""
    if db.query(Admin).first():
        return
    db.add(Admin(
        username=config.ADMIN_USERNAME,
        password_hash=bcrypt.hashpw(
            config.ADMIN_PASSWORD.encode("utf-8"), bcrypt.gensalt()
        ).decode("ascii"),
        is_default_password=True,
    ))
    db.commit()


def verify_admin_password(admin: Admin, password: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8"), admin.password_hash.encode("ascii"))
    except ValueError:
        return False


def _rate_limited(client_ip: str) -> bool:
    now = time.monotonic()
    if _locked_until.get(client_ip, 0) > now:
        return True
    window = [t for t in _login_window.get(client_ip, []) if now - t < LOGIN_WINDOW_SECONDS]
    _login_window[client_ip] = window
    return len(window) >= LOGIN_WINDOW_MAX


def record_login_failure(client_ip: str) -> None:
    now = time.monotonic()
    _login_window.setdefault(client_ip, []).append(now)
    _fail_count[client_ip] = _fail_count.get(client_ip, 0) + 1
    if _fail_count[client_ip] >= LOCKOUT_FAILS:
        _locked_until[client_ip] = now + LOCKOUT_SECONDS
        _fail_count[client_ip] = 0


def reset_login_rate(client_ip: str) -> None:
    _login_window.pop(client_ip, None)
    _fail_count.pop(client_ip, None)
    _locked_until.pop(client_ip, None)


def issue_admin_session(db: Session, admin: Admin) -> str:
    """签发管理员会话令牌并 CAS 递增版本号：新登录使旧会话失效（单会话模型）"""
    for _ in range(5):
        try:
            current = db.query(Admin.token_version).filter(Admin.id == admin.id).scalar() or 0
            result = db.execute(
                update(Admin)
                .where(Admin.id == admin.id, Admin.token_version == current)
                .values(token_version=current + 1)
            )
            db.commit()
        except OperationalError:
            # SQLite 写锁竞争：回滚后重试
            db.rollback()
            continue
        if result.rowcount == 1:
            return create_access_token(
                {
                    "sub_type": "admin",
                    "admin_id": admin.id,
                    "token_version": current + 1,
                },
                expires_delta=timedelta(hours=config.ADMIN_TOKEN_EXPIRE_HOURS),
            )
    raise HTTPException(status_code=500, detail="登录冲突，请重试")


def set_admin_password(db: Session, admin: Admin, new_password: str) -> None:
    """修改管理员密码：旧会话全部失效（版本号递增），默认密码标志清除"""
    if not ADMIN_PASSWORD_MIN <= len(new_password) <= ADMIN_PASSWORD_MAX:
        raise HTTPException(
            status_code=400,
            detail=f"新密码长度需在 {ADMIN_PASSWORD_MIN}-{ADMIN_PASSWORD_MAX} 位之间",
        )
    admin.password_hash = bcrypt.hashpw(
        new_password.encode("utf-8"), bcrypt.gensalt()
    ).decode("ascii")
    admin.is_default_password = False
    admin.token_version = (admin.token_version or 0) + 1
    db.commit()


def force_logout_user(db: Session, user: User) -> None:
    """强制下线 App 用户：递增其 token_version（沿用单设备登录的撤销机制）"""
    user.token_version = (user.token_version or 0) + 1
    db.commit()


def get_client_ip(request) -> str:
    """真实客户端 IP：仅公网模式（后端只接回环）信任反向代理的 X-Forwarded-For"""
    if config.DEPLOY_MODE == "public":
        xff = request.headers.get("x-forwarded-for")
        if xff:
            return xff.split(",")[0].strip()
    return request.client.host if request.client else "unknown"
