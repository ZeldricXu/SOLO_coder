from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from typing import List, Optional
from app.core.database import get_db
from app.core.security import get_current_active_user, require_admin
from app.models import models
from app.schemas import schemas

router = APIRouter(prefix="/api/templates", tags=["模板管理"])


def _build_template_response(template: models.Template, db: Session) -> schemas.TemplateResponse:
    current_version = db.query(models.TemplateVersion).filter(
        models.TemplateVersion.template_id == template.id
    ).order_by(models.TemplateVersion.version.desc()).first()

    fields = [schemas.TemplateFieldResponse(
        id=f.id,
        field_key=f.field_key,
        field_name=f.field_name,
        field_type=f.field_type,
        options=f.options,
        placeholder=f.placeholder,
        is_required=f.is_required,
        sort_order=f.sort_order,
        is_risk_field=f.is_risk_field,
        is_plan_field=f.is_plan_field,
        is_achievement_field=f.is_achievement_field
    ) for f in template.fields]

    return schemas.TemplateResponse(
        id=template.id,
        name=template.name,
        description=template.description,
        is_default=template.is_default,
        is_active=template.is_active,
        created_at=template.created_at,
        current_version=current_version.version if current_version else 0,
        fields=fields
    )


def _create_version_snapshot(template: models.Template) -> list:
    return [
        {
            "field_key": f.field_key,
            "field_name": f.field_name,
            "field_type": f.field_type,
            "options": f.options,
            "placeholder": f.placeholder,
            "is_required": f.is_required,
            "sort_order": f.sort_order,
            "is_risk_field": f.is_risk_field,
            "is_plan_field": f.is_plan_field,
            "is_achievement_field": f.is_achievement_field
        }
        for f in sorted(template.fields, key=lambda x: x.sort_order)
    ]


@router.get("", response_model=List[schemas.TemplateResponse])
def list_templates(
    is_active: Optional[bool] = None,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    query = db.query(models.Template)
    if is_active is not None:
        query = query.filter(models.Template.is_active == is_active)
    templates = query.order_by(models.Template.id.asc()).all()
    return [_build_template_response(t, db) for t in templates]


@router.get("/default", response_model=schemas.TemplateResponse)
def get_default_template(
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    template = db.query(models.Template).filter(models.Template.is_default == True).first()
    if not template:
        template = db.query(models.Template).order_by(models.Template.id.asc()).first()
    if not template:
        raise HTTPException(status_code=404, detail="暂无模板，请先创建")
    return _build_template_response(template, db)


@router.get("/{template_id}", response_model=schemas.TemplateResponse)
def get_template(
    template_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    template = db.query(models.Template).filter(models.Template.id == template_id).first()
    if not template:
        raise HTTPException(status_code=404, detail="模板不存在")
    return _build_template_response(template, db)


@router.get("/{template_id}/versions", response_model=List[schemas.TemplateVersionResponse])
def list_template_versions(
    template_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(get_current_active_user)
):
    template = db.query(models.Template).filter(models.Template.id == template_id).first()
    if not template:
        raise HTTPException(status_code=404, detail="模板不存在")
    versions = db.query(models.TemplateVersion).filter(
        models.TemplateVersion.template_id == template_id
    ).order_by(models.TemplateVersion.version.desc()).all()
    return versions


@router.post("", response_model=schemas.TemplateResponse)
def create_template(
    data: schemas.TemplateCreate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    if data.is_default:
        db.query(models.Template).update({models.Template.is_default: False})
        db.flush()

    template = models.Template(
        name=data.name,
        description=data.description,
        is_default=data.is_default,
        created_by=current_user.id
    )
    db.add(template)
    db.flush()

    for idx, field_data in enumerate(data.fields):
        field_dict = field_data.model_dump(exclude={"sort_order"})
        field = models.TemplateField(
            template_id=template.id,
            sort_order=idx,
            **field_dict
        )
        db.add(field)

    db.flush()
    db.refresh(template)

    snapshot = _create_version_snapshot(template)
    version = models.TemplateVersion(
        template_id=template.id,
        version=1,
        change_note="初始版本",
        fields_snapshot=snapshot,
        created_by=current_user.id
    )
    db.add(version)

    db.commit()
    db.refresh(template)
    return _build_template_response(template, db)


@router.put("/{template_id}", response_model=schemas.TemplateResponse)
def update_template(
    template_id: int,
    data: schemas.TemplateUpdate,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    template = db.query(models.Template).filter(models.Template.id == template_id).first()
    if not template:
        raise HTTPException(status_code=404, detail="模板不存在")

    fields_changed = False

    if data.name is not None:
        template.name = data.name
    if data.description is not None:
        template.description = data.description
    if data.is_active is not None:
        template.is_active = data.is_active
    if data.is_default is not None:
        if data.is_default:
            db.query(models.Template).filter(models.Template.id != template_id).update(
                {models.Template.is_default: False}
            )
            db.flush()
        template.is_default = data.is_default

    if data.fields is not None:
        fields_changed = True
        db.query(models.TemplateField).filter(
            models.TemplateField.template_id == template_id
        ).delete()
        for idx, field_data in enumerate(data.fields):
            field_dict = field_data.model_dump(exclude={"sort_order"})
            field = models.TemplateField(
                template_id=template.id,
                sort_order=idx,
                **field_dict
            )
            db.add(field)
        db.flush()
        db.refresh(template)

    if fields_changed:
        last_version = db.query(models.TemplateVersion).filter(
            models.TemplateVersion.template_id == template_id
        ).order_by(models.TemplateVersion.version.desc()).first()
        new_version_num = (last_version.version if last_version else 0) + 1
        snapshot = _create_version_snapshot(template)
        version = models.TemplateVersion(
            template_id=template.id,
            version=new_version_num,
            change_note=data.change_note or f"版本 {new_version_num} 更新",
            fields_snapshot=snapshot,
            created_by=current_user.id
        )
        db.add(version)

    db.commit()
    db.refresh(template)
    return _build_template_response(template, db)


@router.delete("/{template_id}")
def delete_template(
    template_id: int,
    db: Session = Depends(get_db),
    current_user: models.User = Depends(require_admin)
):
    template = db.query(models.Template).filter(models.Template.id == template_id).first()
    if not template:
        raise HTTPException(status_code=404, detail="模板不存在")
    if template.is_default:
        raise HTTPException(status_code=400, detail="不能删除默认模板")
    used = db.query(models.WeeklyReport).filter(models.WeeklyReport.template_id == template_id).first()
    if used:
        template.is_active = False
        db.commit()
        return {"message": "模板已标记为停用（已有周报引用）"}
    db.query(models.TemplateField).filter(models.TemplateField.template_id == template_id).delete()
    db.query(models.TemplateVersion).filter(models.TemplateVersion.template_id == template_id).delete()
    db.delete(template)
    db.commit()
    return {"message": "删除成功"}
