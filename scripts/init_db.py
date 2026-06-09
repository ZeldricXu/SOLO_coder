#!/usr/bin/env python3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from datetime import datetime
from sqlalchemy.orm import Session

from app.core.database import engine, Base, SessionLocal
from app.core.security import get_password_hash
from app.core.config import settings
from app.core.logging import configure_logging, get_logger
from app.models import (
    User,
    Role,
    Permission,
    Warehouse,
    Zone,
    Category,
    Attribute,
    AttributeTemplate,
    Product,
    SKU,
    Supplier,
    ApprovalWorkflow,
    ApprovalNode,
    AlertRule,
)

configure_logging()
logger = get_logger(__name__)


def create_tables():
    logger.info("Creating database tables...")
    Base.metadata.create_all(bind=engine)
    logger.info("Database tables created successfully")


def init_permissions(db: Session):
    logger.info("Initializing permissions...")

    permissions = [
        {"name": "查看用户", "code": "user:read", "resource_type": "user", "action": "read"},
        {"name": "创建用户", "code": "user:create", "resource_type": "user", "action": "create"},
        {"name": "更新用户", "code": "user:update", "resource_type": "user", "action": "update"},
        {"name": "删除用户", "code": "user:delete", "resource_type": "user", "action": "delete"},
        {"name": "查看角色", "code": "role:read", "resource_type": "role", "action": "read"},
        {"name": "管理角色", "code": "role:manage", "resource_type": "role", "action": "manage"},
        {"name": "查看SKU", "code": "sku:read", "resource_type": "sku", "action": "read"},
        {"name": "创建SKU", "code": "sku:create", "resource_type": "sku", "action": "create"},
        {"name": "更新SKU", "code": "sku:update", "resource_type": "sku", "action": "update"},
        {"name": "删除SKU", "code": "sku:delete", "resource_type": "sku", "action": "delete"},
        {"name": "查看库存", "code": "inventory:read", "resource_type": "inventory", "action": "read"},
        {"name": "调整库存", "code": "inventory:adjust", "resource_type": "inventory", "action": "adjust"},
        {"name": "库存调拨", "code": "inventory:transfer", "resource_type": "inventory", "action": "transfer"},
        {"name": "查看采购订单", "code": "purchase_order:read", "resource_type": "purchase_order", "action": "read"},
        {"name": "创建采购订单", "code": "purchase_order:create", "resource_type": "purchase_order", "action": "create"},
        {"name": "审批采购订单", "code": "purchase_order:approve", "resource_type": "purchase_order", "action": "approve"},
        {"name": "入库收货", "code": "purchase_order:receive", "resource_type": "purchase_order", "action": "receive"},
        {"name": "查看审批", "code": "approval:read", "resource_type": "approval", "action": "read"},
        {"name": "处理审批", "code": "approval:process", "resource_type": "approval", "action": "process"},
        {"name": "配置工作流", "code": "workflow:manage", "resource_type": "workflow", "action": "manage"},
        {"name": "查看预警", "code": "alert:read", "resource_type": "alert", "action": "read"},
        {"name": "处理预警", "code": "alert:process", "resource_type": "alert", "action": "process"},
        {"name": "配置预警规则", "code": "alert_rule:manage", "resource_type": "alert_rule", "action": "manage"},
        {"name": "查看补货建议", "code": "replenishment:read", "resource_type": "replenishment", "action": "read"},
        {"name": "生成补货建议", "code": "replenishment:generate", "resource_type": "replenishment", "action": "generate"},
        {"name": "审批补货建议", "code": "replenishment:approve", "resource_type": "replenishment", "action": "approve"},
        {"name": "查看批次", "code": "batch:read", "resource_type": "batch", "action": "read"},
        {"name": "管理批次", "code": "batch:manage", "resource_type": "batch", "action": "manage"},
        {"name": "查看序列号", "code": "serial:read", "resource_type": "serial", "action": "read"},
        {"name": "管理序列号", "code": "serial:manage", "resource_type": "serial", "action": "manage"},
        {"name": "产品追溯", "code": "trace:query", "resource_type": "trace", "action": "query"},
        {"name": "查看单据", "code": "document:read", "resource_type": "document", "action": "read"},
        {"name": "创建单据", "code": "document:create", "resource_type": "document", "action": "create"},
        {"name": "确认单据", "code": "document:confirm", "resource_type": "document", "action": "confirm"},
        {"name": "查看盘点", "code": "stocktake:read", "resource_type": "stocktake", "action": "read"},
        {"name": "创建盘点计划", "code": "stocktake:create", "resource_type": "stocktake", "action": "create"},
        {"name": "执行盘点", "code": "stocktake:execute", "resource_type": "stocktake", "action": "execute"},
        {"name": "审批调整单", "code": "stocktake:approve", "resource_type": "stocktake", "action": "approve"},
        {"name": "查看审计日志", "code": "audit:read", "resource_type": "audit", "action": "read"},
        {"name": "导出审计日志", "code": "audit:export", "resource_type": "audit", "action": "export"},
        {"name": "管理仓库", "code": "warehouse:manage", "resource_type": "warehouse", "action": "manage"},
        {"name": "管理供应商", "code": "supplier:manage", "resource_type": "supplier", "action": "manage"},
    ]

    for perm_data in permissions:
        if not db.query(Permission).filter(Permission.code == perm_data["code"]).first():
            perm = Permission(**perm_data)
            db.add(perm)

    db.commit()
    logger.info(f"Initialized {len(permissions)} permissions")


