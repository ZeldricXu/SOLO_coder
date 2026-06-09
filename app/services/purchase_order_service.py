import numpy as np
from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any, Tuple
from sqlalchemy.orm import Session
from sqlalchemy import and_, or_, func, desc

from app.core.cache import cache
from app.core.logging import get_logger
from app.core.audit import AuditLogger
from app.models.purchase_order import (
    PurchaseOrder,
    PurchaseOrderItem,
    PurchaseOrderStatus,
)
from app.models.inventory import Inventory
from app.models.inventory_transaction import InventoryTransaction, TransactionType
from app.models.inventory_alert import (
    InventoryAlert,
    AlertRuleType,
    AlertLevel,
    AlertStatus,
)
from app.models.sku import SKU
from app.models.supplier import Supplier
from app.models.warehouse import Warehouse
from app.models.user import User
from app.models.approval_workflow import (
    ApprovalRecord,
    ApprovalStatus,
    ResourceType,
)
from app.schemas.purchase_order import (
    PurchaseOrderCreate,
    PurchaseOrderUpdate,
    PurchaseOrderGenerateRequest,
    PurchaseOrderGenerateItem,
    PurchaseOrderGenerateResponse,
    PurchaseOrderReceiveRequest,
    PurchaseOrderStatusEnum,
    ForecastMethodEnum,
)
from app.services.forecast_service import ForecastService
from app.services.approval_service import ApprovalService

logger = get_logger(__name__)


