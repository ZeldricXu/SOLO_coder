from pydantic import BaseModel, EmailStr, Field
from typing import Optional, List, Dict, Any
from datetime import datetime, date


class UserBase(BaseModel):
    username: str
    email: EmailStr
    full_name: str
    team_id: Optional[int] = None
    role: str = "user"
    wecom_userid: Optional[str] = None
    feishu_open_id: Optional[str] = None


class UserCreate(UserBase):
    password: str


class UserUpdate(BaseModel):
    email: Optional[EmailStr] = None
    full_name: Optional[str] = None
    team_id: Optional[int] = None
    role: Optional[str] = None
    wecom_userid: Optional[str] = None
    feishu_open_id: Optional[str] = None
    is_active: Optional[bool] = None
    password: Optional[str] = None


class UserLogin(BaseModel):
    username: str
    password: str


class UserResponse(UserBase):
    id: int
    is_active: bool
    created_at: datetime
    team_name: Optional[str] = None

    class Config:
        from_attributes = True


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user: UserResponse


class TeamBase(BaseModel):
    name: str
    description: Optional[str] = None
    leader_id: Optional[int] = None
    deadline_day: int = 4
    deadline_hour: int = 18
    deadline_minute: int = 0
    template_id: Optional[int] = None


class TeamCreate(TeamBase):
    pass


class TeamUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    leader_id: Optional[int] = None
    deadline_day: Optional[int] = None
    deadline_hour: Optional[int] = None
    deadline_minute: Optional[int] = None
    template_id: Optional[int] = None


class TeamResponse(TeamBase):
    id: int
    member_count: int = 0
    leader_name: Optional[str] = None
    template_name: Optional[str] = None

    class Config:
        from_attributes = True


class TemplateFieldBase(BaseModel):
    field_key: str
    field_name: str
    field_type: str = "markdown"
    options: Optional[List[Any]] = None
    placeholder: Optional[str] = None
    is_required: bool = True
    sort_order: int = 0
    is_risk_field: bool = False
    is_plan_field: bool = False
    is_achievement_field: bool = False


class TemplateFieldCreate(TemplateFieldBase):
    pass


class TemplateFieldUpdate(BaseModel):
    field_key: Optional[str] = None
    field_name: Optional[str] = None
    field_type: Optional[str] = None
    options: Optional[List[Any]] = None
    placeholder: Optional[str] = None
    is_required: Optional[bool] = None
    sort_order: Optional[int] = None
    is_risk_field: Optional[bool] = None
    is_plan_field: Optional[bool] = None
    is_achievement_field: Optional[bool] = None


class TemplateFieldResponse(TemplateFieldBase):
    id: int

    class Config:
        from_attributes = True


class TemplateVersionResponse(BaseModel):
    id: int
    template_id: int
    version: int
    change_note: Optional[str] = None
    fields_snapshot: List[Dict[str, Any]]
    created_at: datetime

    class Config:
        from_attributes = True


class TemplateBase(BaseModel):
    name: str
    description: Optional[str] = None
    is_default: bool = False


class TemplateCreate(TemplateBase):
    fields: List[TemplateFieldCreate]


class TemplateUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    is_default: Optional[bool] = None
    is_active: Optional[bool] = None
    fields: Optional[List[TemplateFieldCreate]] = None
    change_note: Optional[str] = None


class TemplateResponse(TemplateBase):
    id: int
    is_active: bool
    created_at: datetime
    current_version: int = 1
    fields: List[TemplateFieldResponse] = []

    class Config:
        from_attributes = True


class ReportFieldValueResponse(BaseModel):
    id: int
    field_key: str
    field_name: str
    value: Optional[str] = None
    word_count: int = 0

    class Config:
        from_attributes = True


class WeeklyReportBase(BaseModel):
    content: Dict[str, Any] = {}
    status: str = "draft"


class WeeklyReportCreate(WeeklyReportBase):
    week_key: Optional[str] = None


class WeeklyReportSubmit(WeeklyReportBase):
    proxy_user_id: Optional[int] = None


class WeeklyReportUpdate(BaseModel):
    content: Optional[Dict[str, Any]] = None
    status: Optional[str] = None


class WeeklyReportResponse(BaseModel):
    id: int
    submitter_id: int
    submitter_name: str
    proxy_submitter_id: Optional[int] = None
    proxy_submitter_name: Optional[str] = None
    template_id: Optional[int] = None
    template_name: Optional[str] = None
    template_version_id: Optional[int] = None
    week_key: str
    week_start: date
    week_end: date
    content: Dict[str, Any]
    word_count: int
    status: str
    submitted_at: Optional[datetime] = None
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True


class WeeklyReportListResponse(BaseModel):
    items: List[WeeklyReportResponse]
    total: int
    page: int
    page_size: int


class PlanDeviationItemResponse(BaseModel):
    id: int
    user_id: int
    user_name: str
    planned_item: str
    actual_status: str
    note: Optional[str] = None
    deviation_level: str

    class Config:
        from_attributes = True


class WeeklySummaryResponse(BaseModel):
    id: int
    week_key: str
    week_start: date
    week_end: date
    content: Dict[str, Any]
    generated_at: datetime
    pdf_path: Optional[str] = None
    status: str
    deviation_items: List[PlanDeviationItemResponse] = []

    class Config:
        from_attributes = True


class NotificationSettingBase(BaseModel):
    team_id: int
    wecom_webhook: Optional[str] = None
    feishu_webhook: Optional[str] = None
    notify_emails: Optional[str] = None
    notify_wecom_enabled: bool = False
    notify_feishu_enabled: bool = False
    notify_email_enabled: bool = False


class NotificationSettingUpdate(BaseModel):
    wecom_webhook: Optional[str] = None
    feishu_webhook: Optional[str] = None
    notify_emails: Optional[str] = None
    notify_wecom_enabled: Optional[bool] = None
    notify_feishu_enabled: Optional[bool] = None
    notify_email_enabled: Optional[bool] = None


class NotificationSettingResponse(NotificationSettingBase):
    id: int

    class Config:
        from_attributes = True


class SendReminderRequest(BaseModel):
    user_ids: Optional[List[int]] = None
    team_id: Optional[int] = None
    reminder_type: str = "custom"


class GenerateSummaryRequest(BaseModel):
    week_key: Optional[str] = None
    force: bool = False


class ExportRequest(BaseModel):
    week_key: str
    format: str = "pdf"
    email_to: Optional[List[str]] = None
    push_wecom: bool = False
    push_feishu: bool = False
    push_confluence: bool = False
    push_yuque: bool = False
    push_notion: bool = False


class StatisticsResponse(BaseModel):
    stat_type: str
    week_key: Optional[str] = None
    data: Dict[str, Any]


class ReminderLogResponse(BaseModel):
    id: int
    user_id: int
    user_name: str
    week_key: str
    reminder_type: str
    channel: str
    status: str
    error_message: Optional[str] = None
    created_at: datetime

    class Config:
        from_attributes = True
