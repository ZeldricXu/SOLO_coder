from typing import Optional, List, Dict, Any
from datetime import datetime
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from reporthub.models import get_db, ReportTemplate, Report, Schedule
from reporthub.modules import (
    TemplateModule, DataModule, ExportModule, StatisticsModule,
    ScheduleModule, QueryModule, PermissionModule, StorageModule, VersionModule
)

router = APIRouter(prefix="/api/v1")


class GenerateReportRequest(BaseModel):
    template_id: str
    report_params: Optional[Dict[str, Any]] = None
    generator: Optional[str] = None


class ExportReportRequest(BaseModel):
    report_id: str
    export_format: str


class CreateTemplateRequest(BaseModel):
    template_name: str
    template_type: str = "table"
    data_source: Dict[str, Any]
    fields: List[Dict[str, Any]]
    filters: Optional[List[Dict[str, Any]]] = None


class CreateScheduleRequest(BaseModel):
    template_id: str
    schedule_type: str = "cron"
    schedule_cron: Optional[str] = None
    schedule_interval: Optional[int] = None
    export_format: str = "xlsx"
    notify_users: Optional[List[str]] = None


class GrantPermissionRequest(BaseModel):
    template_id: str
    user_id: str
    role: str = "viewer"


def get_template_module(db: Session = Depends(get_db)) -> TemplateModule:
    return TemplateModule(db)


def get_storage_module() -> StorageModule:
    return StorageModule()


def get_version_module(db: Session = Depends(get_db), storage: StorageModule = Depends(get_storage_module)) -> VersionModule:
    return VersionModule(db, storage)


def get_statistics_module(db: Session = Depends(get_db)) -> StatisticsModule:
    return StatisticsModule(db)


def get_data_module(db: Session = Depends(get_db),
                    storage: StorageModule = Depends(get_storage_module),
                    version: VersionModule = Depends(get_version_module),
                    stats: StatisticsModule = Depends(get_statistics_module)) -> DataModule:
    return DataModule(db, storage, version, stats)


def get_export_module(db: Session = Depends(get_db),
                      storage: StorageModule = Depends(get_storage_module),
                      stats: StatisticsModule = Depends(get_statistics_module)) -> ExportModule:
    return ExportModule(db, storage, stats)


def get_query_module(db: Session = Depends(get_db)) -> QueryModule:
    return QueryModule(db)


def get_schedule_module(db: Session = Depends(get_db),
                        template: TemplateModule = Depends(get_template_module)) -> ScheduleModule:
    return ScheduleModule(db, template)


def get_permission_module(db: Session = Depends(get_db)) -> PermissionModule:
    return PermissionModule(db)


