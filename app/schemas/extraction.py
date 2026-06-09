from typing import List, Optional, Dict, Any
from datetime import datetime
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict

from app.schemas.common import BoundingBox, ValidationError


class FieldDataTypeEnum(str, Enum):
    STRING = "string"
    NUMBER = "number"
    DATE = "date"
    BOOLEAN = "boolean"
    LIST = "list"
    OBJECT = "object"


class ExtractionStatusEnum(str, Enum):
    PENDING = "pending"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    NEEDS_REVIEW = "needs_review"


class FieldValidationStatusEnum(str, Enum):
    VALID = "valid"
    WARNING = "warning"
    ERROR = "error"
    UNCHECKED = "unchecked"


class FieldSchema(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    field_name: str
    field_type: FieldDataTypeEnum = Field(default=FieldDataTypeEnum.STRING, alias="data_type")
    description: Optional[str] = None
    required: bool = False
    default_value: Optional[Any] = None
    validation_rules: Optional[Dict[str, Any]] = None
    examples: Optional[List[str]] = None

    @property
    def data_type(self) -> FieldDataTypeEnum:
        return self.field_type

    def __getitem__(self, key):
        if key == "data_type":
            return self.field_type
        return getattr(self, key)

    def get(self, key, default=None):
        if key == "data_type":
            return self.field_type
        return getattr(self, key, default)


class ExtractionSchemaBase(BaseModel):
    schema_name: str
    schema_version: str = "1.0"
    description: Optional[str] = None
    business_line: Optional[str] = None
    document_types: Optional[List[str]] = None
    fields: List[FieldSchema] = Field(default_factory=list)
    is_active: bool = True
    is_default: bool = False
    created_by: Optional[str] = None
    yaml_source_path: Optional[str] = None
    yaml_content: Optional[str] = None


ExtractionSchema = ExtractionSchemaBase


class ExtractionSchemaCreate(ExtractionSchemaBase):
    pass


class ExtractionSchemaUpdate(BaseModel):
    schema_name: Optional[str] = None
    schema_version: Optional[str] = None
    description: Optional[str] = None
    business_line: Optional[str] = None
    document_types: Optional[List[str]] = None
    fields: Optional[List[FieldSchema]] = None
    is_active: Optional[bool] = None
    is_default: Optional[bool] = None
    yaml_content: Optional[str] = None


class ExtractionSchemaResponse(ExtractionSchemaBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    created_at: datetime
    updated_at: datetime


class ExtractionSchemaWithStats(ExtractionSchemaResponse):
    usage_count: int = 0
    average_confidence: Optional[float] = None
    last_used_at: Optional[datetime] = None


class ExtractedFieldBase(BaseModel):
    field_name: str
    field_type: FieldDataTypeEnum = FieldDataTypeEnum.STRING
    value: Optional[str] = None
    normalized_value: Optional[str] = None
    confidence: float = 0.0
    is_low_confidence: bool = False
    page_number: Optional[int] = None
    bounding_box: Optional[BoundingBox] = None
    text_block: Optional[str] = None
    validation_status: FieldValidationStatusEnum = FieldValidationStatusEnum.UNCHECKED
    validation_errors: Optional[List[ValidationError]] = None
    suggested_value: Optional[str] = None


class ExtractedFieldCreate(ExtractedFieldBase):
    extraction_result_id: int


class ExtractedFieldResponse(ExtractedFieldBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    reviewed: bool = False
    reviewed_value: Optional[str] = None
    reviewed_by: Optional[str] = None
    reviewed_at: Optional[datetime] = None
    is_used_for_training: bool = False
    created_at: datetime
    updated_at: datetime


class ExtractedFieldUpdate(BaseModel):
    value: Optional[str] = None
    normalized_value: Optional[str] = None
    reviewed: Optional[bool] = None
    reviewed_value: Optional[str] = None
    reviewed_by: Optional[str] = None
    validation_status: Optional[FieldValidationStatusEnum] = None
    is_used_for_training: Optional[bool] = None


class ExtractionResultBase(BaseModel):
    document_id: int
    schema_name: str
    schema_version: Optional[str] = None
    status: ExtractionStatusEnum = ExtractionStatusEnum.PENDING
    model_name: Optional[str] = None
    model_version: Optional[str] = None
    is_ab_test: bool = False
    ab_test_group: Optional[str] = None


class ExtractionResultCreate(ExtractionResultBase):
    model_version_id: Optional[int] = None


class ExtractionResultUpdate(BaseModel):
    status: Optional[ExtractionStatusEnum] = None
    overall_confidence: Optional[float] = None
    processing_time: Optional[float] = None
    raw_extraction: Optional[Dict[str, Any]] = None
    structured_output: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None


class ExtractionResultResponse(ExtractionResultBase):
    model_config = ConfigDict(from_attributes=True)

    id: int
    overall_confidence: float = 0.0
    processing_time: Optional[float] = None
    raw_extraction: Optional[Dict[str, Any]] = None
    structured_output: Optional[Dict[str, Any]] = None
    extracted_fields: List[ExtractedFieldResponse] = Field(default_factory=list)
    error_message: Optional[str] = None
    created_at: datetime
    updated_at: datetime


class ExtractionResultDetailResponse(ExtractionResultResponse):
    model_version_id: Optional[int] = None


class ExtractionRequest(BaseModel):
    document_id: int
    schema: ExtractionSchema
    model_version: Optional[str] = None
    include_confidence: bool = True
    include_bounding_boxes: bool = True
    return_raw_output: bool = False


class ExtractionResponse(BaseModel):
    extraction_result_id: int
    status: str
    document_id: int
    overall_confidence: Optional[float] = None
    fields: List[ExtractedFieldResponse] = Field(default_factory=list)
    processing_time: Optional[float] = None
    error_message: Optional[str] = None


class BatchExtractionRequest(BaseModel):
    document_ids: List[int]
    schema: ExtractionSchema
    model_version: Optional[str] = None
    priority: Optional[int] = None
