from typing import Any, Optional


class InventoryException(Exception):
    def __init__(self, message: str, code: int = 400, details: Optional[dict[str, Any]] = None):
        self.message = message
        self.code = code
        self.details = details or {}
        super().__init__(self.message)


class InventoryNotFoundException(InventoryException):
    def __init__(self, inventory_id: Optional[int] = None, message: Optional[str] = None):
        msg = message or f"Inventory not found"
        if inventory_id:
            msg = f"Inventory with id {inventory_id} not found"
        super().__init__(msg, code=404, details={"inventory_id": inventory_id})


class InsufficientInventoryException(InventoryException):
    def __init__(
        self,
        sku_id: int,
        warehouse_id: int,
        requested: int,
        available: int,
        message: Optional[str] = None,
    ):
        msg = message or (
            f"Insufficient inventory for SKU {sku_id} in warehouse {warehouse_id}. "
            f"Requested: {requested}, Available: {available}"
        )
        super().__init__(
            msg,
            code=400,
            details={
                "sku_id": sku_id,
                "warehouse_id": warehouse_id,
                "requested": requested,
                "available": available,
            },
        )


class InventoryLockException(InventoryException):
    def __init__(self, inventory_id: int, message: Optional[str] = None):
        msg = message or f"Failed to acquire lock for inventory {inventory_id}"
        super().__init__(msg, code=409, details={"inventory_id": inventory_id})


class WarehouseNotFoundException(InventoryException):
    def __init__(self, warehouse_id: Optional[int] = None, message: Optional[str] = None):
        msg = message or "Warehouse not found"
        if warehouse_id:
            msg = f"Warehouse with id {warehouse_id} not found"
        super().__init__(msg, code=404, details={"warehouse_id": warehouse_id})


class WarehouseCapacityExceededException(InventoryException):
    def __init__(
        self,
        warehouse_id: int,
        current: int,
        capacity: int,
        requested: int,
        message: Optional[str] = None,
    ):
        msg = message or (
            f"Warehouse {warehouse_id} capacity exceeded. "
            f"Current: {current}, Capacity: {capacity}, Requested: {requested}"
        )
        super().__init__(
            msg,
            code=400,
            details={
                "warehouse_id": warehouse_id,
                "current": current,
                "capacity": capacity,
                "requested": requested,
            },
        )


class ZoneNotFoundException(InventoryException):
    def __init__(self, zone_id: Optional[int] = None, message: Optional[str] = None):
        msg = message or "Zone not found"
        if zone_id:
            msg = f"Zone with id {zone_id} not found"
        super().__init__(msg, code=404, details={"zone_id": zone_id})


class DuplicateCodeException(InventoryException):
    def __init__(self, code: str, entity: str, message: Optional[str] = None):
        msg = message or f"{entity} with code '{code}' already exists"
        super().__init__(msg, code=409, details={"code": code, "entity": entity})


class SupplierNotFoundException(InventoryException):
    def __init__(self, supplier_id: Optional[int] = None, message: Optional[str] = None):
        msg = message or "Supplier not found"
        if supplier_id:
            msg = f"Supplier with id {supplier_id} not found"
        super().__init__(msg, code=404, details={"supplier_id": supplier_id})


class SyncException(InventoryException):
    def __init__(self, sync_id: Optional[int] = None, message: Optional[str] = None):
        msg = message or "Sync operation failed"
        if sync_id:
            msg = f"Sync {sync_id} failed: {message}"
        super().__init__(msg, code=500, details={"sync_id": sync_id})


class SyncConflictException(InventoryException):
    def __init__(
        self,
        conflict_id: int,
        conflict_type: str,
        message: Optional[str] = None,
    ):
        msg = message or f"Sync conflict {conflict_id} of type {conflict_type} requires resolution"
        super().__init__(
            msg,
            code=409,
            details={"conflict_id": conflict_id, "conflict_type": conflict_type},
        )


class SyncDelayAlertException(InventoryException):
    def __init__(
        self,
        source_warehouse_id: int,
        target_warehouse_id: int,
        delay_seconds: int,
        threshold: int,
        message: Optional[str] = None,
    ):
        msg = message or (
            f"Sync delay alert from warehouse {source_warehouse_id} to {target_warehouse_id}. "
            f"Delay: {delay_seconds}s, Threshold: {threshold}s"
        )
        super().__init__(
            msg,
            code=409,
            details={
                "source_warehouse_id": source_warehouse_id,
                "target_warehouse_id": target_warehouse_id,
                "delay_seconds": delay_seconds,
                "threshold": threshold,
            },
        )


class InvalidTransactionException(InventoryException):
    def __init__(self, transaction_type: str, message: Optional[str] = None):
        msg = message or f"Invalid transaction type: {transaction_type}"
        super().__init__(msg, code=400, details={"transaction_type": transaction_type})


class ReservationExpiredException(InventoryException):
    def __init__(self, reservation_id: int, message: Optional[str] = None):
        msg = message or f"Reservation {reservation_id} has expired"
        super().__init__(msg, code=410, details={"reservation_id": reservation_id})


class ConcurrentModificationException(InventoryException):
    def __init__(
        self,
        entity: str,
        entity_id: int,
        message: Optional[str] = None,
    ):
        msg = message or f"Concurrent modification detected for {entity} {entity_id}"
        super().__init__(msg, code=409, details={"entity": entity, "entity_id": entity_id})


class CDCException(InventoryException):
    def __init__(self, message: str, cdc_log_id: Optional[int] = None):
        msg = f"CDC error: {message}"
        if cdc_log_id:
            msg = f"CDC error for log {cdc_log_id}: {message}"
        super().__init__(msg, code=500, details={"cdc_log_id": cdc_log_id})


class PurchaseOrderException(InventoryException):
    def __init__(self, message: str, code: int = 400, details: Optional[dict[str, Any]] = None):
        super().__init__(message, code=code, details=details)


class ApprovalException(InventoryException):
    def __init__(self, message: str, code: int = 400, details: Optional[dict[str, Any]] = None):
        super().__init__(message, code=code, details=details)
