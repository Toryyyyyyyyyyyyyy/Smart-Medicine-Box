# Copyright (c) 2026 Tory_zy Tu


"""
智能药箱 — FastAPI 后端入口
启动: python main.py
  - DEPLOY_MODE=lan（默认）: 自签 TLS + 局域网配对，监听 0.0.0.0:8000
  - DEPLOY_MODE=public     : 仅监听 127.0.0.1，由 Caddy 反代终结 TLS（见 DEPLOY.md）
管理网站: https://<服务器地址>:8000/admin
"""
import os
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

import config
from database import SessionLocal, init_db
from models import Admin
from routers.admin_router import router as admin_router
from routers.auth_router import router as auth_router
from routers.family_control_router import router as family_control_router
from routers.family_router import router as family_router
from routers.pair_router import router as pair_router
from routers.vault_router import router as vault_router
from services import admin_service, server_identity

_ADMIN_HTML = Path(os.path.dirname(os.path.abspath(__file__))) / "static" / "admin.html"


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    init_db()
    db = SessionLocal()
    try:
        admin_service.ensure_default_admin(db)
        admin = db.query(Admin).first()
        if admin and admin.is_default_password:
            print("[WARN] 管理员仍在使用默认密码（Admin/123456），请尽快登录 /admin 修改；公网部署务必修改后再开放")
    finally:
        db.close()
    print("[OK] Database initialized")
    if config.DEPLOY_MODE == "lan":
        cert, key = server_identity.ensure_identity()
        print(f"[OK] LAN TLS 证书: {cert}")
        print(f"[OK] 服务器证书指纹: {server_identity.get_fingerprint()}")
    if config.SECRET_KEY_SOURCE == "generated":
        print("[OK] SECRET_KEY 已自动生成并保存到 backend/.secret_key（请勿提交到版本库）")
    if config.ADMIN_FORCE_PASSWORD_CHANGE:
        print("[WARN] 严格模式：管理员修改默认密码前，管理网站的用户管理功能不可用")
    print(f"[OK] 部署模式: {config.DEPLOY_MODE}  监听 {config.HOST}:{config.PORT}")
    yield


app = FastAPI(
    title="智能药箱 API",
    description="智能药箱后端服务 — 专为老年人用药提醒设计",
    version="1.0.0",
    lifespan=lifespan,
    # 公开 API 文档入口已移除：文档并入管理网站（/admin 的「API 文档」标签页，管理员会话保护）
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)

# CORS 配置（允许客户端请求）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由（业务路由不变；admin/pair 为本次新增）
app.include_router(auth_router)
app.include_router(family_router)
app.include_router(family_control_router)
app.include_router(vault_router)
app.include_router(admin_router)
app.include_router(pair_router)


@app.get("/")
def root():
    """根路由"""
    return {
        "name": "智能药箱 API",
        "version": "1.0.0",
        "admin": "/admin",
    }


@app.get("/api/health")
def health():
    """健康检查"""
    return {"status": "ok"}


@app.get("/admin", include_in_schema=False)
@app.get("/admin/login", include_in_schema=False)
def admin_page():
    """管理网站：静态空壳页面，全部数据来自 Cookie 认证的 /api/admin/*"""
    return FileResponse(_ADMIN_HTML)


if __name__ == "__main__":
    import uvicorn
    kwargs = {}
    if config.DEPLOY_MODE == "lan":
        cert, key = server_identity.ensure_identity()
        kwargs["ssl_keyfile"] = key
        kwargs["ssl_certfile"] = cert
    else:
        # 公网模式：仅信任回环反向代理的转发头，取真实客户端 IP 用于限速
        kwargs["proxy_headers"] = True
        kwargs["forwarded_allow_ips"] = "127.0.0.1"
    uvicorn.run("main:app", host=config.HOST, port=config.PORT, reload=True, **kwargs)
