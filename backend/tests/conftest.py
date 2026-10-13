# Copyright (c) 2026 Tory_zy Tu

"""测试基座：内存 SQLite + 依赖覆盖，绝不触碰真实 medicine_box.db"""
import os
import sys

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from database import Base, get_db  # noqa: E402
from main import app  # noqa: E402
from services import admin_service, pairing_service, server_identity  # noqa: E402

engine = create_engine(
    "sqlite://",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSession = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def override_get_db():
    db = TestingSession()
    try:
        yield db
    finally:
        db.close()


app.dependency_overrides[get_db] = override_get_db


@pytest.fixture()
def db():
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    session = TestingSession()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture()
def client(db):
    # 不用 with 上下文（不运行 lifespan），避免初始化真实数据库
    admin_service.ensure_default_admin(db)
    admin_service._login_window.clear()
    admin_service._fail_count.clear()
    admin_service._locked_until.clear()
    pairing_service._ip_windows.clear()
    yield TestClient(app)


@pytest.fixture(scope="session", autouse=True)
def identity(tmp_path_factory):
    """把服务器身份证书指向临时目录（配对测试需要真实指纹）"""
    import config
    tmp = tmp_path_factory.mktemp("identity")
    config.TLS_CERT_FILE = str(tmp / "identity.crt")
    config.TLS_KEY_FILE = str(tmp / "identity.key")
    server_identity.ensure_identity()
    yield


def admin_login(client, username="Admin", password="123456"):
    """登录管理网站，返回响应（Cookie 由 client 自动携带）"""
    return client.post("/api/admin/login", json={"username": username, "password": password})


def csrf_header(client):
    """从 Cookie 取 CSRF 令牌构造请求头"""
    token = client.cookies.get("admin_csrf", "")
    return {"X-CSRF-Token": token}
