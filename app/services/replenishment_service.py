from datetime import datetime, timedelta, date
from typing import Optional, List, Dict, Any, Tuple
import math
import numpy as np

from sqlalchemy.orm import Session
from sqlalchemy import and_, func, desc, asc

from app.core.cache import cache
from app.core.logging import get_logger
from app.core.audit import AuditLogger
from app.models.replenishment import (
    ReplenishmentSuggestion,
    SalesForecast,
    ReplenishmentStatus,
    ForecastPeriod,
    ForecastMethod,
)
from app.models.inventory import Inventory
from app.models.sku import SKU, SkuStatus
from app.models.warehouse import Warehouse
from app.models.supplier import Supplier
from app.models.product import Product
from app.models.purchase_order import PurchaseOrder, PurchaseOrderItem
from app.models.inventory_transaction import InventoryTransaction, TransactionType
from app.models.user import User
from app.schemas.replenishment import (
    ReplenishmentReviewRequest,
    ReplenishmentConvertRequest,
    ReplenishmentGenerateRequest,
    ForecastRequest,
    ForecastResponse,
    ReplenishmentStatisticsResponse,
    ReplenishmentGenerateResponse,
)
from app.schemas.purchase_order import PurchaseOrderCreate, PurchaseOrderItemCreate
from app.services.crud_base import CRUDBase
from app.services.purchase_order_service import PurchaseOrderService
from app.utils.forecast.seasonal import (
    seasonal_forecast,
)

logger = get_logger(__name__)


