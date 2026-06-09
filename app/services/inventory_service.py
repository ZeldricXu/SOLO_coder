from __future__ import annotations
import logging
from sqlalchemy import and_, func
from sqlalchemy.orm import Session

from app.models.inventory import Inventory
from app.models.inventory_transaction import (
    InventoryTransaction,
    TransactionType,
)
from app.models.cdc import CDCOperation
from app.schemas.warehouse import (
    InventoryDetail,
    InventoryTransactionCreate,
    InventoryFilterParams,
    InventoryAdjustRequest,
    InventoryTransferRequest,
    InventoryReserveRequest,
    InventoryReleaseRequest,
    InventoryOverview,
    TransactionFilterParams,
    FIFOStrategy,
)
from app.utils.exceptions import (
    InventoryNotFoundException,
    InsufficientInventoryException,
    InventoryLockException,
    InvalidTransactionException,
    WarehouseCapacityExceededException,
)
from app.utils.helpers import (
    calculate_available_quantity,
    calculate_total_value,
    get_current_utc_time,
    generate_batch_code,
)
from app.utils.constants import (
    ALERT_LOW_STOCK_THRESHOLD,
    ALERT_OVERSTOCK_THRESHOLD,
    WAREHOUSE_CAPACITY_DANGER_THRESHOLD,
)
from app.utils.sync_engine import CDCCaptureEngine
from app.services.warehouse_service import WarehouseService, ZoneService

logger = logging.getLogger(__name__)


