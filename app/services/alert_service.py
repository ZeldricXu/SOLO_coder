from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any, Tuple
import json
import httpx
import smtplib
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart

from sqlalchemy.orm import Session
from sqlalchemy import and_, or_, func, desc, asc

from app.core.cache import cache
from app.core.logging import get_logger
from app.core.audit import AuditLogger
from app.core.config import settings
from app.models.inventory_alert import (
    AlertRule,
    InventoryAlert,
    AlertRuleType,
    ThresholdType,
    AlertLevel,
    AlertStatus,
)
from app.models.inventory import Inventory
from app.models.sku import SKU, SkuStatus
from app.models.warehouse import Warehouse
from app.models.category import Category
from app.models.batch import Batch
from app.models.user import User
from app.schemas.alert import (
    AlertRuleCreate,
    AlertRuleUpdate,
    AlertAcknowledgeRequest,
    AlertResolveRequest,
    AlertStatisticsResponse,
    AlertCheckResponse,
)
from app.services.crud_base import CRUDBase
from app.utils.forecast.seasonal import (
    seasonal_decompose,
    detect_seasonality,
    calculate_seasonal_indices,
)

logger = get_logger(__name__)


class NotificationService:
    @staticmethod
    async def send_email(recipients: List[str], subject: str, content: str) -> bool:
        try:
            if not settings.SMTP_HOST or not settings.SMTP_USER:
                logger.warning("SMTP not configured, skipping email notification")
                return False

            msg = MIMEMultipart()
            msg["From"] = settings.SMTP_FROM_EMAIL
            msg["To"] = ", ".join(recipients)
            msg["Subject"] = subject
            msg.attach(MIMEText(content, "html", "utf-8"))

            with smtplib.SMTP(settings.SMTP_HOST, settings.SMTP_PORT) as server:
                server.starttls()
                server.login(settings.SMTP_USER, settings.SMTP_PASSWORD)
                server.send_message(msg)

            logger.info("Email notification sent", recipients=recipients)
            return True
        except Exception as e:
            logger.error("Failed to send email notification", error=str(e))
            return False

    @staticmethod
    async def send_dingtalk(webhook_url: str, content: str, is_at_all: bool = False) -> bool:
        try:
            message = {
                "msgtype": "markdown",
                "markdown": {
                    "title": "库存预警通知",
                    "text": content,
                },
                "at": {
                    "isAtAll": is_at_all,
                },
            }

            async with httpx.AsyncClient() as client:
                response = await client.post(webhook_url, json=message, timeout=10)
                result = response.json()
                if result.get("errcode") == 0:
                    logger.info("DingTalk notification sent")
                    return True
                else:
                    logger.error("DingTalk notification failed", error=result.get("errmsg"))
                    return False
        except Exception as e:
            logger.error("Failed to send DingTalk notification", error=str(e))
            return False

    @staticmethod
    async def send_wechat_work(webhook_url: str, content: str) -> bool:
        try:
            message = {
                "msgtype": "markdown",
                "markdown": {
                    "content": content,
                },
            }

            async with httpx.AsyncClient() as client:
                response = await client.post(webhook_url, json=message, timeout=10)
                result = response.json()
                if result.get("errcode") == 0:
                    logger.info("WeChat Work notification sent")
                    return True
                else:
                    logger.error("WeChat Work notification failed", error=result.get("errmsg"))
                    return False
        except Exception as e:
            logger.error("Failed to send WeChat Work notification", error=str(e))
            return False

    @staticmethod
    async def send_webhook(webhook_url: str, payload: Dict[str, Any]) -> bool:
        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(webhook_url, json=payload, timeout=10)
                if 200 <= response.status_code < 300:
                    logger.info("Webhook notification sent", url=webhook_url)
                    return True
                else:
                    logger.error(
                        "Webhook notification failed",
                        url=webhook_url,
                        status_code=response.status_code,
                    )
                    return False
        except Exception as e:
            logger.error("Failed to send webhook notification", error=str(e), url=webhook_url)
            return False


