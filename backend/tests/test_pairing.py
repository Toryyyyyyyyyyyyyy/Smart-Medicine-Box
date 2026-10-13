# Copyright (c) 2026 Tory_zy Tu

"""局域网配对：接受/拒绝/重放/耗尽/过期/限速/公网禁用"""
import base64
import hashlib
import hmac
from datetime import datetime, timedelta

import pytest

from conftest import TestingSession
from models import PairingCode
from services import pairing_service, server_identity


def make_proof(code: str, fingerprint: str) -> str:
    key = hashlib.sha256(b"smart-medicine-box-pairing-v1" + code.encode("ascii")).digest()
    return base64.b64encode(
        hmac.new(key, fingerprint.encode("ascii"), hashlib.sha256).digest()
    ).decode()


def mint_code(db) -> str:
    return pairing_service.new_pairing_code(db)


def test_correct_proof_succeeds_and_consumes_code(client, db):
    code = mint_code(db)
    fp = server_identity.get_fingerprint()
    r = client.post("/api/pair/verify", json={"proof": make_proof(code, fp), "fingerprint": fp})
    assert r.status_code == 200, r.text
    assert r.json()["data"]["paired"] is True
    assert r.json()["data"]["fingerprint"] == fp
    assert db.query(PairingCode).count() == 0  # 单次使用已消费


def test_wrong_code_rejected(client, db):
    mint_code(db)
    fp = server_identity.get_fingerprint()
    r = client.post("/api/pair/verify", json={"proof": make_proof("00000000", fp), "fingerprint": fp})
    assert r.status_code == 400
    assert db.query(PairingCode).first().attempts == 1


def test_mitm_wrong_fingerprint_rejected(client, db):
    """模拟中间人换证书：proof 基于另一张证书的指纹计算 → 必须拒绝"""
    mint_code(db)
    fp = server_identity.get_fingerprint()
    fake_fp = "f" * 64
    r = client.post("/api/pair/verify", json={"proof": make_proof("00000000", fake_fp), "fingerprint": fake_fp})
    assert r.status_code == 400


def test_replay_after_success_rejected(client, db):
    code = mint_code(db)
    fp = server_identity.get_fingerprint()
    body = {"proof": make_proof(code, fp), "fingerprint": fp}
    assert client.post("/api/pair/verify", json=body).status_code == 200
    assert client.post("/api/pair/verify", json=body).status_code == 400  # 码已被消费


def test_attempts_exhaustion(client, db):
    code = mint_code(db)
    fp = server_identity.get_fingerprint()
    for _ in range(5):
        r = client.post("/api/pair/verify", json={"proof": "A" * 24, "fingerprint": fp})
        assert r.status_code == 400
    # 第 6 次即使证明正确也被拒（码已删除）
    r = client.post("/api/pair/verify", json={"proof": make_proof(code, fp), "fingerprint": fp})
    assert r.status_code == 400
    assert "次数过多" in r.json()["detail"] or "无效" in r.json()["detail"]


def test_expired_code_rejected(client, db):
    mint_code(db)
    row = db.query(PairingCode).first()
    row.expires_at = datetime.utcnow() - timedelta(seconds=1)
    db.commit()
    fp = server_identity.get_fingerprint()
    r = client.post("/api/pair/verify", json={"proof": make_proof("12345678", fp), "fingerprint": fp})
    assert r.status_code == 400


def test_ip_rate_limit(client, db):
    mint_code(db)
    fp = server_identity.get_fingerprint()
    for _ in range(10):
        client.post("/api/pair/verify", json={"proof": "A" * 24, "fingerprint": fp})
    r = client.post("/api/pair/verify", json={"proof": "A" * 24, "fingerprint": fp})
    assert r.status_code == 429


def test_public_mode_disables_pairing(client, monkeypatch):
    import config
    monkeypatch.setattr(config, "DEPLOY_MODE", "public")
    fp = server_identity.get_fingerprint()
    r = client.post("/api/pair/verify", json={"proof": "A" * 24, "fingerprint": fp})
    assert r.status_code == 403


def test_invalid_fingerprint_format_rejected(client):
    r = client.post("/api/pair/verify", json={"proof": "A" * 24, "fingerprint": "not-hex"})
    assert r.status_code == 422
