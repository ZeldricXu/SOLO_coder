from __future__ import annotations

from datetime import datetime, timedelta

import pytest
import redis
from sqlalchemy.orm import Session

from app.models import (
    SKU,
    Inventory,
    CDCLog,
    CDCEvent,
    CDCOperation,
    PurchaseOrder,
    PurchaseOrderStatus,
    ApprovalRecord,
    ApprovalType,
    ApprovalStatus,
    ResourceType,
    AlertRuleType,
    ThresholdType,
    InventoryTransaction,
)
from app.services.sku_service import SkuService
from app.services.inventory_sync_service import InventorySyncService
from app.services.purchase_order_service import PurchaseOrderService
from app.services.approval_service import ApprovalService
from app.services.alert_service import AlertService
from app.utils.sync_engine import CDCCaptureEngine, ConflictDetectionEngine
from app.schemas.product import SkuGenerateRequest
from app.schemas.purchase_order import (
    PurchaseOrderCreate,
    PurchaseOrderGenerateRequest,
    PurchaseOrderReceiveRequest,
    PurchaseOrderReceiveItem,
    ForecastMethodEnum,
)
from app.schemas.approval import (
    ApprovalSubmissionRequest,
    ApprovalActionEnum,
)
from app.schemas.alert import AlertRuleCreate
from app.core.cache import cache
from tests.factories import get_factory

pytestmark = [pytest.mark.integration, pytest.mark.slow]


