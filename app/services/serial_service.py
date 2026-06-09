from datetime import datetime, timedelta
from typing import Optional, List, Dict, Any, Tuple
import csv
import io

from sqlalchemy import and_, or_, func, desc, asc
from sqlalchemy.orm import Session, joinedload

from app.core.cache import cache
from app.core.logging import get_logger
from app.core.audit import AuditLogger
from app.models.serial_number import (
    SerialNumber,
    SerialNumberTrace,
    SerialNumberStatus,
    TraceAction,
)
from app.models.batch import Batch
from app.models.sku import SKU
from app.models.warehouse import Warehouse
from app.models.user import User
from app.schemas.serial import (
    SerialNumberCreate,
    SerialNumberUpdate,
    SerialNumberImportRequest,
    SerialNumberImportResponse,
    SerialNumberVerifyRequest,
    SerialNumberVerifyResponse,
    SerialNumberVerifyResult,
    SerialNumberScanRequest,
    SerialNumberScanResponse,
    SerialNumberFilterParams,
    SerialNumberAllocateRequest,
    SerialNumberShipRequest,
    SerialNumberReturnRequest,
    SerialNumberScrapRequest,
    SerialTraceQuery,
    TraceDirectionEnum,
    TraceResponse,
    SerialNumberStatusEnum,
)
from app.services.crud_base import CRUDBase
from app.utils.trace_engine import create_trace_engine
from app.utils.exceptions import InventoryException
from app.utils.constants import EXPIRING_WARNING_DAYS

logger = get_logger(__name__)

SERIAL_CACHE_PREFIX = "serial:info:"
SERIAL_CACHE_TTL = 300
SERIAL_IMPORT_BATCH_SIZE = 1000


