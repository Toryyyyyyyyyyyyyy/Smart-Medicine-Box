# Copyright (c) 2026 Tory_zy Tu

"""服务器运维命令：python cli.py {pair|fingerprint|reset-admin}

pair         生成一次性局域网配对码（在服务器终端显示，供 App 配对使用）
fingerprint  打印服务器证书指纹（调试用）
reset-admin  重置管理员账号为配置中的初始凭据（危险操作，需输入 yes 确认）
"""
import argparse

from database import SessionLocal, init_db
from models import Admin
from services import admin_service, pairing_service, server_identity


def cmd_pair():
    init_db()
    db = SessionLocal()
    try:
        code = pairing_service.new_pairing_code(db)
    finally:
        db.close()
    print("=" * 58)
    print(f"  配对码: {code}")
    print(f"  （{pairing_service.PAIR_TTL_SECONDS // 60} 分钟内有效，仅可使用一次）")
    print("  在 App 的 我的 → 服务器 中填写 https 地址，按提示输入该码。")
    print("=" * 58)


def cmd_fingerprint():
    print(server_identity.get_fingerprint())


def cmd_reset_admin():
    print("!! 警告：此操作将删除现有管理员账号并重置为配置中的初始用户名/密码 !!")
    print("!! （.env 的 ADMIN_USERNAME / ADMIN_PASSWORD，未配置则为 Admin / 123456） !!")
    confirm = input("输入 yes 确认: ").strip()
    if confirm != "yes":
        print("已取消")
        return
    init_db()
    db = SessionLocal()
    try:
        db.query(Admin).delete()
        db.commit()
        admin_service.ensure_default_admin(db)
    finally:
        db.close()
    print("管理员已重置为初始凭据。请立即登录管理网站修改密码。")


def main():
    parser = argparse.ArgumentParser(description="智能药箱服务器运维命令")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("pair", help="生成一次性局域网配对码")
    sub.add_parser("fingerprint", help="打印服务器证书指纹（调试）")
    sub.add_parser("reset-admin", help="重置管理员账号（危险）")
    args = parser.parse_args()
    if args.command == "pair":
        cmd_pair()
    elif args.command == "fingerprint":
        cmd_fingerprint()
    elif args.command == "reset-admin":
        cmd_reset_admin()


if __name__ == "__main__":
    main()
