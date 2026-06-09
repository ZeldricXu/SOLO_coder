import asyncio
from concurrent import futures
from typing import Optional

import grpc
from sqlalchemy.orm import Session

from app.core.config import settings
from app.core.database import SessionLocal
from app.core.logging import get_logger
from app.grpc_api import inventory_pb2, inventory_pb2_grpc
from app.services.inventory_service import InventoryService
from app.services.sku_service import SKUService
from app.services.purchase_order_service import PurchaseOrderService

logger = get_logger(__name__)


def get_db_session() -> Session:
    return SessionLocal()


class InventoryGrpcService(inventory_pb2_grpc.InventoryServiceServicer):
    def __init__(self):
        self.db = get_db_session()
        self.inventory_service = InventoryService(self.db)

    def __del__(self):
        if hasattr(self, "db"):
            self.db.close()

    def GetInventory(self, request, context):
        try:
            inventory = self.inventory_service.get_by_sku_and_warehouse(
                sku_id=request.sku_id,
                warehouse_id=request.warehouse_id,
                zone_id=request.zone_id if request.HasField("zone_id") else None,
            )

            if not inventory:
                context.abort(grpc.StatusCode.NOT_FOUND, "Inventory not found")

            return inventory_pb2.InventoryResponse(
                inventory_id=inventory.id,
                sku_id=inventory.sku_id,
                warehouse_id=inventory.warehouse_id,
                zone_id=inventory.zone_id or 0,
                quantity=inventory.quantity,
                reserved_quantity=inventory.reserved_quantity or 0,
                allocated_quantity=inventory.allocated_quantity or 0,
                available_quantity=inventory.available_quantity or 0,
                in_transit_quantity=inventory.in_transit_quantity or 0,
                unit_cost=float(inventory.unit_cost) if inventory.unit_cost else 0.0,
                total_value=float(inventory.total_value) if inventory.total_value else 0.0,
                last_counted_at=inventory.last_counted_at.isoformat()
                if inventory.last_counted_at
                else None,
                created_at=inventory.created_at.isoformat(),
                updated_at=inventory.updated_at.isoformat(),
            )
        except Exception as e:
            logger.error("GetInventory gRPC error", error=str(e))
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    def UpdateInventory(self, request, context):
        try:
            from app.models.inventory_transaction import TransactionType

            inventory = self.inventory_service.adjust_inventory(
                sku_id=request.sku_id,
                warehouse_id=request.warehouse_id,
                zone_id=request.zone_id if request.HasField("zone_id") else None,
                quantity=request.quantity_change,
                transaction_type=TransactionType(request.transaction_type),
                reference_type=request.reference_type if request.HasField("reference_type") else None,
                reference_id=request.reference_id if request.HasField("reference_id") else None,
                batch_id=request.batch_id if request.HasField("batch_id") else None,
                serial_number=request.serial_number if request.HasField("serial_number") else None,
                reason=request.reason if request.HasField("reason") else None,
                created_by=request.operator_id,
            )

            self.db.commit()

            return inventory_pb2.InventoryResponse(
                inventory_id=inventory.id,
                sku_id=inventory.sku_id,
                warehouse_id=inventory.warehouse_id,
                zone_id=inventory.zone_id or 0,
                quantity=inventory.quantity,
                reserved_quantity=inventory.reserved_quantity or 0,
                allocated_quantity=inventory.allocated_quantity or 0,
                available_quantity=inventory.available_quantity or 0,
                in_transit_quantity=inventory.in_transit_quantity or 0,
                unit_cost=float(inventory.unit_cost) if inventory.unit_cost else 0.0,
                total_value=float(inventory.total_value) if inventory.total_value else 0.0,
                created_at=inventory.created_at.isoformat(),
                updated_at=inventory.updated_at.isoformat(),
            )
        except Exception as e:
            logger.error("UpdateInventory gRPC error", error=str(e))
            self.db.rollback()
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    def ListInventory(self, request, context):
        try:
            page = request.page if request.HasField("page") else 1
            page_size = request.page_size if request.HasField("page_size") else 20

            result = self.inventory_service.get_multi(
                db=self.db,
                page=page,
                page_size=page_size,
                filters={
                    "warehouse_id": request.warehouse_id if request.HasField("warehouse_id") else None,
                    "sku_id": request.sku_id if request.HasField("sku_id") else None,
                },
            )

            return inventory_pb2.ListInventoryResponse(
                items=[
                    inventory_pb2.InventoryResponse(
                        inventory_id=inv.id,
                        sku_id=inv.sku_id,
                        warehouse_id=inv.warehouse_id,
                        zone_id=inv.zone_id or 0,
                        quantity=inv.quantity,
                        reserved_quantity=inv.reserved_quantity or 0,
                        allocated_quantity=inv.allocated_quantity or 0,
                        available_quantity=inv.available_quantity or 0,
                        in_transit_quantity=inv.in_transit_quantity or 0,
                        unit_cost=float(inv.unit_cost) if inv.unit_cost else 0.0,
                        total_value=float(inv.total_value) if inv.total_value else 0.0,
                        created_at=inv.created_at.isoformat(),
                        updated_at=inv.updated_at.isoformat(),
                    )
                    for inv in result["items"]
                ],
                total=result["total"],
                page=page,
                page_size=page_size,
            )
        except Exception as e:
            logger.error("ListInventory gRPC error", error=str(e))
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    def TransferInventory(self, request, context):
        try:
            document = self.inventory_service.transfer_inventory(
                sku_id=request.sku_id,
                from_warehouse_id=request.from_warehouse_id,
                to_warehouse_id=request.to_warehouse_id,
                from_zone_id=request.from_zone_id if request.HasField("from_zone_id") else None,
                to_zone_id=request.to_zone_id if request.HasField("to_zone_id") else None,
                quantity=request.quantity,
                batch_id=request.batch_id if request.HasField("batch_id") else None,
                remark=request.remark if request.HasField("remark") else None,
                created_by=request.operator_id,
            )

            self.db.commit()

            return inventory_pb2.TransferResponse(
                success=True,
                document_no=document.document_no,
            )
        except Exception as e:
            logger.error("TransferInventory gRPC error", error=str(e))
            self.db.rollback()
            return inventory_pb2.TransferResponse(success=False, error=str(e))

    def ReserveInventory(self, request, context):
        try:
            success = self.inventory_service.reserve_inventory(
                sku_id=request.sku_id,
                warehouse_id=request.warehouse_id,
                quantity=request.quantity,
                reference_type=request.reference_type,
                reference_id=request.reference_id,
                batch_id=request.batch_id if request.HasField("batch_id") else None,
                created_by=request.operator_id,
            )

            self.db.commit()

            return inventory_pb2.ReserveResponse(success=success)
        except Exception as e:
            logger.error("ReserveInventory gRPC error", error=str(e))
            self.db.rollback()
            return inventory_pb2.ReserveResponse(success=False, error=str(e))

    def ReleaseReservation(self, request, context):
        try:
            success = self.inventory_service.release_reservation(
                sku_id=request.sku_id,
                warehouse_id=request.warehouse_id,
                quantity=request.quantity,
                reference_type=request.reference_type,
                reference_id=request.reference_id,
                created_by=request.operator_id,
            )

            self.db.commit()

            return inventory_pb2.ReleaseResponse(success=success)
        except Exception as e:
            logger.error("ReleaseReservation gRPC error", error=str(e))
            self.db.rollback()
            return inventory_pb2.ReleaseResponse(success=False, error=str(e))