class InventoryService:
    def __init__(self, db: Session):
        self.db = db
        self.cdc_engine = CDCCaptureEngine(db)
        self.warehouse_service = WarehouseService(db)
        self.zone_service = ZoneService(db)

    def _get_old_inventory_data(self, inventory: Inventory) -> dict:
        return {
            "id": inventory.id,
            "sku_id": inventory.sku_id,
            "warehouse_id": inventory.warehouse_id,
            "zone_id": inventory.zone_id,
            "quantity": inventory.quantity,
            "reserved_quantity": inventory.reserved_quantity,
            "allocated_quantity": inventory.allocated_quantity,
            "available_quantity": inventory.available_quantity,
            "unit_cost": float(inventory.unit_cost),
            "total_value": float(inventory.total_value),
        }

    def _update_available_quantity(self, inventory: Inventory) -> None:
        inventory.available_quantity = calculate_available_quantity(
            inventory.quantity,
            inventory.reserved_quantity,
            inventory.allocated_quantity,
        )
        inventory.total_value = calculate_total_value(
            inventory.quantity, inventory.unit_cost
        )

    def _lock_inventory(self, inventory_id: int) -> Inventory:
        try:
            inventory = (
                self.db.query(Inventory)
                .filter(Inventory.id == inventory_id)
                .with_for_update(nowait=True, skip_locked=False)
                .first()
            )
            if not inventory:
                raise InventoryLockException(inventory_id)
            return inventory
        except Exception as e:
            if "could not obtain lock" in str(e).lower():
                raise InventoryLockException(inventory_id) from e
            raise

    def get_inventory(self, inventory_id: int, lock: bool = False) -> Inventory:
        if lock:
            return self._lock_inventory(inventory_id)

        inventory = self.db.get(Inventory, inventory_id)
        if not inventory:
            raise InventoryNotFoundException(inventory_id)
        return inventory

    def get_inventory_by_key(
        self, sku_id: int, warehouse_id: int, zone_id: int, lock: bool = False
    ) -> Inventory | None:
        query = self.db.query(Inventory).filter(
            and_(
                Inventory.sku_id == sku_id,
                Inventory.warehouse_id == warehouse_id,
                Inventory.zone_id == zone_id,
            )
        )
        if lock:
            query = query.with_for_update(nowait=True)
        return query.first()

    def list_inventories(
        self,
        filters: InventoryFilterParams,
        skip: int = 0,
        limit: int = 100,
        sort_by: str | None = None,
        sort_order: str = "desc",
    ) -> list[Inventory]:
        query = self.db.query(Inventory)

        if filters.sku_id:
            query = query.filter(Inventory.sku_id == filters.sku_id)
        if filters.warehouse_id:
            query = query.filter(Inventory.warehouse_id == filters.warehouse_id)
        if filters.zone_id:
            query = query.filter(Inventory.zone_id == filters.zone_id)
        if filters.min_quantity is not None:
            query = query.filter(Inventory.quantity >= filters.min_quantity)
        if filters.max_quantity is not None:
            query = query.filter(Inventory.quantity <= filters.max_quantity)
        if filters.min_available is not None:
            query = query.filter(Inventory.available_quantity >= filters.min_available)
        if filters.max_available is not None:
            query = query.filter(Inventory.available_quantity <= filters.max_available)
        if filters.has_low_stock:
            query = query.filter(Inventory.available_quantity <= ALERT_LOW_STOCK_THRESHOLD)
        if filters.has_overstock:
            query = query.filter(Inventory.quantity >= ALERT_OVERSTOCK_THRESHOLD)

        if sort_by:
            sort_column = getattr(Inventory, sort_by, None)
            if sort_column is not None:
                if sort_order == "asc":
                    query = query.order_by(sort_column.asc())
                else:
                    query = query.order_by(sort_column.desc())
        else:
            query = query.order_by(Inventory.id.desc())

        return query.offset(skip).limit(limit).all()

    def count_inventories(self, filters: InventoryFilterParams) -> int:
        query = self.db.query(func.count(Inventory.id))

        if filters.sku_id:
            query = query.filter(Inventory.sku_id == filters.sku_id)
        if filters.warehouse_id:
            query = query.filter(Inventory.warehouse_id == filters.warehouse_id)
        if filters.zone_id:
            query = query.filter(Inventory.zone_id == filters.zone_id)
        if filters.min_quantity is not None:
            query = query.filter(Inventory.quantity >= filters.min_quantity)
        if filters.max_quantity is not None:
            query = query.filter(Inventory.quantity <= filters.max_quantity)
        if filters.min_available is not None:
            query = query.filter(Inventory.available_quantity >= filters.min_available)
        if filters.max_available is not None:
            query = query.filter(Inventory.available_quantity <= filters.max_available)
        if filters.has_low_stock:
            query = query.filter(Inventory.available_quantity <= ALERT_LOW_STOCK_THRESHOLD)
        if filters.has_overstock:
            query = query.filter(Inventory.quantity >= ALERT_OVERSTOCK_THRESHOLD)

        return query.scalar() or 0

    def get_inventory_detail(self, inventory_id: int) -> InventoryDetail:
        inventory = self.get_inventory(inventory_id)

        warehouse = self.warehouse_service.get_warehouse(inventory.warehouse_id)
        zone = self.zone_service.get_zone(inventory.zone_id)

        return InventoryDetail(
            id=inventory.id,
            sku_id=inventory.sku_id,
            warehouse_id=inventory.warehouse_id,
            zone_id=inventory.zone_id,
            quantity=inventory.quantity,
            reserved_quantity=inventory.reserved_quantity,
            allocated_quantity=inventory.allocated_quantity,
            available_quantity=inventory.available_quantity,
            in_transit_quantity=inventory.in_transit_quantity,
            unit_cost=float(inventory.unit_cost),
            total_value=float(inventory.total_value),
            last_counted_at=inventory.last_counted_at,
            created_at=inventory.created_at,
            updated_at=inventory.updated_at,
            warehouse_name=warehouse.name,
            zone_name=zone.name,
            sku_code=None,
            sku_name=None,
        )

    def _create_transaction(
        self,
        transaction_in: InventoryTransactionCreate,
        created_by: int | None = None,
    ) -> InventoryTransaction:
        transaction = InventoryTransaction(
            **transaction_in.model_dump(),
            created_at=get_current_utc_time(),
            created_by=created_by,
        )
        self.db.add(transaction)
        self.db.flush()
        return transaction

    def _update_warehouse_capacity(self, warehouse_id: int, quantity_change: int) -> None:
        warehouse = self.warehouse_service.get_warehouse(warehouse_id)
        if warehouse.capacity:
            current_total = (
                self.db.query(func.coalesce(func.sum(Inventory.quantity), 0))
                .filter(Inventory.warehouse_id == warehouse_id)
                .scalar()
                or 0
            )
            new_total = current_total + quantity_change
            utilization_rate = new_total / warehouse.capacity

            if utilization_rate >= WAREHOUSE_CAPACITY_DANGER_THRESHOLD:
                raise WarehouseCapacityExceededException(
                    warehouse_id=warehouse_id,
                    current=current_total,
                    capacity=warehouse.capacity,
                    requested=abs(quantity_change),
                )

    def adjust_inventory(
        self,
        adjust_request: InventoryAdjustRequest,
        created_by: int | None = None,
    ) -> Inventory:
        inventory = self.get_inventory(adjust_request.inventory_id, lock=True)
        old_data = self._get_old_inventory_data(inventory)

        if adjust_request.quantity < 0 and (inventory.quantity + adjust_request.quantity) < 0:
            raise InsufficientInventoryException(
                sku_id=inventory.sku_id,
                warehouse_id=inventory.warehouse_id,
                requested=abs(adjust_request.quantity),
                available=inventory.quantity,
            )

        quantity_change = adjust_request.quantity

        if quantity_change > 0:
            self._update_warehouse_capacity(inventory.warehouse_id, quantity_change)

        inventory.quantity += quantity_change

        if adjust_request.unit_cost is not None and quantity_change >= 0:
            total_qty = inventory.quantity
            if total_qty > 0:
                old_cost_total = float(inventory.unit_cost) * (total_qty - quantity_change)
                new_cost_total = adjust_request.unit_cost * quantity_change
                inventory.unit_cost = (old_cost_total + new_cost_total) / total_qty

        self._update_available_quantity(inventory)
        inventory.updated_at = get_current_utc_time()

        transaction_in = InventoryTransactionCreate(
            sku_id=inventory.sku_id,
            warehouse_id=inventory.warehouse_id,
            zone_id=inventory.zone_id,
            transaction_type=TransactionType.ADJUSTMENT,
            quantity=adjust_request.quantity,
            unit_cost=adjust_request.unit_cost or float(inventory.unit_cost),
            reason=adjust_request.reason,
            reference_type="ADJUSTMENT",
        )
        self._create_transaction(transaction_in, created_by)

        self.cdc_engine.capture_inventory_change(
            inventory, CDCOperation.UPDATE, old_data
        )

        self.db.flush()
        self.db.refresh(inventory)

        logger.info(
            f"Inventory adjusted: id={inventory.id}, qty_change={quantity_change}, "
            f"new_qty={inventory.quantity}, reason={adjust_request.reason}"
        )

        return inventory

    def transfer_inventory(
        self,
        transfer_request: InventoryTransferRequest,
        created_by: int | None = None,
    ) -> dict[str, Inventory]:
        self.warehouse_service.get_warehouse(transfer_request.source_warehouse_id)
        self.warehouse_service.get_warehouse(transfer_request.target_warehouse_id)
        self.zone_service.get_zone(transfer_request.source_zone_id)
        self.zone_service.get_zone(transfer_request.target_zone_id)

        source_inventory = self.get_inventory_by_key(
            transfer_request.sku_id,
            transfer_request.source_warehouse_id,
            transfer_request.source_zone_id,
            lock=True,
        )

        if not source_inventory:
            raise InventoryNotFoundException(
                message=f"No inventory found for SKU {transfer_request.sku_id} "
                f"in source warehouse {transfer_request.source_warehouse_id}, "
                f"zone {transfer_request.source_zone_id}"
            )

        old_source_data = self._get_old_inventory_data(source_inventory)

        available = calculate_available_quantity(
            source_inventory.quantity,
            source_inventory.reserved_quantity,
            source_inventory.allocated_quantity,
        )

        if available < transfer_request.quantity:
            raise InsufficientInventoryException(
                sku_id=transfer_request.sku_id,
                warehouse_id=transfer_request.source_warehouse_id,
                requested=transfer_request.quantity,
                available=available,
            )

        self._update_warehouse_capacity(
            transfer_request.target_warehouse_id, transfer_request.quantity
        )

        source_inventory.quantity -= transfer_request.quantity
        self._update_available_quantity(source_inventory)
        source_inventory.updated_at = get_current_utc_time()

        target_inventory = self.get_inventory_by_key(
            transfer_request.sku_id,
            transfer_request.target_warehouse_id,
            transfer_request.target_zone_id,
            lock=True,
        )

        old_target_data = None
        if target_inventory:
            old_target_data = self._get_old_inventory_data(target_inventory)
            total_qty = target_inventory.quantity + transfer_request.quantity
            old_cost_total = float(target_inventory.unit_cost) * target_inventory.quantity
            new_cost_total = float(source_inventory.unit_cost) * transfer_request.quantity
            target_inventory.unit_cost = (old_cost_total + new_cost_total) / total_qty
            target_inventory.quantity = total_qty
        else:
            target_inventory = Inventory(
                sku_id=transfer_request.sku_id,
                warehouse_id=transfer_request.target_warehouse_id,
                zone_id=transfer_request.target_zone_id,
                quantity=transfer_request.quantity,
                reserved_quantity=0,
                allocated_quantity=0,
                available_quantity=transfer_request.quantity,
                in_transit_quantity=0,
                unit_cost=float(source_inventory.unit_cost),
                total_value=calculate_total_value(
                    transfer_request.quantity, float(source_inventory.unit_cost)
                ),
                created_at=get_current_utc_time(),
                updated_at=get_current_utc_time(),
            )
            self.db.add(target_inventory)

        self._update_available_quantity(target_inventory)
        target_inventory.updated_at = get_current_utc_time()

        batch_id = generate_batch_code()

        source_transaction = InventoryTransactionCreate(
            sku_id=transfer_request.sku_id,
            warehouse_id=transfer_request.source_warehouse_id,
            zone_id=transfer_request.source_zone_id,
            transaction_type=TransactionType.TRANSFER,
            quantity=-transfer_request.quantity,
            unit_cost=float(source_inventory.unit_cost),
            reason=transfer_request.reason or "Inventory transfer",
            reference_type="TRANSFER",
            batch_id=batch_id,
        )
        self._create_transaction(source_transaction, created_by)

        target_transaction = InventoryTransactionCreate(
            sku_id=transfer_request.sku_id,
            warehouse_id=transfer_request.target_warehouse_id,
            zone_id=transfer_request.target_zone_id,
            transaction_type=TransactionType.TRANSFER,
            quantity=transfer_request.quantity,
            unit_cost=float(source_inventory.unit_cost),
            reason=transfer_request.reason or "Inventory transfer",
            reference_type="TRANSFER",
            batch_id=batch_id,
        )
        self._create_transaction(target_transaction, created_by)

        self.cdc_engine.capture_inventory_change(
            source_inventory, CDCOperation.UPDATE, old_source_data
        )
        if old_target_data:
            self.cdc_engine.capture_inventory_change(
                target_inventory, CDCOperation.UPDATE, old_target_data
            )
        else:
            self.cdc_engine.capture_inventory_change(
                target_inventory, CDCOperation.INSERT, None
            )

        self.db.flush()
        self.db.refresh(source_inventory)
        self.db.refresh(target_inventory)

        logger.info(
            f"Inventory transferred: sku={transfer_request.sku_id}, "
            f"qty={transfer_request.quantity}, "
            f"from=wh{transfer_request.source_warehouse_id}/zn{transfer_request.source_zone_id}, "
            f"to=wh{transfer_request.target_warehouse_id}/zn{transfer_request.target_zone_id}"
        )

        return {"source": source_inventory, "target": target_inventory}

    def reserve_inventory(
        self,
        reserve_request: InventoryReserveRequest,
        created_by: int | None = None,
    ) -> Inventory:
        inventory = self.get_inventory(reserve_request.inventory_id, lock=True)
        old_data = self._get_old_inventory_data(inventory)

        available = calculate_available_quantity(
            inventory.quantity,
            inventory.reserved_quantity,
            inventory.allocated_quantity,
        )

        if available < reserve_request.quantity:
            raise InsufficientInventoryException(
                sku_id=inventory.sku_id,
                warehouse_id=inventory.warehouse_id,
                requested=reserve_request.quantity,
                available=available,
            )

        inventory.reserved_quantity += reserve_request.quantity
        self._update_available_quantity(inventory)
        inventory.updated_at = get_current_utc_time()

        transaction_in = InventoryTransactionCreate(
            sku_id=inventory.sku_id,
            warehouse_id=inventory.warehouse_id,
            zone_id=inventory.zone_id,
            transaction_type=TransactionType.ADJUSTMENT,
            quantity=0,
            unit_cost=float(inventory.unit_cost),
            reason=f"Reserve {reserve_request.quantity} units",
            reference_type=reserve_request.reference_type or "RESERVATION",
            reference_id=reserve_request.reference_id,
        )
        self._create_transaction(transaction_in, created_by)

        self.cdc_engine.capture_inventory_change(
            inventory, CDCOperation.UPDATE, old_data
        )

        self.db.flush()
        self.db.refresh(inventory)

        logger.info(
            f"Inventory reserved: id={inventory.id}, qty={reserve_request.quantity}, "
            f"reference={reserve_request.reference_type}/{reserve_request.reference_id}"
        )

        return inventory

    def release_inventory(
        self,
        release_request: InventoryReleaseRequest,
        created_by: int | None = None,
    ) -> Inventory:
        inventory = self.get_inventory(release_request.inventory_id, lock=True)
        old_data = self._get_old_inventory_data(inventory)

        if inventory.reserved_quantity < release_request.quantity:
            raise InvalidTransactionException(
                "RESERVATION",
                message=f"Cannot release {release_request.quantity} units, "
                f"only {inventory.reserved_quantity} reserved",
            )

        inventory.reserved_quantity -= release_request.quantity
        self._update_available_quantity(inventory)
        inventory.updated_at = get_current_utc_time()

        transaction_in = InventoryTransactionCreate(
            sku_id=inventory.sku_id,
            warehouse_id=inventory.warehouse_id,
            zone_id=inventory.zone_id,
            transaction_type=TransactionType.ADJUSTMENT,
            quantity=0,
            unit_cost=float(inventory.unit_cost),
            reason=f"Release {release_request.quantity} units",
            reference_type=release_request.reference_type or "RELEASE",
            reference_id=release_request.reference_id,
        )
        self._create_transaction(transaction_in, created_by)

        self.cdc_engine.capture_inventory_change(
            inventory, CDCOperation.UPDATE, old_data
        )

        self.db.flush()
        self.db.refresh(inventory)

        logger.info(
            f"Inventory released: id={inventory.id}, qty={release_request.quantity}, "
            f"reference={release_request.reference_type}/{release_request.reference_id}"
        )

        return inventory

    def allocate_inventory(
        self,
        inventory_id: int,
        quantity: int,
        reference_type: str | None = None,
        reference_id: int | None = None,
        created_by: int | None = None,
    ) -> Inventory:
        inventory = self.get_inventory(inventory_id, lock=True)
        old_data = self._get_old_inventory_data(inventory)

        if inventory.reserved_quantity < quantity:
            raise InvalidTransactionException(
                "ALLOCATION",
                message=f"Cannot allocate {quantity} units, "
                f"only {inventory.reserved_quantity} reserved",
            )

        inventory.reserved_quantity -= quantity
        inventory.allocated_quantity += quantity
        self._update_available_quantity(inventory)
        inventory.updated_at = get_current_utc_time()

        transaction_in = InventoryTransactionCreate(
            sku_id=inventory.sku_id,
            warehouse_id=inventory.warehouse_id,
            zone_id=inventory.zone_id,
            transaction_type=TransactionType.ADJUSTMENT,
            quantity=0,
            unit_cost=float(inventory.unit_cost),
            reason=f"Allocate {quantity} units",
            reference_type=reference_type or "ALLOCATION",
            reference_id=reference_id,
        )
        self._create_transaction(transaction_in, created_by)

        self.cdc_engine.capture_inventory_change(
            inventory, CDCOperation.UPDATE, old_data
        )

        self.db.flush()
        self.db.refresh(inventory)

        logger.info(
            f"Inventory allocated: id={inventory.id}, qty={quantity}, "
            f"reference={reference_type}/{reference_id}"
        )

        return inventory

    def process_outbound(
        self,
        sku_id: int,
        warehouse_id: int,
        zone_id: int,
        quantity: int,
        strategy: FIFOStrategy = FIFOStrategy.FIFO,
        reference_type: str | None = None,
        reference_id: int | None = None,
        reason: str | None = None,
        created_by: int | None = None,
    ) -> Inventory:
        inventory = self.get_inventory_by_key(sku_id, warehouse_id, zone_id, lock=True)

        if not inventory:
            raise InventoryNotFoundException(
                message=f"No inventory found for SKU {sku_id} "
                f"in warehouse {warehouse_id}, zone {zone_id}"
            )

        old_data = self._get_old_inventory_data(inventory)

        available = calculate_available_quantity(
            inventory.quantity,
            inventory.reserved_quantity,
            inventory.allocated_quantity,
        )

        if available < quantity:
            raise InsufficientInventoryException(
                sku_id=sku_id,
                warehouse_id=warehouse_id,
                requested=quantity,
                available=available,
            )

        inventory.quantity -= quantity
        inventory.allocated_quantity = max(0, inventory.allocated_quantity - quantity)
        self._update_available_quantity(inventory)
        inventory.updated_at = get_current_utc_time()

        transaction_in = InventoryTransactionCreate(
            sku_id=sku_id,
            warehouse_id=warehouse_id,
            zone_id=zone_id,
            transaction_type=TransactionType.OUT,
            quantity=-quantity,
            unit_cost=float(inventory.unit_cost),
            reason=reason or f"Outbound {quantity} units",
            reference_type=reference_type or "OUTBOUND",
            reference_id=reference_id,
        )
        self._create_transaction(transaction_in, created_by)

        self.cdc_engine.capture_inventory_change(
            inventory, CDCOperation.UPDATE, old_data
        )

        self.db.flush()
        self.db.refresh(inventory)

        logger.info(
            f"Outbound processed: sku={sku_id}, warehouse={warehouse_id}, "
            f"zone={zone_id}, qty={quantity}, strategy={strategy}"
        )

        return inventory

    def process_inbound(
        self,
        sku_id: int,
        warehouse_id: int,
        zone_id: int,
        quantity: int,
        unit_cost: float,
        batch_id: str | None = None,
        reference_type: str | None = None,
        reference_id: int | None = None,
        reason: str | None = None,
        created_by: int | None = None,
    ) -> Inventory:
        self.warehouse_service.get_warehouse(warehouse_id)
        self.zone_service.get_zone(zone_id)

        self._update_warehouse_capacity(warehouse_id, quantity)

        inventory = self.get_inventory_by_key(sku_id, warehouse_id, zone_id, lock=True)

        old_data = None
        if inventory:
            old_data = self._get_old_inventory_data(inventory)
            total_qty = inventory.quantity + quantity
            old_cost_total = float(inventory.unit_cost) * inventory.quantity
            new_cost_total = unit_cost * quantity
            inventory.unit_cost = (old_cost_total + new_cost_total) / total_qty
            inventory.quantity = total_qty
        else:
            inventory = Inventory(
                sku_id=sku_id,
                warehouse_id=warehouse_id,
                zone_id=zone_id,
                quantity=quantity,
                reserved_quantity=0,
                allocated_quantity=0,
                available_quantity=quantity,
                in_transit_quantity=0,
                unit_cost=unit_cost,
                total_value=calculate_total_value(quantity, unit_cost),
                created_at=get_current_utc_time(),
                updated_at=get_current_utc_time(),
            )
            self.db.add(inventory)

        self._update_available_quantity(inventory)
        inventory.updated_at = get_current_utc_time()

        if not batch_id:
            batch_id = generate_batch_code()

        transaction_in = InventoryTransactionCreate(
            sku_id=sku_id,
            warehouse_id=warehouse_id,
            zone_id=zone_id,
            transaction_type=TransactionType.IN,
            quantity=quantity,
            unit_cost=unit_cost,
            reason=reason or f"Inbound {quantity} units",
            reference_type=reference_type or "INBOUND",
            reference_id=reference_id,
            batch_id=batch_id,
        )
        self._create_transaction(transaction_in, created_by)

        if old_data:
            self.cdc_engine.capture_inventory_change(
                inventory, CDCOperation.UPDATE, old_data
            )
        else:
            self.cdc_engine.capture_inventory_change(
                inventory, CDCOperation.INSERT, None
            )

        self.db.flush()
        self.db.refresh(inventory)

        logger.info(
            f"Inbound processed: sku={sku_id}, warehouse={warehouse_id}, "
            f"zone={zone_id}, qty={quantity}, cost={unit_cost}"
        )

        return inventory

    def list_transactions(
        self,
        filters: TransactionFilterParams,
        skip: int = 0,
        limit: int = 100,
    ) -> list[InventoryTransaction]:
        query = self.db.query(InventoryTransaction)

        if filters.sku_id:
            query = query.filter(InventoryTransaction.sku_id == filters.sku_id)
        if filters.warehouse_id:
            query = query.filter(InventoryTransaction.warehouse_id == filters.warehouse_id)
        if filters.zone_id:
            query = query.filter(InventoryTransaction.zone_id == filters.zone_id)
        if filters.transaction_type:
            query = query.filter(InventoryTransaction.transaction_type == filters.transaction_type)
        if filters.start_date:
            query = query.filter(InventoryTransaction.created_at >= filters.start_date)
        if filters.end_date:
            query = query.filter(InventoryTransaction.created_at <= filters.end_date)
        if filters.reference_type:
            query = query.filter(InventoryTransaction.reference_type == filters.reference_type)
        if filters.reference_id:
            query = query.filter(InventoryTransaction.reference_id == filters.reference_id)
        if filters.batch_id:
            query = query.filter(InventoryTransaction.batch_id == filters.batch_id)

        return query.order_by(InventoryTransaction.created_at.desc()).offset(skip).limit(limit).all()

    def count_transactions(self, filters: TransactionFilterParams) -> int:
        query = self.db.query(func.count(InventoryTransaction.id))

        if filters.sku_id:
            query = query.filter(InventoryTransaction.sku_id == filters.sku_id)
        if filters.warehouse_id:
            query = query.filter(InventoryTransaction.warehouse_id == filters.warehouse_id)
        if filters.zone_id:
            query = query.filter(InventoryTransaction.zone_id == filters.zone_id)
        if filters.transaction_type:
            query = query.filter(InventoryTransaction.transaction_type == filters.transaction_type)
        if filters.start_date:
            query = query.filter(InventoryTransaction.created_at >= filters.start_date)
        if filters.end_date:
            query = query.filter(InventoryTransaction.created_at <= filters.end_date)
        if filters.reference_type:
            query = query.filter(InventoryTransaction.reference_type == filters.reference_type)
        if filters.reference_id:
            query = query.filter(InventoryTransaction.reference_id == filters.reference_id)
        if filters.batch_id:
            query = query.filter(InventoryTransaction.batch_id == filters.batch_id)

        return query.scalar() or 0

    def get_inventory_overview(self) -> InventoryOverview:
        stats = (
            self.db.query(
                func.count(func.distinct(Inventory.sku_id)).label("total_skus"),
                func.coalesce(func.sum(Inventory.quantity), 0).label("total_qty"),
                func.coalesce(func.sum(Inventory.available_quantity), 0).label("available_qty"),
                func.coalesce(func.sum(Inventory.reserved_quantity), 0).label("reserved_qty"),
                func.coalesce(func.sum(Inventory.allocated_quantity), 0).label("allocated_qty"),
                func.coalesce(func.sum(Inventory.total_value), 0.0).label("total_value"),
            )
            .first()
        )

        total_warehouses = self.warehouse_service.count_warehouses()

        low_stock_count = (
            self.db.query(func.count(Inventory.id))
            .filter(Inventory.available_quantity <= ALERT_LOW_STOCK_THRESHOLD)
            .scalar()
            or 0
        )

        overstock_count = (
            self.db.query(func.count(Inventory.id))
            .filter(Inventory.quantity >= ALERT_OVERSTOCK_THRESHOLD)
            .scalar()
            or 0
        )

        warehouse_utilization = {}
        warehouses = self.warehouse_service.list_warehouses(limit=1000)
        for wh in warehouses:
            util = self.warehouse_service.calculate_warehouse_utilization(wh.id)
            if util is not None:
                warehouse_utilization[wh.code] = util

        return InventoryOverview(
            total_warehouses=total_warehouses,
            total_skus=stats.total_skus if stats else 0,
            total_quantity=stats.total_qty if stats else 0,
            total_available_quantity=stats.available_qty if stats else 0,
            total_reserved_quantity=stats.reserved_qty if stats else 0,
            total_allocated_quantity=stats.allocated_qty if stats else 0,
            total_value=float(stats.total_value) if stats else 0.0,
            low_stock_count=low_stock_count,
            overstock_count=overstock_count,
            warehouse_utilization=warehouse_utilization,
        )

    def get_fifo_inventory(
        self,
        sku_id: int,
        warehouse_id: int,
        strategy: FIFOStrategy = FIFOStrategy.FIFO,
    ) -> list[Inventory]:
        query = (
            self.db.query(Inventory)
            .filter(
                and_(
                    Inventory.sku_id == sku_id,
                    Inventory.warehouse_id == warehouse_id,
                    Inventory.available_quantity > 0,
                )
            )
        )

        if strategy == FIFOStrategy.FIFO:
            query = query.order_by(Inventory.created_at.asc())
        elif strategy == FIFOStrategy.LIFO:
            query = query.order_by(Inventory.created_at.desc())
        elif strategy == FIFOStrategy.FEFO:
            query = query.order_by(Inventory.last_counted_at.asc().nullslast())

        return query.all()


def create_inventory_service(db: Session) -> InventoryService:
    return InventoryService(db)
