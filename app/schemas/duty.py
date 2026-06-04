from datetime import date, datetime
from typing import Optional, List
from pydantic import BaseModel


class DutyScheduleCreate(BaseModel):
    user_id: int
    duty_date: date
    shift_type: str = "day"


class DutySwapRequest(BaseModel):
    schedule_id: int
    from_user_id: int
    to_user_id: int
    reason: Optional[str] = None


class HandoverRequest(BaseModel):
    schedule_id: Optional[int] = None
    from_user_id: int
    to_user_id: int
    custom_content: Optional[str] = None
