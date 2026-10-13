# Copyright (c) 2026 Tory_zy Tu

"""局域网配对接口：验证 App 提交的配对证明（详见 pairing_service 协议说明）"""
from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

import config
from database import get_db
from schemas import ApiResponse, PairVerifyRequest
from services import admin_service, pairing_service

router = APIRouter(tags=["局域网配对"])


@router.post("/api/pair/verify", response_model=ApiResponse)
def pair_verify(
    body: PairVerifyRequest,
    request: Request,
    db: Session = Depends(get_db),
):
    """校验配对证明；成功回显服务器指纹供客户端断言一致性。

    匿名接口但严格限速；公网模式（系统信任链已覆盖身份验证）下禁用。
    """
    if config.DEPLOY_MODE == "public":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="配对仅适用于局域网直连模式")
    try:
        fingerprint = pairing_service.verify_pairing(
            db, body.proof, body.fingerprint, admin_service.get_client_ip(request)
        )
    except pairing_service.PairingError as e:
        raise HTTPException(status_code=e.status_code, detail=e.detail)
    return ApiResponse(data={"paired": True, "fingerprint": fingerprint})