class SerialNumberService:
    def __init__(self, db: Session, current_user: Optional[User] = None):
        self.db = db
        self.current_user = current_user
        self.audit_logger = AuditLogger(db)
        self.crud = CRUDBase(SerialNumber, cache_prefix="serial_number")
        self.trace_engine = create_trace_engine(db)

    def _get_serial_data(self, serial: SerialNumber) -> Dict[str, Any]:
        return {
            "id": serial.id,
            "serial_code": serial.serial_code,
            "sku_id": serial.sku_id,
            "batch_id": serial.batch_id,
            "warehouse_id": serial.warehouse_id,
            "status": serial.status.value,
            "production_date": serial.production_date.isoformat() if serial.production_date else None,
            "expiration_date": serial.expiration_date.isoformat() if serial.expiration_date else None,
            "current_location": serial.current_location,
        }

    def _invalidate_serial_cache(self, serial_id: int, serial_code: str) -> None:
        try:
            cache.delete(f"{SERIAL_CACHE_PREFIX}{serial_id}")
            cache.delete(f"{SERIAL_CACHE_PREFIX}{serial_code}")
            self.trace_engine.invalidate_serial_trace_cache(serial_code)
        except Exception as e:
            logger.warning(f"Failed to invalidate serial cache: {e}")

    def get_serial(self, serial_id: int) -> SerialNumber:
        serial = self.db.get(SerialNumber, serial_id)
        if not serial:
            raise InventoryException(
                f"序列号记录 {serial_id} 不存在",
                code=404,
                details={"serial_id": serial_id}
            )
        return serial

    def get_serial_by_code(self, serial_code: str) -> SerialNumber:
        serial = (
            self.db.query(SerialNumber)
            .filter(SerialNumber.serial_code == serial_code)
            .first()
        )
        if not serial:
            raise InventoryException(
                f"序列号 {serial_code} 不存在",
                code=404,
                details={"serial_code": serial_code}
            )
        return serial

    def _enrich_serial(self, serial: SerialNumber) -> Dict[str, Any]:
        sku = self.db.get(SKU, serial.sku_id)
        batch = self.db.get(Batch, serial.batch_id) if serial.batch_id else None
        warehouse = self.db.get(Warehouse, serial.warehouse_id)

        data = {
            "id": serial.id,
            "serial_code": serial.serial_code,
            "sku_id": serial.sku_id,
            "sku_code": sku.sku_code if sku else None,
            "sku_name": sku.name if sku else None,
            "batch_id": serial.batch_id,
            "batch_no": batch.batch_no if batch else None,
            "warehouse_id": serial.warehouse_id,
            "warehouse_name": warehouse.name if warehouse else None,
            "status": serial.status,
            "production_date": serial.production_date,
            "expiration_date": serial.expiration_date,
            "received_date": serial.received_date,
            "shipped_date": serial.shipped_date,
            "current_location": serial.current_location,
            "remark": serial.remark,
            "created_at": serial.created_at,
            "updated_at": serial.updated_at,
        }
        return data

    def _enrich_serial_detail(self, serial: SerialNumber) -> Dict[str, Any]:
        data = self._enrich_serial(serial)

        trace_count = (
            self.db.query(func.count(SerialNumberTrace.id))
            .filter(SerialNumberTrace.serial_number_id == serial.id)
            .scalar() or 0
        )

        now = datetime.utcnow()
        shelf_life_days = None
        is_expiring = False
        days_to_expire = None

        if serial.expiration_date:
            delta = serial.expiration_date - now
            days_to_expire = delta.days
            shelf_life_days = max(0, days_to_expire)
            is_expiring = 0 <= days_to_expire <= EXPIRING_WARNING_DAYS

        data.update({
            "trace_count": trace_count,
            "shelf_life_days": shelf_life_days,
            "is_expiring": is_expiring,
            "days_to_expire": days_to_expire,
        })

        return data

    def list_serials(
        self,
        filters: SerialNumberFilterParams,
        page: int = 1,
        page_size: int = 20,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> Tuple[List[Dict[str, Any]], int, int]:
        query = self.db.query(SerialNumber).options(
            joinedload(SerialNumber.sku),
            joinedload(SerialNumber.batch),
            joinedload(SerialNumber.warehouse),
        )

        if filters.sku_id:
            query = query.filter(SerialNumber.sku_id == filters.sku_id)
        if filters.batch_id:
            query = query.filter(SerialNumber.batch_id == filters.batch_id)
        if filters.warehouse_id:
            query = query.filter(SerialNumber.warehouse_id == filters.warehouse_id)
        if filters.status:
            query = query.filter(SerialNumber.status == filters.status.value)
        if filters.serial_code_prefix:
            query = query.filter(SerialNumber.serial_code.like(f"{filters.serial_code_prefix}%"))
        if filters.date_from:
            query = query.filter(SerialNumber.created_at >= filters.date_from)
        if filters.date_to:
            query = query.filter(SerialNumber.created_at <= filters.date_to)
        if filters.expiration_from:
            query = query.filter(SerialNumber.expiration_date >= filters.expiration_from)
        if filters.expiration_to:
            query = query.filter(SerialNumber.expiration_date <= filters.expiration_to)
        if filters.is_expiring:
            expiring_date = datetime.utcnow() + timedelta(days=EXPIRING_WARNING_DAYS)
            query = query.filter(
                and_(
                    SerialNumber.expiration_date <= expiring_date,
                    SerialNumber.expiration_date >= datetime.utcnow()
                )
            )
        if filters.keyword:
            keyword = f"%{filters.keyword}%"
            query = query.filter(
                or_(
                    SerialNumber.serial_code.ilike(keyword),
                    SerialNumber.current_location.ilike(keyword),
                    SerialNumber.remark.ilike(keyword),
                )
            )

        total = query.count()

        if sort_by:
            sort_column = getattr(SerialNumber, sort_by, None)
            if sort_column is not None:
                if sort_order == "asc":
                    query = query.order_by(asc(sort_column))
                else:
                    query = query.order_by(desc(sort_column))
        else:
            query = query.order_by(desc(SerialNumber.created_at))

        offset = (page - 1) * page_size
        serials = query.offset(offset).limit(page_size).all()

        total_pages = (total + page_size - 1) // page_size

        return [self._enrich_serial(s) for s in serials], total, total_pages

    def get_serial_detail(self, serial_id: int) -> Dict[str, Any]:
        serial = self.get_serial(serial_id)
        return self._enrich_serial_detail(serial)

    def create_serial(self, serial_in: SerialNumberCreate) -> SerialNumber:
        existing = (
            self.db.query(SerialNumber)
            .filter(SerialNumber.serial_code == serial_in.serial_code)
            .first()
        )
        if existing:
            raise InventoryException(
                f"序列号 {serial_in.serial_code} 已存在",
                code=409,
                details={"serial_code": serial_in.serial_code}
            )

        serial = SerialNumber(
            serial_code=serial_in.serial_code,
            sku_id=serial_in.sku_id,
            batch_id=serial_in.batch_id,
            warehouse_id=serial_in.warehouse_id,
            status=SerialNumberStatus(serial_in.status.value),
            production_date=serial_in.production_date,
            expiration_date=serial_in.expiration_date,
            current_location=serial_in.current_location,
            remark=serial_in.remark,
            received_date=datetime.utcnow(),
        )

        self.db.add(serial)
        self.db.flush()

        self._add_trace_record(
            serial_id=serial.id,
            action=TraceAction.RECEIVE,
            from_location=None,
            to_location=serial.current_location or f"WH_{serial.warehouse_id}",
            reference_type=None,
            reference_id=None,
        )

        self._invalidate_serial_cache(serial.id, serial.serial_code)

        if self.current_user:
            self.audit_logger.log_create(
                user=self.current_user,
                resource_type="serial_number",
                resource_id=serial.id,
                new_value=self._get_serial_data(serial),
            )

        return serial

    def update_serial(self, serial_id: int, serial_in: SerialNumberUpdate) -> None:
        serial = self.get_serial(serial_id)
        old_data = self._get_serial_data(serial)

        if serial.status == SerialNumberStatus.SHIPPED:
            raise InventoryException(
                "序列号已出库，无法修改",
                code=400,
                details={"serial_id": serial_id, "serial_code": serial.serial_code}
            )

        update_data = serial_in.model_dump(exclude_unset=True)
        for field, value in update_data.items():
            if hasattr(serial, field):
                setattr(serial, field, value)

        self.db.flush()
        self._invalidate_serial_cache(serial.id, serial.serial_code)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="serial_number",
                resource_id=serial.id,
                old_value=old_data,
                new_value=self._get_serial_data(serial),
            )

    def _add_trace_record(
        self,
        serial_id: int,
        action: TraceAction,
        from_location: Optional[str],
        to_location: Optional[str],
        reference_type: Optional[str],
        reference_id: Optional[int],
    ) -> SerialNumberTrace:
        trace = SerialNumberTrace(
            serial_number_id=serial_id,
            action=action,
            from_location=from_location,
            to_location=to_location,
            reference_type=reference_type,
            reference_id=reference_id,
            operated_by=self.current_user.id if self.current_user else None,
            operated_at=datetime.utcnow(),
        )
        self.db.add(trace)
        self.db.flush()
        return trace

    def _validate_status_transition(
        self,
        current_status: SerialNumberStatus,
        target_status: SerialNumberStatus,
    ) -> bool:
        valid_transitions = {
            SerialNumberStatus.INSTOCK: [
                SerialNumberStatus.ALLOCATED,
                SerialNumberStatus.SHIPPED,
                SerialNumberStatus.SCRAPPED,
                SerialNumberStatus.RETURNED,
            ],
            SerialNumberStatus.ALLOCATED: [
                SerialNumberStatus.INSTOCK,
                SerialNumberStatus.SHIPPED,
                SerialNumberStatus.SCRAPPED,
            ],
            SerialNumberStatus.SHIPPED: [
                SerialNumberStatus.RETURNED,
            ],
            SerialNumberStatus.RETURNED: [
                SerialNumberStatus.INSTOCK,
                SerialNumberStatus.SCRAPPED,
            ],
            SerialNumberStatus.SCRAPPED: [],
        }

        return target_status in valid_transitions.get(current_status, [])

    def allocate_serial(
        self,
        serial_id: int,
        request: SerialNumberAllocateRequest,
    ) -> None:
        serial = self.get_serial(serial_id)
        old_data = self._get_serial_data(serial)

        if serial.status != SerialNumberStatus.INSTOCK:
            raise InventoryException(
                f"序列号状态为 {serial.status.value}，无法分配",
                code=400,
                details={
                    "serial_id": serial_id,
                    "serial_code": serial.serial_code,
                    "current_status": serial.status.value,
                }
            )

        serial.status = SerialNumberStatus.ALLOCATED
        self.db.flush()

        self._add_trace_record(
            serial_id=serial.id,
            action=TraceAction.ALLOCATE,
            from_location=serial.current_location,
            to_location=f"ALLOCATED_{request.order_id}",
            reference_type=request.order_type,
            reference_id=request.order_id,
        )

        self._invalidate_serial_cache(serial.id, serial.serial_code)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="serial_number",
                resource_id=serial.id,
                old_value=old_data,
                new_value=self._get_serial_data(serial),
            )

    def ship_serial(
        self,
        serial_id: int,
        request: SerialNumberShipRequest,
    ) -> None:
        serial = self.get_serial(serial_id)
        old_data = self._get_serial_data(serial)

        if serial.status not in [SerialNumberStatus.INSTOCK, SerialNumberStatus.ALLOCATED]:
            raise InventoryException(
                f"序列号状态为 {serial.status.value}，无法出库",
                code=400,
                details={
                    "serial_id": serial_id,
                    "serial_code": serial.serial_code,
                    "current_status": serial.status.value,
                }
            )

        serial.status = SerialNumberStatus.SHIPPED
        serial.shipped_date = datetime.utcnow()
        serial.current_location = request.shipping_address or f"CUSTOMER_{request.customer_id or 'UNKNOWN'}"
        self.db.flush()

        self._add_trace_record(
            serial_id=serial.id,
            action=TraceAction.SHIP,
            from_location=serial.current_location,
            to_location=request.shipping_address or f"CUSTOMER_{request.customer_id or 'UNKNOWN'}",
            reference_type=request.order_type,
            reference_id=request.order_id,
        )

        self._invalidate_serial_cache(serial.id, serial.serial_code)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="serial_number",
                resource_id=serial.id,
                old_value=old_data,
                new_value=self._get_serial_data(serial),
            )

    def return_serial(
        self,
        serial_id: int,
        request: SerialNumberReturnRequest,
    ) -> None:
        serial = self.get_serial(serial_id)
        old_data = self._get_serial_data(serial)

        if serial.status != SerialNumberStatus.SHIPPED:
            raise InventoryException(
                f"序列号状态为 {serial.status.value}，无法退货",
                code=400,
                details={
                    "serial_id": serial_id,
                    "serial_code": serial.serial_code,
                    "current_status": serial.status.value,
                }
            )

        serial.status = SerialNumberStatus.RETURNED
        serial.shipped_date = None
        serial.current_location = f"RETURNS_WH_{serial.warehouse_id}"
        self.db.flush()

        self._add_trace_record(
            serial_id=serial.id,
            action=TraceAction.RETURN,
            from_location="CUSTOMER_RETURN",
            to_location=serial.current_location,
            reference_type="RETURN_ORDER",
            reference_id=request.return_order_id,
        )

        self._invalidate_serial_cache(serial.id, serial.serial_code)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="serial_number",
                resource_id=serial.id,
                old_value=old_data,
                new_value=self._get_serial_data(serial),
            )

    def scrap_serial(
        self,
        serial_id: int,
        request: SerialNumberScrapRequest,
    ) -> None:
        serial = self.get_serial(serial_id)
        old_data = self._get_serial_data(serial)

        if serial.status == SerialNumberStatus.SCRAPPED:
            raise InventoryException(
                "序列号已报废",
                code=400,
                details={
                    "serial_id": serial_id,
                    "serial_code": serial.serial_code,
                }
            )

        serial.status = SerialNumberStatus.SCRAPPED
        serial.current_location = f"SCRAPPED_{serial.id}"
        self.db.flush()

        self._add_trace_record(
            serial_id=serial.id,
            action=TraceAction.SCRAP,
            from_location=serial.current_location,
            to_location=f"SCRAPPED_{serial.id}",
            reference_type="SCRAP_ORDER",
            reference_id=request.scrap_order_id,
        )

        self._invalidate_serial_cache(serial.id, serial.serial_code)

        if self.current_user:
            self.audit_logger.log_update(
                user=self.current_user,
                resource_type="serial_number",
                resource_id=serial.id,
                old_value=old_data,
                new_value=self._get_serial_data(serial),
            )

    def import_serials(
        self,
        request: SerialNumberImportRequest,
    ) -> SerialNumberImportResponse:
        success_count = 0
        failed_count = 0
        skipped_count = 0
        updated_count = 0
        failed_items: List[Dict[str, Any]] = []

        for i, item in enumerate(request.items):
            try:
                existing = (
                    self.db.query(SerialNumber)
                    .filter(SerialNumber.serial_code == item.serial_code)
                    .first()
                )

                if existing:
                    if request.skip_existing:
                        skipped_count += 1
                        continue
                    elif request.update_existing:
                        if existing.status == SerialNumberStatus.SHIPPED:
                            failed_count += 1
                            failed_items.append({
                                "index": i,
                                "serial_code": item.serial_code,
                                "error": "已出库序列号无法更新",
                            })
                            continue

                        old_data = self._get_serial_data(existing)

                        if item.batch_id is not None:
                            existing.batch_id = item.batch_id
                        if item.production_date is not None:
                            existing.production_date = item.production_date
                        if item.expiration_date is not None:
                            existing.expiration_date = item.expiration_date
                        if item.remark is not None:
                            existing.remark = item.remark

                        self.db.flush()
                        updated_count += 1

                        if self.current_user:
                            self.audit_logger.log_update(
                                user=self.current_user,
                                resource_type="serial_number",
                                resource_id=existing.id,
                                old_value=old_data,
                                new_value=self._get_serial_data(existing),
                            )

                        self._invalidate_serial_cache(existing.id, existing.serial_code)
                        continue
                    else:
                        failed_count += 1
                        failed_items.append({
                            "index": i,
                            "serial_code": item.serial_code,
                            "error": "序列号已存在",
                        })
                        continue

                serial = SerialNumber(
                    serial_code=item.serial_code,
                    sku_id=item.sku_id,
                    batch_id=item.batch_id,
                    warehouse_id=request.warehouse_id,
                    status=SerialNumberStatus.INSTOCK,
                    production_date=item.production_date,
                    expiration_date=item.expiration_date,
                    remark=item.remark,
                    received_date=datetime.utcnow(),
                )

                self.db.add(serial)
                self.db.flush()

                self._add_trace_record(
                    serial_id=serial.id,
                    action=TraceAction.RECEIVE,
                    from_location=None,
                    to_location=f"WH_{request.warehouse_id}",
                    reference_type="IMPORT",
                    reference_id=None,
                )

                success_count += 1
                self._invalidate_serial_cache(serial.id, serial.serial_code)

                if self.current_user:
                    self.audit_logger.log_create(
                        user=self.current_user,
                        resource_type="serial_number",
                        resource_id=serial.id,
                        new_value=self._get_serial_data(serial),
                    )

            except Exception as e:
                failed_count += 1
                failed_items.append({
                    "index": i,
                    "serial_code": item.serial_code,
                    "error": str(e),
                })

        return SerialNumberImportResponse(
            success_count=success_count,
            failed_count=failed_count,
            skipped_count=skipped_count,
            updated_count=updated_count,
            failed_items=failed_items,
        )

    def verify_serials(
        self,
        request: SerialNumberVerifyRequest,
    ) -> SerialNumberVerifyResponse:
        results: List[SerialNumberVerifyResult] = []
        valid_count = 0
        invalid_count = 0
        available_count = 0

        serial_codes = request.serial_codes
        serials = (
            self.db.query(SerialNumber)
            .filter(SerialNumber.serial_code.in_(serial_codes))
            .all()
        )
        serial_map = {s.serial_code: s for s in serials}

        for code in serial_codes:
            serial = serial_map.get(code)

            if not serial:
                results.append(SerialNumberVerifyResult(
                    serial_code=code,
                    exists=False,
                    is_available=False,
                    status=None,
                    sku_id=None,
                    warehouse_id=None,
                    message="序列号不存在",
                ))
                invalid_count += 1
                continue

            is_available = (
                serial.status == SerialNumberStatus.INSTOCK
                and not self._is_serial_frozen(serial)
            )

            sku_valid = (request.sku_id is None or serial.sku_id == request.sku_id)
            warehouse_valid = (request.warehouse_id is None or serial.warehouse_id == request.warehouse_id)

            if not sku_valid:
                message = f"SKU不匹配，期望: {request.sku_id}，实际: {serial.sku_id}"
            elif not warehouse_valid:
                message = f"仓库不匹配，期望: {request.warehouse_id}，实际: {serial.warehouse_id}"
            elif request.check_available and not is_available:
                message = f"序列号不可用，当前状态: {serial.status.value}"
            else:
                message = "验证通过"
                valid_count += 1
                if is_available:
                    available_count += 1

            if not (sku_valid and warehouse_valid and (not request.check_available or is_available)):
                invalid_count += 1

            results.append(SerialNumberVerifyResult(
                serial_code=code,
                exists=True,
                is_available=is_available,
                status=serial.status.value,
                sku_id=serial.sku_id,
                warehouse_id=serial.warehouse_id,
                message=message,
            ))

        return SerialNumberVerifyResponse(
            results=results,
            valid_count=valid_count,
            invalid_count=invalid_count,
            available_count=available_count,
        )

    def _is_serial_frozen(self, serial: SerialNumber) -> bool:
        if serial.batch_id:
            batch = self.db.get(Batch, serial.batch_id)
            if batch and batch.is_frozen:
                return True
        return False

    def scan_serial(
        self,
        request: SerialNumberScanRequest,
    ) -> SerialNumberScanResponse:
        existing = (
            self.db.query(SerialNumber)
            .filter(SerialNumber.serial_code == request.serial_code)
            .first()
        )

        if not existing:
            if request.auto_create:
                if not request.sku_id:
                    return SerialNumberScanResponse(
                        serial_code=request.serial_code,
                        success=False,
                        message="自动创建需要指定SKU ID",
                        serial_id=None,
                        action=request.action,
                        previous_status=None,
                        new_status=None,
                    )

                serial_in = SerialNumberCreate(
                    serial_code=request.serial_code,
                    sku_id=request.sku_id,
                    batch_id=request.batch_id,
                    warehouse_id=request.warehouse_id,
                    status=SerialNumberStatusEnum.INSTOCK,
                    current_location=request.location,
                )
                serial = self.create_serial(serial_in)

                return SerialNumberScanResponse(
                    serial_code=request.serial_code,
                    success=True,
                    message="序列号创建并入库成功",
                    serial_id=serial.id,
                    action=request.action,
                    previous_status=None,
                    new_status=serial.status.value,
                )
            else:
                return SerialNumberScanResponse(
                    serial_code=request.serial_code,
                    success=False,
                    message="序列号不存在",
                    serial_id=None,
                    action=request.action,
                    previous_status=None,
                    new_status=None,
                )

        previous_status = existing.status

        try:
            if request.action == TraceAction.RECEIVE:
                if existing.status != SerialNumberStatus.INSTOCK:
                    raise InventoryException(f"当前状态 {existing.status.value} 无法执行入库")
                existing.current_location = request.location or f"WH_{request.warehouse_id}"
                existing.received_date = datetime.utcnow()
                new_status = SerialNumberStatus.INSTOCK

            elif request.action == TraceAction.PUTAWAY:
                if existing.status != SerialNumberStatus.INSTOCK:
                    raise InventoryException(f"当前状态 {existing.status.value} 无法执行上架")
                existing.current_location = request.location or existing.current_location
                new_status = SerialNumberStatus.INSTOCK

            elif request.action == TraceAction.TRANSFER:
                if existing.status not in [SerialNumberStatus.INSTOCK, SerialNumberStatus.ALLOCATED]:
                    raise InventoryException(f"当前状态 {existing.status.value} 无法执行调拨")
                existing.warehouse_id = request.warehouse_id
                existing.current_location = request.location or f"WH_{request.warehouse_id}"
                new_status = existing.status

            elif request.action == TraceAction.ALLOCATE:
                if existing.status != SerialNumberStatus.INSTOCK:
                    raise InventoryException(f"当前状态 {existing.status.value} 无法执行分配")
                existing.status = SerialNumberStatus.ALLOCATED
                new_status = SerialNumberStatus.ALLOCATED

            elif request.action == TraceAction.SHIP:
                if existing.status not in [SerialNumberStatus.INSTOCK, SerialNumberStatus.ALLOCATED]:
                    raise InventoryException(f"当前状态 {existing.status.value} 无法执行出库")
                existing.status = SerialNumberStatus.SHIPPED
                existing.shipped_date = datetime.utcnow()
                existing.current_location = request.location or "SHIPPED"
                new_status = SerialNumberStatus.SHIPPED

            elif request.action == TraceAction.RETURN:
                if existing.status != SerialNumberStatus.SHIPPED:
                    raise InventoryException(f"当前状态 {existing.status.value} 无法执行退货")
                existing.status = SerialNumberStatus.RETURNED
                existing.shipped_date = None
                existing.current_location = request.location or f"RETURNS_WH_{request.warehouse_id}"
                new_status = SerialNumberStatus.RETURNED

            elif request.action == TraceAction.SCRAP:
                if existing.status == SerialNumberStatus.SCRAPPED:
                    raise InventoryException("序列号已报废")
                existing.status = SerialNumberStatus.SCRAPPED
                existing.current_location = request.location or f"SCRAPPED_{existing.id}"
                new_status = SerialNumberStatus.SCRAPPED

            else:
                raise InventoryException(f"不支持的操作: {request.action}")

            self.db.flush()

            self._add_trace_record(
                serial_id=existing.id,
                action=request.action,
                from_location=existing.current_location,
                to_location=request.location,
                reference_type=request.reference_type,
                reference_id=request.reference_id,
            )

            self._invalidate_serial_cache(existing.id, existing.serial_code)

            if self.current_user:
                self.audit_logger.log(
                    user_id=self.current_user.id,
                    action=f"scan_{request.action.value.lower()}",
                    resource_type="serial_number",
                    resource_id=existing.id,
                    new_value={
                        "action": request.action.value,
                        "location": request.location,
                    },
                )

            return SerialNumberScanResponse(
                serial_code=request.serial_code,
                success=True,
                message=f"{request.action.value} 操作成功",
                serial_id=existing.id,
                action=request.action,
                previous_status=previous_status.value,
                new_status=new_status.value,
            )

        except InventoryException as e:
            return SerialNumberScanResponse(
                serial_code=request.serial_code,
                success=False,
                message=e.message,
                serial_id=existing.id,
                action=request.action,
                previous_status=previous_status.value,
                new_status=previous_status.value,
            )

    def trace_forward(
        self,
        serial_code: str,
        max_depth: Optional[int] = None,
    ) -> TraceResponse:
        return self.trace_engine.trace_forward(serial_code, max_depth)

    def trace_backward(
        self,
        serial_code: str,
        max_depth: Optional[int] = None,
    ) -> TraceResponse:
        return self.trace_engine.trace_backward(serial_code, max_depth)

    def trace_full(
        self,
        serial_code: str,
        max_depth: Optional[int] = None,
    ) -> TraceResponse:
        return self.trace_engine.trace_full(serial_code, max_depth)

    def get_trace_graph(
        self,
        serial_code: str,
        direction: TraceDirectionEnum = TraceDirectionEnum.FULL,
    ) -> Dict[str, Any]:
        return self.trace_engine.get_trace_graph_data(serial_code, direction)

    def batch_trace(
        self,
        query: SerialTraceQuery,
    ) -> List[TraceResponse]:
        return self.trace_engine.batch_trace(query)

    def export_serials(
        self,
        sku_id: Optional[int] = None,
        batch_id: Optional[int] = None,
        warehouse_id: Optional[int] = None,
        status: Optional[SerialNumberStatusEnum] = None,
    ) -> Tuple[str, List[Dict[str, Any]]]:
        query = self.db.query(SerialNumber).options(
            joinedload(SerialNumber.sku),
            joinedload(SerialNumber.batch),
            joinedload(SerialNumber.warehouse),
        )

        if sku_id:
            query = query.filter(SerialNumber.sku_id == sku_id)
        if batch_id:
            query = query.filter(SerialNumber.batch_id == batch_id)
        if warehouse_id:
            query = query.filter(SerialNumber.warehouse_id == warehouse_id)
        if status:
            query = query.filter(SerialNumber.status == status.value)

        serials = query.order_by(SerialNumber.created_at.desc()).all()

        data = []
        for serial in serials:
            enriched = self._enrich_serial(serial)
            data.append({
                "serial_code": enriched["serial_code"],
                "sku_code": enriched["sku_code"],
                "sku_name": enriched["sku_name"],
                "batch_no": enriched["batch_no"],
                "warehouse_name": enriched["warehouse_name"],
                "status": enriched["status"].value,
                "production_date": enriched["production_date"].isoformat() if enriched["production_date"] else "",
                "expiration_date": enriched["expiration_date"].isoformat() if enriched["expiration_date"] else "",
                "current_location": enriched["current_location"] or "",
                "created_at": enriched["created_at"].isoformat(),
            })

        output = io.StringIO()
        writer = csv.DictWriter(output, fieldnames=data[0].keys() if data else [])
        writer.writeheader()
        writer.writerows(data)

        return output.getvalue(), data


def create_serial_service(db: Session, current_user: Optional[User] = None) -> SerialNumberService:
    return SerialNumberService(db, current_user)
