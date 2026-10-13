# Copyright (c) 2026 Tory_zy Tu

"""数据库连接与初始化"""
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.orm import sessionmaker, declarative_base

from config import DATABASE_URL

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    """FastAPI 依赖：获取数据库会话"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


# 已有表需要后补的列（create_all 只增表不增列，用 ALTER TABLE 补齐）。
# key = 表名，value = 列名 -> 列定义（与 models.py 保持一致）。
_SCHEMA_PATCHES = {
    "users": {
        "user_code": "VARCHAR(12)",
        "username": "VARCHAR(20)",
        "password_salt": "VARCHAR(64)",
        "password_hash": "VARCHAR(128)",
        "family_control_enabled": "BOOLEAN NOT NULL DEFAULT 0",
        "family_control_view_only": "BOOLEAN NOT NULL DEFAULT 0",
        "enc_salt": "VARCHAR(64)",
        "enc_verifier": "VARCHAR(128)",
        "enc_wrapped_family_key": "VARCHAR(500)",
        "screen_aspect": "REAL",
        "last_seen_at": "DATETIME",
        "threshold_minutes": "INTEGER NOT NULL DEFAULT 60",
        "timezone": "VARCHAR(64)",
        "clock_offset_ms": "INTEGER NOT NULL DEFAULT 0",
        "enc_enabled": "BOOLEAN NOT NULL DEFAULT 0",
        "token_version": "INTEGER NOT NULL DEFAULT 0",
    },
}


def ensure_schema():
    """为已存在的表补齐缺失的列（幂等，无 Alembic 时的轻量迁移）"""
    inspector = inspect(engine)
    with engine.begin() as conn:
        for table_name, columns in _SCHEMA_PATCHES.items():
            if table_name not in inspector.get_table_names():
                continue
            existing = {c["name"] for c in inspector.get_columns(table_name)}
            for col_name, col_def in columns.items():
                if col_name not in existing:
                    conn.execute(text(
                        f"ALTER TABLE {table_name} ADD COLUMN {col_name} {col_def}"
                    ))
    # 刷新 inspector 缓存，避免同一进程内重复判断
    inspector.clear_cache()


def init_db():
    """初始化所有表，并补齐已有表缺失的列"""
    Base.metadata.create_all(bind=engine)
    ensure_schema()