class TestFullBusinessWorkflow:
    @pytest.fixture(autouse=True)
    def setup(
        self,
        clean_db: Session,
        docker_redis,
        db: Session,
    ):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.admin_user = self.factory.user.create(username="admin", is_superuser=True)
        self.approver1 = self.factory.user.create(username="approver1")
        self.approver2 = self.factory.user.create(username="approver2")
        self.warehouse = self.factory.warehouse.create(name="主仓库", code="WH-001")
        self.supplier = self.factory.supplier.create(name="测试供应商")
        self.db.commit()

        try:
            self.redis_client = redis.Redis(
                host=docker_redis.get_container_host_ip(),
                port=docker_redis.get_exposed_port(6379),
                db=0,
                decode_responses=True,
            )
            self.redis_client.ping()
        except Exception as e:
            self.redis_client = None
            pytest.skip(f"Redis not available: {e}")

        self.sku_service = SkuService(self.db, self.admin_user)
        self.sync_service = InventorySyncService(self.db, self.admin_user)
        self.po_service = PurchaseOrderService(self.db, self.admin_user)
        self.approval_service = ApprovalService(self.db, self.admin_user)
        self.alert_service = AlertService(self.db, self.admin_user)
        self.cdc_engine = CDCCaptureEngine(self.db)

        self.workflow = self.factory.approval_workflow.create(
            name="采购单审批流程",
            resource_type=ResourceType.PURCHASE_ORDER,
        )
        self.factory.approval_node.create(
            workflow_id=self.workflow.id,
            node_name="一级审批",
            approver_user_id=self.approver1.id,
            node_order=1,
            approval_type=ApprovalType.AND,
        )
        self.factory.approval_node.create(
            workflow_id=self.workflow.id,
            node_name="二级审批",
            approver_user_id=self.approver2.id,
            node_order=2,
            approval_type=ApprovalType.AND,
        )
        self.db.commit()

        yield

        cache.delete_pattern("*")
        if self.redis_client:
            self.redis_client.flushdb()

    def test_full_business_workflow(self):
        colors = ["红色", "蓝色"]
        sizes = ["S", "M", "L"]

        product = self.factory.product.create(name="测试T恤", category_id=None)
        sku_request = SkuGenerateRequest(
            product_id=product.id,
            attributes={
                "颜色": colors,
                "尺寸": sizes,
            },
            cost_price=50.0,
            sale_price=99.0,
        )

        result = self.sku_service.generate_skus(self.db, sku_request)
        self.db.commit()

        assert result["total_count"] == 6
        assert result["success_count"] == 6

        skus = (
            self.db.query(SKU).filter(SKU.product_id == product.id).all()
        )
        assert len(skus) == 6
        sku_codes = [sku.sku_code for sku in skus]
        assert len(set(sku_codes)) == 6

        for sku in skus:
            inventory = self.factory.inventory.create(
                sku_id=sku.id,
                warehouse_id=self.warehouse.id,
                quantity=200,
                available_quantity=200,
                version=1,
            )
            sku.safety_stock = 100
            sku.maximum_stock = 500
            sku.reorder_point = 150
            sku.lead_time_days = 7
        self.db.commit()

        test_sku = skus[0]

        table_name = "inventories"
        record_id = test_sku.id
        old_data = {
            "id": test_sku.inventories[0].id,
            "sku_id": test_sku.id,
            "warehouse_id": self.warehouse.id,
            "quantity": 200,
            "available_quantity": 200,
            "version": 1,
        }
        new_data = {
            "id": test_sku.inventories[0].id,
            "sku_id": test_sku.id,
            "warehouse_id": self.warehouse.id,
            "quantity": 50,
            "available_quantity": 50,
            "version": 2,
        }

        cdc_log = self.cdc_engine.capture_event(
            table_name=table_name,
            operation=CDCOperation.UPDATE,
            record_id=record_id,
            old_data=old_data,
            new_data=new_data,
            source_system="WMS",
        )
        self.db.commit()

        assert cdc_log is not None
        assert cdc_log.operation == CDCOperation.UPDATE

        cdc_event = (
            self.db.query(CDCEvent).filter(CDCEvent.cdc_log_id == cdc_log.id).first()
        )
        assert cdc_event is not None

        sync_result = self.sync_service.process_sync_from_cdc(cdc_event.id)
        self.db.commit()

        assert sync_result["success"] is True

        updated_inventory = (
            self.db.query(Inventory)
            .filter(
                Inventory.sku_id == test_sku.id,
                Inventory.warehouse_id == self.warehouse.id,
            )
            .first()
        )
        assert updated_inventory.quantity == 50
        assert updated_inventory.available_quantity == 50
        assert updated_inventory.version == 2

        alert_rule_create = AlertRuleCreate(
            name="低库存预警规则",
            rule_type=AlertRuleType.LOW_STOCK,
            threshold_type=ThresholdType.FIXED,
            threshold_value=100,
            warning_value=100,
            critical_value=50,
            is_active=True,
            sku_ids=[sku.id for sku in skus],
            warehouse_ids=[self.warehouse.id],
        )
        alert_rule = self.alert_service.create_rule(alert_rule_create)
        self.db.commit()

        alert_result = self.alert_service.check_alerts_sync(rule_id=alert_rule.id)
        self.db.commit()

        assert alert_result.new_alerts_count >= 1

        triggered_sku_ids = [alert.sku_id for alert in alert_result.new_alerts]
        assert test_sku.id in triggered_sku_ids

        generate_request = PurchaseOrderGenerateRequest(
            warehouse_id=self.warehouse.id,
            sku_ids=[test_sku.id],
            auto_create=True,
            forecast_periods=30,
            history_days=90,
            forecast_method=ForecastMethodEnum.MOVING_AVERAGE,
            safety_stock_multiplier=1.0,
        )

        generate_result = self.po_service.generate_purchase_suggestions(
            generate_request, self.admin_user
        )
        self.db.commit()

        assert generate_result.created_order_id is not None

        purchase_order = self.db.get(PurchaseOrder, generate_result.created_order_id)
        assert purchase_order is not None
        assert purchase_order.status == PurchaseOrderStatus.DRAFT
        assert purchase_order.supplier_id == self.supplier.id
        assert len(purchase_order.items) >= 1

        submit_request = ApprovalSubmissionRequest(
            resource_type=ResourceType.PURCHASE_ORDER,
            resource_id=purchase_order.id,
            workflow_id=self.workflow.id,
        )
        self.approval_service.submit_approval(submit_request)
        self.db.commit()

        po_after_submit = self.db.get(PurchaseOrder, purchase_order.id)
        assert po_after_submit.status == PurchaseOrderStatus.PENDING_APPROVAL

        from app.schemas.approval import ApprovalActionRequest as ActionReq

        approval_record1 = (
            self.db.query(ApprovalRecord)
            .filter(ApprovalRecord.resource_id == purchase_order.id)
            .filter(ApprovalRecord.approver_id == self.approver1.id)
            .first()
        )
        assert approval_record1 is not None

        action_request1 = ActionReq(
            action=ApprovalActionEnum.APPROVE,
            approval_opinion="同意采购",
            notify_submitter=True,
        )
        approval_service1 = ApprovalService(self.db, self.approver1)
        approval_service1.process_approval_action(
            approval_record1.id, action_request1, self.approver1
        )
        self.db.commit()

        approval_record2 = (
            self.db.query(ApprovalRecord)
            .filter(ApprovalRecord.resource_id == purchase_order.id)
            .filter(ApprovalRecord.approver_id == self.approver2.id)
            .filter(ApprovalRecord.status == ApprovalStatus.PENDING)
            .first()
        )
        assert approval_record2 is not None

        action_request2 = ActionReq(
            action=ApprovalActionEnum.APPROVE,
            approval_opinion="同意采购",
            notify_submitter=True,
        )
        approval_service2 = ApprovalService(self.db, self.approver2)
        approval_service2.process_approval_action(
            approval_record2.id, action_request2, self.approver2
        )
        self.db.commit()

        po_final = self.db.get(PurchaseOrder, purchase_order.id)
        assert po_final.status == PurchaseOrderStatus.APPROVED

        receive_request = PurchaseOrderReceiveRequest(
            items=[
                PurchaseOrderReceiveItem(
                    item_id=po_final.items[0].id,
                    received_quantity=po_final.items[0].quantity,
                )
            ]
        )
        self.po_service.receive_order(po_final.id, receive_request, self.admin_user)
        self.db.commit()

        po_after_receive = self.db.get(PurchaseOrder, po_final.id)
        assert po_after_receive.status == PurchaseOrderStatus.PARTIAL_RECEIVED or po_after_receive.status == PurchaseOrderStatus.RECEIVED

        final_inventory = (
            self.db.query(Inventory)
            .filter(
                Inventory.sku_id == test_sku.id,
                Inventory.warehouse_id == self.warehouse.id,
            )
            .first()
        )
        expected_quantity = 50 + po_final.items[0].quantity
        assert final_inventory.quantity == expected_quantity


