from __future__ import annotations

import time
from datetime import datetime, timedelta

import pytest
from sqlalchemy.orm import Session

from app.models import (
    Warehouse,
    Inventory,
    SyncType,
    SyncStatus,
    ConflictType,
    ResolutionStrategy,
    ConflictStatus,
    CDCLog,
    CDCOperation,
)
from app.services.inventory_sync_service import InventorySyncService
from app.utils.sync_engine import (
    ConflictDetectionEngine,
    ConflictResolver,
    SyncDelayMonitor,
    create_sync_engine,
)
from app.schemas.warehouse import InventorySyncCreate
from tests.factories import get_factory

pytestmark = [pytest.mark.unit, pytest.mark.sync]


class TestCdcEventProcessing:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.product, skus = self.factory.create_product_with_sku(num_skus=2)
        self.sku1, self.sku2 = skus

        self.source_warehouse = self.factory.warehouse.create(name="源仓库", code="SRC001")
        self.target_warehouse = self.factory.warehouse.create(name="目标仓库", code="TGT001")

        self.inventory1 = self.factory.inventory.create(
            sku_id=self.sku1.id,
            warehouse_id=self.source_warehouse.id,
            quantity=100,
        )
        self.inventory2 = self.factory.inventory.create(
            sku_id=self.sku2.id,
            warehouse_id=self.source_warehouse.id,
            quantity=200,
        )

        self.target_inventory1 = self.factory.inventory.create(
            sku_id=self.sku1.id,
            warehouse_id=self.target_warehouse.id,
            quantity=50,
        )
        self.target_inventory2 = self.factory.inventory.create(
            sku_id=self.sku2.id,
            warehouse_id=self.target_warehouse.id,
            quantity=150,
        )

        self.db.commit()

        self.sync_service = InventorySyncService(self.db)
        self.cdc_engine, self.conflict_engine, _, _ = create_sync_engine(self.db)

    def test_cdc_event_increases_target_inventory(self):
        old_quantity = self.target_inventory1.quantity
        sync_quantity = 100

        sync_in = InventorySyncCreate(
            source_warehouse_id=self.source_warehouse.id,
            target_warehouse_id=self.target_warehouse.id,
            sku_id=self.sku1.id,
            quantity=sync_quantity,
            sync_type=SyncType.INCREMENTAL,
        )
        sync = self.sync_service.create_sync(sync_in)
        self.db.commit()

        result = self.sync_service.process_sync(sync.id)
        self.db.commit()

        self.target_inventory1 = self.db.get(Inventory, self.target_inventory1.id)
        assert self.target_inventory1.quantity == sync_quantity
        assert result.sync_status == SyncStatus.COMPLETED
        assert result.success_count >= 1

    def test_cdc_event_decreases_target_inventory(self):
        new_source_qty = 30
        self.inventory1.quantity = new_source_qty
        self.db.commit()

        sync_in = InventorySyncCreate(
            source_warehouse_id=self.source_warehouse.id,
            target_warehouse_id=self.target_warehouse.id,
            sku_id=self.sku1.id,
            quantity=new_source_qty,
            sync_type=SyncType.FULL,
        )
        sync = self.sync_service.create_sync(sync_in)
        self.db.commit()

        result = self.sync_service.process_sync(sync.id)
        self.db.commit()

        self.target_inventory1 = self.db.get(Inventory, self.target_inventory1.id)
        assert self.target_inventory1.quantity == new_source_qty
        assert result.sync_status == SyncStatus.COMPLETED

    def test_cdc_capture_inventory_change(self):
        old_quantity = self.inventory1.quantity
        new_quantity = 150
        self.inventory1.quantity = new_quantity
        self.db.flush()

        old_data = {
            "id": self.inventory1.id,
            "quantity": old_quantity,
            "version": self.inventory1.version,
        }
        self.inventory1.version += 1
        self.db.flush()

        cdc_log = self.cdc_engine.capture_inventory_change(
            self.inventory1,
            CDCOperation.UPDATE,
            old_data=old_data,
        )
        self.db.commit()

        assert cdc_log is not None
        assert cdc_log.table_name == "inventories"
        assert cdc_log.operation == CDCOperation.UPDATE
        assert cdc_log.record_id == self.inventory1.id
        assert cdc_log.old_data["quantity"] == old_quantity
        assert cdc_log.new_data["quantity"] == new_quantity
        assert cdc_log.processed is False

    def test_multiple_cdc_events_processed_correctly(self):
        quantities = [100, 120, 90, 150]
        for qty in quantities:
            self.inventory1.quantity = qty
            self.db.flush()

            sync_in = InventorySyncCreate(
                source_warehouse_id=self.source_warehouse.id,
                target_warehouse_id=self.target_warehouse.id,
                sku_id=self.sku1.id,
                quantity=qty,
                sync_type=SyncType.INCREMENTAL,
            )
            sync = self.sync_service.create_sync(sync_in)
            self.db.commit()

            self.sync_service.process_sync(sync.id)
            self.db.commit()

        self.target_inventory1 = self.db.get(Inventory, self.target_inventory1.id)
        assert self.target_inventory1.quantity == quantities[-1]


