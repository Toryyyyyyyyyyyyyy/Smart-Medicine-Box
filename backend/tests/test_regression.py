# Copyright (c) 2026 Tory_zy Tu

"""回归冒烟：证明安全改造未破坏现有业务认证流程"""
import base64
import hashlib
import hmac
from datetime import datetime

from models import User


def test_anonymous_endpoints_still_anonymous(client):
    assert client.get("/").status_code == 200
    assert client.get("/api/health").status_code == 200
    assert client.post("/api/auth/login-challenge", json={"user_code": "00000000"}).status_code == 200


def test_full_owner_flow(client):
    """注册 → 挑战 → 登录 → profile → vault 读写"""
    r = client.post("/api/auth/register", json={
        "username": "smoke",
        "password_salt": "c" * 32,
        "password_hash": "d" * 64,
    })
    assert r.status_code == 200, r.text
    data = r.json()["data"]
    code = data["user"]["user_code"]
    token = data["access_token"]

    profile = client.get("/api/auth/profile", headers={"Authorization": f"Bearer {token}"})
    assert profile.status_code == 200

    vault = client.get("/api/vault", headers={"Authorization": f"Bearer {token}"})
    assert vault.status_code == 200

    put = client.put("/api/vault/medicines", headers={"Authorization": f"Bearer {token}"}, json={
        "ciphertext": "QUJD", "expected_version": 0,
    })
    assert put.status_code == 200, put.text

    vault2 = client.get("/api/vault", headers={"Authorization": f"Bearer {token}"})
    assert vault2.status_code == 200
    assert vault2.json()["data"]["medicines"]["version"] == 1


def test_full_family_flow(client, db):
    """家属无登录访问：init → 证明 → verify → settings"""
    user = db.query(User).first()
    if user is None:
        r = client.post("/api/auth/register", json={
            "username": "senior",
            "password_salt": "e" * 32,
            "password_hash": "f" * 64,
        })
        assert r.status_code == 200
        user = db.query(User).first()

    family_pw = "family-pw-456"
    salt = base64.b64decode(user.enc_salt)
    verifier = hashlib.pbkdf2_hmac("sha256", family_pw.encode(), salt + b"auth", 150_000, 32)
    user.enc_verifier = base64.b64encode(verifier).decode()
    user.family_control_enabled = True
    user.last_seen_at = datetime.utcnow()
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

    settings = client.get("/api/family/control/settings", headers={"Authorization": f"Bearer {family_token}"})
    assert settings.status_code == 200
