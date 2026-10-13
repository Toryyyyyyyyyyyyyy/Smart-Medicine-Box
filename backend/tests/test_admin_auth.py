# Copyright (c) 2026 Tory_zy Tu

"""管理网站认证：登录/限速/改密/强制下线/CSRF/跨令牌隔离"""
from conftest import admin_login, csrf_header


def register_and_login_user(client):
    """注册并登录一个 App 用户，返回用户 token"""
    r = client.post("/api/auth/register", json={
        "username": "tester",
        "password_salt": "a" * 32,
        "password_hash": "b" * 64,
    })
    assert r.status_code == 200, r.text
    code = r.json()["data"]["user"]["user_code"]
    ch = client.post("/api/auth/login-challenge", json={"user_code": code})
    assert ch.status_code == 200, ch.text
    ch_data = ch.json()["data"]
    # proof = HMAC-SHA256(password_hash, challenge)
    import base64
    import hashlib
    import hmac
    proof = hmac.new(
        bytes.fromhex("b" * 64),
        base64.b64decode(ch_data["challenge"]),
        hashlib.sha256,
    ).digest()
    login = client.post("/api/auth/login", json={
        "user_code": code,
        "proof": base64.b64encode(proof).decode(),
    })
    assert login.status_code == 200, login.text
    return login.json()["data"]["access_token"]


def test_login_success_sets_cookies(client):
    r = admin_login(client)
    assert r.status_code == 200, r.text
    assert r.json()["data"]["username"] == "Admin"
    assert r.json()["data"]["is_default_password"] is True
    assert "admin_token" in client.cookies
    assert "admin_csrf" in client.cookies


def test_login_wrong_password_and_unknown_user_same_message(client):
    r1 = admin_login(client, "Admin", "wrong-password")
    r2 = admin_login(client, "Nobody", "whatever")
    assert r1.status_code == 401 and r2.status_code == 401
    assert r1.json()["detail"] == r2.json()["detail"]


def test_dashboard_requires_login(client):
    r = client.get("/api/admin/dashboard")
    assert r.status_code == 401


def test_dashboard_counts_and_default_flag(client):
    admin_login(client)
    r = client.get("/api/admin/dashboard")
    assert r.status_code == 200
    data = r.json()["data"]
    assert data["user_count"] >= 0
    assert data["is_default_password"] is True


def test_admin_page_served(client):
    r = client.get("/admin")
    assert r.status_code == 200
    assert "text/html" in r.headers["content-type"]


def test_change_password_flow(client):
    admin_login(client)
    # 旧密码错误
    r = client.post("/api/admin/change-password", headers=csrf_header(client), json={
        "old_password": "bad", "new_password": "new-password-1",
    })
    assert r.status_code == 400
    # 成功改密：新 Cookie 会话延续，旧会话失效
    old_cookie = client.cookies.get("admin_token")
    r = client.post("/api/admin/change-password", headers=csrf_header(client), json={
        "old_password": "123456", "new_password": "new-password-1",
    })
    assert r.status_code == 200, r.text
    new_cookie = client.cookies.get("admin_token")
    assert new_cookie and new_cookie != old_cookie
    # 旧密码无法再登录
    r = client.post("/api/admin/login", json={"username": "Admin", "password": "123456"})
    assert r.status_code == 401
    # 新密码可登录
    r = client.post("/api/admin/login", json={"username": "Admin", "password": "new-password-1"})
    assert r.status_code == 200
    # 默认密码标志已清除
    dash = client.get("/api/admin/dashboard")
    assert dash.json()["data"]["is_default_password"] is False


def test_logout_invalidates_session(client):
    admin_login(client)
    r = client.post("/api/admin/logout", headers=csrf_header(client))
    assert r.status_code == 200
    assert client.get("/api/admin/dashboard").status_code == 401


