from __future__ import annotations

from datetime import datetime, timedelta

import pytest
from sqlalchemy.orm import Session

from app.models import (
    PurchaseOrder,
    PurchaseOrderStatus,
    PurchaseOrderItem,
    Inventory,
    ApprovalRecord,
    ApprovalType,
    ApprovalStatus,
    ResourceType,
)
from app.services.purchase_order_service import PurchaseOrderService
from app.services.approval_service import ApprovalService
from app.schemas.purchase_order import (
    PurchaseOrderCreate,
    PurchaseOrderUpdate,
    PurchaseOrderGenerateRequest,
)
from app.schemas.approval import (
    ApprovalSubmissionRequest,
    ApprovalActionEnum,
)
from tests.factories import get_factory

pytestmark = [pytest.mark.unit, pytest.mark.purchase]


class TestPurchaseOrderAutoGeneration:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.user = self.factory.user.create(username="test_user")
        self.factory.user, skus = self.factory.create_product_with_sku(num_skus=3)
        self.sku1, self.sku2, self.sku3 = skus

        self.supplier = self.factory.supplier.create()
        self.warehouse = self.factory.warehouse.create()

        self.sku1.max_stock = 500
        self.sku1.safety_stock = 100
        self.sku1.reorder_point = 150
        self.db.flush()

        self.inv1 = self.factory.inventory.create(
            sku_id=self.sku1.id, warehouse_id=self.warehouse.id, quantity=50
        )
        self.inv2 = self.factory.inventory.create(
            sku_id=self.sku2.id, warehouse_id=self.warehouse.id, quantity=200
        )
        self.inv3 = self.factory.inventory.create(
            sku_id=self.sku3.id, warehouse_id=self.warehouse.id, quantity=80
        )

        self.sku2.max_stock = 400
        self.sku2.safety_stock = 80
        self.sku2.reorder_point = 120
        self.db.flush()

        self.db.commit()

        self.po_service = PurchaseOrderService(self.db, self.user)
        self.approval_service = ApprovalService(self.db, self.user)

    def test_safety_stock_below_threshold_generates_suggestion(self):
        request = PurchaseOrderGenerateRequest(
            warehouse_id=self.warehouse.id,
            auto_create=False,
            forecast_periods=30,
            history_days=90,
        )

        result = self.po_service.generate_purchase_suggestions(request, self.user)
        self.db.commit()

        assert len(result.items) > 0

        sku1_item = None
        for item in result.items:
            if item.sku_id == self.sku1.id:
                sku1_item = item
                break

        assert sku1_item is not None
        assert sku1_item.suggested_quantity > 0
        assert sku1_item.current_stock == self.inv1.quantity
        assert sku1_item.safety_stock == self.sku1.safety_stock

    def test_suggestion_quantity_equals_max_stock_minus_current(self):
        self.sku1.max_stock = 500
        self.inv1.quantity = 50
        self.db.commit()

        expected_quantity = 500 - 50

        request = PurchaseOrderGenerateRequest(
            warehouse_id=self.warehouse.id,
            sku_ids=[self.sku1.id],
            auto_create=False,
            forecast_periods=30,
            history_days=90,
        )

        result = self.po_service.generate_purchase_suggestions(request, self.user)

        sku1_item = None
        for item in result.items:
            if item.sku_id == self.sku1.id:
                sku1_item = item
                break

        assert sku1_item is not None
        assert sku1_item.suggested_quantity == expected_quantity

    def test_auto_create_purchase_order_from_suggestion(self):
        request = PurchaseOrderGenerateRequest(
            warehouse_id=self.warehouse.id,
            sku_ids=[self.sku1.id],
            auto_create=True,
            forecast_periods=30,
            history_days=90,
        )

        result = self.po_service.generate_purchase_suggestions(request, self.user)
        self.db.commit()

        assert result.created_order_id is not None
        assert result.created_order_no is not None

        created_order = self.db.get(PurchaseOrder, result.created_order_id)
        assert created_order is not None
        assert created_order.status == PurchaseOrderStatus.DRAFT
        assert created_order.supplier_id == self.supplier.id
        assert len(created_order.items) > 0

    def test_no_suggestion_when_stock_above_safety_stock(self):
        self.inv1.quantity = 200
        self.db.commit()

        request = PurchaseOrderGenerateRequest(
            warehouse_id=self.warehouse.id,
            sku_ids=[self.sku1.id],
            auto_create=False,
            forecast_periods=30,
            history_days=90,
        )

        result = self.po_service.generate_purchase_suggestions(request, self.user)

        sku1_item = None
        for item in result.items:
            if item.sku_id == self.sku1.id:
                sku1_item = item
                break

        assert sku1_item is None or sku1_item.suggested_quantity == 0

    def test_multiple_skus_generate_suggestions(self):
        request = PurchaseOrderGenerateRequest(
            warehouse_id=self.warehouse.id,
            auto_create=False,
            forecast_periods=30,
            history_days=90,
        )

        result = self.po_service.generate_purchase_suggestions(request, self.user)

        assert len(result.items) >= 2
        assert result.total_quantity > 0
        assert result.total_amount > 0