class SKUGrpcService(inventory_pb2_grpc.SKUServiceServicer):
    def __init__(self):
        self.db = get_db_session()
        self.sku_service = SKUService(self.db)

    def __del__(self):
        if hasattr(self, "db"):
            self.db.close()

    def GetSKU(self, request, context):
        try:
            sku = self.sku_service.get(self.db, request.sku_id)
            if not sku:
                context.abort(grpc.StatusCode.NOT_FOUND, "SKU not found")

            return inventory_pb2.SKUResponse(
                sku_id=sku.id,
                sku_code=sku.sku_code,
                product_id=sku.product_id,
                cost_price=float(sku.cost_price) if sku.cost_price else 0.0,
                selling_price=float(sku.selling_price) if sku.selling_price else 0.0,
                status=sku.status.value,
                lifecycle_status=sku.lifecycle_status.value,
                safety_stock=sku.safety_stock or 0,
                reorder_point=sku.reorder_point or 0,
                created_at=sku.created_at.isoformat(),
                updated_at=sku.updated_at.isoformat(),
            )
        except Exception as e:
            logger.error("GetSKU gRPC error", error=str(e))
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    def CreateSKU(self, request, context):
        try:
            from app.schemas.product import SkuCreate

            sku_data = SkuCreate(
                product_id=request.product_id,
                sku_code=request.sku_code,
                attributes={attr.name: attr.value for attr in request.attributes},
                cost_price=request.cost_price,
                selling_price=request.selling_price,
                weight=request.weight if request.HasField("weight") else None,
                volume=request.volume if request.HasField("volume") else None,
                safety_stock=request.safety_stock,
                reorder_point=request.reorder_point,
                lead_time_days=request.lead_time_days,
            )

            sku = self.sku_service.create(self.db, sku_data)
            self.db.commit()

            return inventory_pb2.SKUResponse(
                sku_id=sku.id,
                sku_code=sku.sku_code,
                product_id=sku.product_id,
                cost_price=float(sku.cost_price) if sku.cost_price else 0.0,
                selling_price=float(sku.selling_price) if sku.selling_price else 0.0,
                status=sku.status.value,
                lifecycle_status=sku.lifecycle_status.value,
                safety_stock=sku.safety_stock or 0,
                reorder_point=sku.reorder_point or 0,
                created_at=sku.created_at.isoformat(),
                updated_at=sku.updated_at.isoformat(),
            )
        except Exception as e:
            logger.error("CreateSKU gRPC error", error=str(e))
            self.db.rollback()
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    def ListSKUs(self, request, context):
        try:
            page = request.page if request.HasField("page") else 1
            page_size = request.page_size if request.HasField("page_size") else 20

            filters = {}
            if request.HasField("product_id"):
                filters["product_id"] = request.product_id
            if request.HasField("status"):
                filters["status"] = request.status

            result = self.sku_service.get_multi(
                self.db, page=page, page_size=page_size, filters=filters
            )

            return inventory_pb2.ListSKUsResponse(
                items=[
                    inventory_pb2.SKUResponse(
                        sku_id=sku.id,
                        sku_code=sku.sku_code,
                        product_id=sku.product_id,
                        cost_price=float(sku.cost_price) if sku.cost_price else 0.0,
                        selling_price=float(sku.selling_price) if sku.selling_price else 0.0,
                        status=sku.status.value,
                        lifecycle_status=sku.lifecycle_status.value,
                        safety_stock=sku.safety_stock or 0,
                        reorder_point=sku.reorder_point or 0,
                        created_at=sku.created_at.isoformat(),
                        updated_at=sku.updated_at.isoformat(),
                    )
                    for sku in result["items"]
                ],
                total=result["total"],
                page=page,
                page_size=page_size,
            )
        except Exception as e:
            logger.error("ListSKUs gRPC error", error=str(e))
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    def GenerateSKUs(self, request, context):
        try:
            skus = self.sku_service.generate_skus_from_attributes(
                product_id=request.product_id,
                attribute_combinations=dict(request.attribute_combinations),
                base_cost_price=request.base_cost_price,
                base_selling_price=request.base_selling_price,
            )

            self.db.commit()

            return inventory_pb2.GenerateSKUsResponse(
                generated_count=len(skus),
                skus=[
                    inventory_pb2.SKUResponse(
                        sku_id=sku.id,
                        sku_code=sku.sku_code,
                        product_id=sku.product_id,
                        cost_price=float(sku.cost_price) if sku.cost_price else 0.0,
                        selling_price=float(sku.selling_price) if sku.selling_price else 0.0,
                        status=sku.status.value,
                        lifecycle_status=sku.lifecycle_status.value,
                        safety_stock=sku.safety_stock or 0,
                        reorder_point=sku.reorder_point or 0,
                        created_at=sku.created_at.isoformat(),
                        updated_at=sku.updated_at.isoformat(),
                    )
                    for sku in skus
                ],
            )
        except Exception as e:
            logger.error("GenerateSKUs gRPC error", error=str(e))
            self.db.rollback()
            context.abort(grpc.StatusCode.INTERNAL, str(e))


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))

    inventory_pb2_grpc.add_InventoryServiceServicer_to_server(InventoryGrpcService(), server)
    inventory_pb2_grpc.add_SKUServiceServicer_to_server(SKUGrpcService(), server)

    server.add_insecure_port(f"{settings.GRPC_SERVER_HOST}:{settings.GRPC_SERVER_PORT}")

    logger.info(
        "Starting gRPC server",
        host=settings.GRPC_SERVER_HOST,
        port=settings.GRPC_SERVER_PORT,
    )

    server.start()
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