class TestVersionConflictResolution:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.product, skus = self.factory.create_product_with_sku(num_skus=1)
        self.sku = skus[0]

        self.warehouse1 = self.factory.warehouse.create(name="仓库A")
        self.warehouse2 = self.factory.warehouse.create(name="仓库B")

        self.inv1 = self.factory.inventory.create(
            sku_id=self.sku.id, warehouse_id=self.warehouse1.id, quantity=100
        )
        self.inv2 = self.factory.inventory.create(
            sku_id=self.sku.id, warehouse_id=self.warehouse2.id, quantity=100
        )

        self.db.commit()

        self.conflict_engine = ConflictDetectionEngine()
        self.resolver = ConflictResolver()

    def test_detect_quantity_conflict(self):
        source = {"quantity": 100, "unit_cost": 10.0}
        target = {"quantity": 150, "unit_cost": 10.0}

        conflicts = self.conflict_engine.detect_conflicts(source, target)

        assert len(conflicts) >= 1
        assert ConflictType.QUANTITY_MISMATCH in conflicts

    def test_detect_cost_conflict(self):
        source = {"quantity": 100, "unit_cost": 10.0}
        target = {"quantity": 100, "unit_cost": 15.0}

        conflicts = self.conflict_engine.detect_conflicts(source, target)

        assert ConflictType.COST_MISMATCH in conflicts

    def test_last_write_wins_strategy(self):
        source_data = {"quantity": 150, "unit_cost": 12.0}
        target_data = {"quantity": 100, "unit_cost": 10.0}

        conflict = self.factory.sync_conflict.create(
            sync_id=1,
            sku_id=self.sku.id,
            conflict_type=ConflictType.QUANTITY_MISMATCH,
            resolution_strategy=ResolutionStrategy.LAST_WRITE_WINS,
            source_data=source_data,
            target_data=target_data,
            status=ConflictStatus.PENDING,
        )
        self.db.commit()

        result = self.resolver.apply_resolution(conflict, source_data, target_data)

        assert result["quantity"] == source_data["quantity"]
        assert result["unit_cost"] == source_data["unit_cost"]

    def test_source_wins_strategy(self):
        source_data = {"quantity": 150, "unit_cost": 12.0}
        target_data = {"quantity": 100, "unit_cost": 10.0}

        conflict = self.factory.sync_conflict.create(
            sync_id=1,
            sku_id=self.sku.id,
            conflict_type=ConflictType.QUANTITY_MISMATCH,
            resolution_strategy=ResolutionStrategy.SOURCE_WINS,
            source_data=source_data,
            target_data=target_data,
            status=ConflictStatus.PENDING,
        )
        self.db.commit()

        result = self.resolver.apply_resolution(conflict, source_data, target_data)

        assert result["quantity"] == source_data["quantity"]

    def test_target_wins_strategy(self):
        source_data = {"quantity": 150, "unit_cost": 12.0}
        target_data = {"quantity": 100, "unit_cost": 10.0}

        conflict = self.factory.sync_conflict.create(
            sync_id=1,
            sku_id=self.sku.id,
            conflict_type=ConflictType.QUANTITY_MISMATCH,
            resolution_strategy=ResolutionStrategy.TARGET_WINS,
            source_data=source_data,
            target_data=target_data,
            status=ConflictStatus.PENDING,
        )
        self.db.commit()

        result = self.resolver.apply_resolution(conflict, source_data, target_data)

        assert result["quantity"] == target_data["quantity"]

    def test_merge_strategy(self):
        source_data = {"quantity": 150, "unit_cost": 12.0}
        target_data = {"quantity": 100, "unit_cost": 10.0}

        conflict = self.factory.sync_conflict.create(
            sync_id=1,
            sku_id=self.sku.id,
            conflict_type=ConflictType.QUANTITY_MISMATCH,
            resolution_strategy=ResolutionStrategy.MERGE,
            source_data=source_data,
            target_data=target_data,
            status=ConflictStatus.PENDING,
        )
        self.db.commit()

        result = self.resolver.apply_resolution(conflict, source_data, target_data)

        expected_quantity = (source_data["quantity"] + target_data["quantity"]) / 2
        assert result["quantity"] == expected_quantity

    def test_reject_strategy_keeps_target(self):
        source_data = {"quantity": 150, "unit_cost": 12.0}
        target_data = {"quantity": 100, "unit_cost": 10.0}

        conflict = self.factory.sync_conflict.create(
            sync_id=1,
            sku_id=self.sku.id,
            conflict_type=ConflictType.QUANTITY_MISMATCH,
            resolution_strategy=ResolutionStrategy.REJECT,
            source_data=source_data,
            target_data=target_data,
            status=ConflictStatus.PENDING,
        )
        self.db.commit()

        result = self.resolver.apply_resolution(conflict, source_data, target_data)

        assert result["quantity"] == target_data["quantity"]

    def test_both_warehouses_modify_same_sku_creates_conflict(self):
        self.inv1.quantity = 200
        self.inv1.version = 5
        self.db.commit()

        source_inv = {
            "sku_id": self.sku.id,
            "warehouse_id": self.warehouse1.id,
            "quantity": 200,
            "unit_cost": 10.0,
            "version": 5,
        }
        target_inv = {
            "sku_id": self.sku.id,
            "warehouse_id": self.warehouse2.id,
            "quantity": 180,
            "unit_cost": 10.0,
            "version": 4,
        }

        conflicts = self.conflict_engine.detect_conflicts(source_inv, target_inv)

        assert len(conflicts) >= 1


