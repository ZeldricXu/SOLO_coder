from typing import Optional, List
from datetime import datetime
from fastapi import APIRouter, UploadFile, File, HTTPException, Query, Depends, BackgroundTasks
from fastapi.responses import StreamingResponse, JSONResponse
from pydantic import BaseModel

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.common import APIResponse, PaginatedResponse
from app.schemas.document import (
    DocumentCreate,
    DocumentUpdate,
    DocumentResponse,
    ProcessingOptions,
    DocumentTypeEnum,
    DocumentStatusEnum,
    DocumentPriorityEnum,
)
from app.schemas.extraction import ExtractionSchema
from app.services.document_service import DocumentService
from app.tasks.document import process_document_task, process_document_high_priority_task

logger = get_logger(__name__)
settings = get_settings()

router = APIRouter(prefix="/documents", tags=["documents"])


def get_document_service() -> DocumentService:
    return DocumentService()


@router.post("/upload", response_model=APIResponse)
async def upload_document(
    file: UploadFile = File(...),
    priority: DocumentPriorityEnum = DocumentPriorityEnum.MEDIUM,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        file_data = await file.read()

        doc = document_service.create_document(
            file_data=file_data,
            original_filename=file.filename or "unknown",
            mime_type=file.content_type,
            priority=priority,
        )

        return APIResponse(
            success=True,
            message="Document uploaded successfully",
            data={
                "document_id": doc.id,
                "filename": doc.filename,
                "document_type": doc.document_type.value,
                "status": doc.status.value,
                "file_size": doc.file_size,
            },
        )

    except Exception as e:
        logger.error(f"Failed to upload document: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("", response_model=APIResponse[PaginatedResponse])
async def list_documents(
    status: Optional[DocumentStatusEnum] = None,
    doc_type: Optional[DocumentTypeEnum] = None,
    priority: Optional[DocumentPriorityEnum] = None,
    uploaded_after: Optional[datetime] = None,
    uploaded_before: Optional[datetime] = None,
    search: Optional[str] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        documents, total = document_service.list_documents(
            status=status,
            doc_type=doc_type,
            priority=priority,
            uploaded_after=uploaded_after,
            uploaded_before=uploaded_before,
            search=search,
            page=page,
            page_size=page_size,
        )

        items = [
            {
                "id": doc.id,
                "filename": doc.filename,
                "document_type": doc.document_type.value,
                "status": doc.status.value,
                "priority": doc.priority.value,
                "file_size": doc.file_size,
                "page_count": doc.page_count,
                "uploaded_at": doc.uploaded_at,
            }
            for doc in documents
        ]

        return APIResponse(
            success=True,
            data=PaginatedResponse(
                items=items,
                total=total,
                page=page,
                page_size=page_size,
                total_pages=(total + page_size - 1) // page_size,
            ),
        )

    except Exception as e:
        logger.error(f"Failed to list documents: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{document_id}", response_model=APIResponse)
async def get_document(
    document_id: int,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        doc = document_service.get_document_with_details(document_id)
        if not doc:
            raise HTTPException(status_code=404, detail="Document not found")

        return APIResponse(success=True, data=doc)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get document {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.put("/{document_id}", response_model=APIResponse)
async def update_document(
    document_id: int,
    update: DocumentUpdate,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        doc = document_service.update_document(document_id, update)
        if not doc:
            raise HTTPException(status_code=404, detail="Document not found")

        return APIResponse(
            success=True,
            message="Document updated successfully",
            data={
                "id": doc.id,
                "filename": doc.filename,
                "status": doc.status.value,
                "priority": doc.priority.value,
                "metadata": doc.metadata,
            },
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update document {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/{document_id}", response_model=APIResponse)
async def delete_document(
    document_id: int,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        success = document_service.delete_document(document_id)
        if not success:
            raise HTTPException(status_code=404, detail="Document not found")

        return APIResponse(
            success=True,
            message="Document deleted successfully",
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to delete document {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{document_id}/download")
async def download_document(
    document_id: int,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        result = document_service.get_document_file(document_id)
        if not result:
            raise HTTPException(status_code=404, detail="Document not found")

        filename, file_data = result

        return StreamingResponse(
            iter([file_data]),
            media_type="application/octet-stream",
            headers={"Content-Disposition": f"attachment; filename={filename}"},
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to download document {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{document_id}/url", response_model=APIResponse)
async def get_document_url(
    document_id: int,
    expires_in: int = Query(3600, ge=60, le=86400),
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        url = document_service.get_document_file_url(document_id, expires_in)
        if not url:
            raise HTTPException(status_code=404, detail="Document not found")

        return APIResponse(
            success=True,
            data={"url": url, "expires_in": expires_in},
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get document URL {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{document_id}/process", response_model=APIResponse)
async def process_document(
    document_id: int,
    options: Optional[ProcessingOptions] = None,
    extraction_schema: Optional[ExtractionSchema] = None,
    background_tasks: BackgroundTasks = None,
    async_processing: bool = True,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        doc = document_service.get_document(document_id)
        if not doc:
            raise HTTPException(status_code=404, detail="Document not found")

        if async_processing:
            options_dict = options.model_dump() if options else None
            schema_dict = extraction_schema.model_dump() if extraction_schema else None

            if doc.priority == DocumentPriorityEnum.HIGH:
                task = process_document_high_priority_task.delay(
                    document_id=document_id,
                    options=options_dict,
                    extraction_schema=schema_dict,
                )
            else:
                task = process_document_task.delay(
                    document_id=document_id,
                    options=options_dict,
                    extraction_schema=schema_dict,
                )

            return APIResponse(
                success=True,
                message="Processing started",
                data={
                    "task_id": task.id,
                    "document_id": document_id,
                },
            )
        else:
            result = document_service.process_document(
                document_id=document_id,
                options=options,
                extraction_schema=extraction_schema,
            )

            return APIResponse(
                success=True,
                message="Processing completed",
                data=result,
            )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to process document {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{document_id}/status", response_model=APIResponse)
async def get_processing_status(
    document_id: int,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        status = document_service.get_processing_status(document_id)
        if not status:
            raise HTTPException(status_code=404, detail="Document not found")

        return APIResponse(success=True, data=status)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get document status {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{document_id}/reprocess", response_model=APIResponse)
async def reprocess_document(
    document_id: int,
    options: Optional[ProcessingOptions] = None,
    async_processing: bool = True,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        doc = document_service.get_document(document_id)
        if not doc:
            raise HTTPException(status_code=404, detail="Document not found")

        if async_processing:
            options_dict = options.model_dump() if options else None

            if doc.priority == DocumentPriorityEnum.HIGH:
                task = process_document_high_priority_task.delay(
                    document_id=document_id,
                    options=options_dict,
                )
            else:
                task = process_document_task.delay(
                    document_id=document_id,
                    options=options_dict,
                )

            return APIResponse(
                success=True,
                message="Reprocessing started",
                data={
                    "task_id": task.id,
                    "document_id": document_id,
                },
            )
        else:
            result = document_service.reprocess_document(
                document_id=document_id,
                options=options,
            )

            return APIResponse(
                success=True,
                message="Reprocessing completed",
                data=result,
            )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to reprocess document {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{document_id}/extraction", response_model=APIResponse)
async def get_document_extraction(
    document_id: int,
    document_service: DocumentService = Depends(get_document_service),
):
    try:
        from app.services.extraction_service import ExtractionService

        extraction_service = ExtractionService()
        result = extraction_service.get_latest_extraction_result(document_id)

        if not result:
            raise HTTPException(status_code=404, detail="No extraction result found")

        return APIResponse(success=True, data=result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get extraction for document {document_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
