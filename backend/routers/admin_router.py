# Copyright (c) 2026 Tory_zy Tu

"""管理网站接口：Admin 账号认证 + 仪表盘 + 用户管理。

与 App 业务 API 完全隔离：本路由全部依赖 admin 会话（Cookie），
Admin 凭据在任何情况下都不能作为用户/家属令牌使用，反向亦然。
"""
import secrets

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.openapi.docs import get_swagger_ui_html
from sqlalchemy.orm import Session

import config
from admin_auth import ADMIN_COOKIE, CSRF_COOKIE, get_admin, require_admin_csrf
from database import get_db
from models import Admin, EncryptedBlob, EncryptedMedia, Medicine, MedicationRecord, User
from schemas import (
    AdminChangePasswordRequest, AdminDashboardData, AdminLoginRequest,
    AdminUserRow, ApiResponse,
)
from services import admin_service, pairing_service

router = APIRouter(prefix="/api/admin", tags=["管理后台"])

_UNAUTHORIZED_MSG = "用户名或密码错误"  # 用户不存在与密码错误同文案，防枚举


def _set_session_cookies(response: Response, token: str, csrf: str, max_age_seconds: int, secure: bool) -> None:
    """写入管理员会话 Cookie：token HttpOnly；csrf 非 HttpOnly（页面 JS 需读取）"""
    response.set_cookie(
        ADMIN_COOKIE, token,
        max_age=max_age_seconds, httponly=True, samesite="lax", path="/", secure=secure,
    )
    response.set_cookie(
        CSRF_COOKIE, csrf,
        max_age=max_age_seconds, httponly=False, samesite="lax", path="/", secure=secure,
    )


def _delete_session_cookies(response: Response) -> None:
    response.set_cookie(ADMIN_COOKIE, "", max_age=0, path="/")
    response.set_cookie(CSRF_COOKIE, "", max_age=0, path="/")


def _strict_gate(admin: Admin) -> None:
    """严格模式：未修改默认密码前禁用用户管理功能（公网部署防「默认凭据当长期用」）"""
    if config.ADMIN_FORCE_PASSWORD_CHANGE and admin.is_default_password:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="请先修改默认密码")


@router.post("/login", response_model=ApiResponse)
def admin_login(
    body: AdminLoginRequest,
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
):
    """管理员登录：限速 + bcrypt 校验；成功签发版本化会话并写入 Cookie"""
    client_ip = admin_service.get_client_ip(request)
    if admin_service._rate_limited(client_ip):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="尝试过于频繁，请稍后再试")
    admin = db.query(Admin).filter(Admin.username == body.username).first()
    if admin is None or not admin_service.verify_admin_password(admin, body.password):
        admin_service.record_login_failure(client_ip)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=_UNAUTHORIZED_MSG)
    admin_service.reset_login_rate(client_ip)
    token = admin_service.issue_admin_session(db, admin)
    csrf = secrets.token_hex(16)
    _set_session_cookies(
        response, token, csrf,
        max_age_seconds=config.ADMIN_TOKEN_EXPIRE_HOURS * 3600,
        secure=request.url.scheme == "https",
    )
    return ApiResponse(data={"username": admin.username, "is_default_password": admin.is_default_password})


@router.post("/logout", response_model=ApiResponse)
def admin_logout(
    response: Response,
    admin: Admin = Depends(require_admin_csrf),
    db: Session = Depends(get_db),
):
    """退出登录：递增版本号使所有已签发会话服务端失效，并清除 Cookie"""
    admin.token_version = (admin.token_version or 0) + 1
    db.commit()
    _delete_session_cookies(response)
    return ApiResponse(message="已退出登录")


@router.post("/change-password", response_model=ApiResponse)
def admin_change_password(
    body: AdminChangePasswordRequest,
    request: Request,
    response: Response,
    admin: Admin = Depends(require_admin_csrf),
    db: Session = Depends(get_db),
):
    """修改管理员密码：旧密码校验；改后旧会话全部失效，当前会话重发新 Cookie 保持登录"""
    if not admin_service.verify_admin_password(admin, body.old_password):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="旧密码错误")
    admin_service.set_admin_password(db, admin, body.new_password)
    db.refresh(admin)
    token = admin_service.issue_admin_session(db, admin)
    csrf = secrets.token_hex(16)
    _set_session_cookies(
        response, token, csrf,
        max_age_seconds=config.ADMIN_TOKEN_EXPIRE_HOURS * 3600,
        secure=request.url.scheme == "https",
    )
    return ApiResponse(message="密码已更改", data={"is_default_password": False})


@router.get("/dashboard", response_model=ApiResponse)
def admin_dashboard(admin: Admin = Depends(get_admin), db: Session = Depends(get_db)):
    """仪表盘：仅统计聚合，绝不返回任何用户密文/验证材料"""
    data = AdminDashboardData(
        username=admin.username,
        is_default_password=admin.is_default_password,
        user_count=db.query(User).count(),
        medicine_count=db.query(Medicine).count(),
        record_count=db.query(MedicationRecord).count(),
        blob_count=db.query(EncryptedBlob).count(),
        media_count=db.query(EncryptedMedia).count(),
    )
    return ApiResponse(data=data)


@router.get("/users", response_model=ApiResponse)
def admin_users(admin: Admin = Depends(get_admin), db: Session = Depends(get_db)):
    """用户列表：仅安全元数据（不含验证子/包裹密钥/任何密文）"""
    _strict_gate(admin)
    rows = [
        AdminUserRow.model_validate(u)
        for u in db.query(User).order_by(User.id).all()
    ]
    return ApiResponse(data=rows)


@router.post("/users/{user_id}/force-logout", response_model=ApiResponse)
def admin_force_logout(
    user_id: int,
    admin: Admin = Depends(require_admin_csrf),
    db: Session = Depends(get_db),
):
    """强制下线 App 用户：递增其 token_version（沿用单设备登录撤销机制）"""
    _strict_gate(admin)
    user = db.query(User).filter(User.id == user_id).first()
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    admin_service.force_logout_user(db, user)
    return ApiResponse(message="已强制下线")


@router.post("/pairing-code", response_model=ApiResponse)
def admin_pairing_code(
    admin: Admin = Depends(require_admin_csrf),
    db: Session = Depends(get_db),
):
    """在管理网站生成一次性局域网配对码（与终端 python cli.py pair 等价）。

    管理网站经管理员会话 + TLS 保护，属于可信本地渠道；配对码规则不变
    （10 分钟有效、单次使用、5 次尝试、IP 限速）。公网模式禁用（系统信任链负责身份验证）。
    """
    if config.DEPLOY_MODE == "public":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="配对仅适用于局域网直连模式")
    code = pairing_service.new_pairing_code(db)
    return ApiResponse(data={"code": code, "ttl_seconds": pairing_service.PAIR_TTL_SECONDS})


@router.get("/docs", include_in_schema=False)
def admin_docs(admin: Admin = Depends(get_admin)):
    """管理网站内的 API 文档（Swagger UI）：仅管理员会话可访问，/docs 的等价功能"""
    return get_swagger_ui_html(
        openapi_url="/api/admin/openapi",
        title="智能药箱 API 文档",
    )


@router.get("/openapi", include_in_schema=False)
def admin_openapi(request: Request, admin: Admin = Depends(get_admin)):
    """管理网站内的 OpenAPI schema：仅管理员会话可访问（Swagger UI 同源请求自带 Cookie）"""
    return request.app.openapi()
