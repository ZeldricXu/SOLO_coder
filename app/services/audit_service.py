from datetime import datetime, timedelta
import logging
import csv
import io
from typing import Optional, Any
from sqlalchemy import and_, func, or_
from sqlalchemy.orm import Session

from app.models.audit import AuditLog as AuditLogModel, AuditAction
from app.models.user import User
from app.schemas.audit import (
    AuditLog as AuditLogSchema,
    AuditLogQuery,
    AuditStatisticsResponse,
    AuditAnomalyDetectionRequest,
    AuditAnomalyResponse,
    AuditExportRequest,
    AuditExportResponse,
    UserActivityStats,
    ResourceActivityStats,
    AuditLogDetail,
)
from app.utils.exceptions import InventoryException
from app.utils.helpers import get_current_utc_time, generate_code
from app.core.cache import cache

logger = logging.getLogger(__name__)

AUDIT_CACHE_TTL = 300
EXPORT_EXPIRY_HOURS = 24


class AuditService:
    def __init__(self, db: Session, user_id: Optional[int] = None):
        self.db = db
        self.user_id = user_id

    def _get_cache_key(self, log_id: int) -> str:
        return f"audit:log:{log_id}"

    def get_log(self, log_id: int) -> AuditLogModel:
        cache_key = self._get_cache_key(log_id)
        cached: Optional[AuditLogModel] = cache.get(cache_key)
        if cached:
            return cached

        log = self.db.query(AuditLogModel).filter(AuditLogModel.id == log_id).first()
        if not log:
            raise InventoryException(
                f"Audit log {log_id} not found", code=404
            )

        cache.set(cache_key, log, ttl=AUDIT_CACHE_TTL)
        return log

    def get_log_detail(self, log_id: int) -> AuditLogDetail:
        log = self.get_log(log_id)
        user = (
            self.db.query(User).filter(User.id == log.user_id).first()
            if log.user_id
            else None
        )

        changes_summary = []
        if log.old_value and log.new_value:
            all_keys = set(log.old_value.keys()) | set(log.new_value.keys())
            for key in all_keys:
                old_val = log.old_value.get(key)
                new_val = log.new_value.get(key)
                if old_val != new_val:
                    changes_summary.append(
                        {
                            "field": key,
                            "old_value": old_val,
                            "new_value": new_val,
                        }
                    )

        related_logs: Optional[list[AuditLogSchema]] = None
        if log.resource_type and log.resource_id:
            related_models = (
                self.db.query(AuditLogModel)
                .filter(
                    and_(
                        AuditLogModel.resource_type == log.resource_type,
                        AuditLogModel.resource_id == log.resource_id,
                        AuditLogModel.id != log.id,
                    )
                )
                .order_by(AuditLogModel.timestamp.desc())
                .limit(10)
                .all()
            )
            related_logs = [
                AuditLogSchema(
                    id=rl.id,
                    user_id=rl.user_id,
                    action=rl.action,
                    resource_type=rl.resource_type,
                    resource_id=rl.resource_id,
                    old_value=rl.old_value,
                    new_value=rl.new_value,
                    ip_address=rl.ip_address,
                    user_agent=rl.user_agent,
                    timestamp=rl.timestamp,
                    created_at=rl.created_at,
                )
                for rl in related_models
            ]

        return AuditLogDetail(
            id=log.id,
            user_id=log.user_id,
            action=log.action,
            resource_type=log.resource_type,
            resource_id=log.resource_id,
            old_value=log.old_value,
            new_value=log.new_value,
            ip_address=log.ip_address,
            user_agent=log.user_agent,
            timestamp=log.timestamp,
            created_at=log.created_at,
            username=user.username if user else None,
            resource_name=None,
            changes_summary=changes_summary,
            related_logs=related_logs,
        )

    def list_logs(
        self,
        filters: AuditLogQuery,
        skip: int = 0,
        limit: int = 20,
    ) -> list[AuditLogModel]:
        query = self.db.query(AuditLogModel)

        if filters.user_id:
            query = query.filter(AuditLogModel.user_id == filters.user_id)
        if filters.action:
            query = query.filter(AuditLogModel.action == filters.action)
        if filters.resource_type:
            query = query.filter(
                AuditLogModel.resource_type == filters.resource_type
            )
        if filters.resource_id:
            query = query.filter(AuditLogModel.resource_id == filters.resource_id)
        if filters.ip_address:
            query = query.filter(
                AuditLogModel.ip_address.like(f"%{filters.ip_address}%")
            )
        if filters.start_date:
            query = query.filter(AuditLogModel.timestamp >= filters.start_date)
        if filters.end_date:
            query = query.filter(AuditLogModel.timestamp <= filters.end_date)
        if filters.keyword:
            query = query.filter(
                or_(
                    AuditLogModel.resource_type.like(f"%{filters.keyword}%"),
                    AuditLogModel.ip_address.like(f"%{filters.keyword}%"),
                    AuditLogModel.user_agent.like(f"%{filters.keyword}%"),
                )
            )

        return (
            query.order_by(AuditLogModel.timestamp.desc())
            .offset(skip)
            .limit(limit)
            .all()
        )

    def count_logs(self, filters: AuditLogQuery) -> int:
        query = self.db.query(func.count(AuditLogModel.id))

        if filters.user_id:
            query = query.filter(AuditLogModel.user_id == filters.user_id)
        if filters.action:
            query = query.filter(AuditLogModel.action == filters.action)
        if filters.resource_type:
            query = query.filter(
                AuditLogModel.resource_type == filters.resource_type
            )
        if filters.resource_id:
            query = query.filter(AuditLogModel.resource_id == filters.resource_id)
        if filters.ip_address:
            query = query.filter(
                AuditLogModel.ip_address.like(f"%{filters.ip_address}%")
            )
        if filters.start_date:
            query = query.filter(AuditLogModel.timestamp >= filters.start_date)
        if filters.end_date:
            query = query.filter(AuditLogModel.timestamp <= filters.end_date)
        if filters.keyword:
            query = query.filter(
                or_(
                    AuditLogModel.resource_type.like(f"%{filters.keyword}%"),
                    AuditLogModel.ip_address.like(f"%{filters.keyword}%"),
                    AuditLogModel.user_agent.like(f"%{filters.keyword}%"),
                )
            )

        return query.scalar() or 0

    def get_statistics(
        self, filters: Optional[AuditLogQuery] = None
    ) -> AuditStatisticsResponse:
        if filters is None:
            filters = AuditLogQuery(
                user_id=None,
                action=None,
                resource_type=None,
                resource_id=None,
                ip_address=None,
                start_date=None,
                end_date=None,
                keyword=None,
            )

        base_query = self.db.query(AuditLogModel)

        if filters.user_id:
            base_query = base_query.filter(AuditLogModel.user_id == filters.user_id)
        if filters.resource_type:
            base_query = base_query.filter(
                AuditLogModel.resource_type == filters.resource_type
            )
        if filters.start_date:
            base_query = base_query.filter(
                AuditLogModel.timestamp >= filters.start_date
            )
        if filters.end_date:
            base_query = base_query.filter(
                AuditLogModel.timestamp <= filters.end_date
            )

        now = get_current_utc_time()
        today_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        week_start = today_start - timedelta(days=now.weekday())
        month_start = today_start.replace(day=1)

        total_count = base_query.count()
        today_count = base_query.filter(
            AuditLogModel.timestamp >= today_start
        ).count()
        week_count = base_query.filter(
            AuditLogModel.timestamp >= week_start
        ).count()
        month_count = base_query.filter(
            AuditLogModel.timestamp >= month_start
        ).count()

        by_user = {}
        user_stats = (
            base_query.with_entities(
                AuditLogModel.user_id, func.count(AuditLogModel.id)
            )
            .group_by(AuditLogModel.user_id)
            .limit(20)
            .all()
        )
        for user_id, count in user_stats:
            user = (
                self.db.query(User).filter(User.id == user_id).first()
            )
            username = user.username if user else f"user_{user_id}"
            by_user[username] = count

        by_action = {}
        action_stats = (
            base_query.with_entities(
                AuditLogModel.action, func.count(AuditLogModel.id)
            )
            .group_by(AuditLogModel.action)
            .all()
        )
        for action, count in action_stats:
            by_action[action.value if hasattr(action, "value") else str(action)] = count

        by_resource = {}
        resource_stats = (
            base_query.with_entities(
                AuditLogModel.resource_type, func.count(AuditLogModel.id)
            )
            .group_by(AuditLogModel.resource_type)
            .limit(20)
            .all()
        )
        for resource_type, count in resource_stats:
            by_resource[resource_type] = count

        by_hour = {}
        hour_stats = (
            base_query.with_entities(
                func.extract("hour", AuditLogModel.timestamp),
                func.count(AuditLogModel.id),
            )
            .group_by(func.extract("hour", AuditLogModel.timestamp))
            .all()
        )
        for hour, count in hour_stats:
            by_hour[int(hour)] = count

        by_day = {}
        day_stats = (
            base_query.with_entities(
                func.date(AuditLogModel.timestamp),
                func.count(AuditLogModel.id),
            )
            .group_by(func.date(AuditLogModel.timestamp))
            .order_by(func.date(AuditLogModel.timestamp).desc())
            .limit(30)
            .all()
        )
        for day, count in day_stats:
            by_day[str(day)] = count

        return AuditStatisticsResponse(
            total_count=total_count,
            today_count=today_count,
            week_count=week_count,
            month_count=month_count,
            by_user=by_user,
            by_action=by_action,
            by_resource=by_resource,
            by_hour=by_hour,
            by_day=by_day,
        )

    def detect_anomalies(
        self, request: AuditAnomalyDetectionRequest
    ) -> list[AuditAnomalyResponse]:
        now = get_current_utc_time()
        window_start = now - timedelta(minutes=request.time_window_minutes)

        anomalies: list[AuditAnomalyResponse] = []

        user_operations = (
            self.db.query(
                AuditLogModel.user_id,
                func.count(AuditLogModel.id).label("op_count"),
                func.min(AuditLogModel.timestamp).label("first_op"),
                func.max(AuditLogModel.timestamp).label("last_op"),
            )
            .filter(AuditLogModel.timestamp >= window_start)
            .group_by(AuditLogModel.user_id)
            .having(func.count(AuditLogModel.id) >= request.operation_threshold)
            .all()
        )

        for user_id, op_count, first_op, last_op in user_operations:
            user = (
                self.db.query(User).filter(User.id == user_id).first()
            )
            risk_level = "MEDIUM"
            if op_count >= request.operation_threshold * 3:
                risk_level = "HIGH"
            elif op_count >= request.operation_threshold * 2:
                risk_level = "MEDIUM"
            else:
                risk_level = "LOW"

            anomalies.append(
                AuditAnomalyResponse(
                    anomaly_type="HIGH_FREQUENCY_OPERATIONS",
                    user_id=user_id,
                    username=user.username if user else None,
                    ip_address=None,
                    operation_count=op_count,
                    time_window=f"{request.time_window_minutes} minutes",
                    risk_level=risk_level,
                    description=f"User performed {op_count} operations in {request.time_window_minutes} minutes",
                    first_operation_at=first_op,
                    last_operation_at=last_op,
                )
            )

        if request.check_non_working_hours:
            night_start_hour = request.working_hours_end
            night_end_hour = request.working_hours_start

            non_working_ops = (
                self.db.query(
                    AuditLogModel.user_id,
                    AuditLogModel.ip_address,
                    func.count(AuditLogModel.id).label("op_count"),
                    func.min(AuditLogModel.timestamp).label("first_op"),
                    func.max(AuditLogModel.timestamp).label("last_op"),
                )
                .filter(
                    and_(
                        AuditLogModel.timestamp >= window_start,
                        or_(
                            func.extract("hour", AuditLogModel.timestamp)
                            >= night_start_hour,
                            func.extract("hour", AuditLogModel.timestamp)
                            < night_end_hour,
                        ),
                    )
                )
                .group_by(AuditLogModel.user_id, AuditLogModel.ip_address)
                .having(func.count(AuditLogModel.id) >= 5)
                .all()
            )

            for user_id, ip_address, op_count, first_op, last_op in non_working_ops:
                user = (
                    self.db.query(User).filter(User.id == user_id).first()
                )
                anomalies.append(
                    AuditAnomalyResponse(
                        anomaly_type="NON_WORKING_HOURS_OPERATIONS",
                        user_id=user_id,
                        username=user.username if user else None,
                        ip_address=ip_address,
                        operation_count=op_count,
                        time_window=f"{request.time_window_minutes} minutes",
                        risk_level="HIGH",
                        description=f"User performed {op_count} operations during non-working hours (after {night_start_hour}:00 or before {night_end_hour}:00)",
                        first_operation_at=first_op,
                        last_operation_at=last_op,
                    )
                )

        ip_operations = (
            self.db.query(
                AuditLogModel.ip_address,
                func.count(AuditLogModel.id).label("op_count"),
                func.count(func.distinct(AuditLogModel.user_id)).label(
                    "user_count"
                ),
                func.min(AuditLogModel.timestamp).label("first_op"),
                func.max(AuditLogModel.timestamp).label("last_op"),
            )
            .filter(
                and_(
                    AuditLogModel.timestamp >= window_start,
                    AuditLogModel.ip_address.isnot(None),
                )
            )
            .group_by(AuditLogModel.ip_address)
            .having(func.count(AuditLogModel.id) >= request.operation_threshold * 2)
            .all()
        )

        for ip_address, op_count, user_count, first_op, last_op in ip_operations:
            risk_level = "MEDIUM"
            if user_count > 5:
                risk_level = "HIGH"

            anomalies.append(
                AuditAnomalyResponse(
                    anomaly_type="SUSPICIOUS_IP_ACTIVITY",
                    user_id=None,
                    username=None,
                    ip_address=ip_address,
                    operation_count=op_count,
                    time_window=f"{request.time_window_minutes} minutes",
                    risk_level=risk_level,
                    description=f"IP {ip_address} performed {op_count} operations from {user_count} different users",
                    first_operation_at=first_op,
                    last_operation_at=last_op,
                )
            )

        return anomalies

    def export_logs(
        self, request: AuditExportRequest
    ) -> AuditExportResponse:
        filters = request.query or AuditLogQuery(
            user_id=None,
            action=None,
            resource_type=None,
            resource_id=None,
            ip_address=None,
            start_date=None,
            end_date=None,
            keyword=None,
        )
        logs = self.list_logs(filters=filters, skip=0, limit=10000)

        output = io.StringIO()
        writer = csv.writer(output)

        headers = [
            "ID",
            "User ID",
            "Username",
            "Action",
            "Resource Type",
            "Resource ID",
            "IP Address",
            "Timestamp",
        ]
        if request.include_details:
            headers.extend(["Old Value", "New Value", "User Agent"])
        writer.writerow(headers)

        for log in logs:
            user = (
                self.db.query(User).filter(User.id == log.user_id).first()
                if log.user_id
                else None
            )
            row = [
                log.id,
                log.user_id,
                user.username if user else "",
                log.action.value if hasattr(log.action, "value") else str(log.action),
                log.resource_type,
                log.resource_id,
                log.ip_address or "",
                log.timestamp.isoformat(),
            ]
            if request.include_details:
                row.extend(
                    [
                        str(log.old_value) if log.old_value else "",
                        str(log.new_value) if log.new_value else "",
                        log.user_agent or "",
                    ]
                )
            writer.writerow(row)

        csv_content = output.getvalue()
        filename = f"audit_export_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv"
        file_size = len(csv_content.encode("utf-8"))

        export_code = generate_code("AUD", 12)
        cache_key = f"audit:export:{export_code}"
        cache.set(
            cache_key,
            {
                "filename": filename,
                "content": csv_content,
                "content_type": f"text/{request.export_format}",
            },
            ttl=EXPORT_EXPIRY_HOURS * 3600,
        )

        expires_at = now = get_current_utc_time() + timedelta(
            hours=EXPORT_EXPIRY_HOURS
        )

        return AuditExportResponse(
            download_url=f"/api/v1/audit/export/download/{export_code}",
            filename=filename,
            file_size=file_size,
            record_count=len(logs),
            expires_at=expires_at,
        )

    def get_user_activity_stats(
        self,
        user_id: Optional[int] = None,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        limit: int = 100,
    ) -> list[UserActivityStats]:
        query = self.db.query(
            AuditLogModel.user_id,
            func.count(AuditLogModel.id).label("total_ops"),
            func.sum(
                func.case(
                    (AuditLogModel.action == AuditAction.CREATE, 1), else_=0
                )
            ).label("create_ops"),
            func.sum(
                func.case(
                    (AuditLogModel.action == AuditAction.UPDATE, 1), else_=0
                )
            ).label("update_ops"),
            func.sum(
                func.case(
                    (AuditLogModel.action == AuditAction.DELETE, 1), else_=0
                )
            ).label("delete_ops"),
            func.sum(
                func.case(
                    (AuditLogModel.action == AuditAction.LOGIN, 1), else_=0
                )
            ).label("login_ops"),
            func.max(AuditLogModel.timestamp).label("last_active"),
        )

        if user_id:
            query = query.filter(AuditLogModel.user_id == user_id)
        if start_date:
            query = query.filter(AuditLogModel.timestamp >= start_date)
        if end_date:
            query = query.filter(AuditLogModel.timestamp <= end_date)

        query = query.group_by(AuditLogModel.user_id).order_by(
            func.count(AuditLogModel.id).desc()
        )

        if limit:
            query = query.limit(limit)

        results = query.all()

        stats = []
        for (
            uid,
            total_ops,
            create_ops,
            update_ops,
            delete_ops,
            login_ops,
            last_active,
        ) in results:
            user = (
                self.db.query(User).filter(User.id == uid).first()
                if uid
                else None
            )
            if not user:
                continue

            risk_score = self._calculate_user_risk_score(
                uid, create_ops, update_ops, delete_ops, login_ops
            )

            stats.append(
                UserActivityStats(
                    user_id=uid,
                    username=user.username,
                    total_operations=total_ops,
                    create_count=create_ops,
                    update_count=update_ops,
                    delete_count=delete_ops,
                    login_count=login_ops,
                    last_active_at=last_active,
                    risk_score=risk_score,
                )
            )

        return stats

    def _calculate_user_risk_score(
        self,
        user_id: int,
        create_ops: int,
        update_ops: int,
        delete_ops: int,
        login_ops: int,
    ) -> float:
        score = 0.0

        if delete_ops > create_ops * 2:
            score += 30.0

        if update_ops > create_ops * 5:
            score += 20.0

        if login_ops < 1 and (create_ops + update_ops + delete_ops) > 10:
            score += 40.0

        now = get_current_utc_time()
        window_start = now - timedelta(hours=1)
        recent_deletes = (
            self.db.query(func.count(AuditLogModel.id))
            .filter(
                and_(
                    AuditLogModel.user_id == user_id,
                    AuditLogModel.action == AuditAction.DELETE,
                    AuditLogModel.timestamp >= window_start,
                )
            )
            .scalar()
            or 0
        )
        if recent_deletes > 10:
            score += 30.0

        return min(100.0, score)

    def get_resource_activity_stats(
        self,
        resource_type: Optional[str] = None,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        limit: int = 50,
    ) -> list[ResourceActivityStats]:
        query = self.db.query(
            AuditLogModel.resource_type,
            func.count(AuditLogModel.id).label("total_ops"),
            func.sum(
                func.case(
                    (AuditLogModel.action == AuditAction.CREATE, 1), else_=0
                )
            ).label("create_ops"),
            func.sum(
                func.case(
                    (AuditLogModel.action == AuditAction.UPDATE, 1), else_=0
                )
            ).label("update_ops"),
            func.sum(
                func.case(
                    (AuditLogModel.action == AuditAction.DELETE, 1), else_=0
                )
            ).label("delete_ops"),
            func.max(AuditLogModel.timestamp).label("last_modified"),
        )

        if resource_type:
            query = query.filter(AuditLogModel.resource_type == resource_type)
        if start_date:
            query = query.filter(AuditLogModel.timestamp >= start_date)
        if end_date:
            query = query.filter(AuditLogModel.timestamp <= end_date)

        query = query.group_by(AuditLogModel.resource_type).order_by(
            func.count(AuditLogModel.id).desc()
        )

        if limit:
            query = query.limit(limit)

        results = query.all()

        stats = []
        for (
            rtype,
            total_ops,
            create_ops,
            update_ops,
            delete_ops,
            last_modified,
        ) in results:
            most_active_user = (
                self.db.query(AuditLogModel.user_id)
                .filter(AuditLogModel.resource_type == rtype)
                .group_by(AuditLogModel.user_id)
                .order_by(func.count(AuditLogModel.id).desc())
                .first()
            )

            username = None
            if most_active_user:
                user = (
                    self.db.query(User)
                    .filter(User.id == most_active_user[0])
                    .first()
                )
                if user:
                    username = user.username

            stats.append(
                ResourceActivityStats(
                    resource_type=rtype,
                    total_operations=total_ops,
                    create_count=create_ops,
                    update_count=update_ops,
                    delete_count=delete_ops,
                    most_active_user=username,
                    last_modified_at=last_modified,
                )
            )

        return stats

    def get_export_content(self, export_code: str) -> Optional[dict[str, Any]]:
        cache_key = f"audit:export:{export_code}"
        return cache.get(cache_key)


def create_audit_service(
    db: Session, user_id: Optional[int] = None
) -> AuditService:
    return AuditService(db, user_id)
