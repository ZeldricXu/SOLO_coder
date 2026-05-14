import uuid
from typing import Optional, List, Dict, Any
from sqlalchemy.orm import Session

from reporthub.models import ReportTemplate


class TemplateModule:
    def __init__(self, db: Session):
        self.db = db

    def create_template(self, template_name: str, template_type: str, data_source: Dict[str, Any],
                        fields: List[Dict[str, Any]], filters: Optional[List[Dict[str, Any]]] = None) -> ReportTemplate:
        template_id = f"template_{uuid.uuid4().hex[:12]}"
        template = ReportTemplate(
            template_id=template_id,
            template_name=template_name,
            template_type=template_type,
            data_source=data_source,
            fields=fields,
            filters=filters or []
        )
        self.db.add(template)
        self.db.commit()
        self.db.refresh(template)
        return template

    def get_template(self, template_id: str) -> Optional[ReportTemplate]:
        return self.db.query(ReportTemplate).filter(ReportTemplate.template_id == template_id).first()

    def get_all_templates(self) -> List[ReportTemplate]:
        return self.db.query(ReportTemplate).all()

    def update_template(self, template_id: str, **kwargs) -> Optional[ReportTemplate]:
        template = self.get_template(template_id)
        if not template:
            return None
        for key, value in kwargs.items():
            if hasattr(template, key):
                setattr(template, key, value)
        self.db.commit()
        self.db.refresh(template)
        return template

    def delete_template(self, template_id: str) -> bool:
        template = self.get_template(template_id)
        if not template:
            return False
        self.db.delete(template)
        self.db.commit()
        return True
