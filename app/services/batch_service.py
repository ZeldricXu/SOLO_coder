from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any, Tuple

from sqlalchemy import and_, or_, func, desc, asc
from sqlalchemy.orm import Session, joinedload

from app.core.cache import cache
from app.core.database import get_db
from app.core.logging import get_logger
from app.core.audit import AuditLogger
from app.models.batch import Batch, InspectionStatus
from app.models.inventory_alert import (
    InventoryAlert,
    AlertRuleType,
    AlertLevel,
    AlertStatus,
)
from app.models.sku import SKU
from app.models.warehouse import Warehouse
from app.models.supplier import Supplier
from app.models.user import User
from app.schemas.batch import (
    BatchCreate,
    BatchUpdate,
    BatchGenerateRequest,
    BatchGenerateResponse,
    BatchReceiveRequest,
    BatchReceiveResponse,
    BatchSplitRequest,
    BatchSplitResponse,
    BatchMergeRequest,
    BatchMergeResponse,
    BatchFreezeRequest,
    BatchFilterParams,
    BatchInventoryItem,
    AllocationStrategyEnum,
)
from app.services.crud_base import CRUDBase
from app.services.alert_service import AlertService
from app.utils.batch_generator import create_batch_number_generator
from app.utils.trace_engine import create_trace_engine
from app.utils.exceptions import InventoryException
from app.utils.constants import EXPIRING_WARNING_DAYS, EXPIRING_CRITICAL_DAYS

logger = get_logger(__name__)

BATCH_CACHE_PREFIX = "batch:info:"
BATCH_CACHE_TTL = 300
BATCH_INVENTORY_CACHE_PREFIX = "batch:inventory:"