@router.post("/reports/generate")
def generate_report(
    request: GenerateReportRequest,
    data_module: DataModule = Depends(get_data_module),
    template_module: TemplateModule = Depends(get_template_module),
    db: Session = Depends(get_db)
):
    template = template_module.get_template(request.template_id)
    if not template:
        raise HTTPException(status_code=404, detail="模板不存在")
    try:
        report = data_module.generate_report(
            template=template,
            report_params=request.report_params or {},
            generator=request.generator
        )
        return {
            "code": 200,
            "data": {
                "report_id": report.report_id,
                "status": report.status
            }
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/reports/export")
def export_report(
    request: ExportReportRequest,
    query_module: QueryModule = Depends(get_query_module),
    export_module: ExportModule = Depends(get_export_module),
    db: Session = Depends(get_db)
):
    report = query_module.get_report_by_id(request.report_id)
    if not report:
        raise HTTPException(status_code=404, detail="报表不存在")
    try:
        export_file = export_module.export_report(
            report=report,
            export_format=request.export_format
        )
        return {
            "code": 200,
            "data": {
                "export_file": export_file
            }
        }
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/reports/query")
def query_reports(
    template_id: Optional[str] = None,
    start_date: Optional[str] = None,
    end_date: Optional[str] = None,
    keyword: Optional[str] = None,
    limit: int = 100,
    offset: int = 0,
    query_module: QueryModule = Depends(get_query_module)
):
    start_dt = None
    end_dt = None
    if start_date:
        try:
            start_dt = datetime.fromisoformat(start_date)
        except ValueError:
            pass
    if end_date:
        try:
            end_dt = datetime.fromisoformat(end_date)
        except ValueError:
            pass
    reports = query_module.search_reports(
        keyword=keyword,
        template_id=template_id,
        start_date=start_dt,
        end_date=end_dt,
        limit=limit,
        offset=offset
    )
    report_list = [
        {
            "report_id": r.report_id,
            "template_id": r.template_id,
            "report_name": r.report_name,
            "report_format": r.report_format,
            "generated_at": r.generated_at.isoformat() if r.generated_at else None,
            "generator": r.generator,
            "status": r.status
        }
        for r in reports
    ]
    return {
        "code": 200,
        "data": {
            "reports": report_list,
            "total": len(report_list)
        }
    }


@router.get("/reports/{report_id}")
def get_report(
    report_id: str,
    query_module: QueryModule = Depends(get_query_module)
):
    report = query_module.get_report_by_id(report_id)
    if not report:
        raise HTTPException(status_code=404, detail="报表不存在")
    return {
        "code": 200,
        "data": {
            "report_id": report.report_id,
            "template_id": report.template_id,
            "report_name": report.report_name,
            "report_data": report.report_data,
            "report_file": report.report_file,
            "report_format": report.report_format,
            "generated_at": report.generated_at.isoformat() if report.generated_at else None,
            "generator": report.generator,
            "status": report.status
        }
    }


@router.post("/templates")
def create_template(
    request: CreateTemplateRequest,
    template_module: TemplateModule = Depends(get_template_module)
):
    try:
        template = template_module.create_template(
            template_name=request.template_name,
            template_type=request.template_type,
            data_source=request.data_source,
            fields=request.fields,
            filters=request.filters
        )
        return {
            "code": 200,
            "data": {
                "template_id": template.template_id,
                "template_name": template.template_name,
                "created_at": template.created_at.isoformat() if template.created_at else None
            }
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/templates")
def list_templates(template_module: TemplateModule = Depends(get_template_module)):
    templates = template_module.get_all_templates()
    template_list = [
        {
            "template_id": t.template_id,
            "template_name": t.template_name,
            "template_type": t.template_type,
            "created_at": t.created_at.isoformat() if t.created_at else None
        }
        for t in templates
    ]
    return {
        "code": 200,
        "data": {
            "templates": template_list,
            "total": len(template_list)
        }
    }


@router.get("/templates/{template_id}")
def get_template(
    template_id: str,
    template_module: TemplateModule = Depends(get_template_module)
):
    template = template_module.get_template(template_id)
    if not template:
        raise HTTPException(status_code=404, detail="模板不存在")
    return {
        "code": 200,
        "data": {
            "template_id": template.template_id,
            "template_name": template.template_name,
            "template_type": template.template_type,
            "data_source": template.data_source,
            "fields": template.fields,
            "filters": template.filters,
            "created_at": template.created_at.isoformat() if template.created_at else None,
            "updated_at": template.updated_at.isoformat() if template.updated_at else None
        }
    }


@router.delete("/templates/{template_id}")
def delete_template(
    template_id: str,
    template_module: TemplateModule = Depends(get_template_module)
):
    success = template_module.delete_template(template_id)
    if not success:
        raise HTTPException(status_code=404, detail="模板不存在")
    return {"code": 200, "data": {"message": "删除成功"}}


@router.post("/schedules")
def create_schedule(
    request: CreateScheduleRequest,
    schedule_module: ScheduleModule = Depends(get_schedule_module)
):
    try:
        schedule = schedule_module.create_schedule(
            template_id=request.template_id,
            schedule_type=request.schedule_type,
            schedule_cron=request.schedule_cron,
            schedule_interval=request.schedule_interval,
            export_format=request.export_format,
            notify_users=request.notify_users
        )
        return {
            "code": 200,
            "data": {
                "schedule_id": schedule.schedule_id,
                "enabled": schedule.enabled
            }
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/schedules")
def list_schedules(schedule_module: ScheduleModule = Depends(get_schedule_module)):
    schedules = schedule_module.get_all_schedules()
    schedule_list = [
        {
            "schedule_id": s.schedule_id,
            "template_id": s.template_id,
            "schedule_type": s.schedule_type,
            "schedule_cron": s.schedule_cron,
            "enabled": s.enabled,
            "last_run_at": s.last_run_at.isoformat() if s.last_run_at else None,
            "next_run_at": s.next_run_at.isoformat() if s.next_run_at else None
        }
        for s in schedules
    ]
    return {
        "code": 200,
        "data": {
            "schedules": schedule_list,
            "total": len(schedule_list)
        }
    }


@router.post("/schedules/{schedule_id}/enable")
def enable_schedule(
    schedule_id: str,
    schedule_module: ScheduleModule = Depends(get_schedule_module)
):
    success = schedule_module.enable_schedule(schedule_id)
    if not success:
        raise HTTPException(status_code=404, detail="调度任务不存在")
    return {"code": 200, "data": {"message": "调度任务已启用"}}


@router.post("/schedules/{schedule_id}/disable")
def disable_schedule(
    schedule_id: str,
    schedule_module: ScheduleModule = Depends(get_schedule_module)
):
    success = schedule_module.disable_schedule(schedule_id)
    if not success:
        raise HTTPException(status_code=404, detail="调度任务不存在")
    return {"code": 200, "data": {"message": "调度任务已禁用"}}


@router.delete("/schedules/{schedule_id}")
def delete_schedule(
    schedule_id: str,
    schedule_module: ScheduleModule = Depends(get_schedule_module)
):
    success = schedule_module.delete_schedule(schedule_id)
    if not success:
        raise HTTPException(status_code=404, detail="调度任务不存在")
    return {"code": 200, "data": {"message": "删除成功"}}


@router.post("/permissions/grant")
def grant_permission(
    request: GrantPermissionRequest,
    permission_module: PermissionModule = Depends(get_permission_module)
):
    permission = permission_module.grant_permission(
        template_id=request.template_id,
        user_id=request.user_id,
        role=request.role
    )
    return {
        "code": 200,
        "data": {
            "permission_id": permission.permission_id,
            "template_id": permission.template_id,
            "user_id": permission.user_id,
            "role": permission.role
        }
    }


@router.get("/permissions/user/{user_id}")
def get_user_permissions(
    user_id: str,
    permission_module: PermissionModule = Depends(get_permission_module)
):
    permissions = permission_module.get_user_permissions(user_id)
    perm_list = [
        {
            "permission_id": p.permission_id,
            "template_id": p.template_id,
            "role": p.role,
            "can_view": p.can_view,
            "can_generate": p.can_generate,
            "can_export": p.can_export,
            "can_manage": p.can_manage
        }
        for p in permissions
    ]
    return {
        "code": 200,
        "data": {
            "permissions": perm_list,
            "total": len(perm_list)
        }
    }


@router.delete("/permissions/{template_id}/{user_id}")
def revoke_permission(
    template_id: str,
    user_id: str,
    permission_module: PermissionModule = Depends(get_permission_module)
):
    success = permission_module.revoke_permission(template_id, user_id)
    if not success:
        raise HTTPException(status_code=404, detail="权限不存在")
    return {"code": 200, "data": {"message": "权限已撤销"}}


@router.get("/statistics/template/{template_id}")
def get_template_statistics(
    template_id: str,
    stat_month: Optional[str] = None,
    statistics_module: StatisticsModule = Depends(get_statistics_module)
):
    summary = statistics_module.get_summary_report(template_id, stat_month)
    if not summary:
        return {
            "code": 200,
            "data": {
                "template_id": template_id,
                "generate_count": 0,
                "export_count": 0,
                "total_rows": 0,
                "avg_generate_time": 0
            }
        }
    return {"code": 200, "data": summary}


@router.get("/statistics/template/{template_id}/trend")
def get_trend_analysis(
    template_id: str,
    months: int = 6,
    statistics_module: StatisticsModule = Depends(get_statistics_module)
):
    trends = statistics_module.get_trend_analysis(template_id, months)
    return {
        "code": 200,
        "data": {
            "template_id": template_id,
            "trends": trends
        }
    }


@router.get("/versions/report/{report_id}")
def get_report_versions(
    report_id: str,
    version_module: VersionModule = Depends(get_version_module)
):
    history = version_module.get_version_history(report_id)
    return {
        "code": 200,
        "data": {
            "report_id": report_id,
            "versions": history
        }
    }


@router.get("/versions/compare")
def compare_versions(
    report_id: str,
    version1: str,
    version2: str,
    version_module: VersionModule = Depends(get_version_module)
):
    comparison = version_module.compare_versions(report_id, version1, version2)
    if "error" in comparison:
        raise HTTPException(status_code=404, detail=comparison["error"])
    return {"code": 200, "data": comparison}


@router.get("/storage/usage")
def get_storage_usage(storage_module: StorageModule = Depends(get_storage_module)):
    usage = storage_module.get_storage_usage()
    return {"code": 200, "data": usage}


@router.post("/storage/clean")
def clean_expired_files(days: int = 30, storage_module: StorageModule = Depends(get_storage_module)):
    deleted = storage_module.clean_expired_files(days)
    return {"code": 200, "data": {"deleted_files": deleted}}
