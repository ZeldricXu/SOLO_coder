import logging
from typing import Optional, Any
from sqlalchemy import and_, func, or_
from sqlalchemy.orm import Session, selectinload

from app.models.inventory_document import (
    InventoryDocument,
    DocumentItem,
    DocumentType,
    DocumentStatus,
)
from app.models.inventory_transaction import (
    InventoryTransaction,
    TransactionType,
)
from app.models.sku import SKU
from app.models.batch import Batch
from app.schemas.document import (
    DocumentCreate,
    DocumentUpdate,
    DocumentItemCreate,
    DocumentConfirmRequest,
    DocumentCompleteRequest,
    ScanItemRequest,
    ScanItemResponse,
    BatchScanRequest,
    BatchScanResponse,
    DocumentListFilter,
    DocumentDetail,
    DocumentTraceResponse,
    DocumentTraceItem,
    DocumentStatisticsResponse,
)
from app.utils.exceptions import (
    InventoryException,
    InvalidTransactionException,
)
from app.utils.helpers import (
    get_current_utc_time,
    generate_code,
    calculate_total_value,
)
from app.core.cache import cache
from app.core.audit import AuditLogger
from app.services.inventory_service import InventoryService
from app.services.warehouse_service import WarehouseService

logger = logging.getLogger(__name__)

DOCUMENT_CODE_PREFIX = "DOC"
DOCUMENT_LOCK_TIMEOUT = 300


