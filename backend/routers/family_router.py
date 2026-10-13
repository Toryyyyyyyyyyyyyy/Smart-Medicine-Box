# Copyright (c) 2026 Tory_zy Tu

"""家属绑定接口"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from database import get_db
from auth import get_current_user
from models import User, FamilyBinding
from schemas import ApiResponse, FamilyBindRequest

router = APIRouter(prefix="/api/family", tags=["家属绑定"])


@router.post("/bind", response_model=ApiResponse)
def bind_senior(
    body: FamilyBindRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """家属发起绑定控制对象请求（通过用户id查找）"""
    # 查找控制对象
    senior = db.query(User).filter(
        User.user_code == body.senior_user_code.strip(),
    ).first()
    if not senior:
        raise HTTPException(status_code=404, detail="未找到该用户id的用户")

    if senior.id == current_user.id:
        raise HTTPException(status_code=400, detail="不能绑定自己")

    # 检查是否已绑定
    existing = db.query(FamilyBinding).filter(
        FamilyBinding.senior_id == senior.id,
        FamilyBinding.family_id == current_user.id,
    ).first()
    if existing:
        raise HTTPException(status_code=400, detail="已经绑定过该用户")

    binding = FamilyBinding(
        senior_id=senior.id,
        family_id=current_user.id,
        relation=body.relation if hasattr(body, 'relation') else "家属",
        is_accepted=True,  # 简化：直接接受
    )
    db.add(binding)
    db.commit()
    db.refresh(binding)

    return ApiResponse(message="绑定成功", data={
        "id": binding.id,
        "senior_name": senior.username,
        "relation": binding.relation,
    })


@router.get("/bindings", response_model=ApiResponse)
def get_bindings(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """获取用户的绑定列表"""
    # 作为控制对象的被绑定 + 作为家属的绑定
    as_senior = db.query(FamilyBinding).filter(
        FamilyBinding.senior_id == current_user.id,
        FamilyBinding.is_accepted == True,
    ).all()
    as_family = db.query(FamilyBinding).filter(
        FamilyBinding.family_id == current_user.id,
        FamilyBinding.is_accepted == True,
    ).all()

    seniors = []
    families = []

    for b in as_family:
        senior = db.query(User).filter(User.id == b.senior_id).first()
        if senior:
            seniors.append({
                "binding_id": b.id,
                "senior_id": senior.id,
                "senior_name": senior.username,
                "relation": b.relation,
            })

    for b in as_senior:
        family = db.query(User).filter(User.id == b.family_id).first()
        if family:
            families.append({
                "binding_id": b.id,
                "family_id": family.id,
                "family_name": family.username,
                "relation": b.relation,
            })

    return ApiResponse(data={
        "seniors": seniors,   # 我绑定的控制对象（我是家属）
        "families": families, # 绑定我的家属（我是控制对象）
    })


@router.delete("/bindings/{binding_id}", response_model=ApiResponse)
def unbind(
    binding_id: int,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    """解除绑定"""
    binding = db.query(FamilyBinding).filter(
        FamilyBinding.id == binding_id,
    ).first()
    if not binding:
        raise HTTPException(status_code=404, detail="绑定不存在")

    if binding.senior_id != current_user.id and binding.family_id != current_user.id:
        raise HTTPException(status_code=403, detail="无权操作")

    db.delete(binding)
    db.commit()
    return ApiResponse(message="已解除绑定")
