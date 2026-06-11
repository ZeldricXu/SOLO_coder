from app.routers.auth import router as auth_router
from app.routers.users import router as users_router
from app.routers.roles import router as roles_router
from app.routers.sku import router as sku_router
from app.routers.attribute import router as attribute_router
from app.routers.product import router as product_router
from app.routers.warehouse import router as warehouse_router
from app.routers.inventory import router as inventory_router
from app.routers.purchase_order import router as purchase_order_router
from app.routers.approval import router as approval_router
from app.routers.alert import router as alert_router
from app.routers.replenishment import router as replenishment_router
from app.routers.batch import router as batch_router
from app.routers.serial import router as serial_router
from app.routers.document import router as document_router
from app.routers.stocktake import router as stocktake_router
from app.routers.audit import router as audit_router
from app.routers.health import router as health_router
from app.routers.supplier import router as supplier_router
from app.routers.import_export import router as import_export_router
from app.routers.sync_strategy import router as sync_strategy_router

__all__ = [
    "auth_router",
    "users_router",
    "roles_router",
    "sku_router",
    "attribute_router",
    "product_router",
    "warehouse_router",
    "inventory_router",
    "purchase_order_router",
    "approval_router",
    "alert_router",
    "replenishment_router",
    "batch_router",
    "serial_router",
    "document_router",
    "stocktake_router",
    "audit_router",
    "health_router",
    "supplier_router",
    "import_export_router",
    "sync_strategy_router",
]
