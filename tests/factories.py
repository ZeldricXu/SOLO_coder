from __future__ import annotations

from typing import Dict, Any, List, Optional, Tuple
from datetime import datetime, timedelta
from sqlalchemy.orm import Session

from faker import Faker

from app.models import (
    User,
    Role,
    Permission,
    Product,
    ProductStatus,
    Category,
    SKU,
    SkuStatus,
    SkuLifecycleStatus,
    Warehouse,
    WarehouseType,
    Inventory,
    Supplier,
    PurchaseOrder,
    PurchaseOrderStatus,
    PurchaseOrderItem,
    ApprovalWorkflow,
    ApprovalNode,
    ApprovalType,
    NodeType,
    ResourceType,
    AlertRule,
    AlertRuleType,
    ThresholdType,
    AlertLevel,
    InventoryAlert,
    AlertStatus,
    InventorySync,
    SyncType,
    SyncStatus,
    SyncConflict,
    ConflictType,
    ResolutionStrategy,
    ConflictStatus,
    CDCLog,
    CDCEvent,
    CDCOperation,
    CDCSourceSystem,
    CDCEventType,
    AttributeDataType,
    Attribute,
    ReplenishmentSuggestion,
    ReplenishmentStatus,
)
from app.schemas.product import SkuGenerateRequest, SkuGenerateAttributeItem

fake = Faker("zh_CN")


class BaseFactory:
    def __init__(self, db: Session):
        self.db = db

    def _persist(self, obj: Any) -> Any:
        self.db.add(obj)
        self.db.flush()
        self.db.refresh(obj)
        return obj


class UserFactory(BaseFactory):
    def create(
        self,
        username: Optional[str] = None,
        email: Optional[str] = None,
        full_name: Optional[str] = None,
        is_active: bool = True,
        roles: Optional[List[Role]] = None,
    ) -> User:
        user = User(
            username=username or fake.user_name(),
            email=email or fake.email(),
            full_name=full_name or fake.name(),
            hashed_password="hashed_password",
            is_active=is_active,
        )
        if roles:
            user.roles = roles
        return self._persist(user)


class RoleFactory(BaseFactory):
    def create(
        self,
        name: Optional[str] = None,
        code: Optional[str] = None,
        description: Optional[str] = None,
        permissions: Optional[List[Permission]] = None,
    ) -> Role:
        role = Role(
            name=name or fake.job(),
            code=code or f"ROLE_{fake.random_int(100, 999)}",
            description=description or fake.text(max_nb_chars=100),
        )
        if permissions:
            role.permissions = permissions
        return self._persist(role)


class PermissionFactory(BaseFactory):
    def create(
        self,
        name: Optional[str] = None,
        code: Optional[str] = None,
        description: Optional[str] = None,
        resource_type: Optional[str] = None,
        action: Optional[str] = None,
    ) -> Permission:
        resource = resource_type or fake.random_element(["product", "sku", "inventory", "order"])
        act = action or fake.random_element(["create", "read", "update", "delete"])
        return self._persist(
            Permission(
                name=name or f"{resource}:{act}",
                code=code or f"{resource}:{act}",
                description=description or f"{act} {resource}",
                resource_type=resource,
                action=act,
            )
        )


class CategoryFactory(BaseFactory):
    def create(
        self,
        name: Optional[str] = None,
        code: Optional[str] = None,
        parent_id: Optional[int] = None,
        is_active: bool = True,
    ) -> Category:
        return self._persist(
            Category(
                name=name or fake.bs(),
                code=code or f"CAT{fake.random_int(1000, 9999)}",
                parent_id=parent_id,
                is_active=is_active,
            )
        )


class ProductFactory(BaseFactory):
    def create(
        self,
        name: Optional[str] = None,
        code: Optional[str] = None,
        category_id: Optional[int] = None,
        brand: Optional[str] = None,
        status: ProductStatus = ProductStatus.ACTIVE,
        description: Optional[str] = None,
        barcode: Optional[str] = None,
    ) -> Product:
        return self._persist(
            Product(
                name=name or fake.catch_phrase(),
                code=code or f"PRD{fake.random_int(10000, 99999)}",
                category_id=category_id,
                brand=brand or fake.company(),
                status=status,
                description=description or fake.text(max_nb_chars=200),
                barcode=barcode or fake.ean13(),
            )
        )


