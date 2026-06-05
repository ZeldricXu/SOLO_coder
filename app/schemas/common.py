from typing import Generic, List, Optional, TypeVar
from datetime import datetime
from pydantic import BaseModel, Field, ConfigDict

T = TypeVar("T")


class BoundingBox(BaseModel):
    x1: float = Field(..., description="Left coordinate")
    y1: float = Field(..., description="Top coordinate")
    x2: float = Field(..., description="Right coordinate")
    y2: float = Field(..., description="Bottom coordinate")

    @property
    def width(self) -> float:
        return self.x2 - self.x1

    @property
    def height(self) -> float:
        return self.y2 - self.y1

    @property
    def area(self) -> float:
        return self.width * self.height


class Point(BaseModel):
    x: float
    y: float


class TextBlock(BaseModel):
    text: str
    bbox: BoundingBox
    confidence: Optional[float] = None
    block_type: Optional[str] = None
    line_number: Optional[int] = None
    page_number: Optional[int] = None


class TableCellSchema(BaseModel):
    row_index: int
    col_index: int
    row_span: int = 1
    col_span: int = 1
    text: str
    is_header: bool = False
    bbox: Optional[BoundingBox] = None
    confidence: Optional[float] = None


class TableData(BaseModel):
    headers: List[str] = Field(default_factory=list)
    rows: List[List[str]] = Field(default_factory=list)
    row_count: int = 0
    col_count: int = 0


class ImageRegion(BaseModel):
    region_id: str
    bbox: BoundingBox
    image_type: Optional[str] = None
    caption: Optional[str] = None
    page_number: int


class ValidationError(BaseModel):
    field_name: str
    error_code: str
    error_message: str
    severity: str = "error"
    suggested_value: Optional[str] = None


class PaginatedResponse(BaseModel, Generic[T]):
    items: List[T]
    page: int
    page_size: int
    total: int
    total_pages: int


class APIResponse(BaseModel, Generic[T]):
    success: bool = True
    message: Optional[str] = None
    data: Optional[T] = None
    errors: Optional[List[ValidationError]] = None
    timestamp: datetime = Field(default_factory=datetime.utcnow)


class ProgressUpdate(BaseModel):
    task_id: str
    status: str
    progress: float
    message: Optional[str] = None
    current_step: Optional[str] = None
    total_steps: Optional[int] = None
    current_step_number: Optional[int] = None