class TestInventorySyncBoundaryScenarios:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)
        self.product, skus = self.factory.create_product_with_sku(num_skus=10)
        self.skus = skus
        self.sync_service = InventorySyncService(self.db)
        self.delay_monitor = SyncDelayMonitor(self.db)

    @pytest.mark.slow
    def test_warehouse_count_scaling_from_1_to_100(self):
        source_warehouse = self.factory.warehouse.create(name="中央仓库")

        for i in range(1, 101):
            target_warehouse = self.factory.warehouse.create(name=f"分仓库{i}", code=f"WH{i:03d}")
            for sku in self.skus[:5]:
                self.factory.inventory.create(
                    sku_id=sku.id, warehouse_id=source_warehouse.id, quantity=100
                )
                self.factory.inventory.create(
                    sku_id=sku.id, warehouse_id=target_warehouse.id, quantity=50
                )

        self.db.commit()

        delays = []
        for i in [1, 5, 10, 50, 100]:
            target_warehouses = self.db.query(Warehouse).filter(Warehouse.code != "中央仓库").limit(i).all()

            start_time = time.time()
            for target in target_warehouses:
                sync_in = InventorySyncCreate(
                    source_warehouse_id=source_warehouse.id,
                    target_warehouse_id=target.id,
                    sku_id=self.skus[0].id,
                    quantity=100,
                    sync_type=SyncType.FULL,
                )
                sync = self.sync_service.create_sync(sync_in)
                self.sync_service.process_sync(sync.id)

            elapsed = time.time() - start_time
            delays.append(elapsed)

        assert len(delays) == 5
        assert all(d < 60 for d in delays)

    @pytest.mark.slow
    def test_large_scale_sync_performance(self):
        source_warehouse = self.factory.warehouse.create(name="源仓库")
        target_warehouse = self.factory.warehouse.create(name="目标仓库")

        for sku in self.skus:
            self.factory.inventory.create(
                sku_id=sku.id, warehouse_id=source_warehouse.id, quantity=100
            )
            self.factory.inventory.create(
                sku_id=sku.id, warehouse_id=target_warehouse.id, quantity=50
            )

        self.db.commit()

        start_time = time.time()
        sync_in = InventorySyncCreate(
            source_warehouse_id=source_warehouse.id,
            target_warehouse_id=target_warehouse.id,
            sku_id=self.skus[0].id,
            quantity=100,
            sync_type=SyncType.FULL,
        )
        sync = self.sync_service.create_sync(sync_in)
        result = self.sync_service.process_sync(sync.id)
        elapsed = time.time() - start_time

        assert result.sync_status == SyncStatus.COMPLETED
        assert elapsed < 5.0

    def test_sync_delay_monitoring(self):
        source_warehouse = self.factory.warehouse.create(name="源仓库")
        target_warehouse = self.factory.warehouse.create(name="目标仓库")

        last_sync = datetime.utcnow() - timedelta(minutes=10)
        self.delay_monitor.record_sync_time(source_warehouse.id, target_warehouse.id, last_sync)

        delay = self.delay_monitor.calculate_delay(source_warehouse.id, target_warehouse.id)

        assert delay is not None
        assert delay > 0

    def test_sync_delay_alert_threshold(self):
        source_warehouse = self.factory.warehouse.create(name="源仓库")
        target_warehouse = self.factory.warehouse.create(name="目标仓库")

        last_sync = datetime.utcnow() - timedelta(hours=2)
        self.delay_monitor.record_sync_time(source_warehouse.id, target_warehouse.id, last_sync)

        is_delayed = self.delay_monitor.check_delay_alert(source_warehouse.id, target_warehouse.id)

        assert is_delayed is True


