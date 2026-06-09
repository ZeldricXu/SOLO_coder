from __future__ import annotations
from sqlalchemy import and_, func
from sqlalchemy.orm import Session

from app.models.warehouse import Warehouse, Zone
from app.models.inventory import Inventory
from app.schemas.warehouse import (
    WarehouseCreate,
    WarehouseUpdate,
    WarehouseDetail,
    ZoneCreate,
    ZoneUpdate,
    WarehouseInventoryOverview,
)
from app.utils.exceptions import (
    WarehouseNotFoundException,
    ZoneNotFoundException,
    DuplicateCodeException,
)
from app.utils.helpers import (
    generate_warehouse_code,
    generate_zone_code,
    calculate_utilization_rate,
    get_current_utc_time,
)
from app.utils.constants import (
    WAREHOUSE_CAPACITY_WARNING_THRESHOLD,
    WAREHOUSE_CAPACITY_DANGER_THRESHOLD,
)


class WarehouseService:
    def __init__(self, db: Session):
        self.db = db

    def get_warehouse(self, warehouse_id: int) -> Warehouse:
        warehouse = self.db.get(Warehouse, warehouse_id)
        if not warehouse:
            raise WarehouseNotFoundException(warehouse_id)
        return warehouse

    def get_warehouse_by_code(self, code: str) -> Warehouse | None:
        return (
            self.db.query(Warehouse)
            .filter(func.lower(Warehouse.code) == func.lower(code))
            .first()
        )

    def list_warehouses(
        self,
        skip: int = 0,
        limit: int = 100,
        warehouse_type: str | None = None,
        is_active: bool | None = None,
        city: str | None = None,
    ) -> list[Warehouse]:
        query = self.db.query(Warehouse)

        if warehouse_type:
            query = query.filter(Warehouse.warehouse_type == warehouse_type)
        if is_active is not None:
            query = query.filter(Warehouse.is_active == is_active)
        if city:
            query = query.filter(func.lower(Warehouse.city).contains(func.lower(city)))

        return query.order_by(Warehouse.id.desc()).offset(skip).limit(limit).all()

    def count_warehouses(self) -> int:
        return self.db.query(func.count(Warehouse.id)).scalar() or 0

    def create_warehouse(self, warehouse_in: WarehouseCreate) -> Warehouse:
        existing = self.get_warehouse_by_code(warehouse_in.code)
        if existing:
            raise DuplicateCodeException(warehouse_in.code, "Warehouse")

        if not warehouse_in.code:
            warehouse_in.code = generate_warehouse_code()

        warehouse = Warehouse(
            **warehouse_in.model_dump(),
            created_at=get_current_utc_time(),
            updated_at=get_current_utc_time(),
        )
        self.db.add(warehouse)
        self.db.flush()
        self.db.refresh(warehouse)
        return warehouse

    def update_warehouse(
        self, warehouse_id: int, warehouse_in: WarehouseUpdate
    ) -> Warehouse:
        warehouse = self.get_warehouse(warehouse_id)

        update_data = warehouse_in.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(warehouse, key, value)

        warehouse.updated_at = get_current_utc_time()
        self.db.flush()
        self.db.refresh(warehouse)
        return warehouse

    def delete_warehouse(self, warehouse_id: int) -> None:
        warehouse = self.get_warehouse(warehouse_id)
        self.db.delete(warehouse)
        self.db.flush()

    def calculate_warehouse_utilization(self, warehouse_id: int) -> float | None:
        warehouse = self.get_warehouse(warehouse_id)

        if not warehouse.capacity:
            return None

        total_qty = (
            self.db.query(func.coalesce(func.sum(Inventory.quantity), 0))
            .filter(Inventory.warehouse_id == warehouse_id)
            .scalar()
            or 0
        )

        utilization_rate = calculate_utilization_rate(total_qty, warehouse.capacity)
        warehouse.utilization_rate = utilization_rate
        self.db.flush()

        return utilization_rate

    def get_warehouse_utilization_status(self, warehouse_id: int) -> dict:
        utilization_rate = self.calculate_warehouse_utilization(warehouse_id)
        warehouse = self.get_warehouse(warehouse_id)

        if utilization_rate is None:
            return {"status": "unknown", "utilization_rate": None}

        status = "normal"
        if utilization_rate >= WAREHOUSE_CAPACITY_DANGER_THRESHOLD:
            status = "danger"
        elif utilization_rate >= WAREHOUSE_CAPACITY_WARNING_THRESHOLD:
            status = "warning"

        return {
            "warehouse_id": warehouse_id,
            "warehouse_name": warehouse.name,
            "utilization_rate": utilization_rate,
            "status": status,
            "capacity": warehouse.capacity,
            "warning_threshold": WAREHOUSE_CAPACITY_WARNING_THRESHOLD,
            "danger_threshold": WAREHOUSE_CAPACITY_DANGER_THRESHOLD,
        }

    def get_warehouse_detail(self, warehouse_id: int) -> WarehouseDetail:
        warehouse = self.get_warehouse(warehouse_id)

        zone_count = (
            self.db.query(func.count(Zone.id))
            .filter(Zone.warehouse_id == warehouse_id)
            .scalar()
            or 0
        )

        inventory_stats = (
            self.db.query(
                func.coalesce(func.sum(Inventory.quantity), 0).label("total_qty"),
                func.coalesce(func.sum(Inventory.total_value), 0.0).label("total_value"),
            )
            .filter(Inventory.warehouse_id == warehouse_id)
            .first()
        )

        utilization_rate = self.calculate_warehouse_utilization(warehouse_id)

        return WarehouseDetail(
            id=warehouse.id,
            name=warehouse.name,
            code=warehouse.code,
            address=warehouse.address,
            city=warehouse.city,
            province=warehouse.province,
            country=warehouse.country,
            postal_code=warehouse.postal_code,
            contact_person=warehouse.contact_person,
            contact_phone=warehouse.contact_phone,
            contact_email=warehouse.contact_email,
            warehouse_type=warehouse.warehouse_type,
            is_active=warehouse.is_active,
            capacity=warehouse.capacity,
            utilization_rate=utilization_rate,
            created_at=warehouse.created_at,
            updated_at=warehouse.updated_at,
            zone_count=zone_count,
            total_inventory_quantity=inventory_stats.total_qty if inventory_stats else 0,
            total_inventory_value=float(inventory_stats.total_value) if inventory_stats else 0.0,
        )

    def get_warehouse_inventory_overview(
        self, warehouse_id: int
    ) -> WarehouseInventoryOverview:
        warehouse = self.get_warehouse(warehouse_id)

        inventory_stats = (
            self.db.query(
                func.coalesce(func.sum(Inventory.quantity), 0).label("total_qty"),
                func.coalesce(func.sum(Inventory.available_quantity), 0).label("available_qty"),
                func.coalesce(func.sum(Inventory.total_value), 0.0).label("total_value"),
                func.count(func.distinct(Inventory.sku_id)).label("sku_count"),
            )
            .filter(Inventory.warehouse_id == warehouse_id)
            .first()
        )

        zone_count = (
            self.db.query(func.count(Zone.id))
            .filter(Zone.warehouse_id == warehouse_id)
            .scalar()
            or 0
        )

        utilization_rate = self.calculate_warehouse_utilization(warehouse_id)

        return WarehouseInventoryOverview(
            warehouse_id=warehouse.id,
            warehouse_name=warehouse.name,
            warehouse_code=warehouse.code,
            total_quantity=inventory_stats.total_qty if inventory_stats else 0,
            total_available_quantity=inventory_stats.available_qty if inventory_stats else 0,
            total_value=float(inventory_stats.total_value) if inventory_stats else 0.0,
            utilization_rate=utilization_rate,
            sku_count=inventory_stats.sku_count if inventory_stats else 0,
            zone_count=zone_count,
        )


