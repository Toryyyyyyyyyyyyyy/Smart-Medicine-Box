# Copyright (c) 2026 Tory_zy Tu

"""服务器身份密钥与自签证书：局域网 TLS 与配对的身份锚点。

首次启动生成 EC P-256 密钥对 + 自签证书并持久化到 server_identity/，
此后重启复用同一身份。删除该目录即更换服务器身份——已配对客户端会
检测到证书指纹不匹配并明确提示重新配对（设计行为，绝不静默接受）。
"""
import datetime
import hashlib
import ipaddress
import os
import socket

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

import config

_CERT_VALID_YEARS = 10


def _detect_lan_ips():
    """尽力探测本机局域网 IPv4（UDP 连接技巧，不实际发包）"""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
        finally:
            s.close()
    except OSError:
        pass
    return [ip for ip in ips if not ip.startswith("127.")]


def ensure_identity():
    """确保身份密钥与自签证书存在，返回 (cert_path, key_path)。已存在则直接复用，绝不重新生成。"""
    if os.path.exists(config.TLS_CERT_FILE) and os.path.exists(config.TLS_KEY_FILE):
        return config.TLS_CERT_FILE, config.TLS_KEY_FILE
    os.makedirs(os.path.dirname(config.TLS_CERT_FILE) or config.IDENTITY_DIR, exist_ok=True)

    key = ec.generate_private_key(ec.SECP256R1())

    hostname = socket.gethostname()
    san_entries = [
        x509.DNSName("localhost"),
        x509.DNSName(hostname),
        x509.IPAddress(ipaddress.IPv4Address("127.0.0.1")),
    ]
    for ip in _detect_lan_ips():
        try:
            san_entries.append(x509.IPAddress(ipaddress.IPv4Address(ip)))
        except ValueError:
            pass

    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "SmartMedicineBox Server")])
    now = datetime.datetime.utcnow()
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(subject)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365 * _CERT_VALID_YEARS))
        .add_extension(x509.SubjectAlternativeName(san_entries), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    with open(config.TLS_KEY_FILE, "wb") as f:
        f.write(key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        ))
    try:
        os.chmod(config.TLS_KEY_FILE, 0o600)
    except OSError:
        pass
    with open(config.TLS_CERT_FILE, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    return config.TLS_CERT_FILE, config.TLS_KEY_FILE


def get_fingerprint() -> str:
    """服务器 SPKI 指纹：SHA-256(证书公钥 DER) 的小写 hex——配对协议的信任锚。

    与 Android 端 X509Certificate.publicKey.encoded 的 SHA-256 逐字节一致。
    """
    with open(config.TLS_CERT_FILE, "rb") as f:
        cert = x509.load_pem_x509_certificate(f.read())
    spki_der = cert.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return hashlib.sha256(spki_der).hexdigest()