class AttributeFactory(BaseFactory):
    def create(
        self,
        name: Optional[str] = None,
        code: Optional[str] = None,
        data_type: AttributeDataType = AttributeDataType.STRING,
        is_required: bool = False,
        is_filterable: bool = True,
    ) -> Attribute:
        return self._persist(
            Attribute(
                name=name or fake.word(),
                code=code or f"ATTR_{fake.random_int(100, 999)}",
                data_type=data_type,
                is_required=is_required,
                is_filterable=is_filterable,
            )
        )


class SKUFactory(BaseFactory):
    def create(
        self,
        product_id: int,
        sku_code: Optional[str] = None,
        attributes: Optional[Dict[str, Any]] = None,
        price: Optional[float] = None,
        cost: Optional[float] = None,
        status: SkuStatus = SkuStatus.ACTIVE,
        lifecycle_status: SkuLifecycleStatus = SkuLifecycleStatus.PRODUCTION,
    ) -> SKU:
        return self._persist(
            SKU(
                product_id=product_id,
                sku_code=sku_code or f"SKU{fake.random_int(100000, 999999)}",
                attributes=attributes or {},
                price=price or fake.random_int(10, 1000) + 0.99,
                cost=cost or fake.random_int(5, 500) + 0.50,
                status=status,
                lifecycle_status=lifecycle_status,
                safety_stock=fake.random_int(10, 100),
                max_stock=fake.random_int(200, 500),
                reorder_point=fake.random_int(50, 150),
            )
        )


class WarehouseFactory(BaseFactory):
    def create(
        self,
        name: Optional[str] = None,
        code: Optional[str] = None,
        warehouse_type: WarehouseType = WarehouseType.MAIN,
        address: Optional[str] = None,
        capacity: Optional[float] = None,
        is_active: bool = True,
    ) -> Warehouse:
        return self._persist(
            Warehouse(
                name=name or f"{fake.city()}仓库",
                code=code or f"WH{fake.random_int(100, 999)}",
                warehouse_type=warehouse_type,
                address=address or fake.address(),
                capacity=capacity or fake.random_int(1000, 10000),
                is_active=is_active,
            )
        )


class InventoryFactory(BaseFactory):
    def create(
        self,
        sku_id: int,
        warehouse_id: int,
        quantity: Optional[int] = None,
        reserved_quantity: int = 0,
        allocated_quantity: int = 0,
        in_transit_quantity: int = 0,
        unit_cost: Optional[float] = None,
    ) -> Inventory:
        qty = quantity if quantity is not None else fake.random_int(0, 500)
        cost = unit_cost or fake.random_int(10, 500) + 0.50
        return self._persist(
            Inventory(
                sku_id=sku_id,
                warehouse_id=warehouse_id,
                quantity=qty,
                reserved_quantity=reserved_quantity,
                allocated_quantity=allocated_quantity,
                in_transit_quantity=in_transit_quantity,
                available_quantity=qty - reserved_quantity - allocated_quantity,
                unit_cost=cost,
                total_value=qty * cost,
                version=fake.random_int(1, 10),
            )
        )


class SupplierFactory(BaseFactory):
    def create(
        self,
        name: Optional[str] = None,
        code: Optional[str] = None,
        contact: Optional[str] = None,
        phone: Optional[str] = None,
        email: Optional[str] = None,
        lead_time_days: int = 7,
        is_active: bool = True,
    ) -> Supplier:
        return self._persist(
            Supplier(
                name=name or fake.company(),
                code=code or f"SUP{fake.random_int(100, 999)}",
                contact=contact or fake.name(),
                phone=phone or fake.phone_number(),
                email=email or fake.company_email(),
                lead_time_days=lead_time_days,
                is_active=is_active,
            )
        )


class PurchaseOrderFactory(BaseFactory):
    def create(
        self,
        supplier_id: int,
        warehouse_id: int,
        total_amount: Optional[float] = None,
        status: PurchaseOrderStatus = PurchaseOrderStatus.DRAFT,
        expected_date: Optional[datetime] = None,
        items: Optional[List[PurchaseOrderItem]] = None,
        created_by: Optional[int] = None,
    ) -> PurchaseOrder:
        po = PurchaseOrder(
            order_no=f"PO{datetime.utcnow().strftime('%Y%m%d')}{fake.random_int(1000, 9999)}",
            supplier_id=supplier_id,
            warehouse_id=warehouse_id,
            total_amount=total_amount or fake.random_int(1000, 10000) + 0.50,
            status=status,
            expected_date=expected_date or (datetime.utcnow() + timedelta(days=7)),
            created_by=created_by,
        )
        if items:
            po.items = items
        return self._persist(po)