class DocumentService:
    def __init__(self, db: Session, user_id: Optional[int] = None):
        self.db = db
        self.user_id = user_id
        self.audit_logger = AuditLogger(db)
        self.inventory_service = InventoryService(db)
        self.warehouse_service = WarehouseService(db)

    def _get_lock_key(self, document_id: int) -> str:
        return f"document:lock:{document_id}"

    def _acquire_lock(self, document_id: int) -> bool:
        lock_key = self._get_lock_key(document_id)
        return cache.get_client().set(
            lock_key, "1", ex=DOCUMENT_LOCK_TIMEOUT, nx=True
        )

    def _release_lock(self, document_id: int) -> None:
        lock_key = self._get_lock_key(document_id)
        cache.delete(lock_key)

    def _generate_document_no(self, doc_type: DocumentType) -> str:
        type_prefix = {
            DocumentType.PURCHASE_IN: "PI",
            DocumentType.SALES_OUT: "SO",
            DocumentType.TRANSFER: "TR",
            DocumentType.STOCKTAKE: "ST",
            DocumentType.DAMAGE: "DA",
        }.get(doc_type, "DOC")
        return generate_code(f"{type_prefix}", 8)

    def _validate_status_transition(
        self, current_status: DocumentStatus, target_status: DocumentStatus
    ) -> bool:
        valid_transitions = {
            DocumentStatus.DRAFT: [
                DocumentStatus.CONFIRMED,
                DocumentStatus.CANCELLED,
            ],
            DocumentStatus.CONFIRMED: [
                DocumentStatus.PROCESSING,
                DocumentStatus.CANCELLED,
            ],
            DocumentStatus.PROCESSING: [
                DocumentStatus.COMPLETED,
                DocumentStatus.CANCELLED,
            ],
            DocumentStatus.COMPLETED: [],
            DocumentStatus.CANCELLED: [],
        }
        return target_status in valid_transitions.get(current_status, [])

    def _get_transaction_type(self, doc_type: DocumentType) -> TransactionType:
        return {
            DocumentType.PURCHASE_IN: TransactionType.IN,
            DocumentType.SALES_OUT: TransactionType.OUT,
            DocumentType.TRANSFER: TransactionType.TRANSFER,
            DocumentType.STOCKTAKE: TransactionType.COUNT,
            DocumentType.DAMAGE: TransactionType.OUT,
        }.get(doc_type, TransactionType.ADJUSTMENT)

    def _get_document_data(self, doc: InventoryDocument) -> dict[str, Any]:
        return {
            "id": doc.id,
            "document_no": doc.document_no,
            "document_type": doc.document_type.value,
            "warehouse_id": doc.warehouse_id,
            "status": doc.status.value,
            "total_quantity": doc.total_quantity,
            "total_amount": float(doc.total_amount),
        }

    def _calculate_totals(self, items: list[DocumentItem]) -> tuple[int, float]:
        total_qty = sum(item.quantity for item in items)
        total_amount = sum(float(item.total_cost) for item in items)
        return total_qty, total_amount

    def get_document(self, document_id: int, lock: bool = False) -> InventoryDocument:
        if lock and not self._acquire_lock(document_id):
            raise InventoryException(
                f"Document {document_id} is locked by another operation",
                code=409,
            )

        doc = self.db.query(InventoryDocument).filter(
            InventoryDocument.id == document_id
        ).first()

        if not doc:
            if lock:
                self._release_lock(document_id)
            raise InventoryException(
                f"Document {document_id} not found", code=404
            )

        return doc

    def get_document_detail(self, document_id: int) -> DocumentDetail:
        doc = (
            self.db.query(InventoryDocument)
            .options(
                selectinload(InventoryDocument.items),
                selectinload(InventoryDocument.warehouse),
                selectinload(InventoryDocument.target_warehouse),
            )
            .filter(InventoryDocument.id == document_id)
            .first()
        )

        if not doc:
            raise InventoryException(
                f"Document {document_id} not found", code=404
            )

        items = []
        for item in doc.items:
            sku = self.db.query(SKU).filter(SKU.id == item.sku_id).first()
            batch = (
                self.db.query(Batch).filter(Batch.id == item.batch_id).first()
                if item.batch_id
                else None
            )
            items.append(
                DocumentItem(
                    id=item.id,
                    document_id=item.document_id,
                    sku_id=item.sku_id,
                    batch_id=item.batch_id,
                    serial_numbers=item.serial_numbers,
                    quantity=item.quantity,
                    actual_quantity=item.actual_quantity,
                    unit_cost=float(item.unit_cost),
                    total_cost=float(item.total_cost),
                    remark=item.remark,
                    created_at=item.created_at,
                    sku_code=sku.sku_code if sku else None,
                    sku_name=sku.product.name if sku and sku.product else None,
                    batch_no=batch.batch_no if batch else None,
                )
            )

        return DocumentDetail(
            id=doc.id,
            document_no=doc.document_no,
            document_type=doc.document_type,
            warehouse_id=doc.warehouse_id,
            target_warehouse_id=doc.target_warehouse_id,
            status=doc.status,
            total_quantity=doc.total_quantity,
            total_amount=float(doc.total_amount),
            remark=doc.remark,
            reference_type=doc.reference_type,
            reference_id=doc.reference_id,
            created_by=doc.created_by,
            created_at=doc.created_at,
            updated_at=doc.updated_at,
            confirmed_by=doc.confirmed_by,
            confirmed_at=doc.confirmed_at,
            completed_by=doc.completed_by,
            completed_at=doc.completed_at,
            items=items,
            warehouse_name=doc.warehouse.name if doc.warehouse else None,
            target_warehouse_name=(
                doc.target_warehouse.name if doc.target_warehouse else None
            ),
            created_by_name=None,
        )

    def list_documents(
        self,
        filters: DocumentListFilter,
        skip: int = 0,
        limit: int = 20,
        sort_by: Optional[str] = None,
        sort_order: str = "desc",
    ) -> list[InventoryDocument]:
        query = self.db.query(InventoryDocument)

        if filters.document_type:
            query = query.filter(
                InventoryDocument.document_type == filters.document_type
            )
        if filters.status:
            query = query.filter(InventoryDocument.status == filters.status)
        if filters.warehouse_id:
            query = query.filter(
                InventoryDocument.warehouse_id == filters.warehouse_id
            )
        if filters.target_warehouse_id:
            query = query.filter(
                InventoryDocument.target_warehouse_id
                == filters.target_warehouse_id
            )
        if filters.created_by:
            query = query.filter(InventoryDocument.created_by == filters.created_by)
        if filters.start_date:
            query = query.filter(
                InventoryDocument.created_at >= filters.start_date
            )
        if filters.end_date:
            query = query.filter(
                InventoryDocument.created_at <= filters.end_date
            )
        if filters.document_no:
            query = query.filter(
                InventoryDocument.document_no.like(f"%{filters.document_no}%")
            )
        if filters.reference_type:
            query = query.filter(
                InventoryDocument.reference_type == filters.reference_type
            )
        if filters.reference_id:
            query = query.filter(
                InventoryDocument.reference_id == filters.reference_id
            )

        if sort_by and hasattr(InventoryDocument, sort_by):
            sort_column = getattr(InventoryDocument, sort_by)
            query = (
                query.order_by(sort_column.desc())
                if sort_order == "desc"
                else query.order_by(sort_column.asc())
            )
        else:
            query = query.order_by(InventoryDocument.id.desc())

        return query.offset(skip).limit(limit).all()

    def count_documents(self, filters: DocumentListFilter) -> int:
        query = self.db.query(func.count(InventoryDocument.id))

        if filters.document_type:
            query = query.filter(
                InventoryDocument.document_type == filters.document_type
            )
        if filters.status:
            query = query.filter(InventoryDocument.status == filters.status)
        if filters.warehouse_id:
            query = query.filter(
                InventoryDocument.warehouse_id == filters.warehouse_id
            )
        if filters.target_warehouse_id:
            query = query.filter(
                InventoryDocument.target_warehouse_id
                == filters.target_warehouse_id
            )
        if filters.created_by:
            query = query.filter(InventoryDocument.created_by == filters.created_by)
        if filters.start_date:
            query = query.filter(
                InventoryDocument.created_at >= filters.start_date
            )
        if filters.end_date:
            query = query.filter(
                InventoryDocument.created_at <= filters.end_date
            )
        if filters.document_no:
            query = query.filter(
                InventoryDocument.document_no.like(f"%{filters.document_no}%")
            )
        if filters.reference_type:
            query = query.filter(
                InventoryDocument.reference_type == filters.reference_type
            )
        if filters.reference_id:
            query = query.filter(
                InventoryDocument.reference_id == filters.reference_id
            )

        return query.scalar() or 0

    def create_document(self, doc_in: DocumentCreate) -> InventoryDocument:
        self.warehouse_service.get_warehouse(doc_in.warehouse_id)

        if doc_in.target_warehouse_id:
            self.warehouse_service.get_warehouse(doc_in.target_warehouse_id)

        document_no = self._generate_document_no(doc_in.document_type)

        doc = InventoryDocument(
            document_no=document_no,
            document_type=doc_in.document_type,
            warehouse_id=doc_in.warehouse_id,
            target_warehouse_id=doc_in.target_warehouse_id,
            status=DocumentStatus.DRAFT,
            remark=doc_in.remark,
            reference_type=doc_in.reference_type,
            reference_id=doc_in.reference_id,
            created_by=self.user_id,
            created_at=get_current_utc_time(),
            updated_at=get_current_utc_time(),
        )

        self.db.add(doc)
        self.db.flush()

        total_qty = 0
        total_amount = 0.0
        for item_in in doc_in.items:
            sku = self.db.query(SKU).filter(SKU.id == item_in.sku_id).first()
            if not sku:
                raise InventoryException(
                    f"SKU {item_in.sku_id} not found", code=404
                )

            unit_cost = item_in.unit_cost or float(sku.cost_price)
            total_cost = calculate_total_value(item_in.quantity, unit_cost)

            item = DocumentItem(
                document_id=doc.id,
                sku_id=item_in.sku_id,
                batch_id=item_in.batch_id,
                serial_numbers=item_in.serial_numbers,
                quantity=item_in.quantity,
                actual_quantity=item_in.quantity,
                unit_cost=unit_cost,
                total_cost=total_cost,
                remark=item_in.remark,
                created_at=get_current_utc_time(),
            )
            self.db.add(item)
            total_qty += item_in.quantity
            total_amount += total_cost

        doc.total_quantity = total_qty
        doc.total_amount = total_amount

        self.db.flush()
        self.db.refresh(doc)

        self.audit_logger.log(
            user_id=self.user_id,
            action="create",
            resource_type="document",
            resource_id=doc.id,
            new_value=self._get_document_data(doc),
        )

        cache.delete_pattern("document:list:*")

        logger.info(
            f"Document created: id={doc.id}, no={doc.document_no}, "
            f"type={doc.document_type}, created_by={self.user_id}"
        )

        return doc

    def update_document(
        self, document_id: int, doc_in: DocumentUpdate
    ) -> InventoryDocument:
        doc = self.get_document(document_id, lock=True)
        try:
            if doc.status not in [DocumentStatus.DRAFT]:
                raise InvalidTransactionException(
                    "DOCUMENT_UPDATE",
                    f"Cannot update document in status {doc.status}",
                )

            old_data = self._get_document_data(doc)

            if doc_in.warehouse_id:
                self.warehouse_service.get_warehouse(doc_in.warehouse_id)
                doc.warehouse_id = doc_in.warehouse_id

            if doc_in.target_warehouse_id:
                self.warehouse_service.get_warehouse(doc_in.target_warehouse_id)
                doc.target_warehouse_id = doc_in.target_warehouse_id

            if doc_in.remark is not None:
                doc.remark = doc_in.remark
            if doc_in.reference_type is not None:
                doc.reference_type = doc_in.reference_type
            if doc_in.reference_id is not None:
                doc.reference_id = doc_in.reference_id

            doc.updated_at = get_current_utc_time()

            self.db.flush()
            self.db.refresh(doc)

            self.audit_logger.log(
                user_id=self.user_id,
                action="update",
                resource_type="document",
                resource_id=doc.id,
                old_value=old_data,
                new_value=self._get_document_data(doc),
            )

            cache.delete(f"document:{document_id}")
            cache.delete_pattern("document:list:*")

            logger.info(
                f"Document updated: id={doc.id}, status={doc.status}"
            )

            return doc
        finally:
            self._release_lock(document_id)

    def delete_document(self, document_id: int) -> None:
        doc = self.get_document(document_id, lock=True)
        try:
            if doc.status not in [DocumentStatus.DRAFT, DocumentStatus.CANCELLED]:
                raise InvalidTransactionException(
                    "DOCUMENT_DELETE",
                    f"Cannot delete document in status {doc.status}",
                )

            old_data = self._get_document_data(doc)

            self.db.delete(doc)
            self.db.flush()

            self.audit_logger.log(
                user_id=self.user_id,
                action="delete",
                resource_type="document",
                resource_id=document_id,
                old_value=old_data,
            )

            cache.delete(f"document:{document_id}")
            cache.delete_pattern("document:list:*")

            logger.info(f"Document deleted: id={document_id}")
        finally:
            self._release_lock(document_id)

    def confirm_document(
        self, document_id: int, request: DocumentConfirmRequest
    ) -> InventoryDocument:
        doc = self.get_document(document_id, lock=True)
        try:
            if not self._validate_status_transition(
                doc.status, DocumentStatus.CONFIRMED
            ):
                raise InvalidTransactionException(
                    "DOCUMENT_CONFIRM",
                    f"Cannot confirm document from status {doc.status}",
                )

            if not doc.items:
                raise InventoryException(
                    "Document has no items, cannot confirm", code=400
                )

            old_data = self._get_document_data(doc)

            doc.status = DocumentStatus.CONFIRMED
            doc.confirmed_by = self.user_id
            doc.confirmed_at = get_current_utc_time()
            doc.updated_at = get_current_utc_time()

            self.db.flush()
            self.db.refresh(doc)

            self.audit_logger.log(
                user_id=self.user_id,
                action="confirm",
                resource_type="document",
                resource_id=doc.id,
                old_value=old_data,
                new_value=self._get_document_data(doc),
            )

            cache.delete(f"document:{document_id}")
            cache.delete_pattern("document:list:*")

            logger.info(
                f"Document confirmed: id={doc.id}, no={doc.document_no}"
            )

            return doc
        finally:
            self._release_lock(document_id)

    def complete_document(
        self, document_id: int, request: DocumentCompleteRequest
    ) -> InventoryDocument:
        doc = self.get_document(document_id, lock=True)
        try:
            if not self._validate_status_transition(
                doc.status, DocumentStatus.COMPLETED
            ):
                if doc.status == DocumentStatus.CONFIRMED:
                    doc.status = DocumentStatus.PROCESSING

            if doc.status != DocumentStatus.PROCESSING:
                raise InvalidTransactionException(
                    "DOCUMENT_COMPLETE",
                    f"Cannot complete document from status {doc.status}",
                )

            old_data = self._get_document_data(doc)

            self._process_inventory_transactions(doc, request)

            doc.status = DocumentStatus.COMPLETED
            doc.completed_by = self.user_id
            doc.completed_at = get_current_utc_time()
            doc.updated_at = get_current_utc_time()

            self.db.flush()
            self.db.refresh(doc)

            self.audit_logger.log(
                user_id=self.user_id,
                action="complete",
                resource_type="document",
                resource_id=doc.id,
                old_value=old_data,
                new_value=self._get_document_data(doc),
            )

            cache.delete(f"document:{document_id}")
            cache.delete_pattern("document:list:*")

            logger.info(
                f"Document completed: id={doc.id}, no={doc.document_no}"
            )

            return doc
        finally:
            self._release_lock(document_id)

    def cancel_document(self, document_id: int) -> InventoryDocument:
        doc = self.get_document(document_id, lock=True)
        try:
            if not self._validate_status_transition(
                doc.status, DocumentStatus.CANCELLED
            ):
                raise InvalidTransactionException(
                    "DOCUMENT_CANCEL",
                    f"Cannot cancel document from status {doc.status}",
                )

            old_data = self._get_document_data(doc)

            doc.status = DocumentStatus.CANCELLED
            doc.updated_at = get_current_utc_time()

            self.db.flush()
            self.db.refresh(doc)

            self.audit_logger.log(
                user_id=self.user_id,
                action="cancel",
                resource_type="document",
                resource_id=doc.id,
                old_value=old_data,
                new_value=self._get_document_data(doc),
            )

            cache.delete(f"document:{document_id}")
            cache.delete_pattern("document:list:*")

            logger.info(
                f"Document cancelled: id={doc.id}, no={doc.document_no}"
            )

            return doc
        finally:
            self._release_lock(document_id)

    def _process_inventory_transactions(
        self,
        doc: InventoryDocument,
        request: DocumentCompleteRequest,
    ) -> None:
        transaction_type = self._get_transaction_type(doc.document_type)

        for item in doc.items:
            actual_qty = item.actual_quantity or item.quantity

            if doc.document_type in [
                DocumentType.PURCHASE_IN,
            ]:
                self.inventory_service.process_inbound(
                    sku_id=item.sku_id,
                    warehouse_id=doc.warehouse_id,
                    zone_id=1,
                    quantity=actual_qty,
                    unit_cost=float(item.unit_cost),
                    batch_id=str(item.batch_id) if item.batch_id else None,
                    reference_type="DOCUMENT",
                    reference_id=doc.id,
                    reason=f"Document {doc.document_no}",
                    created_by=self.user_id,
                )

            elif doc.document_type in [
                DocumentType.SALES_OUT,
                DocumentType.DAMAGE,
            ]:
                self.inventory_service.process_outbound(
                    sku_id=item.sku_id,
                    warehouse_id=doc.warehouse_id,
                    zone_id=1,
                    quantity=actual_qty,
                    reference_type="DOCUMENT",
                    reference_id=doc.id,
                    reason=f"Document {doc.document_no}",
                    created_by=self.user_id,
                )

            elif doc.document_type == DocumentType.TRANSFER:
                if not doc.target_warehouse_id:
                    raise InventoryException(
                        "Transfer document requires target_warehouse_id",
                        code=400,
                    )
                from app.schemas.warehouse import InventoryTransferRequest

                transfer_request = InventoryTransferRequest(
                    source_warehouse_id=doc.warehouse_id,
                    source_zone_id=1,
                    target_warehouse_id=doc.target_warehouse_id,
                    target_zone_id=1,
                    sku_id=item.sku_id,
                    quantity=actual_qty,
                    reason=f"Document {doc.document_no}",
                )
                self.inventory_service.transfer_inventory(
                    transfer_request, created_by=self.user_id
                )

            elif doc.document_type == DocumentType.STOCKTAKE:
                pass

    def add_document_item(
        self, document_id: int, item_in: DocumentItemCreate
    ) -> DocumentItem:
        doc = self.get_document(document_id, lock=True)
        try:
            if doc.status != DocumentStatus.DRAFT:
                raise InvalidTransactionException(
                    "ADD_ITEM",
                    f"Cannot add item to document in status {doc.status}",
                )

            sku = self.db.query(SKU).filter(SKU.id == item_in.sku_id).first()
            if not sku:
                raise InventoryException(
                    f"SKU {item_in.sku_id} not found", code=404
                )

            unit_cost = item_in.unit_cost or float(sku.cost_price)
            total_cost = calculate_total_value(item_in.quantity, unit_cost)

            item = DocumentItem(
                document_id=doc.id,
                sku_id=item_in.sku_id,
                batch_id=item_in.batch_id,
                serial_numbers=item_in.serial_numbers,
                quantity=item_in.quantity,
                actual_quantity=item_in.quantity,
                unit_cost=unit_cost,
                total_cost=total_cost,
                remark=item_in.remark,
                created_at=get_current_utc_time(),
            )
            self.db.add(item)
            self.db.flush()
            self.db.refresh(item)

            doc.total_quantity += item_in.quantity
            doc.total_amount += total_cost
            doc.updated_at = get_current_utc_time()

            self.db.flush()

            self.audit_logger.log(
                user_id=self.user_id,
                action="add_item",
                resource_type="document_item",
                resource_id=item.id,
                new_value={
                    "document_id": doc.id,
                    "sku_id": item.sku_id,
                    "quantity": item.quantity,
                },
            )

            cache.delete(f"document:{document_id}")
            cache.delete_pattern("document:list:*")

            logger.info(
                f"Item added to document: doc_id={document_id}, item_id={item.id}"
            )

            return item
        finally:
            self._release_lock(document_id)

    def delete_document_item(self, document_id: int, item_id: int) -> None:
        doc = self.get_document(document_id, lock=True)
        try:
            if doc.status != DocumentStatus.DRAFT:
                raise InvalidTransactionException(
                    "DELETE_ITEM",
                    f"Cannot delete item from document in status {doc.status}",
                )

            item = (
                self.db.query(DocumentItem)
                .filter(
                    and_(
                        DocumentItem.id == item_id,
                        DocumentItem.document_id == document_id,
                    )
                )
                .first()
            )

            if not item:
                raise InventoryException(
                    f"Document item {item_id} not found", code=404
                )

            doc.total_quantity -= item.quantity
            doc.total_amount -= float(item.total_cost)
            doc.updated_at = get_current_utc_time()

            self.db.delete(item)
            self.db.flush()

            self.audit_logger.log(
                user_id=self.user_id,
                action="delete_item",
                resource_type="document_item",
                resource_id=item_id,
                old_value={
                    "document_id": document_id,
                    "sku_id": item.sku_id,
                    "quantity": item.quantity,
                },
            )

            cache.delete(f"document:{document_id}")
            cache.delete_pattern("document:list:*")

            logger.info(
                f"Item deleted from document: doc_id={document_id}, item_id={item_id}"
            )
        finally:
            self._release_lock(document_id)

    def scan_item(self, scan_request: ScanItemRequest) -> ScanItemResponse:
        sku = (
            self.db.query(SKU)
            .filter(
                or_(
                    SKU.sku_code == scan_request.barcode,
                )
            )
            .first()
        )

        if not sku:
            return ScanItemResponse(
                success=False,
                quantity=0,
                message=f"Barcode {scan_request.barcode} not found",
            )

        document_id = scan_request.document_id

        if not document_id and scan_request.warehouse_id and scan_request.document_type:
            doc_create = DocumentCreate(
                document_type=scan_request.document_type,
                warehouse_id=scan_request.warehouse_id,
                items=[],
            )
            doc = self.create_document(doc_create)
            document_id = doc.id

        if document_id:
            item_create = DocumentItemCreate(
                sku_id=sku.id,
                quantity=scan_request.quantity,
                batch_id=None,
                serial_numbers=(
                    [scan_request.serial_number]
                    if scan_request.serial_number
                    else None
                ),
            )
            self.add_document_item(document_id, item_create)

        return ScanItemResponse(
            success=True,
            sku_id=sku.id,
            sku_code=sku.sku_code,
            sku_name=sku.product.name if sku.product else None,
            quantity=scan_request.quantity,
            message="Scan successful",
        )

    def batch_scan(self, batch_request: BatchScanRequest) -> BatchScanResponse:
        results: list[ScanItemResponse] = []
        success_count = 0
        failed_count = 0

        for item in batch_request.items:
            try:
                result = self.scan_item(item)
                if result.success:
                    success_count += 1
                else:
                    failed_count += 1
                results.append(result)
            except Exception as e:
                failed_count += 1
                results.append(
                    ScanItemResponse(
                        success=False,
                        quantity=0,
                        message=str(e),
                    )
                )

        return BatchScanResponse(
            success_count=success_count,
            failed_count=failed_count,
            items=results,
        )

    def get_document_trace(
        self, document_id: int
    ) -> DocumentTraceResponse:
        doc = self.get_document(document_id)

        transactions = (
            self.db.query(InventoryTransaction)
            .filter(
                and_(
                    InventoryTransaction.reference_type == "DOCUMENT",
                    InventoryTransaction.reference_id == document_id,
                )
            )
            .order_by(InventoryTransaction.created_at.desc())
            .all()
        )

        items = []
        for trx in transactions:
            sku = self.db.query(SKU).filter(SKU.id == trx.sku_id).first()
            batch = (
                self.db.query(Batch).filter(Batch.id == int(trx.batch_id)).first()
                if trx.batch_id and trx.batch_id.isdigit()
                else None
            )

            items.append(
                DocumentTraceItem(
                    transaction_id=trx.id,
                    transaction_type=trx.transaction_type.value,
                    sku_id=trx.sku_id,
                    sku_code=sku.sku_code if sku else None,
                    quantity=trx.quantity,
                    batch_id=int(trx.batch_id) if trx.batch_id and trx.batch_id.isdigit() else None,
                    batch_no=batch.batch_no if batch else None,
                    serial_number=trx.serial_number,
                    created_at=trx.created_at,
                    created_by=trx.created_by,
                )
            )

        return DocumentTraceResponse(
            document_id=doc.id,
            document_no=doc.document_no,
            items=items,
        )

    def get_print_template(self, document_id: int) -> dict[str, Any]:
        doc = self.get_document_detail(document_id)

        template = {
            "template_type": doc.document_type.value,
            "content": {
                "document_no": doc.document_no,
                "document_type": doc.document_type.value,
                "warehouse_name": doc.warehouse_name,
                "target_warehouse_name": doc.target_warehouse_name,
                "status": doc.status.value,
                "created_at": doc.created_at.isoformat(),
                "items": [
                    {
                        "sku_code": item.sku_code,
                        "sku_name": item.sku_name,
                        "quantity": item.quantity,
                        "unit_cost": item.unit_cost,
                        "total_cost": item.total_cost,
                    }
                    for item in doc.items
                ],
                "total_quantity": doc.total_quantity,
                "total_amount": doc.total_amount,
                "remark": doc.remark,
            },
        }

        return template

    def get_statistics(
        self, filters: Optional[DocumentListFilter] = None
    ) -> DocumentStatisticsResponse:
        if filters is None:
            filters = DocumentListFilter()

        base_query = self.db.query(InventoryDocument)

        if filters.warehouse_id:
            base_query = base_query.filter(
                InventoryDocument.warehouse_id == filters.warehouse_id
            )
        if filters.start_date:
            base_query = base_query.filter(
                InventoryDocument.created_at >= filters.start_date
            )
        if filters.end_date:
            base_query = base_query.filter(
                InventoryDocument.created_at <= filters.end_date
            )

        total_count = base_query.count()
        draft_count = base_query.filter(
            InventoryDocument.status == DocumentStatus.DRAFT
        ).count()
        confirmed_count = base_query.filter(
            InventoryDocument.status == DocumentStatus.CONFIRMED
        ).count()
        processing_count = base_query.filter(
            InventoryDocument.status == DocumentStatus.PROCESSING
        ).count()
        completed_count = base_query.filter(
            InventoryDocument.status == DocumentStatus.COMPLETED
        ).count()
        cancelled_count = base_query.filter(
            InventoryDocument.status == DocumentStatus.CANCELLED
        ).count()

        total_amount = (
            base_query.with_entities(
                func.coalesce(func.sum(InventoryDocument.total_amount), 0.0)
            ).scalar()
            or 0.0
        )

        by_type = {}
        for doc_type in DocumentType:
            count = base_query.filter(
                InventoryDocument.document_type == doc_type
            ).count()
            if count > 0:
                by_type[doc_type.value] = count

        return DocumentStatisticsResponse(
            total_count=total_count,
            draft_count=draft_count,
            confirmed_count=confirmed_count,
            processing_count=processing_count,
            completed_count=completed_count,
            cancelled_count=cancelled_count,
            total_amount=float(total_amount),
            by_type=by_type,
        )


def create_document_service(db: Session, user_id: Optional[int] = None) -> DocumentService:
    return DocumentService(db, user_id)