def init_roles(db: Session):
    logger.info("Initializing roles...")

    roles = [
        {
            "name": "超级管理员",
            "code": "super_admin",
            "description": "系统超级管理员，拥有所有权限",
            "permissions": db.query(Permission).all(),
        },
        {
            "name": "库存管理员",
            "code": "inventory_manager",
            "description": "负责库存日常管理",
            "permission_codes": [
                "inventory:read",
                "inventory:adjust",
                "inventory:transfer",
                "sku:read",
                "batch:read",
                "batch:manage",
                "serial:read",
                "serial:manage",
                "document:read",
                "document:create",
                "document:confirm",
                "trace:query",
            ],
        },
        {
            "name": "采购专员",
            "code": "purchase_officer",
            "description": "负责采购订单管理",
            "permission_codes": [
                "purchase_order:read",
                "purchase_order:create",
                "purchase_order:receive",
                "replenishment:read",
                "replenishment:generate",
                "supplier:manage",
                "sku:read",
                "inventory:read",
            ],
        },
        {
            "name": "财务审批",
            "code": "finance_approver",
            "description": "负责财务审批",
            "permission_codes": [
                "purchase_order:read",
                "purchase_order:approve",
                "approval:read",
                "approval:process",
                "replenishment:read",
                "replenishment:approve",
                "stocktake:read",
                "stocktake:approve",
            ],
        },
        {
            "name": "仓管员",
            "code": "warehouse_keeper",
            "description": "负责仓库日常操作",
            "permission_codes": [
                "inventory:read",
                "document:read",
                "document:create",
                "document:confirm",
                "batch:read",
                "serial:read",
                "stocktake:read",
                "stocktake:execute",
                "trace:query",
            ],
        },
        {
            "name": "运营分析",
            "code": "operations_analyst",
            "description": "负责库存数据分析",
            "permission_codes": [
                "inventory:read",
                "sku:read",
                "purchase_order:read",
                "alert:read",
                "replenishment:read",
                "batch:read",
                "serial:read",
                "document:read",
                "stocktake:read",
                "audit:read",
            ],
        },
    ]

    for role_data in roles:
        if not db.query(Role).filter(Role.code == role_data["code"]).first():
            role = Role(
                name=role_data["name"],
                code=role_data["code"],
                description=role_data["description"],
            )
            if "permissions" in role_data:
                role.permissions = role_data["permissions"]
            elif "permission_codes" in role_data:
                perms = (
                    db.query(Permission)
                    .filter(Permission.code.in_(role_data["permission_codes"]))
                    .all()
                )
                role.permissions = perms
            db.add(role)

    db.commit()
    logger.info(f"Initialized {len(roles)} roles")


def init_super_admin(db: Session):
    logger.info("Initializing super admin user...")

    if not db.query(User).filter(User.username == "admin").first():
        admin_role = db.query(Role).filter(Role.code == "super_admin").first()

        admin = User(
            username="admin",
            email="admin@inventory.com",
            hashed_password=get_password_hash("Admin@123456"),
            full_name="系统管理员",
            phone="13800000000",
            is_active=True,
            is_superuser=True,
            roles=[admin_role] if admin_role else [],
        )
        db.add(admin)
        db.commit()
        logger.info("Super admin created: admin / Admin@123456")
    else:
        logger.info("Super admin already exists")


