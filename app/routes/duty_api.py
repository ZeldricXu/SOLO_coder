from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session
from typing import Optional
from datetime import date
import calendar

from app.database import get_db
from app.templates_shared import templates
from app.services import DutyService
from app.schemas import DutyScheduleCreate, DutySwapRequest, HandoverRequest

router = APIRouter(prefix="/api/duty", tags=["duty"])


@router.get("/schedule")
async def get_schedule(
    year: Optional[int] = None,
    month: Optional[int] = None,
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    schedule = duty_service.get_schedule(year, month)
    return {
        "success": True,
        "schedule": schedule,
    }


@router.post("/schedule")
async def create_schedule(
    data: DutyScheduleCreate,
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    schedule = duty_service.create_schedule(data)
    return {
        "success": True,
        "schedule": schedule,
    }


@router.get("/schedule/today")
async def get_today_duty(
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    duty = duty_service.get_today_duty()
    return {
        "success": True,
        "duty": duty,
    }


@router.get("/schedule/current")
async def get_current_duty(
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    duty = duty_service.get_duty_user()
    return {
        "success": True,
        "duty": duty,
    }


@router.get("/schedule/week")
async def get_week_schedule(
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    schedule = duty_service.get_current_week_schedule()
    return {
        "success": True,
        "schedule": schedule,
    }


@router.get("/schedule/upcoming")
async def get_upcoming(
    days: int = Query(7),
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    duties = duty_service.get_upcoming_duties(days=days)
    return {
        "success": True,
        "duties": duties,
    }


@router.post("/schedule/generate-month")
async def generate_month_schedule(
    year: int,
    month: int,
    user_ids: list[int],
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    duty_service.create_monthly_schedule(year, month, user_ids)
    return {
        "success": True,
        "message": f"已生成 {year}年{month}月 的排班表",
    }


@router.delete("/schedule/{schedule_id}")
async def delete_schedule(
    schedule_id: int,
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    success = duty_service.delete_schedule(schedule_id)
    if not success:
        raise HTTPException(status_code=404, detail="Schedule not found")
    return {
        "success": True,
        "message": "Schedule deleted",
    }


@router.post("/swap")
async def swap_duty(
    data: DutySwapRequest,
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    success = duty_service.swap_duty(data)
    if not success:
        raise HTTPException(status_code=404, detail="Schedule not found or swap failed")
    return {
        "success": True,
        "message": "Duty swapped successfully",
    }


@router.post("/handover")
async def generate_handover(
    data: HandoverRequest,
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    report = duty_service.generate_handover_report(data)
    return {
        "success": True,
        "report": report,
    }


@router.get("/reports")
async def get_reports(
    limit: int = Query(50),
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    reports = duty_service.get_handover_reports(limit=limit)
    return {
        "success": True,
        "count": len(reports),
        "reports": reports,
    }


@router.get("/reports/{report_id}")
async def get_report(
    report_id: int,
    db: Session = Depends(get_db),
):
    duty_service = DutyService(db)
    report = duty_service.get_report_by_id(report_id)
    if not report:
        raise HTTPException(status_code=404, detail="Report not found")
    return {
        "success": True,
        **report,
    }


@router.get("/partial/week-calendar")
async def get_week_calendar_partial(
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    duty_service = DutyService(db)
    week_schedule = duty_service.get_current_week_schedule()
    today_duty = duty_service.get_today_duty()
    current_duty = duty_service.get_duty_user()

    scope = {"type": "http", "method": "GET", "path": "/api/duty/partial/week-calendar", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/duty_calendar.html",
        {
            "request": request,
            "week_schedule": week_schedule,
            "today_duty": today_duty,
            "current_duty": current_duty,
        },
    )


@router.get("/partial/handover/{report_id}")
async def get_handover_partial(
    report_id: int,
    db: Session = Depends(get_db),
):
    from starlette.requests import Request as StarletteRequest

    duty_service = DutyService(db)
    report_data = duty_service.get_report_by_id(report_id)
    if not report_data:
        raise HTTPException(status_code=404, detail="Report not found")

    scope = {"type": "http", "method": "GET", "path": "/api/duty/partial/handover", "headers": []}
    request = StarletteRequest(scope)

    return templates.TemplateResponse(
        "components/handover_report.html",
        {
            "request": request,
            **report_data,
        },
    )
