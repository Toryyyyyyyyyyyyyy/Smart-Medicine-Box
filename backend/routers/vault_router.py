# Copyright (c) 2026 Tory_zy Tu

"""端到端加密 vault：服务器只做密文哑存，不解析任何药品/记录字段。

    - 本人（普通 token）与家属（family_control token）共用 get_vault_access
    - 写操作在 view_only 时返回 403（仅允许查看）
    - blob 采用乐观锁（expected_version 不匹配返回 409）
"""
from datetime import datetime

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from sqlalchemy.orm import Session

from database import get_db
from auth import get_current_user, get_vault_access
from models import EncryptedBlob, EncryptedMedia, User
from schemas import (
    ApiResponse,
    EncryptionEnabledRequest,
    VaultBlobUpdateRequest,
)

router = APIRouter(tags=["端到端加密 vault"])


def _ensure_writable(access: dict) -> None:
    if access["view_only"]:
        raise HTTPException(status_code=403, detail="该用户已开启仅允许查看，无法进行此操作")


def _get_blob(db: Session, owner_id: int, kind: str) -> EncryptedBlob:
    return db.query(EncryptedBlob).filter(
        EncryptedBlob.owner_id == owner_id,
        EncryptedBlob.kind == kind,
    ).first()


def _blob_dict(blob: EncryptedBlob) -> dict:
    if blob is None:
        return {"ciphertext": None, "version": 0, "updated_at": None}
    return {
        "ciphertext": blob.ciphertext,
        "version": blob.version,
        "updated_at": blob.updated_at.isoformat() if blob.updated_at else None,
    }


@router.get("/api/vault", response_model=ApiResponse)
def get_vault(access: dict = Depends(get_vault_access), db: Session = Depends(get_db)):
    """获取全部密文：药品 blob、记录 blob、家属历史 blob、媒体元数据列表"""
    owner_id = access["owner_id"]
    medicines = _get_blob(db, owner_id, "medicines")
    records = _get_blob(db, owner_id, "records")
    family_history = _get_blob(db, owner_id, "family_history")
    media = db.query(EncryptedMedia).filter(EncryptedMedia.owner_id == owner_id).all()
    return ApiResponse(data={
        "medicines": _blob_dict(medicines),
        "records": _blob_dict(records),
        "family_history": _blob_dict(family_history),
        "media": [
            {
                "media_id": m.media_id,
                "size": m.size,
                "content_type": m.content_type,
                "filename": m.filename,
                "updated_at": m.updated_at.isoformat() if m.updated_at else None,
            }
            for m in media
        ],
    })


@router.put("/api/vault/medicines", response_model=ApiResponse)
def put_medicines_blob(
    body: VaultBlobUpdateRequest,
    access: dict = Depends(get_vault_access),
    db: Session = Depends(get_db),
):
    _ensure_writable(access)
    blob = _get_blob(db, access["owner_id"], "medicines")
    if blob is None:
        blob = EncryptedBlob(owner_id=access["owner_id"], kind="medicines", version=0)
        db.add(blob)
    if blob.version != body.expected_version:
        raise HTTPException(status_code=409, detail="版本冲突，请重新拉取后合并")
    blob.ciphertext = body.ciphertext
    blob.version += 1
    blob.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(blob)
    return ApiResponse(message="保存成功", data=_blob_dict(blob))


@router.put("/api/vault/records", response_model=ApiResponse)
def put_records_blob(
    body: VaultBlobUpdateRequest,
    access: dict = Depends(get_vault_access),
    db: Session = Depends(get_db),
):
    _ensure_writable(access)
    blob = _get_blob(db, access["owner_id"], "records")
    if blob is None:
        blob = EncryptedBlob(owner_id=access["owner_id"], kind="records", version=0)
        db.add(blob)
    if blob.version != body.expected_version:
        raise HTTPException(status_code=409, detail="版本冲突，请重新拉取后合并")
    blob.ciphertext = body.ciphertext
    blob.version += 1
    blob.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(blob)
    return ApiResponse(message="保存成功", data=_blob_dict(blob))


@router.put("/api/vault/family-history", response_model=ApiResponse)
def put_family_history_blob(
    body: VaultBlobUpdateRequest,
    access: dict = Depends(get_vault_access),
    db: Session = Depends(get_db),
):
    """家属历史记录 blob（端到端加密：密码随账号同步但服务器只见密文），乐观锁同药品/记录"""
    _ensure_writable(access)
    blob = _get_blob(db, access["owner_id"], "family_history")
    if blob is None:
        blob = EncryptedBlob(owner_id=access["owner_id"], kind="family_history", version=0)
        db.add(blob)
    if blob.version != body.expected_version:
        raise HTTPException(status_code=409, detail="版本冲突，请重新拉取后合并")
    blob.ciphertext = body.ciphertext
    blob.version += 1
    blob.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(blob)
    return ApiResponse(message="保存成功", data=_blob_dict(blob))


@router.post("/api/vault/media", response_model=ApiResponse)
async def upload_media(
    media_id: str = Form(...),
    content_type: str = Form(default="application/octet-stream"),
    filename: str = Form(default=""),
    file: UploadFile = File(...),
    access: dict = Depends(get_vault_access),
    db: Session = Depends(get_db),
):
    """上传加密媒体（客户端已用 mediaKey 加密的密文字节）"""
    _ensure_writable(access)
    data = await file.read()
    existing = db.query(EncryptedMedia).filter(
        EncryptedMedia.owner_id == access["owner_id"],
        EncryptedMedia.media_id == media_id,
    ).first()
    if existing:
        existing.ciphertext = data
        existing.size = len(data)
        existing.content_type = content_type
        existing.filename = filename
        existing.updated_at = datetime.utcnow()
    else:
        db.add(EncryptedMedia(
            owner_id=access["owner_id"],
            media_id=media_id,
            ciphertext=data,
            content_type=content_type,
            filename=filename,
            size=len(data),
        ))
    db.commit()
    return ApiResponse(message="上传成功", data={"media_id": media_id, "size": len(data)})


@router.get("/api/vault/media/{media_id}")
def get_media(
    media_id: str,
    access: dict = Depends(get_vault_access),
    db: Session = Depends(get_db),
):
    """下载加密媒体（返回密文字节，客户端本地解密）"""
    m = db.query(EncryptedMedia).filter(
        EncryptedMedia.owner_id == access["owner_id"],
        EncryptedMedia.media_id == media_id,
    ).first()
    if not m:
        raise HTTPException(status_code=404, detail="媒体不存在")
    return Response(content=m.ciphertext or b"", media_type=m.content_type)


@router.delete("/api/vault/media/{media_id}", response_model=ApiResponse)
def delete_media(
    media_id: str,
    access: dict = Depends(get_vault_access),
    db: Session = Depends(get_db),
):
    _ensure_writable(access)
    m = db.query(EncryptedMedia).filter(
        EncryptedMedia.owner_id == access["owner_id"],
        EncryptedMedia.media_id == media_id,
    ).first()
    if not m:
        raise HTTPException(status_code=404, detail="媒体不存在")
    db.delete(m)
    db.commit()
    return ApiResponse(message="删除成功")


@router.put("/api/auth/encryption-enabled", response_model=ApiResponse)
def set_encryption_enabled(
    body: EncryptionEnabledRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """置端到端加密开关（迁移全部上传成功后由本人调用）"""
    current_user.enc_enabled = body.enabled
    db.commit()
    db.refresh(current_user)
    return ApiResponse(message="已更新", data={"enc_enabled": current_user.enc_enabled})
