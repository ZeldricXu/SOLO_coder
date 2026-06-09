from typing import Optional, List
from datetime import datetime
from fastapi import APIRouter, HTTPException, Query, Depends, UploadFile, File
from pydantic import BaseModel

from app.core.config import get_settings
from app.core.logging_config import get_logger
from app.schemas.common import APIResponse, PaginatedResponse
from app.schemas.extraction import (
    ExtractionStatusEnum,
    ExtractionSchemaCreate,
    ExtractionSchemaUpdate,
    ExtractionSchemaResponse,
    ExtractionSchemaWithStats,
)
from app.services.extraction_service import ExtractionService
from app.services.schema_service import ExtractionSchemaService

logger = get_logger(__name__)
settings = get_settings()

router = APIRouter(prefix="/extractions", tags=["extractions"])


class YAMLUploadRequest(BaseModel):
    created_by: str = "admin"


def get_extraction_service() -> ExtractionService:
    return ExtractionService()


def get_schema_service() -> ExtractionSchemaService:
    return ExtractionSchemaService()


@router.get("", response_model=APIResponse[PaginatedResponse])
async def list_extraction_results(
    document_id: Optional[int] = None,
    status: Optional[ExtractionStatusEnum] = None,
    model_version: Optional[str] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        results, total = extraction_service.list_extraction_results(
            document_id=document_id,
            status=status,
            model_version=model_version,
            page=page,
            page_size=page_size,
        )

        return APIResponse(
            success=True,
            data=PaginatedResponse(
                items=results,
                total=total,
                page=page,
                page_size=page_size,
                total_pages=(total + page_size - 1) // page_size,
            ),
        )

    except Exception as e:
        logger.error(f"Failed to list extraction results: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{extraction_id}", response_model=APIResponse)
async def get_extraction_result(
    extraction_id: int,
    include_fields: bool = True,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        result = extraction_service.get_extraction_result(
            extraction_id,
            include_fields=include_fields,
        )
        if not result:
            raise HTTPException(status_code=404, detail="Extraction result not found")

        return APIResponse(success=True, data=result)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get extraction result {extraction_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/{extraction_id}", response_model=APIResponse)
async def delete_extraction_result(
    extraction_id: int,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        success = extraction_service.delete_extraction_result(extraction_id)
        if not success:
            raise HTTPException(status_code=404, detail="Extraction result not found")

        return APIResponse(
            success=True,
            message="Extraction result deleted successfully",
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to delete extraction result {extraction_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/compare/{result_id_a}/{result_id_b}", response_model=APIResponse)
async def compare_extraction_results(
    result_id_a: int,
    result_id_b: int,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        comparison = extraction_service.compare_extraction_results(
            result_id_a,
            result_id_b,
        )

        return APIResponse(success=True, data=comparison)

    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to compare extraction results: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/statistics", response_model=APIResponse)
async def get_extraction_statistics(
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        stats = extraction_service.get_extraction_statistics(
            start_date=start_date,
            end_date=end_date,
        )

        return APIResponse(success=True, data=stats)

    except Exception as e:
        logger.error(f"Failed to get extraction statistics: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/history/{document_id}/{field_name}", response_model=APIResponse)
async def get_field_history(
    document_id: int,
    field_name: str,
    extraction_service: ExtractionService = Depends(get_extraction_service),
):
    try:
        history = extraction_service.get_field_history(
            document_id=document_id,
            field_name=field_name,
        )

        return APIResponse(success=True, data={"field_name": field_name, "history": history})

    except Exception as e:
        logger.error(f"Failed to get field history: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schemas", response_model=APIResponse[PaginatedResponse])
async def list_schemas(
    business_line: Optional[str] = None,
    document_type: Optional[str] = None,
    is_active: Optional[bool] = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        schemas = schema_service.list_schemas(
            business_line=business_line,
            document_type=document_type,
            is_active=is_active,
            skip=(page - 1) * page_size,
            limit=page_size,
        )

        total = len(schemas)

        return APIResponse(
            success=True,
            data=PaginatedResponse(
                items=[s.model_dump() for s in schemas],
                total=total,
                page=page,
                page_size=page_size,
                total_pages=(total + page_size - 1) // page_size,
            ),
        )

    except Exception as e:
        logger.error(f"Failed to list schemas: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schemas/{schema_id}", response_model=APIResponse[ExtractionSchemaResponse])
async def get_schema(
    schema_id: int,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        schema = schema_service.get_schema(schema_id)
        if not schema:
            raise HTTPException(status_code=404, detail="Schema not found")

        return APIResponse(success=True, data=schema)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get schema {schema_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schemas/name/{schema_name}", response_model=APIResponse[ExtractionSchemaResponse])
async def get_schema_by_name(
    schema_name: str,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        schema = schema_service.get_schema_by_name(schema_name)
        if not schema:
            raise HTTPException(status_code=404, detail="Schema not found")

        return APIResponse(success=True, data=schema)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get schema by name {schema_name}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schemas/default", response_model=APIResponse[ExtractionSchemaResponse])
async def get_default_schema(
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        schema = schema_service.get_default_schema()
        if not schema:
            raise HTTPException(status_code=404, detail="Default schema not found")

        return APIResponse(success=True, data=schema)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get default schema: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/schemas", response_model=APIResponse[ExtractionSchemaResponse])
async def create_schema(
    schema_in: ExtractionSchemaCreate,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        existing = schema_service.get_schema_by_name(schema_in.schema_name)
        if existing:
            raise HTTPException(
                status_code=400,
                detail=f"Schema with name '{schema_in.schema_name}' already exists",
            )

        schema = schema_service.create_schema(schema_in)
        return APIResponse(success=True, data=schema, message="Schema created successfully")

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to create schema: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/schemas/upload/yaml", response_model=APIResponse[ExtractionSchemaResponse])
async def upload_schema_yaml(
    file: UploadFile = File(...),
    created_by: str = "admin",
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        content = await file.read()
        yaml_content = content.decode("utf-8")

        schema = schema_service.load_schema_from_yaml_content(yaml_content, created_by=created_by)
        return APIResponse(
            success=True,
            data=schema,
            message=f"Schema loaded from {file.filename} successfully",
        )

    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(f"Failed to upload schema YAML: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.put("/schemas/{schema_id}", response_model=APIResponse[ExtractionSchemaResponse])
async def update_schema(
    schema_id: int,
    schema_in: ExtractionSchemaUpdate,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        schema = schema_service.update_schema(schema_id, schema_in)
        if not schema:
            raise HTTPException(status_code=404, detail="Schema not found")

        return APIResponse(success=True, data=schema, message="Schema updated successfully")

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to update schema {schema_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/schemas/{schema_id}", response_model=APIResponse)
async def delete_schema(
    schema_id: int,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        success = schema_service.delete_schema(schema_id)
        if not success:
            raise HTTPException(
                status_code=404,
                detail="Schema not found or cannot delete default schema",
            )

        return APIResponse(success=True, message="Schema deleted successfully")

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to delete schema {schema_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.put("/schemas/{schema_id}/set-default", response_model=APIResponse[ExtractionSchemaResponse])
async def set_default_schema(
    schema_id: int,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        schema = schema_service.set_default_schema(schema_id)
        if not schema:
            raise HTTPException(status_code=404, detail="Schema not found")

        return APIResponse(
            success=True,
            data=schema,
            message="Default schema updated successfully",
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to set default schema {schema_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schemas/{schema_id}/stats", response_model=APIResponse[ExtractionSchemaWithStats])
async def get_schema_stats(
    schema_id: int,
    start_date: Optional[datetime] = None,
    end_date: Optional[datetime] = None,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        stats = schema_service.get_schema_with_stats(schema_id, start_date, end_date)
        if not stats:
            raise HTTPException(status_code=404, detail="Schema not found")

        return APIResponse(success=True, data=stats)

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to get schema stats {schema_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schemas/{schema_id}/export/yaml", response_model=APIResponse)
async def export_schema_yaml(
    schema_id: int,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        yaml_content = schema_service.export_schema_to_yaml(schema_id)
        if not yaml_content:
            raise HTTPException(status_code=404, detail="Schema not found")

        return APIResponse(
            success=True,
            data={"yaml_content": yaml_content},
            message="Schema exported successfully",
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Failed to export schema YAML {schema_id}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/schemas/reload-directory", response_model=APIResponse)
async def reload_schemas_from_directory(
    directory: Optional[str] = None,
    schema_service: ExtractionSchemaService = Depends(get_schema_service),
):
    try:
        schema_service.clear_cache()
        loaded_schemas = schema_service.load_schemas_from_directory(directory)

        return APIResponse(
            success=True,
            data={
                "loaded_count": len(loaded_schemas),
                "loaded_schemas": [s.model_dump() for s in loaded_schemas],
            },
            message=f"Successfully loaded {len(loaded_schemas)} schemas from directory",
        )

    except Exception as e:
        logger.error(f"Failed to reload schemas from directory: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))