class TestApprovalWorkflowEscalation:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.workflow, self.nodes, self.users = (
            self.factory.create_multi_level_approval_workflow(
                num_levels=3, auto_upgrade=True
            )
        )
        self.approver1, self.approver2, self.approver3 = self.users

        self.product, skus = self.factory.create_product_with_sku(num_skus=1)
        self.sku = skus[0]
        self.supplier = self.factory.supplier.create()
        self.warehouse = self.factory.warehouse.create()

        self.requester = self.factory.user.create(username="requester")
        self.db.commit()

        self.po_service = PurchaseOrderService(self.db, self.requester)
        self.approval_service = ApprovalService(self.db, self.requester)

        self.inventory = self.factory.inventory.create(
            sku_id=self.sku.id, warehouse_id=self.warehouse.id, quantity=50
        )
        self.sku.safety_stock = 100
        self.sku.max_stock = 500
        self.db.commit()

    def test_approval_timeout_auto_escalates_to_supervisor(self):
        order_items = [
            {
                "sku_id": self.sku.id,
                "quantity": 100,
                "unit_price": 10.0,
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

        po = self.po_service.create_order(po_create, self.requester)
        self.db.commit()

        submit_request = ApprovalSubmissionRequest(
            resource_type=ResourceType.PURCHASE_ORDER,
            resource_id=po.id,
            workflow_id=self.workflow.id,
        )

        submission = self.approval_service.submit_approval(submit_request)
        self.db.commit()

        approval_record = (
            self.db.query(ApprovalRecord)
            .filter(ApprovalRecord.resource_id == po.id)
            .filter(ApprovalRecord.node_id == self.nodes[0].id)
            .first()
        )

        assert approval_record is not None
        assert approval_record.status == ApprovalStatus.PENDING
        assert approval_record.approver_id == self.approver1.id

        approval_record.created_at = datetime.utcnow() - timedelta(hours=25)
        approval_record.status = ApprovalStatus.PENDING
        self.db.commit()

        self.approval_service.check_timeouts()
        self.db.commit()

        record_after = self.db.get(ApprovalRecord, approval_record.id)

        assert record_after.status == ApprovalStatus.ESCALATED
        assert record_after.approver_id == self.approver2.id

    def test_approval_rejection_rolls_back_purchase_order(self):
        order_items = [
            {
                "sku_id": self.sku.id,
                "quantity": 100,
                "unit_price": 10.0,
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

        po = self.po_service.create_order(po_create, self.requester)
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
            .filter(ApprovalRecord.approver_id == self.approver1.id)
            .first()
        )

        from app.schemas.approval import ApprovalActionRequest as ActionReq
        action_request = ActionReq(
            action=ApprovalActionEnum.REJECT,
            approval_opinion="价格过高",
            notify_submitter=True,
        )

        approval_service_as_approver1 = ApprovalService(self.db, self.approver1)
        approval_service_as_approver1.process_approval_action(approval_record.id, action_request, self.approver1)
        self.db.commit()

        po_after_reject = self.db.get(PurchaseOrder, po.id)
        assert po_after_reject.status == PurchaseOrderStatus.REJECTED

    def test_approval_approved_changes_purchase_order_status(self):
        order_items = [
            {
                "sku_id": self.sku.id,
                "quantity": 100,
                "unit_price": 10.0,
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

        po = self.po_service.create_order(po_create, self.requester)
        self.db.commit()

        submit_request = ApprovalSubmissionRequest(
            resource_type=ResourceType.PURCHASE_ORDER,
            resource_id=po.id,
            workflow_id=self.workflow.id,
        )

        self.approval_service.submit_approval(submit_request)
        self.db.commit()

        from app.schemas.approval import ApprovalActionRequest as ActionReq

        for i in range(len(self.nodes)):
            pending_record = (
                self.db.query(ApprovalRecord)
                .filter(ApprovalRecord.resource_id == po.id)
                .filter(ApprovalRecord.status == ApprovalStatus.PENDING)
                .filter(ApprovalRecord.approver_id == self.users[i].id)
                .first()
            )

            assert pending_record is not None

            action_request = ActionReq(
                action=ApprovalActionEnum.APPROVE,
                approval_opinion=f"Approved by approver {i+1}",
                notify_submitter=True,
            )

            current_service = ApprovalService(self.db, self.users[i])
            current_service.process_approval_action(pending_record.id, action_request, self.users[i])
            self.db.commit()

        po_final = self.db.get(PurchaseOrder, po.id)
        assert po_final.status == PurchaseOrderStatus.APPROVED


class TestApprovalChainBoundaryScenarios:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.product, skus = self.factory.create_product_with_sku(num_skus=1)
        self.sku = skus[0]
        self.supplier = self.factory.supplier.create()
        self.warehouse = self.factory.warehouse.create()
        self.requester = self.factory.user.create(username="requester")
        self.db.commit()

    def test_10_level_approval_chain_state_consistency(self):
        workflow, nodes, users = self.factory.create_multi_level_approval_workflow(
            num_levels=10, auto_upgrade=False
        )
        self.db.commit()

        assert len(nodes) == 10
        assert len(users) == 10

        po_service = PurchaseOrderService(self.db, self.requester)
        approval_service = ApprovalService(self.db, self.requester)

        order_items = [
            {
                "sku_id": self.sku.id,
                "quantity": 100,
                "unit_price": 10.0,
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

        po = po_service.create_order(po_create, self.requester)
        self.db.commit()

        submit_request = ApprovalSubmissionRequest(
            resource_type=ResourceType.PURCHASE_ORDER,
            resource_id=po.id,
            workflow_id=workflow.id,
        )

        approval_service.submit_approval(submit_request)
        self.db.commit()

        from app.schemas.approval import ApprovalActionRequest as ActionReq

        for i in range(10):
            approval_record = (
                self.db.query(ApprovalRecord)
                .filter(ApprovalRecord.resource_id == po.id)
                .filter(ApprovalRecord.status == ApprovalStatus.PENDING)
                .filter(ApprovalRecord.approver_id == users[i].id)
                .first()
            )

            assert approval_record is not None
            assert approval_record.node_id == nodes[i].id
            assert approval_record.approver_id == users[i].id

            current_approver_service = ApprovalService(self.db, users[i])
            action_request = ActionReq(
                action=ApprovalActionEnum.APPROVE,
                approval_opinion=f"Approved by approver {i+1}",
                notify_submitter=True,
            )
            current_approver_service.process_approval_action(approval_record.id, action_request, users[i])
            self.db.commit()

        po_final = self.db.get(PurchaseOrder, po.id)
        assert po_final.status == PurchaseOrderStatus.APPROVED

        all_records = (
            self.db.query(ApprovalRecord)
            .filter(ApprovalRecord.resource_id == po.id)
            .order_by(ApprovalRecord.node_id)
            .all()
        )

        assert len(all_records) == 10
        for i, record in enumerate(all_records):
            assert record.status == ApprovalStatus.APPROVED
            assert record.approver_id == users[i].id


class TestPurchaseOrderStatusTransitions:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.user = self.factory.user.create()
        self.product, skus = self.factory.create_product_with_sku(num_skus=2)
        self.sku1, self.sku2 = skus
        self.supplier = self.factory.supplier.create()
        self.warehouse = self.factory.warehouse.create()
        self.db.commit()

        self.po_service = PurchaseOrderService(self.db, self.user)

    def test_create_draft_purchase_order(self):
        order_items = [
            {
                "sku_id": self.sku1.id,
                "quantity": 100,
                "unit_price": 50.0,
                "tax_rate": 0.0,
            },
            {
                "sku_id": self.sku2.id,
                "quantity": 50,
                "unit_price": 30.0,
                "tax_rate": 0.0,
            },
        ]
        po_create = PurchaseOrderCreate(
            supplier_id=self.supplier.id,
            warehouse_id=self.warehouse.id,
            order_date=datetime.utcnow(),
            expected_date=datetime.utcnow() + timedelta(days=7),
            items=order_items,
        )

        po = self.po_service.create_order(po_create, self.user)
        self.db.commit()

        assert po.status == PurchaseOrderStatus.DRAFT
        assert po.order_no is not None
        assert len(po.items) == 2
        assert po.total_amount == 100 * 50 + 50 * 30
        assert po.created_by == self.user.id

    def test_update_draft_purchase_order(self):
        order_items = [
            {
                "sku_id": self.sku1.id,
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

        po = self.po_service.create_order(po_create, self.user)
        self.db.commit()

        update_items = [
            {
                "sku_id": self.sku1.id,
                "quantity": 200,
                "unit_price": 60.0,
                "tax_rate": 0.0,
            }
        ]
        po_update = PurchaseOrderUpdate(items=update_items)

        updated_po = self.po_service.update_order(po.id, po_update, self.user)
        self.db.commit()

        assert updated_po.items[0].quantity == 200
        assert updated_po.items[0].unit_price == 60.0
        assert updated_po.total_amount == 200 * 60

    def test_cannot_update_approved_order(self):
        order_items = [
            {
                "sku_id": self.sku1.id,
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

        po = self.po_service.create_order(po_create, self.user)
        po.status = PurchaseOrderStatus.APPROVED
        self.db.commit()

        po_update = PurchaseOrderUpdate(remark="test update")

        with pytest.raises(ValueError) as exc_info:
            self.po_service.update_order(po.id, po_update, self.user)

        assert "Cannot update order in status" in str(exc_info.value)

    def test_delete_draft_purchase_order(self):
        order_items = [
            {
                "sku_id": self.sku1.id,
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

        po = self.po_service.create_order(po_create, self.user)
        self.db.commit()

        result = self.po_service.delete_order(po.id, self.user)
        self.db.commit()

        assert result is True

        deleted_po = self.db.get(PurchaseOrder, po.id)
        assert deleted_po is None

    def test_purchase_order_list_with_filters(self):
        for i in range(5):
            status = PurchaseOrderStatus.DRAFT if i % 2 == 0 else PurchaseOrderStatus.APPROVED
            self.factory.purchase_order.create(
                supplier_id=self.supplier.id,
                warehouse_id=self.warehouse.id,
                status=status,
            )
        self.db.commit()

        draft_orders, total, _ = self.po_service.list_orders(
            status=PurchaseOrderStatus.DRAFT
        )

        assert total == 3
        assert len(draft_orders) == 3

    def test_receive_purchase_order_updates_inventory(self):
        order_items = [
            {
                "sku_id": self.sku1.id,
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

        po = self.po_service.create_order(po_create, self.user)
        po.status = PurchaseOrderStatus.APPROVED
        self.db.commit()

        initial_inventory = self.factory.inventory.create(
            sku_id=self.sku1.id, warehouse_id=self.warehouse.id, quantity=0
        )
        self.db.commit()

        from app.schemas.purchase_order import PurchaseOrderReceiveRequest, PurchaseOrderReceiveItem

        receive_request = PurchaseOrderReceiveRequest(
            items=[
                PurchaseOrderReceiveItem(
                    item_id=po.items[0].id,
                    received_quantity=80,
                )
            ]
        )

        result = self.po_service.receive_order(po.id, receive_request, self.user)
        self.db.commit()

        updated_inventory = self.db.get(Inventory, initial_inventory.id)
        assert updated_inventory.quantity == 80

        updated_item = self.db.get(PurchaseOrderItem, po.items[0].id)
        assert updated_item.received_quantity == 80


class TestApprovalTypes:
    @pytest.fixture(autouse=True)
    def setup(self, clean_db: Session):
        self.db = clean_db
        self.factory = get_factory(self.db)

        self.product, skus = self.factory.create_product_with_sku(num_skus=1)
        self.sku = skus[0]
        self.supplier = self.factory.supplier.create()
        self.warehouse = self.factory.warehouse.create()
        self.requester = self.factory.user.create(username="requester")
        self.approvers = [
            self.factory.user.create(username=f"approver_{i}") for i in range(3)
        ]
        self.db.commit()

    def test_and_approval_requires_all_approvers(self):
        workflow = self.factory.approval_workflow.create(
            name="AND审批流程",
        )
        node = self.factory.approval_node.create(
            workflow_id=workflow.id,
            node_name="审批节点",
            approver_user_id=self.approvers[0].id,
            node_order=1,
            approval_type=ApprovalType.AND,
        )
        for approver in self.approvers[1:]:
            self.factory.approval_node.create(
                workflow_id=workflow.id,
                node_name="审批节点",
                approver_user_id=approver.id,
                node_order=1,
                approval_type=ApprovalType.AND,
            )
        self.db.commit()

        po_service = PurchaseOrderService(self.db, self.requester)
        approval_service = ApprovalService(self.db, self.requester)

        order_items = [
            {
                "sku_id": self.sku.id,
                "quantity": 100,
                "unit_price": 10.0,
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

        po = po_service.create_order(po_create, self.requester)
        self.db.commit()

        submit_request = ApprovalSubmissionRequest(
            resource_type=ResourceType.PURCHASE_ORDER,
            resource_id=po.id,
            workflow_id=workflow.id,
        )

        approval_service.submit_approval(submit_request)
        self.db.commit()

        from app.schemas.approval import ApprovalActionRequest as ActionReq

        approval_service_1 = ApprovalService(self.db, self.approvers[0])
        pending_record = self.db.query(ApprovalRecord).filter(
            ApprovalRecord.resource_id == po.id,
            ApprovalRecord.status == ApprovalStatus.PENDING,
            ApprovalRecord.approver_id == self.approvers[0].id,
        ).first()

        action_request = ActionReq(
            action=ApprovalActionEnum.APPROVE,
            approval_opinion="Approved",
            notify_submitter=True,
        )
        approval_service_1.process_approval_action(pending_record.id, action_request, self.approvers[0])
        self.db.commit()

        po_after_first = self.db.get(PurchaseOrder, po.id)
        assert po_after_first.status == PurchaseOrderStatus.PENDING_APPROVAL

        for approver in self.approvers[1:]:
            pending_record = self.db.query(ApprovalRecord).filter(
                ApprovalRecord.resource_id == po.id,
                ApprovalRecord.status == ApprovalStatus.PENDING,
                ApprovalRecord.approver_id == approver.id,
            ).first()

            current_service = ApprovalService(self.db, approver)
            action_request = ActionReq(
                action=ApprovalActionEnum.APPROVE,
                approval_opinion="Approved",
                notify_submitter=True,
            )
            current_service.process_approval_action(pending_record.id, action_request, approver)
            self.db.commit()

        po_final = self.db.get(PurchaseOrder, po.id)
        assert po_final.status == PurchaseOrderStatus.APPROVED

    def test_or_approval_requires_one_approver(self):
        workflow = self.factory.approval_workflow.create(
            name="OR审批流程",
        )
        for approver in self.approvers:
            self.factory.approval_node.create(
                workflow_id=workflow.id,
                node_name="审批节点",
                approver_user_id=approver.id,
                node_order=1,
                approval_type=ApprovalType.OR,
            )
        self.db.commit()

        po_service = PurchaseOrderService(self.db, self.requester)
        approval_service = ApprovalService(self.db, self.requester)

        order_items = [
            {
                "sku_id": self.sku.id,
                "quantity": 100,
                "unit_price": 10.0,
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

        po = po_service.create_order(po_create, self.requester)
        self.db.commit()

        submit_request = ApprovalSubmissionRequest(
            resource_type=ResourceType.PURCHASE_ORDER,
            resource_id=po.id,
            workflow_id=workflow.id,
        )

        approval_service.submit_approval(submit_request)
        self.db.commit()

        from app.schemas.approval import ApprovalActionRequest as ActionReq

        approval_service_2 = ApprovalService(self.db, self.approvers[1])
        pending_record = self.db.query(ApprovalRecord).filter(
            ApprovalRecord.resource_id == po.id,
            ApprovalRecord.status == ApprovalStatus.PENDING,
            ApprovalRecord.approver_id == self.approvers[1].id,
        ).first()

        action_request = ActionReq(
            action=ApprovalActionEnum.APPROVE,
            approval_opinion="Approved by second approver",
            notify_submitter=True,
        )
        approval_service_2.process_approval_action(pending_record.id, action_request, self.approvers[1])
        self.db.commit()

        po_final = self.db.get(PurchaseOrder, po.id)
        assert po_final.status == PurchaseOrderStatus.APPROVED

    def test_percentage_approval(self):
        workflow = self.factory.approval_workflow.create(
            name="比例审批流程",
        )
        for approver in self.approvers:
            self.factory.approval_node.create(
                workflow_id=workflow.id,
                node_name="审批节点",
                approver_user_id=approver.id,
                node_order=1,
                approval_type=ApprovalType.PERCENTAGE,
                pass_percentage=60,
            )
        self.db.commit()

        po_service = PurchaseOrderService(self.db, self.requester)
        approval_service = ApprovalService(self.db, self.requester)

        order_items = [
            {
                "sku_id": self.sku.id,
                "quantity": 100,
                "unit_price": 10.0,
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

        po = po_service.create_order(po_create, self.requester)
        self.db.commit()

        submit_request = ApprovalSubmissionRequest(
            resource_type=ResourceType.PURCHASE_ORDER,
            resource_id=po.id,
            workflow_id=workflow.id,
        )

        approval_service.submit_approval(submit_request)
        self.db.commit()

        from app.schemas.approval import ApprovalActionRequest as ActionReq

        approval_service_0 = ApprovalService(self.db, self.approvers[0])
        pending_record = self.db.query(ApprovalRecord).filter(
            ApprovalRecord.resource_id == po.id,
            ApprovalRecord.status == ApprovalStatus.PENDING,
            ApprovalRecord.approver_id == self.approvers[0].id,
        ).first()

        action_request = ActionReq(
            action=ApprovalActionEnum.APPROVE,
            approval_opinion="Approved",
            notify_submitter=True,
        )
        approval_service_0.process_approval_action(pending_record.id, action_request, self.approvers[0])
        self.db.commit()

        po_after_first = self.db.get(PurchaseOrder, po.id)
        assert po_after_first.status == PurchaseOrderStatus.PENDING_APPROVAL

        approval_service_1 = ApprovalService(self.db, self.approvers[1])
        pending_record = self.db.query(ApprovalRecord).filter(
            ApprovalRecord.resource_id == po.id,
            ApprovalRecord.status == ApprovalStatus.PENDING,
            ApprovalRecord.approver_id == self.approvers[1].id,
        ).first()

        action_request = ActionReq(
            action=ApprovalActionEnum.APPROVE,
            approval_opinion="Approved",
            notify_submitter=True,
        )
        approval_service_1.process_approval_action(pending_record.id, action_request, self.approvers[1])
        self.db.commit()

        po_final = self.db.get(PurchaseOrder, po.id)
        assert po_final.status == PurchaseOrderStatus.APPROVED
