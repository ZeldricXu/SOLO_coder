import uuid
from typing import Optional, List, Dict, Any
from datetime import datetime
from sqlalchemy.orm import Session

from reporthub.models import ReportPermission


class PermissionModule:
    ROLES = {
        "admin": {"can_view": True, "can_generate": True, "can_export": True, "can_manage": True},
        "editor": {"can_view": True, "can_generate": True, "can_export": True, "can_manage": False},
        "viewer": {"can_view": True, "can_generate": False, "can_export": False, "can_manage": False}
    }

    def __init__(self, db: Session):
        self.db = db

    def grant_permission(self, template_id: str, user_id: str, role: str = "viewer",
                         custom_permissions: Optional[Dict[str, bool]] = None) -> ReportPermission:
        existing = self.db.query(ReportPermission).filter(
            ReportPermission.template_id == template_id,
            ReportPermission.user_id == user_id
        ).first()
        if existing:
            return self.update_permission(template_id, user_id, role=role, custom_permissions=custom_permissions)
        permission_id = f"perm_{uuid.uuid4().hex[:12]}"
        role_permissions = self.ROLES.get(role, self.ROLES["viewer"]).copy()
        if custom_permissions:
            role_permissions.update(custom_permissions)
        permission = ReportPermission(
            permission_id=permission_id,
            template_id=template_id,
            user_id=user_id,
            role=role,
            can_view=role_permissions["can_view"],
            can_generate=role_permissions["can_generate"],
            can_export=role_permissions["can_export"],
            can_manage=role_permissions["can_manage"]
        )
        self.db.add(permission)
        self.db.commit()
        self.db.refresh(permission)
        return permission

    def get_permission(self, template_id: str, user_id: str) -> Optional[ReportPermission]:
        return self.db.query(ReportPermission).filter(
            ReportPermission.template_id == template_id,
            ReportPermission.user_id == user_id
        ).first()

    def get_user_permissions(self, user_id: str) -> List[ReportPermission]:
        return self.db.query(ReportPermission).filter(ReportPermission.user_id == user_id).all()

    def get_template_permissions(self, template_id: str) -> List[ReportPermission]:
        return self.db.query(ReportPermission).filter(ReportPermission.template_id == template_id).all()

    def update_permission(self, template_id: str, user_id: str,
                          role: Optional[str] = None,
                          custom_permissions: Optional[Dict[str, bool]] = None) -> Optional[ReportPermission]:
        permission = self.get_permission(template_id, user_id)
        if not permission:
            return None
        if role and role in self.ROLES:
            role_permissions = self.ROLES[role].copy()
            permission.role = role
            permission.can_view = role_permissions["can_view"]
            permission.can_generate = role_permissions["can_generate"]
            permission.can_export = role_permissions["can_export"]
            permission.can_manage = role_permissions["can_manage"]
        if custom_permissions:
            if "can_view" in custom_permissions:
                permission.can_view = custom_permissions["can_view"]
            if "can_generate" in custom_permissions:
                permission.can_generate = custom_permissions["can_generate"]
            if "can_export" in custom_permissions:
                permission.can_export = custom_permissions["can_export"]
            if "can_manage" in custom_permissions:
                permission.can_manage = custom_permissions["can_manage"]
        self.db.commit()
        self.db.refresh(permission)
        return permission

    def revoke_permission(self, template_id: str, user_id: str) -> bool:
        permission = self.get_permission(template_id, user_id)
        if not permission:
            return False
        self.db.delete(permission)
        self.db.commit()
        return True

    def check_view_permission(self, template_id: str, user_id: str) -> bool:
        permission = self.get_permission(template_id, user_id)
        return permission and permission.can_view

    def check_generate_permission(self, template_id: str, user_id: str) -> bool:
        permission = self.get_permission(template_id, user_id)
        return permission and permission.can_generate

    def check_export_permission(self, template_id: str, user_id: str) -> bool:
        permission = self.get_permission(template_id, user_id)
        return permission and permission.can_export

    def check_manage_permission(self, template_id: str, user_id: str) -> bool:
        permission = self.get_permission(template_id, user_id)
        return permission and permission.can_manage

    def get_user_accessible_templates(self, user_id: str, action: str = "view") -> List[str]:
        permissions = self.get_user_permissions(user_id)
        accessible = []
        for p in permissions:
            if action == "view" and p.can_view:
                accessible.append(p.template_id)
            elif action == "generate" and p.can_generate:
                accessible.append(p.template_id)
            elif action == "export" and p.can_export:
                accessible.append(p.template_id)
            elif action == "manage" and p.can_manage:
                accessible.append(p.template_id)
        return accessible