class ZoneService:
    def __init__(self, db: Session):
        self.db = db
        self.warehouse_service = WarehouseService(db)

    def get_zone(self, zone_id: int) -> Zone:
        zone = self.db.get(Zone, zone_id)
        if not zone:
            raise ZoneNotFoundException(zone_id)
        return zone

    def get_zone_by_code(self, warehouse_id: int, code: str) -> Zone | None:
        return (
            self.db.query(Zone)
            .filter(
                and_(
                    Zone.warehouse_id == warehouse_id,
                    func.lower(Zone.code) == func.lower(code),
                )
            )
            .first()
        )

    def list_zones(
        self,
        warehouse_id: int | None = None,
        skip: int = 0,
        limit: int = 100,
        storage_type: str | None = None,
    ) -> list[Zone]:
        query = self.db.query(Zone)

        if warehouse_id:
            self.warehouse_service.get_warehouse(warehouse_id)
            query = query.filter(Zone.warehouse_id == warehouse_id)
        if storage_type:
            query = query.filter(
                func.lower(Zone.storage_type).contains(func.lower(storage_type))
            )

        return query.order_by(Zone.id.desc()).offset(skip).limit(limit).all()

    def count_zones(self, warehouse_id: int | None = None) -> int:
        query = self.db.query(func.count(Zone.id))
        if warehouse_id:
            query = query.filter(Zone.warehouse_id == warehouse_id)
        return query.scalar() or 0

    def create_zone(self, zone_in: ZoneCreate) -> Zone:
        self.warehouse_service.get_warehouse(zone_in.warehouse_id)

        existing = self.get_zone_by_code(zone_in.warehouse_id, zone_in.code)
        if existing:
            raise DuplicateCodeException(zone_in.code, "Zone")

        if not zone_in.code:
            zone_in.code = generate_zone_code()

        zone = Zone(
            **zone_in.model_dump(),
            created_at=get_current_utc_time(),
        )
        self.db.add(zone)
        self.db.flush()
        self.db.refresh(zone)
        return zone

    def update_zone(self, zone_id: int, zone_in: ZoneUpdate) -> Zone:
        zone = self.get_zone(zone_id)

        update_data = zone_in.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(zone, key, value)

        self.db.flush()
        self.db.refresh(zone)
        return zone

    def delete_zone(self, zone_id: int) -> None:
        zone = self.get_zone(zone_id)
        self.db.delete(zone)
        self.db.flush()


def create_warehouse_service(db: Session) -> tuple[WarehouseService, ZoneService]:
    return WarehouseService(db), ZoneService(db)
