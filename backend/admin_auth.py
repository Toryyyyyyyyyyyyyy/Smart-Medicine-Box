# Copyright (c) 2026 Tory_zy Tu

"""管理网站认证依赖：HttpOnly Cookie 中的管理员 JWT + 双提交 CSRF。

与 auth.py 的用户/家属令牌严格隔离：admin 令牌带 sub_type="admin" 且无 user_id，
现有 get_current_user/get_vault_access 会拒绝；用户/家属令牌无 admin 声明，
get_admin 亦拒绝。隔离靠声明本身成立，不依赖任何共享代码。
"""
import hmac

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from auth import verify_token
from database import get_db
from models import Admin

ADMIN_COOKIE = "admin_token"
CSRF_COOKIE = "admin_csrf"


def get_admin(request: Request, db: Session = Depends(get_db)) -> Admin:
    """从 HttpOnly Cookie 解析管理员会话；缺失/无效/已失效（版本号不匹配）一律 401"""
    token = request.cookies.get(ADMIN_COOKIE)
    if not token:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录或会话已失效")
    payload = verify_token(token)
    if payload is None or payload.get("sub_type") != "admin":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录或会话已失效")
    admin_id = payload.get("admin_id")
    if admin_id is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录或会话已失效")
    admin = db.query(Admin).filter(Admin.id == admin_id).first()
    if admin is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="未登录或会话已失效")
    if payload.get("token_version", 0) != (admin.token_version or 0):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="会话已失效，请重新登录")
    return admin


def require_admin_csrf(request: Request, admin: Admin = Depends(get_admin)) -> Admin:
    """双提交 CSRF：请求头 X-CSRF-Token 必须与 admin_csrf Cookie 一致（常量时间比较）"""
    header_token = request.headers.get("x-csrf-token", "")
    cookie_token = request.cookies.get(CSRF_COOKIE, "")
    if not hmac.compare_digest(header_token, cookie_token):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="CSRF 校验失败")
    return admin