class PurchaseOrderItemFactory(BaseFactory):
    def create(
        self,
        purchase_order_id: int,
        sku_id: int,
        quantity: Optional[int] = None,
        unit_price: Optional[float] = None,
        received_quantity: int = 0,
    ) -> PurchaseOrderItem:
        qty = quantity or fake.random_int(10, 200)
        price = unit_price or fake.random_int(10, 500) + 0.99
        return self._persist(
            PurchaseOrderItem(
                purchase_order_id=purchase_order_id,
                sku_id=sku_id,
                quantity=qty,
                unit_price=price,
                received_quantity=received_quantity,
                total_amount=qty * price,
            )
        )


class ApprovalWorkflowFactory(BaseFactory):
    def create(
        self,
        name: Optional[str] = None,
        code: Optional[str] = None,
        resource_type: ResourceType = ResourceType.PURCHASE_ORDER,
        is_active: bool = True,
    ) -> ApprovalWorkflow:
        return self._persist(
            ApprovalWorkflow(
                name=name or f"{resource_type.value}审批流程",
                code=code or f"WF{datetime.utcnow().strftime('%Y%m%d%H%M%S')}{fake.random_int(100, 999)}",
                resource_type=resource_type,
                is_active=is_active,
            )
        )


class ApprovalNodeFactory(BaseFactory):
    def create(
        self,
        workflow_id: int,
        node_name: Optional[str] = None,
        approver_user_id: Optional[int] = None,
        approver_role_id: Optional[int] = None,
        node_order: int = 1,
        timeout_hours: int = 24,
        auto_upgrade: bool = False,
        upgrade_user_id: Optional[int] = None,
        approval_type: Optional[ApprovalType] = ApprovalType.AND,
    ) -> ApprovalNode:
        return self._persist(
            ApprovalNode(
                workflow_id=workflow_id,
                node_name=node_name or f"节点{node_order}",
                required_user_id=approver_user_id,
                required_role_id=approver_role_id,
                sort_order=node_order,
                node_type=NodeType.APPROVAL,
                approval_type=approval_type,
                timeout_hours=timeout_hours,
                auto_upgrade=auto_upgrade,
                upgrade_user_id=upgrade_user_id,
            )
        )


class AlertRuleFactory(BaseFactory):
    def create(
        self,
        rule_name: Optional[str] = None,
        rule_type: AlertRuleType = AlertRuleType.LOW_STOCK,
        threshold_type: ThresholdType = ThresholdType.QUANTITY,
        threshold_value: Optional[float] = None,
        alert_level: AlertLevel = AlertLevel.WARNING,
        sku_id: Optional[int] = None,
        category_id: Optional[int] = None,
        warehouse_id: Optional[int] = None,
        is_active: bool = True,
    ) -> AlertRule:
        return self._persist(
            AlertRule(
                rule_name=rule_name or f"{rule_type.value}预警规则",
                rule_type=rule_type,
                threshold_type=threshold_type,
                threshold_value=threshold_value or fake.random_int(10, 100),
                alert_level=alert_level,
                sku_id=sku_id,
                category_id=category_id,
                warehouse_id=warehouse_id,
                is_active=is_active,
            )
        )


class InventoryAlertFactory(BaseFactory):
    def create(
        self,
        rule_id: int,
        sku_id: int,
        warehouse_id: int,
        alert_level: AlertLevel = AlertLevel.WARNING,
        alert_message: Optional[str] = None,
        alert_data: Optional[Dict[str, Any]] = None,
        status: AlertStatus = AlertStatus.OPEN,
    ) -> InventoryAlert:
        return self._persist(
            InventoryAlert(
                rule_id=rule_id,
                sku_id=sku_id,
                warehouse_id=warehouse_id,
                alert_level=alert_level,
                alert_message=alert_message or fake.text(max_nb_chars=100),
                alert_data=alert_data or {},
                status=status,
            )
        )