class AlertService:
    def __init__(self, db: Session, current_user: Optional[User] = None):
        self.db = db
        self.current_user = current_user
        self.audit_logger = AuditLogger(db)
        self.notification = NotificationService()
        self.rule_crud = CRUDBase(AlertRule, cache_prefix="alert_rule")
        self.alert_crud = CRUDBase(InventoryAlert, cache_prefix="inventory_alert")

    def _build_alert_message(
        self,
        rule: AlertRule,
        sku: SKU,
        warehouse: Warehouse,
        current_value: float,
        threshold_value: float,
        alert_level: AlertLevel,
    ) -> str:
        level_text = "⚠️ 警告" if alert_level == AlertLevel.WARNING else "🚨 严重"
        rule_type_text = {
            AlertRuleType.LOW_STOCK: "低库存预警",
            AlertRuleType.HIGH_STOCK: "高库存预警",
            AlertRuleType.OUT_OF_STOCK: "缺货预警",
            AlertRuleType.EXPIRING: "临期预警",
            AlertRuleType.SLOW_MOVING: "滞销预警",
        }.get(rule.rule_type, "库存预警")

        return (
            f"**{level_text} - {rule_type_text}**\n\n"
            f"**预警规则**: {rule.name}\n"
            f"**SKU**: {sku.sku_code} - {sku.product.name if hasattr(sku, 'product') else 'N/A'}\n"
            f"**仓库**: {warehouse.name}\n"
            f"**当前值**: {current_value}\n"
            f"**阈值**: {threshold_value}\n"
            f"**触发时间**: {datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S')}"
        )

    async def _send_notifications(
        self,
        rule: AlertRule,
        alert: InventoryAlert,
        sku: SKU,
        warehouse: Warehouse,
    ) -> None:
        if not rule.notify_channels:
            return

        message = self._build_alert_message(
            rule, sku, warehouse, alert.current_value, alert.threshold_value, alert.alert_level
        )

        payload = {
            "alert_id": alert.id,
            "rule_id": rule.id,
            "rule_name": rule.name,
            "sku_id": sku.id,
            "sku_code": sku.sku_code,
            "warehouse_id": warehouse.id,
            "warehouse_name": warehouse.name,
            "alert_type": alert.alert_type.value,
            "alert_level": alert.alert_level.value,
            "current_value": alert.current_value,
            "threshold_value": alert.threshold_value,
            "message": alert.message,
            "created_at": alert.created_at.isoformat(),
        }

        for channel in rule.notify_channels:
            try:
                if channel == "EMAIL":
                    admins = (
                        self.db.query(User)
                        .filter(User.is_active == True)
                        .filter(User.role_id.isnot(None))
                        .limit(10)
                        .all()
                    )
                    emails = [u.email for u in admins if u.email]
                    if emails:
                        await self.notification.send_email(
                            recipients=emails,
                            subject=f"【库存预警】{rule.name}",
                            content=message.replace("\n", "<br>"),
                        )
                elif channel == "DINGTALK" and settings.WEBHOOK_URL:
                    await self.notification.send_dingtalk(
                        webhook_url=settings.WEBHOOK_URL,
                        content=message,
                        is_at_all=alert.alert_level == AlertLevel.CRITICAL,
                    )
                elif channel == "WECHAT_WORK" and settings.WEBHOOK_URL:
                    await self.notification.send_wechat_work(
                        webhook_url=settings.WEBHOOK_URL,
                        content=message,
                    )
                elif channel == "WEBHOOK" and settings.WEBHOOK_URL:
                    await self.notification.send_webhook(
                        webhook_url=settings.WEBHOOK_URL,
                        payload=payload,
                    )
            except Exception as e:
                logger.error(
                    "Failed to send notification",
                    channel=channel,
                    alert_id=alert.id,
                    error=str(e),
                )

    def create_rule(self, obj_in: AlertRuleCreate) -> AlertRule:
        extra_data = {}
        if obj_in.sku_ids:
            extra_data["sku_ids"] = obj_in.sku_ids
        if obj_in.warehouse_ids:
            extra_data["warehouse_ids"] = obj_in.warehouse_ids
        if obj_in.notify_channels:
            extra_data["notify_channels"] = [c.value for c in obj_in.notify_channels]

        rule = self.rule_crud.create(self.db, obj_in=obj_in, extra_data=extra_data)

        if self.current_user:
            self.audit_logger.log_create(
                user=self.current_user,
                resource_type="alert_rule",
                resource_id=rule.id,
                new_value=obj_in.model_dump(),
            )

        logger.info("Alert rule created", rule_id=rule.id, rule_name=rule.name)
        return rule

    def update_rule(self, rule_id: int, obj_in: AlertRuleUpdate) -> AlertRule:
        rule = self.rule_crud.get_or_404(self.db, id=rule_id, use_cache=False)
        old_data = {c.name: getattr(rule, c.name) for c in rule.__table__.columns}

        update_data = obj_in.model_dump(exclude_unset=True)
        if "notify_channels" in update_data and update_data["notify_channels"]:
            update_data["notify_channels"] = [c.value for c in update_data["notify_channels"]]

        rule = self.rule_crud.update(self.db, db_obj=rule, obj_in=update_data)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="alert_rule",
                resource_id=rule.id,
                old_value=old_data,
                new_value=update_data,
            )

        logger.info("Alert rule updated", rule_id=rule_id)
        return rule

    def delete_rule(self, rule_id: int) -> AlertRule:
        rule = self.rule_crud.get_or_404(self.db, id=rule_id, use_cache=False)
        old_data = {c.name: getattr(rule, c.name) for c in rule.__table__.columns}

        rule = self.rule_crud.delete(self.db, id=rule_id)

        if self.current_user:
            self.audit_logger.log_delete(
                user=self.current_user,
                resource_type="alert_rule",
                resource_id=rule_id,
                old_value=old_data,
            )

        logger.info("Alert rule deleted", rule_id=rule_id)
        return rule

    def enable_rule(self, rule_id: int) -> AlertRule:
        rule = self.rule_crud.get_or_404(self.db, id=rule_id, use_cache=False)
        old_data = {"is_active": rule.is_active}

        rule.is_active = True
        rule.updated_at = datetime.utcnow()
        self.db.flush()
        self.db.refresh(rule)

        cache.delete(f"alert_rule:{rule_id}")
        cache.delete_pattern("alert_rule:list:*")

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="alert_rule",
                resource_id=rule_id,
                old_value=old_data,
                new_value={"is_active": True},
            )

        logger.info("Alert rule enabled", rule_id=rule_id)
        return rule

    def disable_rule(self, rule_id: int) -> AlertRule:
        rule = self.rule_crud.get_or_404(self.db, id=rule_id, use_cache=False)
        old_data = {"is_active": rule.is_active}

        rule.is_active = False
        rule.updated_at = datetime.utcnow()
        self.db.flush()
        self.db.refresh(rule)

        cache.delete(f"alert_rule:{rule_id}")
        cache.delete_pattern("alert_rule:list:*")

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="alert_rule",
                resource_id=rule_id,
                old_value=old_data,
                new_value={"is_active": False},
            )

        logger.info("Alert rule disabled", rule_id=rule_id)
        return rule

    def get_rule(self, rule_id: int) -> Optional[AlertRule]:
        return self.rule_crud.get(self.db, id=rule_id)

    def list_rules(
        self,
        page: int = 1,
        page_size: int = 20,
        rule_type: Optional[AlertRuleType] = None,
        is_active: Optional[bool] = None,
        threshold_type: Optional[ThresholdType] = None,
        keyword: Optional[str] = None,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> Tuple[List[AlertRule], int, int]:
        filters = {}
        if rule_type:
            filters["rule_type"] = rule_type
        if is_active is not None:
            filters["is_active"] = is_active
        if threshold_type:
            filters["threshold_type"] = threshold_type

        search_filters = []
        if keyword:
            search_filters.append(AlertRule.name.like(f"%{keyword}%"))

        result = self.rule_crud.get_multi(
            self.db,
            page=page,
            page_size=page_size,
            filters=filters,
            search_filters=search_filters if search_filters else None,
            sort_by=sort_by,
            sort_order=sort_order,
            use_cache=True,
        )

        return result.items, result.total, result.total_pages

    def get_alert(self, alert_id: int) -> Optional[InventoryAlert]:
        return self.alert_crud.get(self.db, id=alert_id)

    def list_alerts(
        self,
        page: int = 1,
        page_size: int = 20,
        status: Optional[AlertStatus] = None,
        alert_level: Optional[AlertLevel] = None,
        alert_type: Optional[AlertRuleType] = None,
        sku_id: Optional[int] = None,
        warehouse_id: Optional[int] = None,
        rule_id: Optional[int] = None,
        date_from: Optional[datetime] = None,
        date_to: Optional[datetime] = None,
        acknowledged: Optional[bool] = None,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> Tuple[List[InventoryAlert], int, int]:
        stmt = self.db.query(InventoryAlert)
        count_stmt = self.db.query(func.count(InventoryAlert.id))

        where_conditions = []
        if status:
            where_conditions.append(InventoryAlert.status == status)
        if alert_level:
            where_conditions.append(InventoryAlert.alert_level == alert_level)
        if alert_type:
            where_conditions.append(InventoryAlert.alert_type == alert_type)
        if sku_id:
            where_conditions.append(InventoryAlert.sku_id == sku_id)
        if warehouse_id:
            where_conditions.append(InventoryAlert.warehouse_id == warehouse_id)
        if rule_id:
            where_conditions.append(InventoryAlert.rule_id == rule_id)
        if date_from:
            where_conditions.append(InventoryAlert.created_at >= date_from)
        if date_to:
            where_conditions.append(InventoryAlert.created_at <= date_to)
        if acknowledged is not None:
            if acknowledged:
                where_conditions.append(InventoryAlert.acknowledged_by.isnot(None))
            else:
                where_conditions.append(InventoryAlert.acknowledged_by.is_(None))

        if where_conditions:
            condition = and_(*where_conditions)
            stmt = stmt.where(condition)
            count_stmt = count_stmt.where(condition)

        total = count_stmt.scalar() or 0

        if sort_by and hasattr(InventoryAlert, sort_by):
            sort_column = getattr(InventoryAlert, sort_by)
            stmt = stmt.order_by(desc(sort_column) if sort_order == "desc" else asc(sort_column))
        else:
            stmt = stmt.order_by(desc(InventoryAlert.created_at))

        offset = (page - 1) * page_size
        stmt = stmt.offset(offset).limit(page_size)

        items = stmt.all()
        total_pages = (total + page_size - 1) // page_size

        return items, total, total_pages

    def acknowledge_alert(
        self, alert_id: int, request: AlertAcknowledgeRequest
    ) -> InventoryAlert:
        alert = self.alert_crud.get_or_404(self.db, id=alert_id, use_cache=False)

        if alert.status in [AlertStatus.RESOLVED, AlertStatus.CLOSED]:
            raise ValueError("Cannot acknowledge a resolved or closed alert")

        if not self.current_user:
            raise ValueError("Current user is required")

        old_data = {
            "status": alert.status.value,
            "acknowledged_by": alert.acknowledged_by,
            "acknowledged_at": alert.acknowledged_at,
        }

        alert.status = AlertStatus.ACKNOWLEDGED
        alert.acknowledged_by = self.current_user.id
        alert.acknowledged_at = datetime.utcnow()
        self.db.flush()
        self.db.refresh(alert)

        cache.delete(f"inventory_alert:{alert_id}")
        cache.delete_pattern("inventory_alert:list:*")

        self.audit_logger.log_update(
            user=self.current_user,
            resource_type="inventory_alert",
            resource_id=alert_id,
            old_value=old_data,
            new_value={
                "status": AlertStatus.ACKNOWLEDGED.value,
                "acknowledged_by": self.current_user.id,
                "acknowledged_at": alert.acknowledged_at.isoformat(),
                "remark": request.remark,
            },
        )

        logger.info("Alert acknowledged", alert_id=alert_id, user_id=self.current_user.id)
        return alert

    def resolve_alert(
        self, alert_id: int, request: AlertResolveRequest
    ) -> InventoryAlert:
        alert = self.alert_crud.get_or_404(self.db, id=alert_id, use_cache=False)

        if alert.status == AlertStatus.CLOSED:
            raise ValueError("Cannot resolve a closed alert")

        if not self.current_user:
            raise ValueError("Current user is required")

        old_data = {
            "status": alert.status.value,
            "resolved_by": alert.resolved_by,
            "resolved_at": alert.resolved_at,
        }

        alert.status = AlertStatus.RESOLVED
        alert.resolved_by = self.current_user.id
        alert.resolved_at = datetime.utcnow()
        self.db.flush()
        self.db.refresh(alert)

        cache.delete(f"inventory_alert:{alert_id}")
        cache.delete_pattern("inventory_alert:list:*")

        self.audit_logger.log_update(
            user=self.current_user,
            resource_type="inventory_alert",
            resource_id=alert_id,
            old_value=old_data,
            new_value={
                "status": AlertStatus.RESOLVED.value,
                "resolved_by": self.current_user.id,
                "resolved_at": alert.resolved_at.isoformat(),
                "resolution": request.resolution,
                "remark": request.remark,
            },
        )

        logger.info("Alert resolved", alert_id=alert_id, user_id=self.current_user.id)
        return alert

    def close_alert(self, alert_id: int) -> InventoryAlert:
        alert = self.alert_crud.get_or_404(self.db, id=alert_id, use_cache=False)

        if not self.current_user:
            raise ValueError("Current user is required")

        old_data = {"status": alert.status.value}

        alert.status = AlertStatus.CLOSED
        self.db.flush()
        self.db.refresh(alert)

        cache.delete(f"inventory_alert:{alert_id}")
        cache.delete_pattern("inventory_alert:list:*")

        self.audit_logger.log_update(
            user=self.current_user,
            resource_type="inventory_alert",
            resource_id=alert_id,
            old_value=old_data,
            new_value={"status": AlertStatus.CLOSED.value},
        )

        logger.info("Alert closed", alert_id=alert_id, user_id=self.current_user.id)
        return alert

    def _get_sku_value_for_rule(
        self,
        rule: AlertRule,
        sku: SKU,
        warehouse: Warehouse,
    ) -> Tuple[float, float, AlertLevel]:
        inventories = (
            self.db.query(Inventory)
            .filter(
                Inventory.sku_id == sku.id,
                Inventory.warehouse_id == warehouse.id,
            )
            .all()
        )
        total_quantity = sum(inv.quantity for inv in inventories) if inventories else 0
        available_quantity = sum(inv.available_quantity for inv in inventories) if inventories else 0

        current_value = 0.0
        threshold_value = rule.threshold_value

        if rule.rule_type == AlertRuleType.LOW_STOCK:
            current_value = available_quantity
            if rule.threshold_type == ThresholdType.PERCENTAGE:
                max_stock = sku.maximum_stock or 100
                threshold_value = (rule.threshold_value / 100) * max_stock

        elif rule.rule_type == AlertRuleType.HIGH_STOCK:
            current_value = total_quantity
            if rule.threshold_type == ThresholdType.PERCENTAGE:
                max_stock = sku.maximum_stock or 100
                threshold_value = (rule.threshold_value / 100) * max_stock

        elif rule.rule_type == AlertRuleType.OUT_OF_STOCK:
            current_value = available_quantity
            threshold_value = 0

        elif rule.rule_type == AlertRuleType.EXPIRING:
            threshold_days = rule.threshold_value
            today = datetime.utcnow().date()
            expiring_batches = (
                self.db.query(Batch)
                .filter(
                    Batch.sku_id == sku.id,
                    Batch.warehouse_id == warehouse.id,
                    Batch.expiration_date.isnot(None),
                    Batch.remaining_quantity > 0,
                )
                .all()
            )

            expiring_quantity = 0
            for batch in expiring_batches:
                if batch.expiration_date:
                    days_to_expire = (batch.expiration_date.date() - today).days
                    if days_to_expire <= threshold_days:
                        expiring_quantity += batch.remaining_quantity

            current_value = expiring_quantity

        elif rule.rule_type == AlertRuleType.SLOW_MOVING:
            days_threshold = rule.threshold_value
            threshold_date = datetime.utcnow() - timedelta(days=days_threshold)

            from app.models.inventory_transaction import InventoryTransaction, TransactionType

            recent_sales = (
                self.db.query(func.sum(InventoryTransaction.quantity))
                .filter(
                    InventoryTransaction.sku_id == sku.id,
                    InventoryTransaction.warehouse_id == warehouse.id,
                    InventoryTransaction.transaction_type == TransactionType.OUT,
                    InventoryTransaction.created_at >= threshold_date,
                )
                .scalar()
            ) or 0

            current_value = days_threshold if recent_sales == 0 else 0
            threshold_value = days_threshold

        if rule.rule_type in [AlertRuleType.HIGH_STOCK, AlertRuleType.OUT_OF_STOCK]:
            if current_value >= rule.critical_value:
                alert_level = AlertLevel.CRITICAL
            elif current_value >= rule.warning_value:
                alert_level = AlertLevel.WARNING
            else:
                alert_level = AlertLevel.WARNING
        else:
            if current_value <= rule.critical_value:
                alert_level = AlertLevel.CRITICAL
            elif current_value <= rule.warning_value:
                alert_level = AlertLevel.WARNING
            else:
                alert_level = AlertLevel.WARNING

        return current_value, threshold_value, alert_level

    def _should_trigger_alert(
        self,
        rule: AlertRule,
        current_value: float,
    ) -> bool:
        if rule.rule_type in [AlertRuleType.HIGH_STOCK]:
            return current_value >= rule.threshold_value
        else:
            return current_value <= rule.threshold_value

    def _check_existing_alert(
        self,
        rule_id: int,
        sku_id: int,
        warehouse_id: int,
    ) -> Optional[InventoryAlert]:
        return (
            self.db.query(InventoryAlert)
            .filter(
                InventoryAlert.rule_id == rule_id,
                InventoryAlert.sku_id == sku_id,
                InventoryAlert.warehouse_id == warehouse_id,
                InventoryAlert.status.in_([AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED]),
            )
            .first()
        )

    async def check_alerts(
        self,
        rule_id: Optional[int] = None,
        sku_ids: Optional[List[int]] = None,
        warehouse_ids: Optional[List[int]] = None,
    ) -> AlertCheckResponse:
        rules_query = self.db.query(AlertRule).filter(AlertRule.is_active == True)

        if rule_id:
            rules_query = rules_query.filter(AlertRule.id == rule_id)

        rules = rules_query.all()

        sku_query = self.db.query(SKU).filter(SKU.status == SkuStatus.ACTIVE)
        if sku_ids:
            sku_query = sku_query.filter(SKU.id.in_(sku_ids))
        skus = sku_query.all()

        warehouse_query = self.db.query(Warehouse)
        if warehouse_ids:
            warehouse_query = warehouse_query.filter(Warehouse.id.in_(warehouse_ids))
        warehouses = warehouse_query.all()

        checked_count = 0
        new_alerts: List[InventoryAlert] = []
        resolved_count = 0

        for rule in rules:
            rule_skus = skus
            if rule.sku_ids:
                rule_skus = [s for s in skus if s.id in rule.sku_ids]
            if rule.category_id:
                from app.models.product import Product
                rule_skus = [
                    s for s in rule_skus
                    if s.product and s.product.category_id == rule.category_id
                ]

            rule_warehouses = warehouses
            if rule.warehouse_ids:
                rule_warehouses = [w for w in warehouses if w.id in rule.warehouse_ids]

            for sku in rule_skus:
                for warehouse in rule_warehouses:
                    checked_count += 1

                    current_value, threshold_value, alert_level = self._get_sku_value_for_rule(
                        rule, sku, warehouse
                    )

                    should_trigger = self._should_trigger_alert(rule, current_value)
                    existing_alert = self._check_existing_alert(rule.id, sku.id, warehouse.id)

                    if should_trigger and not existing_alert:
                        alert = InventoryAlert(
                            rule_id=rule.id,
                            sku_id=sku.id,
                            warehouse_id=warehouse.id,
                            alert_level=alert_level,
                            alert_type=rule.rule_type,
                            current_value=current_value,
                            threshold_value=threshold_value,
                            message=self._build_alert_message(
                                rule, sku, warehouse, current_value, threshold_value, alert_level
                            ),
                            status=AlertStatus.OPEN,
                        )
                        self.db.add(alert)
                        self.db.flush()
                        self.db.refresh(alert)
                        new_alerts.append(alert)

                        cache.delete_pattern("inventory_alert:list:*")

                        await self._send_notifications(rule, alert, sku, warehouse)

                        self.audit_logger.log(
                            user_id=self.current_user.id if self.current_user else None,
                            action="create",
                            resource_type="inventory_alert",
                            resource_id=alert.id,
                            new_value={
                                "rule_id": rule.id,
                                "sku_id": sku.id,
                                "warehouse_id": warehouse.id,
                                "alert_type": rule.rule_type.value,
                                "current_value": current_value,
                                "threshold_value": threshold_value,
                            },
                        )

                    elif not should_trigger and existing_alert:
                        existing_alert.status = AlertStatus.RESOLVED
                        existing_alert.resolved_at = datetime.utcnow()
                        resolved_count += 1

                        cache.delete(f"inventory_alert:{existing_alert.id}")
                        cache.delete_pattern("inventory_alert:list:*")

        self.db.flush()

        logger.info(
            "Alert check completed",
            checked_count=checked_count,
            new_alerts=len(new_alerts),
            resolved_count=resolved_count,
        )

        return AlertCheckResponse(
            checked_count=checked_count,
            new_alerts_count=len(new_alerts),
            resolved_alerts_count=resolved_count,
            new_alerts=new_alerts,
        )

    def adjust_thresholds_for_seasonality(
        self,
        rule_id: int,
        sales_history_days: int = 90,
    ) -> Dict[str, Any]:
        rule = self.rule_crud.get_or_404(self.db, id=rule_id, use_cache=False)

        sku_query = self.db.query(SKU).filter(SKU.status == SkuStatus.ACTIVE)
        if rule.sku_ids:
            sku_query = sku_query.filter(SKU.id.in_(rule.sku_ids))
        skus = sku_query.all()

        adjustments = []
        for sku in skus:
            from app.models.inventory_transaction import InventoryTransaction, TransactionType

            start_date = datetime.utcnow() - timedelta(days=sales_history_days)
            sales_data = (
                self.db.query(
                    func.date(InventoryTransaction.created_at).label("date"),
                    func.sum(InventoryTransaction.quantity).label("quantity"),
                )
                .filter(
                    InventoryTransaction.sku_id == sku.id,
                    InventoryTransaction.transaction_type == TransactionType.OUT,
                    InventoryTransaction.created_at >= start_date,
                )
                .group_by(func.date(InventoryTransaction.created_at))
                .order_by("date")
                .all()
            )

            if len(sales_data) < 14:
                continue

            quantities = [row.quantity or 0 for row in sales_data]
            seasonality_period = detect_seasonality(quantities)

            if seasonality_period:
                indices = calculate_seasonal_indices(quantities, period=seasonality_period)
                current_season_index = indices[-1] if len(indices) > 0 else 1.0

                old_threshold = rule.threshold_value
                old_warning = rule.warning_value
                old_critical = rule.critical_value

                if rule.rule_type in [AlertRuleType.LOW_STOCK, AlertRuleType.OUT_OF_STOCK]:
                    adjustment_factor = max(0.5, min(2.0, current_season_index))
                else:
                    adjustment_factor = max(0.5, min(2.0, 1 / current_season_index if current_season_index > 0 else 1.0))

                new_threshold = round(old_threshold * adjustment_factor, 2)
                new_warning = round(old_warning * adjustment_factor, 2)
                new_critical = round(old_critical * adjustment_factor, 2)

                adjustments.append({
                    "sku_id": sku.id,
                    "sku_code": sku.sku_code,
                    "seasonality_period": seasonality_period,
                    "current_season_index": current_season_index,
                    "adjustment_factor": adjustment_factor,
                    "old_threshold": old_threshold,
                    "new_threshold": new_threshold,
                    "old_warning": old_warning,
                    "new_warning": new_warning,
                    "old_critical": old_critical,
                    "new_critical": new_critical,
                })

        return {
            "rule_id": rule_id,
            "rule_name": rule.name,
            "adjustments": adjustments,
            "total_sku_analyzed": len(skus),
            "sku_with_seasonality": len(adjustments),
        }

    def get_statistics(
        self,
        date_from: Optional[datetime] = None,
        date_to: Optional[datetime] = None,
    ) -> AlertStatisticsResponse:
        if not date_from:
            date_from = datetime.utcnow() - timedelta(days=30)
        if not date_to:
            date_to = datetime.utcnow()

        today_start = datetime.utcnow().replace(hour=0, minute=0, second=0, microsecond=0)
        week_start = today_start - timedelta(days=today_start.weekday())
        month_start = today_start.replace(day=1)

        base_query = self.db.query(InventoryAlert).filter(
            InventoryAlert.created_at >= date_from,
            InventoryAlert.created_at <= date_to,
        )

        total_count = base_query.count()
        open_count = base_query.filter(InventoryAlert.status == AlertStatus.OPEN).count()
        acknowledged_count = base_query.filter(
            InventoryAlert.status == AlertStatus.ACKNOWLEDGED
        ).count()
        resolved_count = base_query.filter(
            InventoryAlert.status == AlertStatus.RESOLVED
        ).count()
        closed_count = base_query.filter(InventoryAlert.status == AlertStatus.CLOSED).count()

        warning_count = base_query.filter(
            InventoryAlert.alert_level == AlertLevel.WARNING
        ).count()
        critical_count = base_query.filter(
            InventoryAlert.alert_level == AlertLevel.CRITICAL
        ).count()

        low_stock_count = base_query.filter(
            InventoryAlert.alert_type == AlertRuleType.LOW_STOCK
        ).count()
        high_stock_count = base_query.filter(
            InventoryAlert.alert_type == AlertRuleType.HIGH_STOCK
        ).count()
        out_of_stock_count = base_query.filter(
            InventoryAlert.alert_type == AlertRuleType.OUT_OF_STOCK
        ).count()
        expiring_count = base_query.filter(
            InventoryAlert.alert_type == AlertRuleType.EXPIRING
        ).count()
        slow_moving_count = base_query.filter(
            InventoryAlert.alert_type == AlertRuleType.SLOW_MOVING
        ).count()

        today_count = base_query.filter(
            InventoryAlert.created_at >= today_start
        ).count()
        week_count = base_query.filter(
            InventoryAlert.created_at >= week_start
        ).count()
        month_count = base_query.filter(
            InventoryAlert.created_at >= month_start
        ).count()

        trend_data = (
            self.db.query(
                func.date(InventoryAlert.created_at).label("date"),
                func.count(InventoryAlert.id).label("count"),
            )
            .filter(
                InventoryAlert.created_at >= date_from,
                InventoryAlert.created_at <= date_to,
            )
            .group_by(func.date(InventoryAlert.created_at))
            .order_by("date")
            .all()
        )

        top_skus = (
            self.db.query(
                InventoryAlert.sku_id,
                func.count(InventoryAlert.id).label("alert_count"),
            )
            .filter(
                InventoryAlert.created_at >= date_from,
                InventoryAlert.created_at <= date_to,
            )
            .group_by(InventoryAlert.sku_id)
            .order_by(desc("alert_count"))
            .limit(10)
            .all()
        )

        top_warehouses = (
            self.db.query(
                InventoryAlert.warehouse_id,
                func.count(InventoryAlert.id).label("alert_count"),
            )
            .filter(
                InventoryAlert.created_at >= date_from,
                InventoryAlert.created_at <= date_to,
            )
            .group_by(InventoryAlert.warehouse_id)
            .order_by(desc("alert_count"))
            .limit(10)
            .all()
        )

        return AlertStatisticsResponse(
            total_count=total_count,
            open_count=open_count,
            acknowledged_count=acknowledged_count,
            resolved_count=resolved_count,
            closed_count=closed_count,
            warning_count=warning_count,
            critical_count=critical_count,
            low_stock_count=low_stock_count,
            high_stock_count=high_stock_count,
            out_of_stock_count=out_of_stock_count,
            expiring_count=expiring_count,
            slow_moving_count=slow_moving_count,
            today_count=today_count,
            week_count=week_count,
            month_count=month_count,
            trend_data=[{"date": str(row.date), "count": row.count} for row in trend_data],
            top_skus=[{"sku_id": row.sku_id, "alert_count": row.alert_count} for row in top_skus],
            top_warehouses=[
                {"warehouse_id": row.warehouse_id, "alert_count": row.alert_count}
                for row in top_warehouses
            ],
        )


def create_alert_service(db: Session, current_user: Optional[User] = None) -> AlertService:
    return AlertService(db=db, current_user=current_user)
