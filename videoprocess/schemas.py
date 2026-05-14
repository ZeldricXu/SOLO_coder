from typing import Optional, Dict, Any, List
from pydantic import BaseModel, Field


class VideoUploadResponse(BaseModel):
    video_id: str
    video_name: str
    video_format: str
    video_size: int
    status: str


class TranscodeRequest(BaseModel):
    video_id: str
    target_format: str = "mp4"
    target_codec: Optional[str] = None
    profile: Optional[str] = None


class TranscodeResponse(BaseModel):
    transcode_id: str
    video_id: str
    target_format: str
    status: str


class EditRequest(BaseModel):
    video_id: str
    edit_type: str = Field(description="cut or merge")
    edit_params: Dict[str, Any] = Field(default_factory=dict)
    add_watermark: bool = False
    watermark_text: Optional[str] = None


class EditResponse(BaseModel):
    edit_id: str
    video_id: str
    edit_type: str
    status: str


class WatermarkRequest(BaseModel):
    video_id: str
    text: Optional[str] = None
    image_path: Optional[str] = None
    position: str = "bottom-right"
    opacity: float = 0.7


class ThumbnailRequest(BaseModel):
    video_id: str
    sizes: Optional[List[str]] = None
    capture_time: Optional[float] = None


class VideoInfoResponse(BaseModel):
    video_id: str
    video_name: str
    video_format: str
    video_duration: float
    video_size: int
    upload_user: str
    video_status: str
    storage_path: str


class QualityReportResponse(BaseModel):
    quality_id: str
    video_id: str
    resolution: Optional[str]
    bitrate: int
    frame_rate: float
    quality_score: int
    quality_issues: List[str]


class StatisticsResponse(BaseModel):
    stat_date: str
    upload_count: int
    transcode_count: int
    edit_count: int
    total_size: int
    avg_duration: float


class HistoryResponse(BaseModel):
    history_id: str
    video_id: str
    action_type: str
    status: str
    created_at: str


class ApiResponse(BaseModel):
    code: int = 200
    message: str = "success"
    data: Optional[Any] = None


class ApiErrorResponse(BaseModel):
    code: int = 400
    message: str = "error"
    details: Optional[str] = None