def init_sample_data(db: Session):
    logger.info("Initializing sample data...")

    if db.query(Warehouse).count() == 0:
        warehouses = [
            {
                "name": "上海总仓",
                "code": "WH-SH-001",
                "warehouse_type": "MAIN",
                "address": "上海市浦东新区张江高科技园区",
                "city": "上海",
                "province": "上海市",
                "country": "中国",
                "postal_code": "201203",
                "contact_person": "张经理",
                "contact_phone": "13800000001",
                "contact_email": "zhang@inventory.com",
                "capacity": 100000,
            },
            {
                "name": "广州分仓",
                "code": "WH-GZ-001",
                "warehouse_type": "BRANCH",
                "address": "广州市天河区珠江新城",
                "city": "广州",
                "province": "广东省",
                "country": "中国",
                "postal_code": "510620",
                "contact_person": "李经理",
                "contact_phone": "13800000002",
                "contact_email": "li@inventory.com",
                "capacity": 50000,
            },
            {
                "name": "北京分仓",
                "code": "WH-BJ-001",
                "warehouse_type": "BRANCH",
                "address": "北京市朝阳区亦庄经济开发区",
                "city": "北京",
                "province": "北京市",
                "country": "中国",
                "postal_code": "100176",
                "contact_person": "王经理",
                "contact_phone": "13800000003",
                "contact_email": "wang@inventory.com",
                "capacity": 50000,
            },
        ]

        for wh_data in warehouses:
            wh = Warehouse(**wh_data)
            db.add(wh)
            db.flush()

            zones = [
                {"name": "仓储区A", "code": f"ZONE-A-{wh.code}", "storage_type": "NORMAL"},
                {"name": "仓储区B", "code": f"ZONE-B-{wh.code}", "storage_type": "BULK"},
                {"name": "冷藏区", "code": f"ZONE-C-{wh.code}", "storage_type": "COLD"},
                {"name": "贵重品区", "code": f"ZONE-V-{wh.code}", "storage_type": "VALUABLES"},
            ]
            for zone_data in zones:
                zone = Zone(**zone_data, warehouse_id=wh.id)
                db.add(zone)

        db.commit()
        logger.info(f"Created {len(warehouses)} warehouses and {len(warehouses) * 4} zones")

    if db.query(Category).count() == 0:
        categories = [
            {"name": "电子产品", "code": "CAT-ELEC", "level": 1, "sort_order": 1},
            {"name": "服装鞋帽", "code": "CAT-CLOTH", "level": 1, "sort_order": 2},
            {"name": "食品饮料", "code": "CAT-FOOD", "level": 1, "sort_order": 3},
            {"name": "家居用品", "code": "CAT-HOME", "level": 1, "sort_order": 4},
            {"name": "美妆个护", "code": "CAT-BEAUTY", "level": 1, "sort_order": 5},
        ]

        for cat_data in categories:
            cat = Category(**cat_data)
            db.add(cat)

        db.commit()
        logger.info(f"Created {len(categories)} categories")

    if db.query(Attribute).count() == 0:
        attributes = [
            {
                "name": "颜色",
                "code": "COLOR",
                "data_type": "SELECT",
                "options": ["红色", "蓝色", "绿色", "黑色", "白色", "灰色"],
                "is_required": True,
                "is_searchable": True,
                "is_filterable": True,
            },
            {
                "name": "尺寸",
                "code": "SIZE",
                "data_type": "SELECT",
                "options": ["XS", "S", "M", "L", "XL", "XXL", "28", "29", "30", "31", "32"],
                "is_required": True,
                "is_searchable": True,
                "is_filterable": True,
            },
            {
                "name": "容量",
                "code": "CAPACITY",
                "data_type": "SELECT",
                "options": ["16GB", "32GB", "64GB", "128GB", "256GB", "512GB", "1TB"],
                "is_required": True,
                "is_searchable": True,
                "is_filterable": True,
            },
            {
                "name": "材质",
                "code": "MATERIAL",
                "data_type": "SELECT",
                "options": ["纯棉", "涤纶", "丝绸", "羊毛", "真皮", "人造革", "塑料", "金属"],
                "is_required": False,
                "is_searchable": True,
                "is_filterable": True,
            },
            {
                "name": "保质期",
                "code": "SHELF_LIFE",
                "data_type": "NUMBER",
                "is_required": False,
                "is_searchable": False,
                "is_filterable": True,
            },
        ]

        for attr_data in attributes:
            attr = Attribute(**attr_data)
            db.add(attr)

        db.commit()
        logger.info(f"Created {len(attributes)} attributes")

    if db.query(Supplier).count() == 0:
        suppliers = [
            {
                "name": "深圳电子科技有限公司",
                "code": "SUP-ELEC-001",
                "contact_person": "陈总",
                "contact_phone": "13900000001",
                "contact_email": "chen@supplier.com",
                "address": "深圳市南山区科技园",
                "city": "深圳",
                "province": "广东省",
                "country": "中国",
                "credit_rating": "A",
                "payment_terms": "NET30",
                "lead_time_days": 15,
                "minimum_order_qty": 100,
            },
            {
                "name": "杭州服装制造有限公司",
                "code": "SUP-CLOTH-001",
                "contact_person": "刘总",
                "contact_phone": "13900000002",
                "contact_email": "liu@supplier.com",
                "address": "杭州市余杭区服装工业园",
                "city": "杭州",
                "province": "浙江省",
                "country": "中国",
                "credit_rating": "A",
                "payment_terms": "NET45",
                "lead_time_days": 20,
                "minimum_order_qty": 50,
            },
            {
                "name": "广州食品有限公司",
                "code": "SUP-FOOD-001",
                "contact_person": "赵总",
                "contact_phone": "13900000003",
                "contact_email": "zhao@supplier.com",
                "address": "广州市白云区食品工业园",
                "city": "广州",
                "province": "广东省",
                "country": "中国",
                "credit_rating": "B",
                "payment_terms": "NET15",
                "lead_time_days": 7,
                "minimum_order_qty": 200,
            },
        ]

        for sup_data in suppliers:
            sup = Supplier(**sup_data)
            db.add(sup)

        db.commit()
        logger.info(f"Created {len(suppliers)} suppliers")

    if db.query(ApprovalWorkflow).count() == 0:
        purchase_role = db.query(Role).filter(Role.code == "purchase_officer").first()
        finance_role = db.query(Role).filter(Role.code == "finance_approver").first()

        workflows = [
            {
                "name": "采购订单审批流",
                "code": "PO_APPROVAL",
                "resource_type": "PURCHASE_ORDER",
                "description": "采购订单三级审批：采购专员→财务审批→总经理",
                "nodes": [
                    {
                        "node_name": "开始",
                        "node_type": "START",
                        "approval_type": "AND",
                        "sort_order": 1,
                    },
                    {
                        "node_name": "采购经理审核",
                        "node_type": "APPROVAL",
                        "approval_type": "AND",
                        "required_role_id": purchase_role.id if purchase_role else None,
                        "sort_order": 2,
                    },
                    {
                        "node_name": "财务审批",
                        "node_type": "APPROVAL",
                        "approval_type": "AND",
                        "required_role_id": finance_role.id if finance_role else None,
                        "sort_order": 3,
                    },
                    {
                        "node_name": "结束",
                        "node_type": "END",
                        "approval_type": "AND",
                        "sort_order": 4,
                    },
                ],
            }
        ]

        for wf_data in workflows:
            nodes_data = wf_data.pop("nodes")
            wf = ApprovalWorkflow(**wf_data)
            db.add(wf)
            db.flush()

            for node_data in nodes_data:
                node = ApprovalNode(**node_data, workflow_id=wf.id)
                db.add(node)

        db.commit()
        logger.info("Created approval workflows")

    if db.query(AlertRule).count() == 0:
        alert_rules = [
            {
                "name": "低库存预警",
                "rule_type": "LOW_STOCK",
                "threshold_type": "QUANTITY",
                "threshold_value": 50,
                "warning_value": 30,
                "critical_value": 10,
                "is_active": True,
                "notify_channels": ["email", "webhook"],
            },
            {
                "name": "缺货预警",
                "rule_type": "OUT_OF_STOCK",
                "threshold_type": "QUANTITY",
                "threshold_value": 0,
                "is_active": True,
                "notify_channels": ["email"],
            },
            {
                "name": "临期预警(30天)",
                "rule_type": "EXPIRING",
                "threshold_type": "DAYS",
                "threshold_value": 30,
                "is_active": True,
                "notify_channels": ["email"],
            },
            {
                "name": "高库存预警(超过最高库存)",
                "rule_type": "HIGH_STOCK",
                "threshold_type": "PERCENTAGE",
                "threshold_value": 120,
                "is_active": True,
                "notify_channels": ["webhook"],
            },
        ]

        for rule_data in alert_rules:
            rule = AlertRule(**rule_data)
            db.add(rule)

        db.commit()
        logger.info(f"Created {len(alert_rules)} alert rules")


def main():
    logger.info("=" * 60)
    logger.info("Inventory Management Platform - Database Initialization")
    logger.info("=" * 60)

    try:
        create_tables()
        db = SessionLocal()
        try:
            init_permissions(db)
            init_roles(db)
            init_super_admin(db)
            init_sample_data(db)

            logger.info("=" * 60)
            logger.info("Database initialization completed successfully!")
            logger.info("=" * 60)
            logger.info("Default super admin: admin / Admin@123456")
            logger.info("=" * 60)
        finally:
            db.close()
    except Exception as e:
        logger.error("Database initialization failed", error=str(e))
        sys.exit(1)


if __name__ == "__main__":
    main()
