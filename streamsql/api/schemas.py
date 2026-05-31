from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field


class BaseResponse(BaseModel):
    code: int = Field(200, description="Response code")
    message: str = Field("success", description="Response message")


class ResourceCreateRequest(BaseModel):
    type: str = Field(..., description="Resource type")
    config: dict[str, Any] = Field(default_factory=dict, description="Resource config")
    labels: dict[str, str] = Field(default_factory=dict, description="Resource labels")


class ResourceResponse(BaseModel):
    id: str = Field(..., description="Resource ID")
    status: str = Field(..., description="Resource status")
    type: str = Field(..., description="Resource type")
    config: dict[str, Any] = Field(default_factory=dict)
    progress: float = Field(0.0, description="Progress percentage")
    created_at: datetime = Field(default_factory=datetime.utcnow)


class ResourceStatusResponse(BaseResponse):
    data: ResourceResponse


class ResourceListResponse(BaseResponse):
    data: list[ResourceResponse]
    total: int = 0


class BatchOperationRequest(BaseModel):
    operations: list[dict[str, Any]] = Field(..., description="List of operations")


class BatchOperationResponse(BaseResponse):
    data: dict[str, Any]


class MetadataCrawlRequest(BaseModel):
    data_source: dict[str, Any] = Field(..., description="Data source configuration")
    scan_tables: Optional[list[str]] = None
    sample_size: int = 1000


class MetadataCrawlResponse(BaseResponse):
    data: dict[str, Any]


class CDCCaptureRequest(BaseModel):
    source_config: dict[str, Any] = Field(..., description="CDC source config")
    output_config: dict[str, Any] = Field(..., description="Output config")
    serializer_format: str = "json"


class CDCCaptureResponse(BaseResponse):
    data: dict[str, Any]


class SQLParseRequest(BaseModel):
    sql: str = Field(..., description="SQL to parse")
    optimize: bool = True


class SQLParseResponse(BaseResponse):
    data: dict[str, Any]


class VectorIndexRequest(BaseModel):
    texts: list[str] = Field(..., description="Texts to index")
    index_type: str = "hnsw"
    embedding_model: str = "mock"


class VectorIndexResponse(BaseResponse):
    data: dict[str, Any]


class VectorSearchRequest(BaseModel):
    query_text: str
    top_k: int = 10


class VectorSearchResponse(BaseResponse):
    data: dict[str, Any]


class LifecyclePolicyRequest(BaseModel):
    table_name: str
    hot_ttl_days: int = 30
    cold_ttl_days: int = 90
    archive_ttl_days: int = 365
    auto_cleanup: bool = True


class LifecyclePolicyResponse(BaseResponse):
    data: dict[str, Any]


class LineageExtractRequest(BaseModel):
    sql: str | list[str] = Field(..., description="SQL or list of SQL")


class LineageResponse(BaseResponse):
    data: dict[str, Any]


class LineageImpactRequest(BaseModel):
    table_name: str


class LineageImpactResponse(BaseResponse):
    data: dict[str, Any]


class TimeSeriesCompressRequest(BaseModel):
    timestamps: list[int]
    values: list[float]
    encoder_type: str = "gorilla"


class TimeSeriesCompressResponse(BaseResponse):
    data: dict[str, Any]


class TimeSeriesQueryRequest(BaseModel):
    start_time: Optional[int] = None
    end_time: Optional[int] = None
    resolution: str = "raw"


class QualityRuleRequest(BaseModel):
    rule_type: str
    name: str
    column: str
    table: str
    parameters: dict[str, Any] = Field(default_factory=dict)
    severity: str = "error"


class QualityRuleResponse(BaseResponse):
    data: dict[str, Any]


class QualityValidateRequest(BaseModel):
    data: list[dict[str, Any]] = Field(..., description="Data to validate")
    table_name: str = ""
    rule_ids: Optional[list[str]] = None


class QualityValidateResponse(BaseResponse):
    data: dict[str, Any]


class QualityReportResponse(BaseResponse):
    data: dict[str, Any]