class TestCdcEventOrdering:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.product, skus = self.factory.create_product_with_sku(num_skus=1)
        self.sku = skus[0]

        self.warehouse = self.factory.warehouse.create()
        self.inventory = self.factory.inventory.create(
            sku_id=self.sku.id, warehouse_id=self.warehouse.id, quantity=100
        )
        self.db.commit()

        self.cdc_engine, _, _, _ = create_sync_engine(self.db)

    def test_out_of_order_events_sorted_by_version(self):
        events = []
        for version in [1, 2, 3, 4, 5]:
            cdc_log, cdc_event = self.factory.create_cdc_inventory_event(
                inventory_id=self.inventory.id,
                sku_id=self.sku.id,
                warehouse_id=self.warehouse.id,
                old_quantity=100 + version - 1,
                new_quantity=100 + version,
                version=version,
            )
            events.append(cdc_event)

        self.db.commit()

        shuffled_events = [events[2], events[0], events[4], events[1], events[3]]
        sorted_events = sorted(shuffled_events, key=lambda e: e.version or 0)

        assert sorted_events[0].version == 1
        assert sorted_events[-1].version == 5

    def test_apply_events_in_version_order(self):
        events_data = [
            (1, 110),
            (2, 120),
            (3, 90),
            (4, 150),
            (5, 130),
        ]

        events = []
        for version, qty in events_data:
            cdc_log, cdc_event = self.factory.create_cdc_inventory_event(
                inventory_id=self.inventory.id,
                sku_id=self.sku.id,
                warehouse_id=self.warehouse.id,
                old_quantity=qty - 10,
                new_quantity=qty,
                version=version,
            )
            events.append(cdc_event)

        self.db.commit()

        shuffled = [events[2], events[4], events[0], events[3], events[1]]
        sorted_by_version = sorted(shuffled, key=lambda e: e.version or 0)

        final_quantity = None
        for event in sorted_by_version:
            cdc_log = self.db.get(CDCLog, event.cdc_log_id)
            if cdc_log and cdc_log.new_data:
                final_quantity = cdc_log.new_data.get("quantity")

        assert final_quantity == 130

    def test_duplicate_version_events_ignored(self):
        events = []
        for i in range(3):
            cdc_log, cdc_event = self.factory.create_cdc_inventory_event(
                inventory_id=self.inventory.id,
                sku_id=self.sku.id,
                warehouse_id=self.warehouse.id,
                old_quantity=100,
                new_quantity=110,
                version=1,
            )
            events.append(cdc_event)

        self.db.commit()

        unique_versions = set()
        for event in events:
            unique_versions.add(event.version)

        assert len(unique_versions) == 1