class BatchService:
    def __init__(self, db: Session, current_user: Optional[User] = None):
        self.db = db
        self.current_user = current_user
        self.audit_logger = AuditLogger(db)
        self.crud = CRUDBase(Batch, cache_prefix="batch")
        self.batch_generator = create_batch_number_generator(db)
        self.trace_engine = create_trace_engine(db)
        self.alert_service = AlertService(db, current_user)

    def _get_batch_data(self, batch: Batch) -> Dict[str, Any]:
        return {
            "id": batch.id,
            "batch_no": batch.batch_no,
            "sku_id": batch.sku_id,
            "warehouse_id": batch.warehouse_id,
            "supplier_id": batch.supplier_id,
            "quantity": batch.quantity,
            "remaining_quantity": batch.remaining_quantity,
            "unit_cost": float(batch.unit_cost),
            "production_date": batch.production_date.isoformat() if batch.production_date else None,
            "expiration_date": batch.expiration_date.isoformat() if batch.expiration_date else None,
            "inspection_status": batch.inspection_status.value,
            "is_frozen": batch.is_frozen,
        }

    def _invalidate_batch_cache(self, batch_id: int, batch_no: str) -> None:
        try:
            cache.delete(f"{BATCH_CACHE_PREFIX}{batch_id}")
            cache.delete(f"{BATCH_CACHE_PREFIX}{batch_no}")
            cache.delete_pattern(f"{BATCH_INVENTORY_CACHE_PREFIX}*")
        except Exception as e:
            logger.warning(f"Failed to invalidate batch cache: {e}")

    def _get_cached_batch(self, batch_id: int) -> Optional[Dict[str, Any]]:
        try:
            return cache.get(f"{BATCH_CACHE_PREFIX}{batch_id}")
        except Exception:
            return None

    def _set_cached_batch(self, batch_id: int, data: Dict[str, Any]) -> None:
        try:
            cache.set(f"{BATCH_CACHE_PREFIX}{batch_id}", data, ttl=BATCH_CACHE_TTL)
        except Exception:
            pass

    def get_batch(self, batch_id: int) -> Batch:
        batch = self.db.get(Batch, batch_id)
        if not batch:
            raise InventoryException(
                f"批次 {batch_id} 不存在",
                code=404,
                details={"batch_id": batch_id}
            )
        return batch

    def get_batch_by_no(self, batch_no: str) -> Batch:
        batch = (
            self.db.query(Batch)
            .filter(Batch.batch_no == batch_no)
            .first()
        )
        if not batch:
            raise InventoryException(
                f"批次号 {batch_no} 不存在",
                code=404,
                details={"batch_no": batch_no}
            )
        return batch

    def _enrich_batch(self, batch: Batch) -> Dict[str, Any]:
        sku = self.db.get(SKU, batch.sku_id)
        warehouse = self.db.get(Warehouse, batch.warehouse_id)
        supplier = self.db.get(Supplier, batch.supplier_id) if batch.supplier_id else None

        data = {
            "id": batch.id,
            "batch_no": batch.batch_no,
            "sku_id": batch.sku_id,
            "sku_code": sku.sku_code if sku else None,
            "sku_name": sku.name if sku else None,
            "warehouse_id": batch.warehouse_id,
            "warehouse_name": warehouse.name if warehouse else None,
            "supplier_id": batch.supplier_id,
            "supplier_name": supplier.name if supplier else None,
            "quantity": batch.quantity,
            "remaining_quantity": batch.remaining_quantity,
            "unit_cost": float(batch.unit_cost),
            "production_date": batch.production_date,
            "expiration_date": batch.expiration_date,
            "received_date": batch.received_date,
            "manufacture_date": batch.manufacture_date,
            "lot_number": batch.lot_number,
            "inspection_status": batch.inspection_status,
            "quality_grade": batch.quality_grade,
            "remark": batch.remark,
            "created_at": batch.created_at,
            "updated_at": batch.updated_at,
        }
        return data

    def _enrich_batch_detail(self, batch: Batch) -> Dict[str, Any]:
        data = self._enrich_batch(batch)

        serial_count = (
            self.db.query(func.count())
            .filter(and_(
                Batch.id == batch.id,
                Batch.serial_numbers.any()
            ))
            .scalar() or 0
        )

        now = datetime.utcnow()
        shelf_life_days = None
        is_expiring = False
        days_to_expire = None

        if batch.expiration_date:
            delta = batch.expiration_date - now
            days_to_expire = delta.days
            shelf_life_days = max(0, days_to_expire)
            is_expiring = days_to_expire <= EXPIRING_WARNING_DAYS

        data.update({
            "serial_number_count": serial_count,
            "is_frozen": batch.is_frozen,
            "frozen_reason": batch.frozen_reason,
            "frozen_at": batch.frozen_at,
            "shelf_life_days": shelf_life_days,
            "is_expiring": is_expiring,
            "days_to_expire": days_to_expire,
        })

        return data

    def list_batches(
        self,
        filters: BatchFilterParams,
        page: int = 1,
        page_size: int = 20,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> Tuple[List[Dict[str, Any]], int, int]:
        query = self.db.query(Batch).options(
            joinedload(Batch.sku),
            joinedload(Batch.warehouse),
            joinedload(Batch.supplier),
        )

        if filters.sku_id:
            query = query.filter(Batch.sku_id == filters.sku_id)
        if filters.warehouse_id:
            query = query.filter(Batch.warehouse_id == filters.warehouse_id)
        if filters.supplier_id:
            query = query.filter(Batch.supplier_id == filters.supplier_id)
        if filters.inspection_status:
            query = query.filter(Batch.inspection_status == filters.inspection_status.value)
        if filters.is_frozen is not None:
            query = query.filter(Batch.is_frozen == filters.is_frozen)
        if filters.date_from:
            query = query.filter(Batch.received_date >= filters.date_from)
        if filters.date_to:
            query = query.filter(Batch.received_date <= filters.date_to)
        if filters.expiration_from:
            query = query.filter(Batch.expiration_date >= filters.expiration_from)
        if filters.expiration_to:
            query = query.filter(Batch.expiration_date <= filters.expiration_to)
        if filters.min_remaining is not None:
            query = query.filter(Batch.remaining_quantity >= filters.min_remaining)
        if filters.max_remaining is not None:
            query = query.filter(Batch.remaining_quantity <= filters.max_remaining)
        if filters.is_expiring:
            expiring_date = datetime.utcnow() + timedelta(days=EXPIRING_WARNING_DAYS)
            query = query.filter(
                and_(
                    Batch.expiration_date <= expiring_date,
                    Batch.expiration_date >= datetime.utcnow()
                )
            )
        if filters.keyword:
            keyword = f"%{filters.keyword}%"
            query = query.filter(
                or_(
                    Batch.batch_no.ilike(keyword),
                    Batch.lot_number.ilike(keyword),
                    Batch.remark.ilike(keyword),
                )
            )

        total = query.count()

        if sort_by:
            sort_column = getattr(Batch, sort_by, None)
            if sort_column is not None:
                if sort_order == "asc":
                    query = query.order_by(asc(sort_column))
                else:
                    query = query.order_by(desc(sort_column))
        else:
            query = query.order_by(desc(Batch.created_at))

        offset = (page - 1) * page_size
        batches = query.offset(offset).limit(page_size).all()

        total_pages = (total + page_size - 1) // page_size

        return [self._enrich_batch(b) for b in batches], total, total_pages

    def get_batch_detail(self, batch_id: int) -> Dict[str, Any]:
        batch = self.get_batch(batch_id)
        return self._enrich_batch_detail(batch)

    def generate_batch_numbers(
        self,
        request: BatchGenerateRequest,
    ) -> BatchGenerateResponse:
        batch_numbers = self.batch_generator.generate_batch(
            count=request.count,
            sku_id=request.sku_id,
            warehouse_id=request.warehouse_id,
            supplier_id=request.supplier_id,
            rule=request.rule,
            prefix=request.prefix,
        )

        if self.current_user:
            self.audit_logger.log(
                user_id=self.current_user.id,
                action="generate_batch_numbers",
                resource_type="batch",
                new_value={"count": request.count, "rule": request.rule.value, "batch_numbers": batch_numbers},
            )

        return BatchGenerateResponse(
            batch_numbers=batch_numbers,
            rule=request.rule,
            count=request.count,
        )

    def create_batch(self, batch_in: BatchCreate) -> Batch:
        if batch_in.batch_no:
            existing = (
                self.db.query(Batch)
                .filter(Batch.batch_no == batch_in.batch_no)
                .first()
            )
            if existing:
                raise InventoryException(
                    f"批次号 {batch_in.batch_no} 已存在",
                    code=409,
                    details={"batch_no": batch_in.batch_no}
                )
            batch_no = batch_in.batch_no
        else:
            batch_no = self.batch_generator.generate_single(
                sku_id=batch_in.sku_id,
                warehouse_id=batch_in.warehouse_id,
                supplier_id=batch_in.supplier_id,
            )

        batch = Batch(
            batch_no=batch_no,
            sku_id=batch_in.sku_id,
            warehouse_id=batch_in.warehouse_id,
            supplier_id=batch_in.supplier_id,
            quantity=batch_in.quantity,
            remaining_quantity=batch_in.quantity,
            unit_cost=batch_in.unit_cost,
            production_date=batch_in.production_date,
            expiration_date=batch_in.expiration_date,
            manufacture_date=batch_in.manufacture_date,
            lot_number=batch_in.lot_number,
            inspection_status=InspectionStatus(batch_in.inspection_status.value),
            quality_grade=batch_in.quality_grade,
            remark=batch_in.remark,
            received_date=datetime.utcnow(),
        )

        self.db.add(batch)
        self.db.flush()

        self._invalidate_batch_cache(batch.id, batch.batch_no)

        if self.current_user:
            self.audit_logger.log_create(
                user=self.current_user,
                resource_type="batch",
                resource_id=batch.id,
                new_value=self._get_batch_data(batch),
            )

        return batch

    def update_batch(self, batch_id: int, batch_in: BatchUpdate) -> None:
        batch = self.get_batch(batch_id)
        old_data = self._get_batch_data(batch)

        if batch.is_frozen:
            raise InventoryException(
                "批次已冻结，无法修改",
                code=400,
                details={"batch_id": batch_id, "batch_no": batch.batch_no}
            )

        update_data = batch_in.model_dump(exclude_unset=True)
        for field, value in update_data.items():
            if hasattr(batch, field):
                if field == "inspection_status":
                    value = InspectionStatus(value.value)
                setattr(batch, field, value)

        self.db.flush()
        self._invalidate_batch_cache(batch.id, batch.batch_no)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="batch",
                resource_id=batch.id,
                old_value=old_data,
                new_value=self._get_batch_data(batch),
            )

    def receive_batches(self, request: BatchReceiveRequest) -> BatchReceiveResponse:
        batch_ids: List[int] = []
        batch_numbers: List[str] = []
        total_quantity = 0
        total_amount = 0.0

        for item in request.items:
            batch_no = self.batch_generator.generate_single(
                sku_id=item.sku_id,
                warehouse_id=request.warehouse_id,
                supplier_id=request.supplier_id,
            )

            batch = Batch(
                batch_no=batch_no,
                sku_id=item.sku_id,
                warehouse_id=request.warehouse_id,
                supplier_id=request.supplier_id,
                quantity=item.quantity,
                remaining_quantity=item.quantity,
                unit_cost=item.unit_cost,
                production_date=item.production_date,
                expiration_date=item.expiration_date,
                lot_number=item.lot_number,
                inspection_status=InspectionStatus.PENDING,
                remark=request.remark,
                received_date=datetime.utcnow(),
            )

            self.db.add(batch)
            self.db.flush()

            batch_ids.append(batch.id)
            batch_numbers.append(batch.batch_no)
            total_quantity += item.quantity
            total_amount += item.quantity * float(item.unit_cost)

            self._invalidate_batch_cache(batch.id, batch.batch_no)

            if self.current_user:
                self.audit_logger.log_create(
                    user=self.current_user,
                    resource_type="batch",
                    resource_id=batch.id,
                    new_value=self._get_batch_data(batch),
                )

            if batch.expiration_date:
                self._check_expiring_batch(batch)

        return BatchReceiveResponse(
            batch_ids=batch_ids,
            batch_numbers=batch_numbers,
            total_quantity=total_quantity,
            total_amount=round(total_amount, 2),
        )

    def freeze_batch(self, batch_id: int, request: BatchFreezeRequest) -> None:
        batch = self.get_batch(batch_id)

        if batch.is_frozen:
            raise InventoryException(
                "批次已冻结",
                code=400,
                details={"batch_id": batch_id, "batch_no": batch.batch_no}
            )

        old_data = self._get_batch_data(batch)

        batch.is_frozen = True
        batch.frozen_reason = request.reason
        batch.frozen_at = datetime.utcnow()
        batch.frozen_by = self.current_user.id if self.current_user else None

        self.db.flush()
        self._invalidate_batch_cache(batch.id, batch.batch_no)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="batch",
                resource_id=batch.id,
                old_value=old_data,
                new_value=self._get_batch_data(batch),
            )

    def unfreeze_batch(self, batch_id: int) -> None:
        batch = self.get_batch(batch_id)

        if not batch.is_frozen:
            raise InventoryException(
                "批次未冻结",
                code=400,
                details={"batch_id": batch_id, "batch_no": batch.batch_no}
            )

        old_data = self._get_batch_data(batch)

        batch.is_frozen = False
        batch.frozen_reason = None
        batch.frozen_at = None
        batch.frozen_by = None

        self.db.flush()
        self._invalidate_batch_cache(batch.id, batch.batch_no)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="batch",
                resource_id=batch.id,
                old_value=old_data,
                new_value=self._get_batch_data(batch),
            )

    def split_batch(self, batch_id: int, request: BatchSplitRequest) -> BatchSplitResponse:
        batch = self.get_batch(batch_id)

        if batch.is_frozen:
            raise InventoryException(
                "批次已冻结，无法拆分",
                code=400,
                details={"batch_id": batch_id, "batch_no": batch.batch_no}
            )

        if request.split_quantity > batch.remaining_quantity:
            raise InventoryException(
                f"拆分数量 {request.split_quantity} 大于剩余数量 {batch.remaining_quantity}",
                code=400,
                details={
                    "batch_id": batch_id,
                    "batch_no": batch.batch_no,
                    "split_quantity": request.split_quantity,
                    "remaining_quantity": batch.remaining_quantity,
                }
            )

        if request.new_batch_no:
            existing = (
                self.db.query(Batch)
                .filter(Batch.batch_no == request.new_batch_no)
                .first()
            )
            if existing:
                raise InventoryException(
                    f"批次号 {request.new_batch_no} 已存在",
                    code=409,
                    details={"batch_no": request.new_batch_no}
                )
            new_batch_no = request.new_batch_no
        else:
            new_batch_no = self.batch_generator.generate_single(
                sku_id=batch.sku_id,
                warehouse_id=request.target_warehouse_id or batch.warehouse_id,
                supplier_id=batch.supplier_id,
            )

        old_data = self._get_batch_data(batch)

        new_batch = Batch(
            batch_no=new_batch_no,
            sku_id=batch.sku_id,
            warehouse_id=request.target_warehouse_id or batch.warehouse_id,
            supplier_id=batch.supplier_id,
            quantity=request.split_quantity,
            remaining_quantity=request.split_quantity,
            unit_cost=batch.unit_cost,
            production_date=batch.production_date,
            expiration_date=batch.expiration_date,
            manufacture_date=batch.manufacture_date,
            lot_number=batch.lot_number,
            inspection_status=batch.inspection_status,
            quality_grade=batch.quality_grade,
            remark=request.remark or batch.remark,
            received_date=datetime.utcnow(),
        )

        self.db.add(new_batch)
        self.db.flush()

        batch.remaining_quantity -= request.split_quantity
        batch.quantity -= request.split_quantity

        self.db.flush()

        self._invalidate_batch_cache(batch.id, batch.batch_no)
        self._invalidate_batch_cache(new_batch.id, new_batch.batch_no)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="batch",
                resource_id=batch.id,
                old_value=old_data,
                new_value=self._get_batch_data(batch),
            )
            self.audit_logger.log_create(
                user=self.current_user,
                resource_type="batch",
                resource_id=new_batch.id,
                new_value=self._get_batch_data(new_batch),
            )

        return BatchSplitResponse(
            original_batch_id=batch.id,
            original_batch_no=batch.batch_no,
            original_remaining_quantity=batch.remaining_quantity,
            new_batch_id=new_batch.id,
            new_batch_no=new_batch.batch_no,
            new_quantity=request.split_quantity,
        )

    def merge_batches(self, request: BatchMergeRequest) -> BatchMergeResponse:
        target_batch = self.get_batch(request.target_batch_id)

        if target_batch.is_frozen:
            raise InventoryException(
                "目标批次已冻结，无法合并",
                code=400,
                details={"target_batch_id": request.target_batch_id}
            )

        merged_quantity = 0
        merged_batch_ids: List[int] = []

        for source_batch_id in request.source_batch_ids:
            source_batch = self.get_batch(source_batch_id)

            if source_batch_id == request.target_batch_id:
                continue

            if source_batch.is_frozen:
                raise InventoryException(
                    f"源批次 {source_batch_id} 已冻结，无法合并",
                    code=400,
                    details={"source_batch_id": source_batch_id}
                )

            if (source_batch.sku_id != target_batch.sku_id or
                source_batch.warehouse_id != target_batch.warehouse_id or
                source_batch.supplier_id != target_batch.supplier_id):
                raise InventoryException(
                    f"批次 {source_batch_id} 与目标批次属性不匹配（SKU/仓库/供应商必须一致）",
                    code=400,
                    details={
                        "source_batch_id": source_batch_id,
                        "target_batch_id": target_batch.id,
                    }
                )

            if source_batch.remaining_quantity <= 0:
                continue

            old_target_data = self._get_batch_data(target_batch)
            old_source_data = self._get_batch_data(source_batch)

            transfer_quantity = source_batch.remaining_quantity
            target_batch.remaining_quantity += transfer_quantity
            target_batch.quantity += transfer_quantity

            source_batch.remaining_quantity = 0
            source_batch.quantity = 0

            merged_quantity += transfer_quantity
            merged_batch_ids.append(source_batch_id)

            self.db.flush()

            if self.current_user:
                self.audit_logger.log_update(
                    user=self.current_user,
                    resource_type="batch",
                    resource_id=target_batch.id,
                    old_value=old_target_data,
                    new_value=self._get_batch_data(target_batch),
                )
                self.audit_logger.log_update(
                    user=self.current_user,
                    resource_type="batch",
                    resource_id=source_batch.id,
                    old_value=old_source_data,
                    new_value=self._get_batch_data(source_batch),
                )

            self._invalidate_batch_cache(source_batch.id, source_batch.batch_no)

        self._invalidate_batch_cache(target_batch.id, target_batch.batch_no)

        return BatchMergeResponse(
            target_batch_id=target_batch.id,
            target_batch_no=target_batch.batch_no,
            merged_quantity=merged_quantity,
            merged_batch_ids=merged_batch_ids,
        )

    def get_batch_inventory(
        self,
        batch_id: Optional[int] = None,
        sku_id: Optional[int] = None,
        warehouse_id: Optional[int] = None,
    ) -> List[BatchInventoryItem]:
        cache_key = f"{BATCH_INVENTORY_CACHE_PREFIX}{batch_id or 'all'}_{sku_id or 'all'}_{warehouse_id or 'all'}"
        try:
            cached = cache.get(cache_key)
            if cached:
                return cached
        except Exception:
            pass

        query = self.db.query(Batch).options(
            joinedload(Batch.sku),
            joinedload(Batch.warehouse),
        ).filter(Batch.remaining_quantity > 0)

        if batch_id:
            query = query.filter(Batch.id == batch_id)
        if sku_id:
            query = query.filter(Batch.sku_id == sku_id)
        if warehouse_id:
            query = query.filter(Batch.warehouse_id == warehouse_id)

        batches = query.order_by(Batch.received_date.asc()).all()

        items: List[BatchInventoryItem] = []
        for batch in batches:
            items.append(BatchInventoryItem(
                batch_id=batch.id,
                batch_no=batch.batch_no,
                sku_id=batch.sku_id,
                sku_code=batch.sku.sku_code if batch.sku else "",
                sku_name=batch.sku.name if batch.sku else None,
                quantity=batch.quantity,
                remaining_quantity=batch.remaining_quantity,
                unit_cost=float(batch.unit_cost),
                total_value=round(batch.remaining_quantity * float(batch.unit_cost), 2),
                production_date=batch.production_date,
                expiration_date=batch.expiration_date,
                inspection_status=batch.inspection_status.value,
                is_frozen=batch.is_frozen,
                warehouse_id=batch.warehouse_id,
                warehouse_name=batch.warehouse.name if batch.warehouse else None,
            ))

        try:
            cache.set(cache_key, items, ttl=60)
        except Exception:
            pass

        return items

    def allocate_batches_by_strategy(
        self,
        sku_id: int,
        warehouse_id: int,
        quantity: int,
        strategy: AllocationStrategyEnum = AllocationStrategyEnum.FIFO,
    ) -> List[Tuple[Batch, int]]:
        query = self.db.query(Batch).filter(
            and_(
                Batch.sku_id == sku_id,
                Batch.warehouse_id == warehouse_id,
                Batch.remaining_quantity > 0,
                Batch.is_frozen.is_(False),
                Batch.inspection_status == InspectionStatus.PASSED,
            )
        )

        if strategy == AllocationStrategyEnum.FIFO:
            query = query.order_by(Batch.received_date.asc(), Batch.created_at.asc())
        elif strategy == AllocationStrategyEnum.FEFO:
            query = query.order_by(Batch.expiration_date.asc().nullslast(), Batch.received_date.asc())
        elif strategy == AllocationStrategyEnum.LIFO:
            query = query.order_by(Batch.received_date.desc(), Batch.created_at.desc())
        else:
            query = query.order_by(Batch.received_date.asc(), Batch.created_at.asc())

        batches = query.all()

        allocations: List[Tuple[Batch, int]] = []
        remaining_needed = quantity

        for batch in batches:
            if remaining_needed <= 0:
                break

            allocate_qty = min(batch.remaining_quantity, remaining_needed)
            allocations.append((batch, allocate_qty))
            remaining_needed -= allocate_qty

        if remaining_needed > 0:
            raise InventoryException(
                f"库存不足，需要 {quantity}，可用 {quantity - remaining_needed}",
                code=400,
                details={
                    "sku_id": sku_id,
                    "warehouse_id": warehouse_id,
                    "requested": quantity,
                    "available": quantity - remaining_needed,
                    "strategy": strategy.value,
                }
            )

        return allocations

    def _check_expiring_batch(self, batch: Batch) -> None:
        if not batch.expiration_date:
            return

        now = datetime.utcnow()
        days_to_expire = (batch.expiration_date - now).days

        if days_to_expire <= EXPIRING_CRITICAL_DAYS:
            level = AlertLevel.CRITICAL
        elif days_to_expire <= EXPIRING_WARNING_DAYS:
            level = AlertLevel.WARNING
        else:
            return

        existing_alert = (
            self.db.query(InventoryAlert)
            .filter(
                and_(
                    InventoryAlert.sku_id == batch.sku_id,
                    InventoryAlert.warehouse_id == batch.warehouse_id,
                    InventoryAlert.alert_type == AlertRuleType.EXPIRING,
                    InventoryAlert.status == AlertStatus.OPEN,
                )
            )
            .first()
        )

        if not existing_alert:
            alert = InventoryAlert(
                rule_id=0,
                sku_id=batch.sku_id,
                warehouse_id=batch.warehouse_id,
                alert_level=level,
                alert_type=AlertRuleType.EXPIRING,
                current_value=days_to_expire,
                threshold_value=EXPIRING_WARNING_DAYS,
                message=f"批次 {batch.batch_no} 将在 {days_to_expire} 天后到期",
                status=AlertStatus.OPEN,
            )
            self.db.add(alert)
            self.db.flush()

    def check_expiring_batches(self, days: int = EXPIRING_WARNING_DAYS) -> List[Dict[str, Any]]:
        expiring_date = datetime.utcnow() + timedelta(days=days)

        batches = (
            self.db.query(Batch)
            .options(
                joinedload(Batch.sku),
                joinedload(Batch.warehouse),
            )
            .filter(
                and_(
                    Batch.expiration_date <= expiring_date,
                    Batch.expiration_date >= datetime.utcnow(),
                    Batch.remaining_quantity > 0,
                    Batch.is_frozen == False,
                )
            )
            .order_by(Batch.expiration_date.asc())
            .all()
        )

        result = []
        for batch in batches:
            days_to_expire = (batch.expiration_date - datetime.utcnow()).days
            result.append({
                "batch_id": batch.id,
                "batch_no": batch.batch_no,
                "sku_id": batch.sku_id,
                "sku_code": batch.sku.sku_code if batch.sku else None,
                "sku_name": batch.sku.name if batch.sku else None,
                "warehouse_id": batch.warehouse_id,
                "warehouse_name": batch.warehouse.name if batch.warehouse else None,
                "remaining_quantity": batch.remaining_quantity,
                "expiration_date": batch.expiration_date,
                "days_to_expire": days_to_expire,
                "is_critical": days_to_expire <= EXPIRING_CRITICAL_DAYS,
            })
            self._check_expiring_batch(batch)

        return result

    def get_batch_trace(self, batch_id: int) -> Dict[str, Any]:
        batch = self.get_batch(batch_id)

        return {
            "batch_id": batch.id,
            "batch_no": batch.batch_no,
            "forward_trace": [],
            "backward_trace": [],
        }

    def calculate_shelf_life(self, batch_id: int) -> Dict[str, Any]:
        batch = self.get_batch(batch_id)

        now = datetime.utcnow()
        result = {
            "batch_id": batch.id,
            "batch_no": batch.batch_no,
            "production_date": batch.production_date,
            "expiration_date": batch.expiration_date,
            "received_date": batch.received_date,
        }

        if batch.production_date and batch.expiration_date:
            total_shelf_life = (batch.expiration_date - batch.production_date).days
            remaining_shelf_life = (batch.expiration_date - now).days
            used_shelf_life = (now - batch.production_date).days
            remaining_ratio = (remaining_shelf_life / total_shelf_life * 100) if total_shelf_life > 0 else 0

            result.update({
                "total_shelf_life_days": total_shelf_life,
                "remaining_shelf_life_days": max(0, remaining_shelf_life),
                "used_shelf_life_days": max(0, used_shelf_life),
                "remaining_ratio_percent": round(remaining_ratio, 2),
                "is_expired": remaining_shelf_life < 0,
                "is_expiring": 0 <= remaining_shelf_life <= EXPIRING_WARNING_DAYS,
                "is_critical": 0 <= remaining_shelf_life <= EXPIRING_CRITICAL_DAYS,
            })

        return result


def create_batch_service(db: Session, current_user: Optional[User] = None) -> BatchService:
    return BatchService(db, current_user)
