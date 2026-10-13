# Copyright (c) 2026 Tory_zy Tu

"""管理网站新增能力：API 文档（管理员保护）+ 配对码生成"""
import base64
import hashlib
import hmac

from conftest import admin_login, csrf_header
from services import pairing_service, server_identity


def test_docs_requires_login(client):
    assert client.get("/api/admin/docs").status_code == 401
    assert client.get("/api/admin/openapi").status_code == 401


def test_docs_served_to_admin(client):
    admin_login(client)
    r = client.get("/api/admin/docs")
    assert r.status_code == 200
    assert "swagger" in r.text.lower()


def test_openapi_served_to_admin(client):
    admin_login(client)
    r = client.get("/api/admin/openapi")
    assert r.status_code == 200
    data = r.json()
    assert data.get("openapi")
    assert "/api/auth/login" in data["paths"]


def test_pairing_code_requires_csrf(client):
    admin_login(client)
    r = client.post("/api/admin/pairing-code")
    assert r.status_code == 403


def test_pairing_code_generated_and_completes_full_verify(client, db):
    admin_login(client)
    r = client.post("/api/admin/pairing-code", headers=csrf_header(client))
    assert r.status_code == 200, r.text
    code = r.json()["data"]["code"]
    assert len(code) == 8 and code.isdigit()
    assert r.json()["data"]["ttl_seconds"] == pairing_service.PAIR_TTL_SECONDS
    # 生成的码可以走完整配对校验（与 App 的派生一致）
    key = hashlib.sha256(b"smart-medicine-box-pairing-v1" + code.encode("ascii")).digest()
    fp = server_identity.get_fingerprint()
    proof = base64.b64encode(hmac.new(key, fp.encode("ascii"), hashlib.sha256).digest()).decode()
    v = client.post("/api/pair/verify", json={"proof": proof, "fingerprint": fp})
    assert v.status_code == 200, v.text
    assert v.json()["data"]["fingerprint"] == fp


def test_pairing_code_disabled_in_public_mode(client, monkeypatch):
    import config
    monkeypatch.setattr(config, "DEPLOY_MODE", "public")
    admin_login(client)
    r = client.post("/api/admin/pairing-code", headers=csrf_header(client))
    assert r.status_code == 403
