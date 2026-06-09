from datetime import datetime
from typing import Any, Optional, Callable, TypeVar
from functools import wraps
from fastapi import Depends, Request
from sqlalchemy.orm import Session

from app.models.audit import AuditLog
from app.models.user import User
from app.core.database import get_db

F = TypeVar("F", bound=Callable[..., Any])


class AuditLogger:
    def __init__(self, db: Session):
        self.db = db

    def log(
        self,
        user_id: Optional[int],
        action: str,
        resource_type: str,
        resource_id: Optional[int] = None,
        old_value: Optional[dict[str, Any]] = None,
        new_value: Optional[dict[str, Any]] = None,
        ip_address: Optional[str] = None,
        user_agent: Optional[str] = None,
    ) -> AuditLog:
        audit_log = AuditLog(
            user_id=user_id,
            action=action,
            resource_type=resource_type,
            resource_id=resource_id,
            old_value=old_value,
            new_value=new_value,
            ip_address=ip_address,
            user_agent=user_agent,
            timestamp=datetime.utcnow(),
        )
        self.db.add(audit_log)
        self.db.flush()
        return audit_log

    def log_create(
        self,
        user: User,
        resource_type: str,
        resource_id: int,
        new_value: dict[str, Any],
        ip_address: Optional[str] = None,
        user_agent: Optional[str] = None,
    ) -> AuditLog:
        return self.log(
            user_id=user.id,
            action="create",
            resource_type=resource_type,
            resource_id=resource_id,
            new_value=new_value,
            ip_address=ip_address,
            user_agent=user_agent,
        )

    def log_update(
        self,
        user: User,
        resource_type: str,
        resource_id: int,
        old_value: dict[str, Any],
        new_value: dict[str, Any],
        ip_address: Optional[str] = None,
        user_agent: Optional[str] = None,
    ) -> AuditLog:
        return self.log(
            user_id=user.id,
            action="update",
            resource_type=resource_type,
            resource_id=resource_id,
            old_value=old_value,
            new_value=new_value,
            ip_address=ip_address,
            user_agent=user_agent,
        )

    def log_delete(
        self,
        user: User,
        resource_type: str,
        resource_id: int,
        old_value: dict[str, Any],
        ip_address: Optional[str] = None,
        user_agent: Optional[str] = None,
    ) -> AuditLog:
        return self.log(
            user_id=user.id,
            action="delete",
            resource_type=resource_type,
            resource_id=resource_id,
            old_value=old_value,
            ip_address=ip_address,
            user_agent=user_agent,
        )

    def log_login(
        self,
        user: User,
        ip_address: Optional[str] = None,
        user_agent: Optional[str] = None,
    ) -> AuditLog:
        return self.log(
            user_id=user.id,
            action="login",
            resource_type="user",
            resource_id=user.id,
            ip_address=ip_address,
            user_agent=user_agent,
        )

    def log_logout(
        self,
        user: User,
        ip_address: Optional[str] = None,
        user_agent: Optional[str] = None,
    ) -> AuditLog:
        return self.log(
            user_id=user.id,
            action="logout",
            resource_type="user",
            resource_id=user.id,
            ip_address=ip_address,
            user_agent=user_agent,
        )

    def log_action(
        self,
        action: str,
        resource_type: str,
    ) -> Callable[[F], F]:
        def decorator(func: F) -> F:
            @wraps(func)
            def wrapper(*args: Any, **kwargs: Any) -> Any:
                request: Optional[Request] = kwargs.get("request")
                db: Optional[Session] = kwargs.get("db")
                current_user_id: Optional[int] = kwargs.get("current_user_id")

                ip_address = None
                user_agent = None
                if request:
                    ip_address = getattr(request, "client", None)
                    if ip_address:
                        ip_address = ip_address.host
                    user_agent = request.headers.get("user-agent")

                result = func(*args, **kwargs)

                if db and current_user_id:
                    resource_id = None
                    if result and hasattr(result, "data"):
                        data = result.data
                        if hasattr(data, "id"):
                            resource_id = data.id
                        elif isinstance(data, dict) and "id" in data:
                            resource_id = data["id"]

                    self.log(
                        user_id=current_user_id,
                        action=action,
                        resource_type=resource_type,
                        resource_id=resource_id,
                        ip_address=ip_address,
                        user_agent=user_agent,
                    )

                return result

            return wrapper  # type: ignore

        return decorator


class _AuditLoggerProxy:
    def log_action(
        self,
        action: str,
        resource_type: str,
    ) -> Callable[[F], F]:
        def decorator(func: F) -> F:
            @wraps(func)
            def wrapper(
                *args: Any,
                db: Session = Depends(get_db),
                request: Optional[Request] = None,
                current_user_id: Optional[int] = None,
                **kwargs: Any,
            ) -> Any:
                logger = AuditLogger(db)
                action_decorator = logger.log_action(action, resource_type)
                decorated_func = action_decorator(func)
                return decorated_func(
                    *args,
                    db=db,
                    request=request,
                    current_user_id=current_user_id,
                    **kwargs,
                )

            return wrapper  # type: ignore

        return decorator


audit_logger = _AuditLoggerProxy()