class TestIntegrationExceptionScenarios:
    @pytest.fixture(autouse=True)
    def setup(
        self,
        clean_db: Session,
        docker_redis,
        db: Session,
    ):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.admin_user = self.factory.user.create(username="admin")
        self.approver = self.factory.user.create(username="approver")
        self.warehouse = self.factory.warehouse.create(name="主仓库")
        self.supplier = self.factory.supplier.create(name="供应商")
        self.db.commit()

        try:
            self.redis_client = redis.Redis(
                host=docker_redis.get_container_host_ip(),
                port=docker_redis.get_exposed_port(6379),
                db=0,
                decode_responses=True,
            )
            self.redis_client.ping()
        except Exception as e:
            self.redis_client = None
            pytest.skip(f"Redis not available: {e}")

        self.sku_service = SkuService(self.db, self.admin_user)
        self.sync_service = InventorySyncService(self.db, self.admin_user)
        self.po_service = PurchaseOrderService(self.db, self.admin_user)
        self.approval_service = ApprovalService(self.db, self.admin_user)
        self.cdc_engine = CDCCaptureEngine(self.db)
        self.conflict_engine = ConflictDetectionEngine()

        self.workflow = self.factory.approval_workflow.create(
            name="采购单审批流程",
            resource_type=ResourceType.PURCHASE_ORDER,
        )
        self.factory.approval_node.create(
            workflow_id=self.workflow.id,
            node_name="一级审批",
            approver_user_id=self.approver.id,
            node_order=1,
            approval_type=ApprovalType.AND,
        )
        self.db.commit()

        yield

        cache.delete_pattern("*")
        if self.redis_client:
            self.redis_client.flushdb()

    def test_cdc_events_out_of_order_sorted_by_version(self):
        product = self.factory.product.create(name="测试商品")
        sku = self.factory.sku.create(product_id=product.id, sku_code="TEST-001")
        inventory = self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            quantity=100,
            available_quantity=100,
            version=1,
        )
        self.db.commit()

        event_v3 = self.factory.cdc_log.create(
            table_name="inventories",
            operation=CDCOperation.UPDATE,
            record_id=sku.id,
            old_data={
                "id": inventory.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse.id,
                "quantity": 150,
                "version": 2,
            },
            new_data={
                "id": inventory.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse.id,
                "quantity": 200,
                "version": 3,
            },
            processed=False,
        )
        event_v2 = self.factory.cdc_log.create(
            table_name="inventories",
            operation=CDCOperation.UPDATE,
            record_id=sku.id,
            old_data={
                "id": inventory.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse.id,
                "quantity": 100,
                "version": 1,
            },
            new_data={
                "id": inventory.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse.id,
                "quantity": 150,
                "version": 2,
            },
            processed=False,
        )
        self.db.commit()

        from app.utils.sync_engine import CDCCaptureEngine

        engine = CDCCaptureEngine(self.db)
        unprocessed_events = engine.get_unprocessed_events(
            table_name="inventories", record_id=sku.id
        )

        assert len(unprocessed_events) == 2
        assert unprocessed_events[0].new_data["version"] == 2
        assert unprocessed_events[1].new_data["version"] == 3

        for event in unprocessed_events:
            cdc_event = (
                self.db.query(CDCEvent)
                .filter(CDCEvent.cdc_log_id == event.id)
                .first()
            )
            if cdc_event:
                sync_result = self.sync_service.process_sync_from_cdc(cdc_event.id)
                self.db.commit()
                assert sync_result["success"] is True

        final_inventory = (
            self.db.query(Inventory)
            .filter(
                Inventory.sku_id == sku.id,
                Inventory.warehouse_id == self.warehouse.id,
            )
            .first()
        )
        assert final_inventory.quantity == 200
        assert final_inventory.version == 3

    def test_approval_rejection_rolls_back_purchase_order(self):
        product = self.factory.product.create(name="测试商品")
        sku = self.factory.sku.create(product_id=product.id)
        self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            quantity=50,
        )
        sku.safety_stock = 100
        sku.maximum_stock = 500
        self.db.commit()

        order_items = [
            {
                "sku_id": sku.id,
                "quantity": 100,
                "unit_price": 50.0,
                "tax_rate": 0.0,
            }
        ]
        po_create = PurchaseOrderCreate(
            supplier_id=self.supplier.id,
            warehouse_id=self.warehouse.id,
            order_date=datetime.utcnow(),
            expected_date=datetime.utcnow() + timedelta(days=7),
            items=order_items,
        )
        po = self.po_service.create_order(po_create, self.admin_user)
        self.db.commit()

        submit_request = ApprovalSubmissionRequest(
            resource_type=ResourceType.PURCHASE_ORDER,
            resource_id=po.id,
            workflow_id=self.workflow.id,
        )
        self.approval_service.submit_approval(submit_request)
        self.db.commit()

        po_after_submit = self.db.get(PurchaseOrder, po.id)
        assert po_after_submit.status == PurchaseOrderStatus.PENDING_APPROVAL

        approval_record = (
            self.db.query(ApprovalRecord)
            .filter(ApprovalRecord.resource_id == po.id)
            .filter(ApprovalRecord.approver_id == self.approver.id)
            .first()
        )

        from app.schemas.approval import ApprovalActionRequest as ActionReq

        action_request = ActionReq(
            action=ApprovalActionEnum.REJECT,
            approval_opinion="价格过高，需要重新谈判",
            notify_submitter=True,
        )
        approval_service = ApprovalService(self.db, self.approver)
        approval_service.process_approval_action(
            approval_record.id, action_request, self.approver
        )
        self.db.commit()

        po_after_reject = self.db.get(PurchaseOrder, po.id)
        assert po_after_reject.status == PurchaseOrderStatus.REJECTED

    def test_redis_disconnection_recovery_from_postgresql(self):
        product = self.factory.product.create(name="测试商品")
        sku = self.factory.sku.create(product_id=product.id)
        inventory = self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse.id,
            quantity=100,
            available_quantity=100,
            version=1,
        )
        self.db.commit()

        cdc_logs = []
        for i in range(5):
            old_qty = 100 + i * 20
            new_qty = 100 + (i + 1) * 20
            cdc_log = self.factory.cdc_log.create(
                table_name="inventories",
                operation=CDCOperation.UPDATE,
                record_id=sku.id,
                old_data={
                    "id": inventory.id,
                    "sku_id": sku.id,
                    "warehouse_id": self.warehouse.id,
                    "quantity": old_qty,
                    "version": i + 1,
                },
                new_data={
                    "id": inventory.id,
                    "sku_id": sku.id,
                    "warehouse_id": self.warehouse.id,
                    "quantity": new_qty,
                    "version": i + 2,
                },
                processed=False,
            )
            cdc_logs.append(cdc_log)
        self.db.commit()

        if self.redis_client:
            original_keys = self.redis_client.keys("*")
            self.redis_client.flushdb()

        from app.utils.sync_engine import CDCCaptureEngine

        engine = CDCCaptureEngine(self.db)
        unprocessed = engine.get_unprocessed_events(
            table_name="inventories", record_id=sku.id
        )

        assert len(unprocessed) == 5

        for event in unprocessed:
            cdc_event = (
                self.db.query(CDCEvent)
                .filter(CDCEvent.cdc_log_id == event.id)
                .first()
            )
            if cdc_event:
                sync_result = self.sync_service.process_sync_from_cdc(cdc_event.id)
                self.db.commit()
                assert sync_result["success"] is True

        final_inventory = (
            self.db.query(Inventory)
            .filter(
                Inventory.sku_id == sku.id,
                Inventory.warehouse_id == self.warehouse.id,
            )
            .first()
        )
        assert final_inventory.quantity == 200
        assert final_inventory.version == 6

        processed_logs = (
            self.db.query(CDCLog)
            .filter(CDCLog.table_name == "inventories")
            .filter(CDCLog.record_id == str(sku.id))
            .all()
        )
        assert all(log.processed for log in processed_logs)


