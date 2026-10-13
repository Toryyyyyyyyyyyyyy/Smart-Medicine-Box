# Copyright (c) 2026 Tory_zy Tu

"""服务器身份：幂等生成、指纹稳定、与独立计算一致"""
import hashlib
import os

from cryptography import x509
from cryptography.hazmat.primitives import serialization

import config
from services import server_identity


def test_ensure_identity_creates_and_reuses():
    cert, key = server_identity.ensure_identity()
    assert os.path.exists(cert) and os.path.exists(key)
    mtime_cert = os.path.getmtime(cert)
    cert2, key2 = server_identity.ensure_identity()
    assert (cert2, key2) == (cert, key)
    assert os.path.getmtime(cert) == mtime_cert  # 未重新生成


def test_fingerprint_stable_and_matches_independent_computation():
    fp1 = server_identity.get_fingerprint()
    fp2 = server_identity.get_fingerprint()
    assert fp1 == fp2
    with open(config.TLS_CERT_FILE, "rb") as f:
        cert = x509.load_pem_x509_certificate(f.read())
    spki = cert.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    assert fp1 == hashlib.sha256(spki).hexdigest()
    assert len(fp1) == 64