class ReplenishmentService:
    def __init__(self, db: Session, current_user: Optional[User] = None):
        self.db = db
        self.current_user = current_user
        self.audit_logger = AuditLogger(db)
        self.suggestion_crud = CRUDBase(
            ReplenishmentSuggestion, cache_prefix="replenishment_suggestion"
        )
        self.forecast_crud = CRUDBase(SalesForecast, cache_prefix="sales_forecast")
        self.po_service = PurchaseOrderService(db, current_user)

    def _get_sku_inventory(self, sku_id: int, warehouse_id: int) -> Tuple[int, int, int]:
        inventories = (
            self.db.query(Inventory)
            .filter(
                Inventory.sku_id == sku_id,
                Inventory.warehouse_id == warehouse_id,
            )
            .all()
        )

        total_quantity = sum(inv.quantity for inv in inventories) if inventories else 0
        available_quantity = sum(inv.available_quantity for inv in inventories) if inventories else 0
        in_transit_quantity = sum(inv.in_transit_quantity for inv in inventories) if inventories else 0

        return total_quantity, available_quantity, in_transit_quantity

    def _get_sales_history(
        self,
        sku_id: int,
        warehouse_id: Optional[int] = None,
        days: int = 90,
    ) -> List[Dict[str, Any]]:
        start_date = datetime.utcnow() - timedelta(days=days)

        query = self.db.query(
            func.date(InventoryTransaction.created_at).label("date"),
            func.sum(InventoryTransaction.quantity).label("quantity"),
        ).filter(
            InventoryTransaction.sku_id == sku_id,
            InventoryTransaction.transaction_type == TransactionType.OUT,
            InventoryTransaction.created_at >= start_date,
        )

        if warehouse_id:
            query = query.filter(InventoryTransaction.warehouse_id == warehouse_id)

        sales_data = (
            query.group_by(func.date(InventoryTransaction.created_at))
            .order_by("date")
            .all()
        )

        return [
            {"date": str(row.date), "quantity": row.quantity or 0}
            for row in sales_data
        ]

    def _calculate_forecast(
        self,
        sku_id: int,
        warehouse_id: Optional[int] = None,
        forecast_days: int = 30,
        historical_days: int = 90,
        consider_seasonality: bool = True,
        forecast_method: str = "SEASONAL",
    ) -> Tuple[List[float], float, Dict[str, Any]]:
        sales_history = self._get_sales_history(sku_id, warehouse_id, historical_days)

        if len(sales_history) < 14:
            avg_daily = sum(s["quantity"] for s in sales_history) / max(len(sales_history), 1)
            forecast = [max(0, avg_daily) for _ in range(forecast_days)]
            return forecast, max(0, avg_daily), {
                "method": "SIMPLE_AVERAGE",
                "historical_days": len(sales_history),
                "avg_daily_sales": avg_daily,
            }

        quantities = [s["quantity"] for s in sales_history]

        if consider_seasonality and forecast_method == "SEASONAL":
            try:
                _, forecast, metadata = seasonal_forecast(
                    quantities,
                    periods=forecast_days,
                    method="multiplicative",
                    trend_method="linear",
                )
                forecast = [max(0, f) for f in forecast]
                avg_forecast = sum(forecast) / len(forecast) if forecast else 0
                return forecast, avg_forecast, metadata
            except Exception as e:
                logger.warning(
                    "Seasonal forecast failed, falling back to simple method",
                    sku_id=sku_id,
                    error=str(e),
                )

        from app.utils.forecast.algorithms import exponential_smoothing

        try:
            _, forecast = exponential_smoothing(
                quantities, periods=forecast_days, alpha=0.3
            )
            forecast = [max(0, f) for f in forecast]
        except Exception:
            avg_daily = sum(quantities) / len(quantities)
            forecast = [max(0, avg_daily) for _ in range(forecast_days)]

        avg_forecast = sum(forecast) / len(forecast) if forecast else 0

        return forecast, avg_forecast, {
            "method": "EXPONENTIAL_SMOOTHING",
            "historical_days": len(sales_history),
        }

    def _calculate_safety_stock(
        self,
        avg_daily_demand: float,
        lead_time_days: int,
        safety_factor: float = 1.5,
        demand_std_dev: Optional[float] = None,
    ) -> int:
        if demand_std_dev is None:
            demand_std_dev = avg_daily_demand * 0.3

        safety_stock = safety_factor * demand_std_dev * math.sqrt(lead_time_days)
        return max(0, int(math.ceil(safety_stock)))

    def _calculate_reorder_point(
        self,
        avg_daily_demand: float,
        lead_time_days: int,
        safety_stock: int,
    ) -> int:
        lead_time_demand = avg_daily_demand * lead_time_days
        return int(math.ceil(lead_time_demand) + safety_stock)

    def _calculate_economic_order_quantity(
        self,
        annual_demand: float,
        ordering_cost: float = 100.0,
        holding_cost_rate: float = 0.2,
        unit_cost: float = 10.0,
    ) -> int:
        if annual_demand <= 0 or holding_cost_rate <= 0 or unit_cost <= 0:
            return 0

        holding_cost = unit_cost * holding_cost_rate
        if holding_cost <= 0:
            return 0

        eoq = math.sqrt((2 * annual_demand * ordering_cost) / holding_cost)
        return int(math.ceil(eoq))

    def _get_supplier_for_sku(self, sku: SKU) -> Optional[Supplier]:
        from app.models.product import Product

        product = self.db.query(Product).filter(Product.id == sku.product_id).first()
        if not product:
            suppliers = self.db.query(Supplier).filter(Supplier.is_active == True).limit(1).all()
            return suppliers[0] if suppliers else None

        suppliers = (
            self.db.query(Supplier)
            .filter(Supplier.is_active == True)
            .order_by(Supplier.id)
            .limit(5)
            .all()
        )
        return suppliers[0] if suppliers else None

    def _calculate_order_date(
        self,
        current_stock: int,
        avg_daily_demand: float,
        lead_time_days: int,
        safety_stock: int,
    ) -> Optional[datetime]:
        available_stock = current_stock - safety_stock
        if available_stock <= 0:
            return datetime.utcnow()

        days_until_reorder = available_stock / max(avg_daily_demand, 0.01)
        days_until_reorder = max(0, days_until_reorder - lead_time_days)

        if days_until_reorder <= 0:
            return datetime.utcnow()

        return datetime.utcnow() + timedelta(days=days_until_reorder)

    def generate_suggestion(
        self,
        sku: SKU,
        warehouse: Warehouse,
        supplier: Supplier,
        request: ReplenishmentGenerateRequest,
    ) -> Optional[ReplenishmentSuggestion]:
        total_quantity, available_quantity, in_transit_quantity = self._get_sku_inventory(
            sku.id, warehouse.id
        )

        forecast_days = request.forecast_days
        if request.consider_lead_time:
            lead_time_days = supplier.lead_time_days or sku.lead_time_days or 7
            forecast_days += lead_time_days

        forecast, avg_daily_demand, forecast_metadata = self._calculate_forecast(
            sku_id=sku.id,
            warehouse_id=warehouse.id,
            forecast_days=forecast_days,
            consider_seasonality=request.consider_seasonality,
        )

        total_forecast_demand = sum(forecast)

        lead_time_days = supplier.lead_time_days or sku.lead_time_days or 7

        sales_history = self._get_sales_history(sku.id, warehouse.id, days=90)
        quantities = [s["quantity"] for s in sales_history]
        demand_std_dev = (
            float(np.std(quantities)) if len(quantities) > 1 else avg_daily_demand * 0.3
        )

        safety_stock = self._calculate_safety_stock(
            avg_daily_demand=avg_daily_demand,
            lead_time_days=lead_time_days,
            safety_factor=request.safety_stock_factor,
            demand_std_dev=demand_std_dev,
        )

        reorder_point = self._calculate_reorder_point(
            avg_daily_demand=avg_daily_demand,
            lead_time_days=lead_time_days,
            safety_stock=safety_stock,
        )

        net_requirement = (
            total_forecast_demand + safety_stock - available_quantity - in_transit_quantity
        )

        if net_requirement <= 0 and available_quantity >= reorder_point:
            return None

        min_order_qty = request.min_order_quantity or supplier.minimum_order_qty or 1
        suggested_quantity = max(min_order_qty, int(math.ceil(net_requirement)))

        eoq = self._calculate_economic_order_quantity(
            annual_demand=avg_daily_demand * 365,
            unit_cost=sku.cost_price or 10.0,
        )
        if eoq > 0 and suggested_quantity < eoq:
            suggested_quantity = max(min_order_qty, eoq)

        suggested_unit_price = sku.cost_price or 0.0
        estimated_total_cost = round(suggested_quantity * suggested_unit_price, 2)

        expected_delivery_date = (datetime.utcnow() + timedelta(days=lead_time_days)).date()

        reason_parts = []
        if net_requirement > 0:
            reason_parts.append(f"净需求{int(net_requirement)}件")
        if available_quantity < reorder_point:
            reason_parts.append(f"库存低于再订货点({reorder_point})")
        if forecast_metadata.get("seasonality_detected"):
            reason_parts.append("考虑季节性因素")
        if request.consider_lead_time:
            reason_parts.append(f"考虑交货周期({lead_time_days}天)")

        reason = "、".join(reason_parts) if reason_parts else "智能补货建议"

        suggestion_data = {
            "sku_id": sku.id,
            "supplier_id": supplier.id,
            "warehouse_id": warehouse.id,
            "suggested_quantity": suggested_quantity,
            "suggested_unit_price": suggested_unit_price,
            "estimated_total_cost": estimated_total_cost,
            "reason": reason,
            "demand_forecast": int(math.ceil(total_forecast_demand)),
            "current_stock": available_quantity,
            "safety_stock": safety_stock,
            "lead_time_days": lead_time_days,
            "expected_delivery_date": expected_delivery_date,
            "status": ReplenishmentStatus.PENDING,
            "created_by": self.current_user.id if self.current_user else 1,
        }

        suggestion = ReplenishmentSuggestion(**suggestion_data)
        return suggestion

    async def generate_suggestions(
        self,
        request: ReplenishmentGenerateRequest,
    ) -> ReplenishmentGenerateResponse:
        sku_query = self.db.query(SKU).filter(SKU.status == SkuStatus.ACTIVE)

        if request.sku_ids:
            sku_query = sku_query.filter(SKU.id.in_(request.sku_ids))
        if request.category_id:
            sku_query = sku_query.join(Product).filter(
                Product.category_id == request.category_id
            )
        if request.supplier_id:
            pass

        skus = sku_query.all()

        warehouse_query = self.db.query(Warehouse)
        if request.warehouse_id:
            warehouse_query = warehouse_query.filter(Warehouse.id == request.warehouse_id)
        warehouses = warehouse_query.all()

        suggestions: List[ReplenishmentSuggestion] = []
        forecast_summary: Dict[str, Any] = {
            "total_sku_analyzed": len(skus),
            "total_warehouses": len(warehouses),
            "seasonality_detected_count": 0,
        }

        for sku in skus:
            supplier = self._get_supplier_for_sku(sku)
            if not supplier:
                continue

            if request.supplier_id and supplier.id != request.supplier_id:
                continue

            for warehouse in warehouses:
                suggestion = self.generate_suggestion(sku, warehouse, supplier, request)
                if suggestion:
                    existing = (
                        self.db.query(ReplenishmentSuggestion)
                        .filter(
                            ReplenishmentSuggestion.sku_id == sku.id,
                            ReplenishmentSuggestion.warehouse_id == warehouse.id,
                            ReplenishmentSuggestion.status == ReplenishmentStatus.PENDING,
                        )
                        .first()
                    )

                    if existing:
                        continue

                    self.db.add(suggestion)
                    self.db.flush()
                    self.db.refresh(suggestion)
                    suggestions.append(suggestion)

                    cache.delete_pattern("replenishment_suggestion:list:*")

                    if self.current_user:
                        self.audit_logger.log_create(
                            user=self.current_user,
                            resource_type="replenishment_suggestion",
                            resource_id=suggestion.id,
                            new_value={
                                "sku_id": sku.id,
                                "warehouse_id": warehouse.id,
                                "suggested_quantity": suggestion.suggested_quantity,
                                "estimated_total_cost": suggestion.estimated_total_cost,
                            },
                        )

        self.db.flush()

        total_suggested_quantity = sum(s.suggested_quantity for s in suggestions)
        total_estimated_cost = sum(s.estimated_total_cost for s in suggestions)

        logger.info(
            "Replenishment suggestions generated",
            count=len(suggestions),
            total_quantity=total_suggested_quantity,
            total_cost=total_estimated_cost,
        )

        return ReplenishmentGenerateResponse(
            generated_count=len(suggestions),
            total_suggested_quantity=total_suggested_quantity,
            total_estimated_cost=round(total_estimated_cost, 2),
            suggestions=suggestions,
            forecast_summary=forecast_summary,
        )

    def get_suggestion(self, suggestion_id: int) -> Optional[ReplenishmentSuggestion]:
        return self.suggestion_crud.get(self.db, id=suggestion_id)

    def list_suggestions(
        self,
        page: int = 1,
        page_size: int = 20,
        status: Optional[ReplenishmentStatus] = None,
        sku_id: Optional[int] = None,
        supplier_id: Optional[int] = None,
        warehouse_id: Optional[int] = None,
        created_by: Optional[int] = None,
        date_from: Optional[datetime] = None,
        date_to: Optional[datetime] = None,
        min_quantity: Optional[int] = None,
        max_quantity: Optional[int] = None,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> Tuple[List[ReplenishmentSuggestion], int, int]:
        stmt = self.db.query(ReplenishmentSuggestion)
        count_stmt = self.db.query(func.count(ReplenishmentSuggestion.id))

        where_conditions = []
        if status:
            where_conditions.append(ReplenishmentSuggestion.status == status)
        if sku_id:
            where_conditions.append(ReplenishmentSuggestion.sku_id == sku_id)
        if supplier_id:
            where_conditions.append(ReplenishmentSuggestion.supplier_id == supplier_id)
        if warehouse_id:
            where_conditions.append(ReplenishmentSuggestion.warehouse_id == warehouse_id)
        if created_by:
            where_conditions.append(ReplenishmentSuggestion.created_by == created_by)
        if date_from:
            where_conditions.append(ReplenishmentSuggestion.created_at >= date_from)
        if date_to:
            where_conditions.append(ReplenishmentSuggestion.created_at <= date_to)
        if min_quantity:
            where_conditions.append(
                ReplenishmentSuggestion.suggested_quantity >= min_quantity
            )
        if max_quantity:
            where_conditions.append(
                ReplenishmentSuggestion.suggested_quantity <= max_quantity
            )

        if where_conditions:
            condition = and_(*where_conditions)
            stmt = stmt.where(condition)
            count_stmt = count_stmt.where(condition)

        total = count_stmt.scalar() or 0

        if sort_by and hasattr(ReplenishmentSuggestion, sort_by):
            sort_column = getattr(ReplenishmentSuggestion, sort_by)
            stmt = stmt.order_by(desc(sort_column) if sort_order == "desc" else asc(sort_column))
        else:
            stmt = stmt.order_by(desc(ReplenishmentSuggestion.created_at))

        offset = (page - 1) * page_size
        stmt = stmt.offset(offset).limit(page_size)

        items = stmt.all()
        total_pages = (total + page_size - 1) // page_size

        return items, total, total_pages

    def review_suggestion(
        self,
        suggestion_id: int,
        request: ReplenishmentReviewRequest,
    ) -> ReplenishmentSuggestion:
        suggestion = self.suggestion_crud.get_or_404(
            self.db, id=suggestion_id, use_cache=False
        )

        if suggestion.status != ReplenishmentStatus.PENDING:
            raise ValueError("Only pending suggestions can be reviewed")

        if not self.current_user:
            raise ValueError("Current user is required")

        old_data = {
            "status": suggestion.status.value,
            "suggested_quantity": suggestion.suggested_quantity,
            "reviewed_by": suggestion.reviewed_by,
            "reviewed_at": suggestion.reviewed_at,
        }

        if request.approved:
            suggestion.status = ReplenishmentStatus.APPROVED
        else:
            suggestion.status = ReplenishmentStatus.REJECTED

        if request.adjusted_quantity:
            suggestion.suggested_quantity = request.adjusted_quantity
            suggestion.estimated_total_cost = round(
                suggestion.suggested_quantity * suggestion.suggested_unit_price, 2
            )

        suggestion.reviewed_by = self.current_user.id
        suggestion.reviewed_at = datetime.utcnow()
        suggestion.updated_at = datetime.utcnow()

        self.db.flush()
        self.db.refresh(suggestion)

        cache.delete(f"replenishment_suggestion:{suggestion_id}")
        cache.delete_pattern("replenishment_suggestion:list:*")

        self.audit_logger.log_update(
            user=self.current_user,
            resource_type="replenishment_suggestion",
            resource_id=suggestion_id,
            old_value=old_data,
            new_value={
                "status": suggestion.status.value,
                "suggested_quantity": suggestion.suggested_quantity,
                "reviewed_by": self.current_user.id,
                "reviewed_at": suggestion.reviewed_at.isoformat(),
                "approved": request.approved,
                "remark": request.remark,
            },
        )

        logger.info(
            "Replenishment suggestion reviewed",
            suggestion_id=suggestion_id,
            approved=request.approved,
            user_id=self.current_user.id,
        )

        return suggestion

    def convert_to_purchase_order(
        self,
        suggestion_id: int,
        request: ReplenishmentConvertRequest,
    ) -> int:
        suggestion = self.suggestion_crud.get_or_404(
            self.db, id=suggestion_id, use_cache=False
        )

        if suggestion.status != ReplenishmentStatus.APPROVED:
            raise ValueError("Only approved suggestions can be converted")

        if not self.current_user:
            raise ValueError("Current user is required")

        old_data = {
            "status": suggestion.status.value,
            "purchase_order_id": suggestion.purchase_order_id,
        }

        if request.purchase_order_id:
            purchase_order = self.db.query(PurchaseOrder).filter(
                PurchaseOrder.id == request.purchase_order_id
            ).first()
            if not purchase_order:
                raise ValueError("Purchase order not found")

            po_item = PurchaseOrderItem(
                purchase_order_id=purchase_order.id,
                sku_id=suggestion.sku_id,
                quantity=suggestion.suggested_quantity,
                unit_price=suggestion.suggested_unit_price,
                total_amount=suggestion.estimated_total_cost,
                remark=request.remark,
            )
            self.db.add(po_item)

            purchase_order.total_amount += suggestion.estimated_total_cost
            purchase_order.grand_total += suggestion.estimated_total_cost

            purchase_order_id = purchase_order.id
        else:
            order_date = request.order_date or datetime.utcnow()
            expected_date = request.expected_date or datetime.combine(
                suggestion.expected_delivery_date, datetime.min.time()
            )

            po_create = PurchaseOrderCreate(
                supplier_id=suggestion.supplier_id,
                warehouse_id=suggestion.warehouse_id,
                order_date=order_date,
                expected_date=expected_date,
                remark=request.remark or f"来自补货建议 #{suggestion_id}",
                items=[
                    PurchaseOrderItemCreate(
                        sku_id=suggestion.sku_id,
                        quantity=suggestion.suggested_quantity,
                        unit_price=suggestion.suggested_unit_price,
                        remark=request.remark,
                    )
                ],
            )

            purchase_order = self.po_service.create_order(po_create, self.current_user)
            purchase_order_id = purchase_order.id

        suggestion.status = ReplenishmentStatus.CONVERTED
        suggestion.purchase_order_id = purchase_order_id
        suggestion.updated_at = datetime.utcnow()

        self.db.flush()
        self.db.refresh(suggestion)

        cache.delete(f"replenishment_suggestion:{suggestion_id}")
        cache.delete_pattern("replenishment_suggestion:list:*")
        cache.delete_pattern("purchase_order:*")

        self.audit_logger.log_update(
            user=self.current_user,
            resource_type="replenishment_suggestion",
            resource_id=suggestion_id,
            old_value=old_data,
            new_value={
                "status": ReplenishmentStatus.CONVERTED.value,
                "purchase_order_id": purchase_order_id,
            },
        )

        logger.info(
            "Replenishment suggestion converted to PO",
            suggestion_id=suggestion_id,
            purchase_order_id=purchase_order_id,
            user_id=self.current_user.id,
        )

        return purchase_order_id

    def generate_forecast(
        self,
        request: ForecastRequest,
    ) -> List[ForecastResponse]:
        sku_query = self.db.query(SKU).filter(SKU.status == SkuStatus.ACTIVE)

        if request.sku_ids:
            sku_query = sku_query.filter(SKU.id.in_(request.sku_ids))
        if request.category_id:
            sku_query = sku_query.join(Product).filter(
                Product.category_id == request.category_id
            )

        skus = sku_query.all()
        results: List[ForecastResponse] = []

        for sku in skus:
            try:
                forecast, avg_daily, metadata = self._calculate_forecast(
                    sku_id=sku.id,
                    warehouse_id=request.warehouse_id,
                    forecast_days=request.forecast_days,
                    historical_days=request.historical_days,
                    consider_seasonality=request.consider_seasonality,
                    forecast_method=request.forecast_method.value,
                )

                historical_values = self._get_sales_history(
                    sku.id, request.warehouse_id, request.historical_days
                )

                forecast_start_date = date.today()
                forecast_values = [
                    {
                        "date": (forecast_start_date + timedelta(days=i)).isoformat(),
                        "forecast": round(forecast[i], 2),
                    }
                    for i in range(len(forecast))
                ]

                seasonality_strength = metadata.get("seasonal_strength")
                seasonality_detected = (
                    seasonality_strength is not None and seasonality_strength > 0.3
                )

                forecast_record = SalesForecast(
                    sku_id=sku.id,
                    forecast_date=date.today(),
                    forecast_period=ForecastPeriod(request.forecast_period.value),
                    forecast_method=ForecastMethod(
                        request.forecast_method.value
                        if request.forecast_method.value in [e.value for e in ForecastMethod]
                        else ForecastMethod.ARIMA.value
                    ),
                    historical_data={"values": historical_values},
                    forecast_data={"values": forecast_values},
                    confidence_level=0.85,
                    mape=metadata.get("mape"),
                    rmse=metadata.get("rmse"),
                    mae=metadata.get("mae"),
                )
                self.db.add(forecast_record)
                self.db.flush()

                product = self.db.query(Product).filter(Product.id == sku.product_id).first()
                sku_name = product.name if product else sku.sku_code

                results.append(
                    ForecastResponse(
                        sku_id=sku.id,
                        sku_code=sku.sku_code,
                        sku_name=sku_name,
                        forecast_period=request.forecast_period,
                        forecast_method=request.forecast_method,
                        forecast_days=request.forecast_days,
                        confidence_level=0.85,
                        forecast_values=forecast_values,
                        historical_values=historical_values,
                        seasonal_indices=metadata.get("seasonal_indices"),
                        mape=metadata.get("mape"),
                        rmse=metadata.get("rmse"),
                        mae=metadata.get("mae"),
                        seasonality_detected=seasonality_detected,
                        seasonality_strength=seasonality_strength,
                    )
                )

            except Exception as e:
                logger.error(
                    "Failed to generate forecast for SKU",
                    sku_id=sku.id,
                    error=str(e),
                )
                continue

        self.db.flush()
        cache.delete_pattern("sales_forecast:list:*")

        logger.info("Sales forecast generated", sku_count=len(results))
        return results

    def get_forecast_list(
        self,
        page: int = 1,
        page_size: int = 20,
        sku_id: Optional[int] = None,
        forecast_period: Optional[ForecastPeriod] = None,
        forecast_method: Optional[ForecastMethod] = None,
        date_from: Optional[date] = None,
        date_to: Optional[date] = None,
        min_confidence: Optional[float] = None,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> Tuple[List[SalesForecast], int, int]:
        stmt = self.db.query(SalesForecast)
        count_stmt = self.db.query(func.count(SalesForecast.id))

        where_conditions = []
        if sku_id:
            where_conditions.append(SalesForecast.sku_id == sku_id)
        if forecast_period:
            where_conditions.append(SalesForecast.forecast_period == forecast_period)
        if forecast_method:
            where_conditions.append(SalesForecast.forecast_method == forecast_method)
        if date_from:
            where_conditions.append(SalesForecast.forecast_date >= date_from)
        if date_to:
            where_conditions.append(SalesForecast.forecast_date <= date_to)
        if min_confidence:
            where_conditions.append(SalesForecast.confidence_level >= min_confidence)

        if where_conditions:
            condition = and_(*where_conditions)
            stmt = stmt.where(condition)
            count_stmt = count_stmt.where(condition)

        total = count_stmt.scalar() or 0

        if sort_by and hasattr(SalesForecast, sort_by):
            sort_column = getattr(SalesForecast, sort_by)
            stmt = stmt.order_by(desc(sort_column) if sort_order == "desc" else asc(sort_column))
        else:
            stmt = stmt.order_by(desc(SalesForecast.created_at))

        offset = (page - 1) * page_size
        stmt = stmt.offset(offset).limit(page_size)

        items = stmt.all()
        total_pages = (total + page_size - 1) // page_size

        return items, total, total_pages

    def get_statistics(
        self,
        date_from: Optional[datetime] = None,
        date_to: Optional[datetime] = None,
    ) -> ReplenishmentStatisticsResponse:
        if not date_from:
            date_from = datetime.utcnow() - timedelta(days=30)
        if not date_to:
            date_to = datetime.utcnow()

        today_start = datetime.utcnow().replace(hour=0, minute=0, second=0, microsecond=0)
        week_start = today_start - timedelta(days=today_start.weekday())
        month_start = today_start.replace(day=1)

        base_query = self.db.query(ReplenishmentSuggestion).filter(
            ReplenishmentSuggestion.created_at >= date_from,
            ReplenishmentSuggestion.created_at <= date_to,
        )

        total_suggestions = base_query.count()
        pending_count = base_query.filter(
            ReplenishmentSuggestion.status == ReplenishmentStatus.PENDING
        ).count()
        approved_count = base_query.filter(
            ReplenishmentSuggestion.status == ReplenishmentStatus.APPROVED
        ).count()
        rejected_count = base_query.filter(
            ReplenishmentSuggestion.status == ReplenishmentStatus.REJECTED
        ).count()
        converted_count = base_query.filter(
            ReplenishmentSuggestion.status == ReplenishmentStatus.CONVERTED
        ).count()

        total_suggested_quantity = (
            base_query.with_entities(func.sum(ReplenishmentSuggestion.suggested_quantity))
            .scalar()
            or 0
        )
        approved_quantity = (
            base_query.filter(ReplenishmentSuggestion.status == ReplenishmentStatus.APPROVED)
            .with_entities(func.sum(ReplenishmentSuggestion.suggested_quantity))
            .scalar()
            or 0
        )
        converted_quantity = (
            base_query.filter(ReplenishmentSuggestion.status == ReplenishmentStatus.CONVERTED)
            .with_entities(func.sum(ReplenishmentSuggestion.suggested_quantity))
            .scalar()
            or 0
        )

        total_suggested_cost = (
            base_query.with_entities(func.sum(ReplenishmentSuggestion.estimated_total_cost))
            .scalar()
            or 0.0
        )
        approved_cost = (
            base_query.filter(ReplenishmentSuggestion.status == ReplenishmentStatus.APPROVED)
            .with_entities(func.sum(ReplenishmentSuggestion.estimated_total_cost))
            .scalar()
            or 0.0
        )
        converted_cost = (
            base_query.filter(ReplenishmentSuggestion.status == ReplenishmentStatus.CONVERTED)
            .with_entities(func.sum(ReplenishmentSuggestion.estimated_total_cost))
            .scalar()
            or 0.0
        )

        avg_lead_time = (
            base_query.with_entities(func.avg(ReplenishmentSuggestion.lead_time_days))
            .scalar()
            or 0.0
        )

        approval_rate = (approved_count / total_suggestions * 100) if total_suggestions > 0 else 0.0
        conversion_rate = (
            converted_count / max(approved_count, 1) * 100
        ) if approved_count > 0 else 0.0

        today_count = base_query.filter(
            ReplenishmentSuggestion.created_at >= today_start
        ).count()
        week_count = base_query.filter(
            ReplenishmentSuggestion.created_at >= week_start
        ).count()
        month_count = base_query.filter(
            ReplenishmentSuggestion.created_at >= month_start
        ).count()

        trend_data = (
            self.db.query(
                func.date(ReplenishmentSuggestion.created_at).label("date"),
                func.count(ReplenishmentSuggestion.id).label("count"),
                func.sum(ReplenishmentSuggestion.suggested_quantity).label("quantity"),
            )
            .filter(
                ReplenishmentSuggestion.created_at >= date_from,
                ReplenishmentSuggestion.created_at <= date_to,
            )
            .group_by(func.date(ReplenishmentSuggestion.created_at))
            .order_by("date")
            .all()
        )

        top_skus = (
            self.db.query(
                ReplenishmentSuggestion.sku_id,
                func.count(ReplenishmentSuggestion.id).label("suggestion_count"),
                func.sum(ReplenishmentSuggestion.suggested_quantity).label("total_quantity"),
            )
            .filter(
                ReplenishmentSuggestion.created_at >= date_from,
                ReplenishmentSuggestion.created_at <= date_to,
            )
            .group_by(ReplenishmentSuggestion.sku_id)
            .order_by(desc("total_quantity"))
            .limit(10)
            .all()
        )

        top_suppliers = (
            self.db.query(
                ReplenishmentSuggestion.supplier_id,
                func.count(ReplenishmentSuggestion.id).label("suggestion_count"),
                func.sum(ReplenishmentSuggestion.suggested_quantity).label("total_quantity"),
            )
            .filter(
                ReplenishmentSuggestion.created_at >= date_from,
                ReplenishmentSuggestion.created_at <= date_to,
            )
            .group_by(ReplenishmentSuggestion.supplier_id)
            .order_by(desc("total_quantity"))
            .limit(10)
            .all()
        )

        top_warehouses = (
            self.db.query(
                ReplenishmentSuggestion.warehouse_id,
                func.count(ReplenishmentSuggestion.id).label("suggestion_count"),
                func.sum(ReplenishmentSuggestion.suggested_quantity).label("total_quantity"),
            )
            .filter(
                ReplenishmentSuggestion.created_at >= date_from,
                ReplenishmentSuggestion.created_at <= date_to,
            )
            .group_by(ReplenishmentSuggestion.warehouse_id)
            .order_by(desc("total_quantity"))
            .limit(10)
            .all()
        )

        return ReplenishmentStatisticsResponse(
            total_suggestions=total_suggestions,
            pending_count=pending_count,
            approved_count=approved_count,
            rejected_count=rejected_count,
            converted_count=converted_count,
            total_suggested_quantity=total_suggested_quantity,
            total_approved_quantity=approved_quantity,
            total_converted_quantity=converted_quantity,
            total_suggested_cost=round(total_suggested_cost, 2),
            total_approved_cost=round(approved_cost, 2),
            total_converted_cost=round(converted_cost, 2),
            approval_rate=round(approval_rate, 2),
            conversion_rate=round(conversion_rate, 2),
            average_lead_time=round(avg_lead_time, 2),
            today_count=today_count,
            week_count=week_count,
            month_count=month_count,
            trend_data=[
                {
                    "date": str(row.date),
                    "count": row.count,
                    "quantity": row.quantity or 0,
                }
                for row in trend_data
            ],
            top_skus=[
                {
                    "sku_id": row.sku_id,
                    "suggestion_count": row.suggestion_count,
                    "total_quantity": row.total_quantity or 0,
                }
                for row in top_skus
            ],
            top_suppliers=[
                {
                    "supplier_id": row.supplier_id,
                    "suggestion_count": row.suggestion_count,
                    "total_quantity": row.total_quantity or 0,
                }
                for row in top_suppliers
            ],
            top_warehouses=[
                {
                    "warehouse_id": row.warehouse_id,
                    "suggestion_count": row.suggestion_count,
                    "total_quantity": row.total_quantity or 0,
                }
                for row in top_warehouses
            ],
        )


def create_replenishment_service(
    db: Session, current_user: Optional[User] = None
) -> ReplenishmentService:
    return ReplenishmentService(db=db, current_user=current_user)