class TestIntegrationDataConsistency:
    @pytest.fixture(autouse=True)
    def setup(
        self,
        clean_db: Session,
        docker_redis,
        db: Session,
    ):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.admin_user = self.factory.user.create(username="admin")
        self.warehouse1 = self.factory.warehouse.create(name="仓库A")
        self.warehouse2 = self.factory.warehouse.create(name="仓库B")
        self.db.commit()

        try:
            self.redis_client = redis.Redis(
                host=docker_redis.get_container_host_ip(),
                port=docker_redis.get_exposed_port(6379),
                db=0,
                decode_responses=True,
            )
            self.redis_client.ping()
        except Exception as e:
            self.redis_client = None
            pytest.skip(f"Redis not available: {e}")

        self.sync_service = InventorySyncService(self.db, self.admin_user)
        self.cdc_engine = CDCCaptureEngine(self.db)

        yield

        cache.delete_pattern("*")
        if self.redis_client:
            self.redis_client.flushdb()

    def test_multi_warehouse_inventory_consistency(self):
        product = self.factory.product.create(name="测试商品")
        sku = self.factory.sku.create(product_id=product.id)
        inv1 = self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse1.id,
            quantity=100,
            version=1,
        )
        inv2 = self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse2.id,
            quantity=100,
            version=1,
        )
        self.db.commit()

        cdc_log1 = self.factory.cdc_log.create(
            table_name="inventories",
            operation=CDCOperation.UPDATE,
            record_id=sku.id,
            old_data={
                "id": inv1.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse1.id,
                "quantity": 100,
                "version": 1,
            },
            new_data={
                "id": inv1.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse1.id,
                "quantity": 150,
                "version": 2,
            },
        )
        cdc_log2 = self.factory.cdc_log.create(
            table_name="inventories",
            operation=CDCOperation.UPDATE,
            record_id=sku.id,
            old_data={
                "id": inv2.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse2.id,
                "quantity": 100,
                "version": 1,
            },
            new_data={
                "id": inv2.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse2.id,
                "quantity": 80,
                "version": 2,
            },
        )
        self.db.commit()

        for cdc_log in [cdc_log1, cdc_log2]:
            cdc_event = (
                self.db.query(CDCEvent)
                .filter(CDCEvent.cdc_log_id == cdc_log.id)
                .first()
            )
            if cdc_event:
                sync_result = self.sync_service.process_sync_from_cdc(cdc_event.id)
                self.db.commit()
                assert sync_result["success"] is True

        updated_inv1 = self.db.get(Inventory, inv1.id)
        updated_inv2 = self.db.get(Inventory, inv2.id)

        assert updated_inv1.quantity == 150
        assert updated_inv1.version == 2
        assert updated_inv2.quantity == 80
        assert updated_inv2.version == 2

    def test_inventory_transaction_audit_trail(self):
        product = self.factory.product.create(name="测试商品")
        sku = self.factory.sku.create(product_id=product.id)
        inventory = self.factory.inventory.create(
            sku_id=sku.id,
            warehouse_id=self.warehouse1.id,
            quantity=100,
            version=1,
        )
        self.db.commit()

        initial_qty = inventory.quantity
        change_qty = 50

        cdc_log = self.factory.cdc_log.create(
            table_name="inventories",
            operation=CDCOperation.UPDATE,
            record_id=sku.id,
            old_data={
                "id": inventory.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse1.id,
                "quantity": initial_qty,
                "version": 1,
            },
            new_data={
                "id": inventory.id,
                "sku_id": sku.id,
                "warehouse_id": self.warehouse1.id,
                "quantity": initial_qty + change_qty,
                "version": 2,
            },
        )
        self.db.commit()

        cdc_event = (
            self.db.query(CDCEvent)
            .filter(CDCEvent.cdc_log_id == cdc_log.id)
            .first()
        )
        sync_result = self.sync_service.process_sync_from_cdc(cdc_event.id)
        self.db.commit()

        assert sync_result["success"] is True

        transaction = (
            self.db.query(InventoryTransaction)
            .filter(InventoryTransaction.sku_id == sku.id)
            .filter(InventoryTransaction.warehouse_id == self.warehouse1.id)
            .order_by(InventoryTransaction.created_at.desc())
            .first()
        )

        assert transaction is not None
        assert transaction.quantity == change_qty

        updated_inventory = self.db.get(Inventory, inventory.id)
        assert updated_inventory.quantity == initial_qty + change_qty
        assert updated_inventory.version == 2