class InventorySyncFactory(BaseFactory):
    def create(
        self,
        source_warehouse_id: int,
        target_warehouse_id: int,
        sku_id: int,
        quantity: Optional[int] = None,
        sync_type: SyncType = SyncType.INCREMENTAL,
        sync_status: SyncStatus = SyncStatus.PENDING,
        version: Optional[int] = None,
    ) -> InventorySync:
        return self._persist(
            InventorySync(
                source_warehouse_id=source_warehouse_id,
                target_warehouse_id=target_warehouse_id,
                sku_id=sku_id,
                quantity=quantity or fake.random_int(1, 100),
                sync_type=sync_type,
                sync_status=sync_status,
                version=version or fake.random_int(1, 10),
            )
        )


class SyncConflictFactory(BaseFactory):
    def create(
        self,
        sync_id: int,
        sku_id: int,
        conflict_type: ConflictType = ConflictType.VERSION_CONFLICT,
        resolution_strategy: ResolutionStrategy = ResolutionStrategy.LAST_WRITE_WINS,
        source_data: Optional[Dict[str, Any]] = None,
        target_data: Optional[Dict[str, Any]] = None,
        status: ConflictStatus = ConflictStatus.PENDING,
    ) -> SyncConflict:
        return self._persist(
            SyncConflict(
                sync_id=sync_id,
                sku_id=sku_id,
                conflict_type=conflict_type,
                resolution_strategy=resolution_strategy,
                source_data=source_data or {},
                target_data=target_data or {},
                status=status,
            )
        )


class CDCLogFactory(BaseFactory):
    def create(
        self,
        table_name: str = "inventories",
        operation: CDCOperation = CDCOperation.UPDATE,
        record_id: Optional[int] = None,
        old_data: Optional[Dict[str, Any]] = None,
        new_data: Optional[Dict[str, Any]] = None,
        source_system: CDCSourceSystem = CDCSourceSystem.WMS,
        processed: bool = False,
    ) -> CDCLog:
        return self._persist(
            CDCLog(
                table_name=table_name,
                operation=operation,
                record_id=record_id or fake.random_int(1, 1000),
                old_data=old_data,
                new_data=new_data,
                source_system=source_system,
                processed=processed,
            )
        )


class CDCEventFactory(BaseFactory):
    def create(
        self,
        cdc_log_id: int,
        event_type: CDCEventType = CDCEventType.INVENTORY_CHANGED,
        status: str = "PENDING",
        version: Optional[int] = None,
    ) -> CDCEvent:
        return self._persist(
            CDCEvent(
                cdc_log_id=cdc_log_id,
                event_type=event_type,
                status=status,
                version=version or fake.random_int(1, 10),
            )
        )


class ReplenishmentSuggestionFactory(BaseFactory):
    def create(
        self,
        sku_id: int,
        warehouse_id: int,
        supplier_id: int,
        suggested_quantity: Optional[int] = None,
        current_stock: Optional[int] = None,
        forecast_demand: Optional[int] = None,
        lead_time_days: int = 7,
        status: ReplenishmentStatus = ReplenishmentStatus.PENDING,
    ) -> ReplenishmentSuggestion:
        return self._persist(
            ReplenishmentSuggestion(
                sku_id=sku_id,
                warehouse_id=warehouse_id,
                supplier_id=supplier_id,
                suggested_quantity=suggested_quantity or fake.random_int(50, 200),
                current_stock=current_stock or fake.random_int(0, 50),
                forecast_demand=forecast_demand or fake.random_int(100, 300),
                lead_time_days=lead_time_days,
                status=status,
            )
        )