def test_login_rate_limit(client):
    for _ in range(10):
        client.post("/api/admin/login", json={"username": "Admin", "password": "bad"})
    r = client.post("/api/admin/login", json={"username": "Admin", "password": "123456"})
    # 连败 5 次触发 60s 锁定期，正确密码也被拒
    assert r.status_code == 429, r.text


def test_csrf_required_for_mutations(client):
    admin_login(client)
    # 缺 CSRF 头
    r = client.post("/api/admin/logout")
    assert r.status_code == 403
    # 错误 CSRF
    r = client.post("/api/admin/logout", headers={"X-CSRF-Token": "wrong"})
    assert r.status_code == 403
    # 正确 CSRF
    r = client.post("/api/admin/logout", headers=csrf_header(client))
    assert r.status_code == 200


def test_force_logout_kills_user_session(client, db):
    admin_login(client)
    user_token = register_and_login_user(client)
    assert client.get(
        "/api/auth/profile", headers={"Authorization": f"Bearer {user_token}"}
    ).status_code == 200
    r = client.post("/api/admin/users/1/force-logout", headers=csrf_header(client))
    assert r.status_code == 200, r.text
    assert client.get(
        "/api/auth/profile", headers={"Authorization": f"Bearer {user_token}"}
    ).status_code == 401


def test_force_logout_missing_user_404(client):
    admin_login(client)
    r = client.post("/api/admin/users/999/force-logout", headers=csrf_header(client))
    assert r.status_code == 404


def test_strict_mode_blocks_user_management(client, monkeypatch):
    import config
    monkeypatch.setattr(config, "ADMIN_FORCE_PASSWORD_CHANGE", True)
    admin_login(client)
    assert client.get("/api/admin/users").status_code == 403
    assert client.post("/api/admin/users/1/force-logout", headers=csrf_header(client)).status_code == 403


def test_cross_token_isolation_admin_cookie_on_business_api(client):
    admin_login(client)
    # admin Cookie 打业务 API：HTTPBearer 无凭据 → 拒绝（401/403 皆可）
    assert client.get("/api/auth/profile").status_code in (401, 403)
    assert client.get("/api/vault").status_code in (401, 403)


def test_cross_token_isolation_user_token_on_admin_api(client):
    user_token = register_and_login_user(client)
    r = client.get("/api/admin/dashboard", headers={"Authorization": f"Bearer {user_token}"})
    assert r.status_code == 401


def test_cross_token_isolation_family_token_on_admin_api(client, db):
    # 造一个已开启家人控制且「在线」的用户，走完家属 init/verify 拿家属令牌
    from datetime import datetime
    from models import User
    import base64
    import hashlib
    import hmac

    r = client.post("/api/auth/register", json={
        "username": "senior",
        "password_salt": "a" * 32,
        "password_hash": "b" * 64,
    })
    assert r.status_code == 200, r.text
    user = db.query(User).first()
    user.family_control_enabled = True
    user.last_seen_at = datetime.utcnow()
    db.commit()

    family_pw = "family-pw-123"
    salt = base64.b64decode(user.enc_salt)
    verifier = hashlib.pbkdf2_hmac("sha256", family_pw.encode(), salt + b"auth", 150_000, 32)
    user.enc_verifier = base64.b64encode(verifier).decode()
    db.commit()

    init = client.post("/api/family/access/init", json={"user_code": user.user_code})
    assert init.status_code == 200, init.text
    challenge = base64.b64decode(init.json()["data"]["challenge"])
    proof = hmac.new(verifier, challenge, hashlib.sha256).digest()
    verify = client.post("/api/family/access/verify", json={
        "user_code": user.user_code,
        "proof": base64.b64encode(proof).decode(),
    })
    assert verify.status_code == 200, verify.text
    family_token = verify.json()["data"]["access_token"]
    r = client.get("/api/admin/dashboard", headers={"Authorization": f"Bearer {family_token}"})
    assert r.status_code == 401