class PurchaseOrderService:
    def __init__(self, db: Session, current_user: Optional[User] = None):
        self.db = db
        self.current_user = current_user
        self.audit_logger = AuditLogger(db)
        self.forecast_service = ForecastService(db)
        self.approval_service = ApprovalService(db, current_user)

    def _generate_order_no(self) -> str:
        today = datetime.utcnow().strftime("%Y%m%d")
        prefix = f"PO{today}"

        last_order = (
            self.db.query(PurchaseOrder)
            .filter(PurchaseOrder.order_no.like(f"{prefix}%"))
            .order_by(desc(PurchaseOrder.order_no))
            .first()
        )

        if last_order:
            seq = int(last_order.order_no[-4:]) + 1
        else:
            seq = 1

        return f"{prefix}{seq:04d}"

    def _calculate_order_amounts(
        self, items_data: List[Dict[str, Any]], order_data: Dict[str, Any]
    ) -> Tuple[float, float, float, float, float, List[Dict[str, Any]]]:
        total_amount = 0.0
        items_with_amounts = []

        for item in items_data:
            quantity = item["quantity"]
            unit_price = item["unit_price"]
            tax_rate = item.get("tax_rate", 0.0)

            item_total = quantity * unit_price
            tax_amount = item_total * tax_rate
            total_amount += item_total

            items_with_amounts.append({
                **item,
                "total_amount": item_total,
                "tax_amount": tax_amount,
            })

        shipping_cost = order_data.get("shipping_cost", 0.0)
        tax_rate = order_data.get("tax_rate", 0.0)
        discount_rate = order_data.get("discount_rate", 0.0)

        subtotal = total_amount + shipping_cost
        discount_amount = subtotal * discount_rate
        amount_after_discount = subtotal - discount_amount
        tax_amount = amount_after_discount * tax_rate
        grand_total = amount_after_discount + tax_amount

        return (
            round(total_amount, 2),
            round(tax_amount, 2),
            round(discount_amount, 2),
            round(grand_total, 2),
            round(shipping_cost, 2),
            items_with_amounts,
        )

    def get_order(
        self,
        order_id: int,
    ) -> Optional[PurchaseOrder]:
        cache_key = f"purchase_order:{order_id}"
        cached = cache.get(cache_key)
        if cached:
            return cached

        order = self.db.query(PurchaseOrder).filter(PurchaseOrder.id == order_id).first()
        if order:
            cache.set(cache_key, order, ttl=300)
        return order

    def list_orders(
        self,
        page: int = 1,
        page_size: int = 20,
        status: Optional[List[PurchaseOrderStatus]] = None,
        supplier_id: Optional[int] = None,
        warehouse_id: Optional[int] = None,
        created_by: Optional[int] = None,
        start_date: Optional[datetime] = None,
        end_date: Optional[datetime] = None,
        order_no: Optional[str] = None,
        keyword: Optional[str] = None,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> Tuple[List[PurchaseOrder], int, int]:
        query = self.db.query(PurchaseOrder)

        if status:
            query = query.filter(PurchaseOrder.status.in_(status))
        if supplier_id:
            query = query.filter(PurchaseOrder.supplier_id == supplier_id)
        if warehouse_id:
            query = query.filter(PurchaseOrder.warehouse_id == warehouse_id)
        if created_by:
            query = query.filter(PurchaseOrder.created_by == created_by)
        if start_date:
            query = query.filter(PurchaseOrder.order_date >= start_date)
        if end_date:
            query = query.filter(PurchaseOrder.order_date <= end_date)
        if order_no:
            query = query.filter(PurchaseOrder.order_no.ilike(f"%{order_no}%"))
        if keyword:
            query = query.filter(
                or_(
                    PurchaseOrder.order_no.ilike(f"%{keyword}%"),
                    PurchaseOrder.remark.ilike(f"%{keyword}%"),
                )
            )

        total = query.count()

        sort_column = sort_by if sort_by else "created_at"
        sort_func = desc if sort_order.lower() == "desc" else lambda x: x
        query = query.order_by(sort_func(getattr(PurchaseOrder, sort_column)))

        offset = (page - 1) * page_size
        orders = query.offset(offset).limit(page_size).all()

        total_pages = (total + page_size - 1) // page_size

        return orders, total, total_pages

    def create_order(
        self,
        order_data: PurchaseOrderCreate,
        created_by: User,
    ) -> PurchaseOrder:
        order_dict = order_data.model_dump()
        items_data = order_dict.pop("items")

        order_no = self._generate_order_no()

        (
            total_amount,
            tax_amount,
            discount_amount,
            grand_total,
            shipping_cost,
            items_with_amounts,
        ) = self._calculate_order_amounts(items_data, order_dict)

        new_order = PurchaseOrder(
            **order_dict,
            order_no=order_no,
            total_amount=total_amount,
            tax_amount=tax_amount,
            discount_amount=discount_amount,
            grand_total=grand_total,
            shipping_cost=shipping_cost,
            status=PurchaseOrderStatus.DRAFT,
            created_by=created_by.id,
        )

        self.db.add(new_order)
        self.db.flush()

        for item_data in items_with_amounts:
            item = PurchaseOrderItem(
                purchase_order_id=new_order.id,
                sku_id=item_data["sku_id"],
                quantity=item_data["quantity"],
                unit_price=item_data["unit_price"],
                tax_rate=item_data.get("tax_rate", 0.0),
                tax_amount=item_data["tax_amount"],
                total_amount=item_data["total_amount"],
                remark=item_data.get("remark"),
            )
            self.db.add(item)

        self.db.flush()

        self.audit_logger.log_create(
            user=created_by,
            resource_type="purchase_order",
            resource_id=new_order.id,
            new_value={
                "order_no": order_no,
                "supplier_id": order_data.supplier_id,
                "warehouse_id": order_data.warehouse_id,
                "total_amount": grand_total,
                "item_count": len(items_data),
            },
        )

        cache.delete_pattern("purchase_order:*")

        return new_order

    def update_order(
        self,
        order_id: int,
        update_data: PurchaseOrderUpdate,
        updated_by: User,
    ) -> Optional[PurchaseOrder]:
        order = self.get_order(order_id)
        if not order:
            return None

        if order.status not in [PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.REJECTED]:
            raise ValueError(f"Cannot update order in status: {order.status}")

        old_value = {
            "supplier_id": order.supplier_id,
            "warehouse_id": order.warehouse_id,
            "total_amount": order.grand_total,
            "status": order.status.value,
        }

        update_dict = update_data.model_dump(exclude_unset=True)
        items_data = update_dict.pop("items", None)

        for key, value in update_dict.items():
            setattr(order, key, value)

        if items_data is not None:
            for item in order.items:
                self.db.delete(item)
            self.db.flush()

            (
                total_amount,
                tax_amount,
                discount_amount,
                grand_total,
                shipping_cost,
                items_with_amounts,
            ) = self._calculate_order_amounts(items_data, update_dict)

            order.total_amount = total_amount
            order.tax_amount = tax_amount
            order.discount_amount = discount_amount
            order.grand_total = grand_total
            order.shipping_cost = shipping_cost

            for item_data in items_with_amounts:
                item = PurchaseOrderItem(
                    purchase_order_id=order.id,
                    sku_id=item_data["sku_id"],
                    quantity=item_data["quantity"],
                    unit_price=item_data["unit_price"],
                    tax_rate=item_data.get("tax_rate", 0.0),
                    tax_amount=item_data["tax_amount"],
                    total_amount=item_data["total_amount"],
                    remark=item_data.get("remark"),
                )
                self.db.add(item)

        if order.status == PurchaseOrderStatus.REJECTED:
            order.status = PurchaseOrderStatus.DRAFT

        order.updated_at = datetime.utcnow()

        self.audit_logger.log_update(
            user=updated_by,
            resource_type="purchase_order",
            resource_id=order.id,
            old_value=old_value,
            new_value={
                "supplier_id": order.supplier_id,
                "warehouse_id": order.warehouse_id,
                "total_amount": order.grand_total,
                "status": order.status.value,
            },
        )

        cache.delete(f"purchase_order:{order_id}")
        cache.delete_pattern("purchase_order:*")

        return order

    def delete_order(
        self,
        order_id: int,
        deleted_by: User,
    ) -> bool:
        order = self.get_order(order_id)
        if not order:
            return False

        if order.status not in [PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.REJECTED]:
            raise ValueError(f"Cannot delete order in status: {order.status}")

        old_value = {
            "order_no": order.order_no,
            "supplier_id": order.supplier_id,
            "total_amount": order.grand_total,
        }

        self.db.delete(order)

        self.audit_logger.log_delete(
            user=deleted_by,
            resource_type="purchase_order",
            resource_id=order.id,
            old_value=old_value,
        )

        cache.delete(f"purchase_order:{order_id}")
        cache.delete_pattern("purchase_order:*")

        return True

    def check_low_stock(
        self,
        warehouse_id: Optional[int] = None,
        sku_ids: Optional[List[int]] = None,
        category_id: Optional[int] = None,
    ) -> List[Dict[str, Any]]:
        query = (
            self.db.query(
                SKU,
                Inventory,
                func.sum(Inventory.available_quantity).label("total_available"),
            )
            .join(Inventory, Inventory.sku_id == SKU.id)
            .filter(SKU.status == "ACTIVE")
            .group_by(SKU.id, Inventory.id)
        )

        if warehouse_id:
            query = query.filter(Inventory.warehouse_id == warehouse_id)
        if sku_ids:
            query = query.filter(SKU.id.in_(sku_ids))
        if category_id:
            query = query.join(
                "product"
            ).filter(SKU.product.has(category_id=category_id))

        results = query.all()
        low_stock_items = []

        for sku, inventory, total_available in results:
            available = total_available if total_available else inventory.available_quantity
            safety_stock = sku.safety_stock
            reorder_point = sku.reorder_point

            if available <= safety_stock:
                alert_level = AlertLevel.CRITICAL if available < safety_stock else AlertLevel.WARNING
                low_stock_items.append({
                    "sku_id": sku.id,
                    "sku_code": sku.sku_code,
                    "sku_name": getattr(sku.product, "name", None) if sku.product else None,
                    "warehouse_id": inventory.warehouse_id,
                    "available_quantity": available,
                    "safety_stock": safety_stock,
                    "reorder_point": reorder_point,
                    "deficit": max(0, safety_stock - available),
                    "alert_level": alert_level.value,
                    "unit_price": sku.cost_price,
                    "lead_time_days": sku.lead_time_days,
                })

        return low_stock_items

    def generate_purchase_suggestions(
        self,
        request: PurchaseOrderGenerateRequest,
        created_by: User,
    ) -> PurchaseOrderGenerateResponse:
        low_stock_items = self.check_low_stock(
            warehouse_id=request.warehouse_id,
            sku_ids=request.sku_ids,
            category_id=request.category_id,
        )

        suggestion_items: List[PurchaseOrderGenerateItem] = []
        total_quantity = 0
        total_amount = 0.0

        for item in low_stock_items:
            sku_id = item["sku_id"]
            warehouse_id = request.warehouse_id or item["warehouse_id"]

            _, forecast, metrics = self.forecast_service.forecast_demand(
                sku_id=sku_id,
                method=request.forecast_method,
                periods=request.forecast_periods,
                warehouse_id=warehouse_id,
                history_days=request.history_days,
            )

            forecast_demand = float(np.sum(forecast))
            lead_time_days = request.lead_time_days or item["lead_time_days"] or 7

            lead_time_demand = self.forecast_service.calculate_lead_time_demand(
                sku_id=sku_id,
                lead_time_days=lead_time_days,
                warehouse_id=warehouse_id,
                method=request.forecast_method,
                history_days=request.history_days,
            )

            safety_stock = self.forecast_service.calculate_safety_stock(
                sku_id=sku_id,
                warehouse_id=warehouse_id,
                service_level=request.service_level,
                lead_time_days=lead_time_days,
                history_days=request.history_days,
            )

            required_stock = int(
                lead_time_demand
                + forecast_demand * request.safety_stock_multiplier
                + safety_stock
            )

            current_available = item["available_quantity"]
            suggested_qty = max(0, required_stock - current_available)

            if suggested_qty <= 0:
                continue

            supplier = (
                self.db.query(Supplier)
                .filter(Supplier.is_active == True)
                .order_by(Supplier.lead_time_days)
                .first()
            )

            unit_price = item["unit_price"]
            item_total = suggested_qty * unit_price

            suggestion_items.append(
                PurchaseOrderGenerateItem(
                    sku_id=sku_id,
                    sku_code=item["sku_code"],
                    sku_name=item["sku_name"],
                    current_stock=item["available_quantity"] + item.get("reserved_stock", 0),
                    reserved_stock=item.get("reserved_stock", 0),
                    available_stock=item["available_quantity"],
                    safety_stock=safety_stock,
                    reorder_point=lead_time_demand + safety_stock,
                    forecast_demand=forecast_demand,
                    lead_time_days=lead_time_days,
                    suggested_quantity=suggested_qty,
                    unit_price=unit_price,
                    supplier_id=supplier.id if supplier else None,
                    supplier_name=supplier.name if supplier else None,
                )
            )

            total_quantity += suggested_qty
            total_amount += item_total

        created_order_id = None
        created_order_no = None

        if request.auto_create and suggestion_items and created_by:
            supplier_ids = {item.supplier_id for item in suggestion_items if item.supplier_id}
            if supplier_ids:
                for supplier_id in supplier_ids:
                    supplier_items = [
                        item for item in suggestion_items
                        if item.supplier_id == supplier_id
                    ]

                    order_items = [
                        {
                            "sku_id": item.sku_id,
                            "quantity": item.suggested_quantity,
                            "unit_price": item.unit_price,
                            "tax_rate": 0.0,
                            "remark": f"Auto-generated from stock replenishment suggestion",
                        }
                        for item in supplier_items
                    ]

                    create_data = PurchaseOrderCreate(
                        supplier_id=supplier_id,
                        warehouse_id=request.warehouse_id or supplier_items[0].get("warehouse_id", 1),
                        order_date=datetime.utcnow(),
                        expected_date=datetime.utcnow() + timedelta(days=supplier_items[0].lead_time_days),
                        items=order_items,
                        remark="Auto-generated purchase order from stock replenishment",
                    )

                    new_order = self.create_order(create_data, created_by)
                    created_order_id = new_order.id
                    created_order_no = new_order.order_no
            else:
                default_supplier = (
                    self.db.query(Supplier)
                    .filter(Supplier.is_active == True)
                    .order_by(Supplier.id)
                    .first()
                )

                if default_supplier:
                    order_items = [
                        {
                            "sku_id": item.sku_id,
                            "quantity": item.suggested_quantity,
                            "unit_price": item.unit_price,
                            "tax_rate": 0.0,
                            "remark": "Auto-generated from stock replenishment suggestion",
                        }
                        for item in suggestion_items
                    ]

                    create_data = PurchaseOrderCreate(
                        supplier_id=default_supplier.id,
                        warehouse_id=request.warehouse_id or 1,
                        order_date=datetime.utcnow(),
                        expected_date=datetime.utcnow() + timedelta(days=7),
                        items=order_items,
                        remark="Auto-generated purchase order from stock replenishment",
                    )

                    new_order = self.create_order(create_data, created_by)
                    created_order_id = new_order.id
                    created_order_no = new_order.order_no

        return PurchaseOrderGenerateResponse(
            items=suggestion_items,
            total_quantity=total_quantity,
            total_amount=round(total_amount, 2),
            forecast_method=request.forecast_method,
            forecast_periods=request.forecast_periods,
            history_days=request.history_days,
            created_order_id=created_order_id,
            created_order_no=created_order_no,
        )

    def submit_for_approval(
        self,
        order_id: int,
        submitted_by: User,
    ) -> Dict[str, Any]:
        order = self.get_order(order_id)
        if not order:
            raise ValueError("Order not found")

        if order.status not in [PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.REJECTED]:
            raise ValueError(f"Cannot submit order in status: {order.status}")

        if not order.items:
            raise ValueError("Cannot submit empty order")

        order.status = PurchaseOrderStatus.SUBMITTED
        order.updated_at = datetime.utcnow()

        self.db.flush()

        try:
            approval_result = self.approval_service.submit_approval({
                "resource_id": order.id,
                "resource_type": ResourceType.PURCHASE_ORDER,
                "submitter_remark": f"Purchase Order {order.order_no} submitted for approval",
            })

            order.status = PurchaseOrderStatus.APPROVING
            order.updated_at = datetime.utcnow()

            self.audit_logger.log(
                user_id=submitted_by.id,
                action="submit_approval",
                resource_type="purchase_order",
                resource_id=order.id,
                new_value={
                    "order_no": order.order_no,
                    "status": order.status.value,
                    "workflow_id": approval_result.get("workflow_id"),
                },
            )

            cache.delete(f"purchase_order:{order_id}")

            return {
                "success": True,
                "order_id": order.id,
                "order_no": order.order_no,
                "status": order.status.value,
                "workflow_id": approval_result.get("workflow_id"),
                "workflow_name": approval_result.get("workflow_name"),
                "current_node": approval_result.get("current_node_name"),
                "next_approvers": approval_result.get("next_approvers", []),
            }

        except Exception as e:
            order.status = PurchaseOrderStatus.DRAFT
            logger.error(f"Failed to submit approval for order {order_id}: {e}")
            raise

    def receive_order(
        self,
        order_id: int,
        receive_data: PurchaseOrderReceiveRequest,
        received_by: User,
    ) -> Dict[str, Any]:
        order = self.get_order(order_id)
        if not order:
            raise ValueError("Order not found")

        if order.status not in [
            PurchaseOrderStatus.APPROVED,
            PurchaseOrderStatus.PROCESSING,
            PurchaseOrderStatus.PARTIAL_RECEIVED,
        ]:
            raise ValueError(f"Cannot receive order in status: {order.status}")

        order_items = {item.id: item for item in order.items}
        total_received = 0
        total_rejected = 0

        for item_receive in receive_data.items:
            order_item = order_items.get(item_receive.item_id)
            if not order_item:
                raise ValueError(f"Order item {item_receive.item_id} not found")

            if order_item.quantity - order_item.received_quantity < item_receive.received_quantity:
                raise ValueError(
                    f"Cannot receive more than remaining quantity for item {item_receive.item_id}"
                )

            order_item.received_quantity += item_receive.received_quantity
            order_item.rejected_quantity += item_receive.rejected_quantity or 0

            total_received += item_receive.received_quantity
            total_rejected += item_receive.rejected_quantity or 0

            if item_receive.received_quantity > 0:
                warehouse_id = receive_data.warehouse_id or order.warehouse_id
                zone_id = receive_data.zone_id or 1

                inventory = (
                    self.db.query(Inventory)
                    .filter(
                        and_(
                            Inventory.sku_id == order_item.sku_id,
                            Inventory.warehouse_id == warehouse_id,
                            Inventory.zone_id == zone_id,
                        )
                    )
                    .first()
                )

                if inventory:
                    inventory.quantity += item_receive.received_quantity
                    inventory.available_quantity += item_receive.received_quantity
                    inventory.updated_at = datetime.utcnow()
                else:
                    inventory = Inventory(
                        sku_id=order_item.sku_id,
                        warehouse_id=warehouse_id,
                        zone_id=zone_id,
                        quantity=item_receive.received_quantity,
                        available_quantity=item_receive.received_quantity,
                        unit_cost=order_item.unit_price,
                        total_value=item_receive.received_quantity * order_item.unit_price,
                    )
                    self.db.add(inventory)

                transaction = InventoryTransaction(
                    sku_id=order_item.sku_id,
                    warehouse_id=warehouse_id,
                    zone_id=zone_id,
                    transaction_type=TransactionType.IN,
                    quantity=item_receive.received_quantity,
                    unit_cost=order_item.unit_price,
                    reference_type="PURCHASE_ORDER",
                    reference_id=order.id,
                    batch_id=item_receive.batch_no,
                    reason=f"GRN for PO {order.order_no}",
                    created_by=received_by.id,
                )
                self.db.add(transaction)

        all_received = all(
            item.received_quantity >= item.quantity for item in order.items
        )

        if all_received:
            order.status = PurchaseOrderStatus.RECEIVED
        elif order.status == PurchaseOrderStatus.PARTIAL_RECEIVED or total_received > 0:
            order.status = PurchaseOrderStatus.PARTIAL_RECEIVED
        else:
            order.status = PurchaseOrderStatus.PROCESSING

        order.actual_date = receive_data.actual_date or datetime.utcnow()
        order.updated_at = datetime.utcnow()

        self.db.flush()

        self.audit_logger.log(
            user_id=received_by.id,
            action="receive",
            resource_type="purchase_order",
            resource_id=order.id,
            new_value={
                "order_no": order.order_no,
                "total_received": total_received,
                "total_rejected": total_rejected,
                "status": order.status.value,
            },
        )

        cache.delete(f"purchase_order:{order_id}")

        return {
            "success": True,
            "order_id": order.id,
            "order_no": order.order_no,
            "status": order.status.value,
            "total_received": total_received,
            "total_rejected": total_rejected,
            "all_received": all_received,
        }

    def close_order(
        self,
        order_id: int,
        close_reason: str,
        closed_by: User,
    ) -> Dict[str, Any]:
        order = self.get_order(order_id)
        if not order:
            raise ValueError("Order not found")

        if order.status in [PurchaseOrderStatus.CLOSED, PurchaseOrderStatus.CANCELLED]:
            raise ValueError(f"Order is already {order.status}")

        old_status = order.status.value
        order.status = PurchaseOrderStatus.CLOSED
        order.updated_at = datetime.utcnow()

        self.db.flush()

        pending_records = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.resource_type == ResourceType.PURCHASE_ORDER,
                    ApprovalRecord.resource_id == order.id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
            .all()
        )

        for record in pending_records:
            record.status = ApprovalStatus.REJECTED
            record.approval_opinion = f"Order closed: {close_reason}"
            record.approved_at = datetime.utcnow()

        self.audit_logger.log(
            user_id=closed_by.id,
            action="close",
            resource_type="purchase_order",
            resource_id=order.id,
            old_value={"status": old_status},
            new_value={
                "status": order.status.value,
                "close_reason": close_reason,
            },
        )

        cache.delete(f"purchase_order:{order_id}")

        return {
            "success": True,
            "order_id": order.id,
            "order_no": order.order_no,
            "status": order.status.value,
            "close_reason": close_reason,
        }

    def cancel_order(
        self,
        order_id: int,
        cancel_reason: str,
        cancelled_by: User,
    ) -> Dict[str, Any]:
        order = self.get_order(order_id)
        if not order:
            raise ValueError("Order not found")

        if order.status in [
            PurchaseOrderStatus.RECEIVED,
            PurchaseOrderStatus.PARTIAL_RECEIVED,
            PurchaseOrderStatus.CLOSED,
            PurchaseOrderStatus.CANCELLED,
        ]:
            raise ValueError(f"Cannot cancel order in status: {order.status}")

        old_status = order.status.value
        order.status = PurchaseOrderStatus.CANCELLED
        order.updated_at = datetime.utcnow()

        self.db.flush()

        pending_records = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.resource_type == ResourceType.PURCHASE_ORDER,
                    ApprovalRecord.resource_id == order.id,
                    ApprovalRecord.status == ApprovalStatus.PENDING,
                )
            )
            .all()
        )

        for record in pending_records:
            record.status = ApprovalStatus.REJECTED
            record.approval_opinion = f"Order cancelled: {cancel_reason}"
            record.approved_at = datetime.utcnow()

        self.audit_logger.log(
            user_id=cancelled_by.id,
            action="cancel",
            resource_type="purchase_order",
            resource_id=order.id,
            old_value={"status": old_status},
            new_value={
                "status": order.status.value,
                "cancel_reason": cancel_reason,
            },
        )

        cache.delete(f"purchase_order:{order_id}")

        return {
            "success": True,
            "order_id": order.id,
            "order_no": order.order_no,
            "status": order.status.value,
            "cancel_reason": cancel_reason,
        }

    def get_order_detail(
        self,
        order_id: int,
        current_user: Optional[User] = None,
    ) -> Optional[Dict[str, Any]]:
        order = self.get_order(order_id)
        if not order:
            return None

        approval_records = (
            self.db.query(ApprovalRecord)
            .filter(
                and_(
                    ApprovalRecord.resource_type == ResourceType.PURCHASE_ORDER,
                    ApprovalRecord.resource_id == order.id,
                )
            )
            .order_by(ApprovalRecord.created_at)
            .all()
        )

        inventory_transactions = (
            self.db.query(InventoryTransaction)
            .filter(
                and_(
                    InventoryTransaction.reference_type == "PURCHASE_ORDER",
                    InventoryTransaction.reference_id == order.id,
                )
            )
            .all()
        )

        order_dict = {
            "id": order.id,
            "order_no": order.order_no,
            "supplier_id": order.supplier_id,
            "supplier_name": order.supplier.name if order.supplier else None,
            "warehouse_id": order.warehouse_id,
            "warehouse_name": order.warehouse.name if order.warehouse else None,
            "total_amount": order.total_amount,
            "tax_amount": order.tax_amount,
            "discount_amount": order.discount_amount,
            "shipping_cost": order.shipping_cost,
            "grand_total": order.grand_total,
            "status": order.status,
            "order_date": order.order_date,
            "expected_date": order.expected_date,
            "actual_date": order.actual_date,
            "shipping_method": order.shipping_method,
            "tax_rate": order.tax_rate,
            "discount_rate": order.discount_rate,
            "remark": order.remark,
            "created_by": order.created_by,
            "created_by_name": order.creator.username if order.creator else None,
            "created_at": order.created_at,
            "updated_at": order.updated_at,
            "approved_by": order.approved_by,
            "approved_by_name": order.approver.username if order.approver else None,
            "approved_at": order.approved_at,
            "items": [],
            "approval_records": [],
            "inventory_transactions": [],
        }

        for item in order.items:
            item_dict = {
                "id": item.id,
                "sku_id": item.sku_id,
                "sku_code": item.sku.sku_code if item.sku else None,
                "sku_name": item.sku.product.name if item.sku and item.sku.product else None,
                "quantity": item.quantity,
                "unit_price": item.unit_price,
                "received_quantity": item.received_quantity,
                "rejected_quantity": item.rejected_quantity,
                "tax_rate": item.tax_rate,
                "tax_amount": item.tax_amount,
                "total_amount": item.total_amount,
                "remark": item.remark,
                "created_at": item.created_at,
            }
            order_dict["items"].append(item_dict)

        for record in approval_records:
            record_dict = {
                "id": record.id,
                "node_id": record.node_id,
                "node_name": record.node.node_name if record.node else None,
                "approver_id": record.approver_id,
                "approver_name": record.approver.username if record.approver else None,
                "status": record.status.value,
                "approval_opinion": record.approval_opinion,
                "approved_at": record.approved_at,
                "created_at": record.created_at,
            }
            order_dict["approval_records"].append(record_dict)

        for tx in inventory_transactions:
            tx_dict = {
                "id": tx.id,
                "sku_id": tx.sku_id,
                "transaction_type": tx.transaction_type.value,
                "quantity": tx.quantity,
                "unit_cost": tx.unit_cost,
                "created_at": tx.created_at,
            }
            order_dict["inventory_transactions"].append(tx_dict)

        order_dict["can_submit"] = order.status in [
            PurchaseOrderStatus.DRAFT,
            PurchaseOrderStatus.REJECTED,
        ]
        order_dict["can_receive"] = order.status in [
            PurchaseOrderStatus.APPROVED,
            PurchaseOrderStatus.PROCESSING,
            PurchaseOrderStatus.PARTIAL_RECEIVED,
        ]
        order_dict["can_close"] = order.status in [
            PurchaseOrderStatus.RECEIVED,
            PurchaseOrderStatus.PARTIAL_RECEIVED,
            PurchaseOrderStatus.APPROVED,
            PurchaseOrderStatus.PROCESSING,
        ]
        order_dict["can_cancel"] = order.status in [
            PurchaseOrderStatus.DRAFT,
            PurchaseOrderStatus.SUBMITTED,
            PurchaseOrderStatus.APPROVING,
            PurchaseOrderStatus.REJECTED,
        ]
        order_dict["can_edit"] = order.status in [
            PurchaseOrderStatus.DRAFT,
            PurchaseOrderStatus.REJECTED,
        ]

        if current_user:
            order_dict["can_approve"] = self.approval_service.can_approve_resource(
                resource_id=order.id,
                resource_type=ResourceType.PURCHASE_ORDER,
                user_id=current_user.id,
            )

        current_node = self.approval_service.get_current_approval_node(
            resource_id=order.id,
            resource_type=ResourceType.PURCHASE_ORDER,
        )
        if current_node:
            order_dict["current_approval_node"] = current_node.node_name
            order_dict["approval_status"] = "APPROVING"
        elif order.status == PurchaseOrderStatus.APPROVED:
            order_dict["approval_status"] = "APPROVED"
        elif order.status == PurchaseOrderStatus.REJECTED:
            order_dict["approval_status"] = "REJECTED"
        else:
            order_dict["approval_status"] = "NOT_SUBMITTED"

        return order_dict

    def process_approval_callback(
        self,
        resource_id: int,
        approval_status: ApprovalStatus,
        approved_by: Optional[int] = None,
    ) -> None:
        order = self.get_order(resource_id)
        if not order:
            return

        if approval_status == ApprovalStatus.APPROVED:
            order.status = PurchaseOrderStatus.APPROVED
            order.approved_by = approved_by
            order.approved_at = datetime.utcnow()
        elif approval_status == ApprovalStatus.REJECTED:
            order.status = PurchaseOrderStatus.REJECTED
        elif approval_status == ApprovalStatus.PENDING:
            order.status = PurchaseOrderStatus.PARTIAL_APPROVED

        order.updated_at = datetime.utcnow()

        cache.delete(f"purchase_order:{resource_id}")

        logger.info(
            f"Purchase order {order.order_no} status updated to {order.status} "
            f"via approval callback"
        )


def create_purchase_order_service(db: Session) -> PurchaseOrderService:
    forecast_service = ForecastService(db)
    return PurchaseOrderService(db, forecast_service)