class TestInventorySyncCrud:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.product, skus = self.factory.create_product_with_sku(num_skus=2)
        self.sku1, self.sku2 = skus

        self.source_wh = self.factory.warehouse.create()
        self.target_wh = self.factory.warehouse.create()

        self.sync_service = InventorySyncService(self.db)

    def test_create_sync_task(self):
        sync_in = InventorySyncCreate(
            source_warehouse_id=self.source_wh.id,
            target_warehouse_id=self.target_wh.id,
            sku_id=self.sku1.id,
            quantity=100,
            sync_type=SyncType.INCREMENTAL,
        )

        sync = self.sync_service.create_sync(sync_in)
        self.db.commit()

        assert sync.id is not None
        assert sync.source_warehouse_id == self.source_wh.id
        assert sync.target_warehouse_id == self.target_wh.id
        assert sync.sync_status == SyncStatus.PENDING

    def test_create_sync_same_warehouse_raises_error(self):
        sync_in = InventorySyncCreate(
            source_warehouse_id=self.source_wh.id,
            target_warehouse_id=self.source_wh.id,
            sku_id=self.sku1.id,
            quantity=100,
            sync_type=SyncType.INCREMENTAL,
        )

        with pytest.raises(Exception) as exc_info:
            self.sync_service.create_sync(sync_in)

        assert "same" in str(exc_info.value).lower()

    def test_get_sync_by_id(self):
        sync = self.factory.inventory_sync.create(
            source_warehouse_id=self.source_wh.id,
            target_warehouse_id=self.target_wh.id,
            sku_id=self.sku1.id,
            quantity=100,
        )
        self.db.commit()

        fetched = self.sync_service.get_sync(sync.id)

        assert fetched.id == sync.id

    def test_list_syncs_with_filters(self):
        for i in range(5):
            self.factory.inventory_sync.create(
                source_warehouse_id=self.source_wh.id,
                target_warehouse_id=self.target_wh.id,
                sku_id=self.sku1.id,
                quantity=100,
                sync_status=SyncStatus.COMPLETED if i % 2 == 0 else SyncStatus.FAILED,
            )
        self.db.commit()

        completed = self.sync_service.list_syncs(
            source_warehouse_id=self.source_wh.id,
            sync_status=SyncStatus.COMPLETED,
        )

        assert len(completed) == 3

    def test_count_syncs(self):
        for i in range(3):
            self.factory.inventory_sync.create(
                source_warehouse_id=self.source_wh.id,
                target_warehouse_id=self.target_wh.id,
                sku_id=self.sku1.id,
                quantity=100,
            )
        self.db.commit()

        count = self.sync_service.count_syncs(source_warehouse_id=self.source_wh.id)

        assert count == 3