class TestDataGenerator:
    __test__ = False
    
    def __init__(self, db: Session):
        self.db = db
        self.user = UserFactory(db)
        self.role = RoleFactory(db)
        self.permission = PermissionFactory(db)
        self.category = CategoryFactory(db)
        self.product = ProductFactory(db)
        self.sku = SKUFactory(db)
        self.attribute = AttributeFactory(db)
        self.warehouse = WarehouseFactory(db)
        self.inventory = InventoryFactory(db)
        self.supplier = SupplierFactory(db)
        self.purchase_order = PurchaseOrderFactory(db)
        self.purchase_order_item = PurchaseOrderItemFactory(db)
        self.approval_workflow = ApprovalWorkflowFactory(db)
        self.approval_node = ApprovalNodeFactory(db)
        self.alert_rule = AlertRuleFactory(db)
        self.inventory_alert = InventoryAlertFactory(db)
        self.inventory_sync = InventorySyncFactory(db)
        self.sync_conflict = SyncConflictFactory(db)
        self.cdc_log = CDCLogFactory(db)
        self.cdc_event = CDCEventFactory(db)
        self.replenishment = ReplenishmentSuggestionFactory(db)

    def create_product_with_sku(
        self,
        num_skus: int = 1,
        category_id: Optional[int] = None,
    ) -> Tuple[Product, List[SKU]]:
        if not category_id:
            cat = self.category.create()
            category_id = cat.id

        product = self.product.create(category_id=category_id)
        skus = []
        for _ in range(num_skus):
            sku = self.sku.create(product_id=product.id)
            skus.append(sku)
        self.db.commit()
        return product, skus

    def create_warehouse_with_inventory(
        self,
        sku_id: int,
        quantity: Optional[int] = None,
    ) -> Tuple[Warehouse, Inventory]:
        warehouse = self.warehouse.create()
        inventory = self.inventory.create(
            sku_id=sku_id,
            warehouse_id=warehouse.id,
            quantity=quantity,
        )
        self.db.commit()
        return warehouse, inventory

    def create_sku_generation_request(
        self,
        product_id: int,
        colors: Optional[List[str]] = None,
        sizes: Optional[List[str]] = None,
    ) -> SkuGenerateRequest:
        colors = colors or ["红色", "蓝色"]
        sizes = sizes or ["S", "M", "L"]

        attributes = [
            SkuGenerateAttributeItem(
                attribute_code="color",
                attribute_name="颜色",
                values=colors,
            ),
            SkuGenerateAttributeItem(
                attribute_code="size",
                attribute_name="尺寸",
                values=sizes,
            ),
        ]

        return SkuGenerateRequest(
            product_id=product_id,
            attributes=attributes,
        )

    def create_multi_level_approval_workflow(
        self,
        num_levels: int = 3,
        auto_upgrade: bool = False,
    ) -> Tuple[ApprovalWorkflow, List[ApprovalNode], List[User]]:
        workflow = self.approval_workflow.create()
        nodes = []
        users = []

        for i in range(1, num_levels + 1):
            user = self.user.create(username=f"approver_{i}")
            users.append(user)

            upgrade_user_id = users[i - 2].id if (i > 1 and auto_upgrade) else None
            node = self.approval_node.create(
                workflow_id=workflow.id,
                node_name=f"审批节点{i}",
                approver_user_id=user.id,
                node_order=i,
                auto_upgrade=auto_upgrade,
                upgrade_user_id=upgrade_user_id,
            )
            nodes.append(node)

        self.db.commit()
        return workflow, nodes, users

    def create_cdc_inventory_event(
        self,
        inventory_id: int,
        sku_id: int,
        warehouse_id: int,
        old_quantity: int,
        new_quantity: int,
        version: int = 1,
    ) -> Tuple[CDCLog, CDCEvent]:
        old_data = {
            "id": inventory_id,
            "sku_id": sku_id,
            "warehouse_id": warehouse_id,
            "quantity": old_quantity,
            "version": version - 1,
        }
        new_data = {
            "id": inventory_id,
            "sku_id": sku_id,
            "warehouse_id": warehouse_id,
            "quantity": new_quantity,
            "version": version,
        }

        cdc_log = self.cdc_log.create(
            table_name="inventories",
            operation=CDCOperation.UPDATE,
            record_id=inventory_id,
            old_data=old_data,
            new_data=new_data,
        )
        cdc_event = self.cdc_event.create(
            cdc_log_id=cdc_log.id,
            event_type=CDCEventType.INVENTORY_CHANGED,
            version=version,
        )
        self.db.commit()
        return cdc_log, cdc_event

    def create_complete_supply_chain(
        self,
        num_skus: int = 1,
    ) -> Dict[str, Any]:
        category = self.category.create()
        product = self.product.create(category_id=category.id)
        skus = [self.sku.create(product_id=product.id) for _ in range(num_skus)]
        supplier = self.supplier.create()
        warehouse = self.warehouse.create()
        inventories = [
            self.inventory.create(
                sku_id=sku.id,
                warehouse_id=warehouse.id,
                quantity=100,
            )
            for sku in skus
        ]
        self.db.commit()

        return {
            "category": category,
            "product": product,
            "skus": skus,
            "supplier": supplier,
            "warehouse": warehouse,
            "inventories": inventories,
        }


def get_factory(db: Session) -> TestDataGenerator:
    return TestDataGenerator(db)
