from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
import os
import logging
from contextlib import asynccontextmanager

from app.core.config import get_settings
from app.core.database import engine, Base, SessionLocal
from app.core.security import get_password_hash
from app.models import models
from app.core.utils import get_week_key, get_week_range

from app.api import auth, users, teams, templates, reports, summaries, statistics, export

settings = get_settings()

logging.basicConfig(
    level=logging.INFO if settings.APP_ENV != "development" else logging.DEBUG,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)
logger = logging.getLogger(__name__)

EXPORT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app", "exports")
os.makedirs(EXPORT_DIR, exist_ok=True)


def _init_default_data():
    db = SessionLocal()
    try:
        user_count = db.query(models.User).count()
        if user_count == 0:
            logger.info("Creating default super_admin user: admin / admin123")
            admin = models.User(
                username="admin",
                email="admin@example.com",
                full_name="系统管理员",
                hashed_password=get_password_hash("admin123"),
                role="super_admin",
                is_active=True
            )
            db.add(admin)
            db.flush()

            team1 = models.Team(
                name="研发一部",
                description="核心产品研发",
                leader_id=None,
                deadline_day=4,
                deadline_hour=18,
                deadline_minute=0
            )
            team2 = models.Team(
                name="产品设计部",
                description="产品与用户体验设计",
                leader_id=None,
                deadline_day=4,
                deadline_hour=18,
                deadline_minute=0
            )
            team3 = models.Team(
                name="市场运营部",
                description="市场推广与用户运营",
                leader_id=None,
                deadline_day=4,
                deadline_hour=17,
                deadline_minute=30
            )
            for t in [team1, team2, team3]:
                db.add(t)
            db.flush()

            default_template = models.Template(
                name="标准周报模板",
                description="默认使用的周报模板",
                is_default=True,
                created_by=admin.id,
                is_active=True
            )
            db.add(default_template)
            db.flush()

            fields = [
                models.TemplateField(
                    template_id=default_template.id,
                    field_key="week_achievement",
                    field_name="本周完成工作",
                    field_type="markdown",
                    is_required=True,
                    sort_order=1,
                    is_achievement_field=True,
                    placeholder="请分点列出本周主要完成的工作，例如：\n- 完成用户登录模块开发\n- 修复10个线上bug"
                ),
                models.TemplateField(
                    template_id=default_template.id,
                    field_key="next_plan",
                    field_name="下周工作计划",
                    field_type="markdown",
                    is_required=True,
                    sort_order=2,
                    is_plan_field=True,
                    placeholder="请分点列出下周计划完成的工作"
                ),
                models.TemplateField(
                    template_id=default_template.id,
                    field_key="work_projects",
                    field_name="参与项目",
                    field_type="multiselect",
                    options=[
                        {"label": "项目A - 用户中心", "value": "project_a"},
                        {"label": "项目B - 订单系统", "value": "project_b"},
                        {"label": "项目C - 数据平台", "value": "project_c"},
                        {"label": "日常维护", "value": "maintenance"}
                    ],
                    is_required=True,
                    sort_order=3,
                    placeholder="请选择本周参与的项目（可多选）"
                ),
                models.TemplateField(
                    template_id=default_template.id,
                    field_key="work_hours",
                    field_name="本周工时（天）",
                    field_type="select",
                    options=[
                        {"label": "< 3天", "value": "lt3"},
                        {"label": "3-4天", "value": "3to4"},
                        {"label": "5天", "value": "5"},
                        {"label": "> 5天（加班）", "value": "gt5"}
                    ],
                    is_required=True,
                    sort_order=4,
                    placeholder="请选择本周有效工作时长"
                ),
                models.TemplateField(
                    template_id=default_template.id,
                    field_key="risk_block",
                    field_name="风险与阻塞",
                    field_type="markdown",
                    is_required=False,
                    sort_order=5,
                    is_risk_field=True,
                    placeholder="如有风险或需要协调的阻塞项，请填写；若无请填'无'"
                ),
                models.TemplateField(
                    template_id=default_template.id,
                    field_key="thoughts",
                    field_name="思考与建议",
                    field_type="markdown",
                    is_required=False,
                    sort_order=6,
                    placeholder="对团队/流程的建议、心得感悟等（选填）"
                )
            ]
            for f in fields:
                db.add(f)
            db.flush()

            fields_snapshot = [
                {
                    "field_key": f.field_key,
                    "field_name": f.field_name,
                    "field_type": f.field_type,
                    "options": f.options,
                    "placeholder": f.placeholder,
                    "is_required": f.is_required,
                    "sort_order": f.sort_order,
                    "is_risk_field": f.is_risk_field,
                    "is_plan_field": f.is_plan_field,
                    "is_achievement_field": f.is_achievement_field
                } for f in fields
            ]
            version = models.TemplateVersion(
                template_id=default_template.id,
                version=1,
                change_note="初始标准模板",
                fields_snapshot=fields_snapshot,
                created_by=admin.id
            )
            db.add(version)
            db.flush()

            team1.template_id = default_template.id
            team2.template_id = default_template.id
            team3.template_id = default_template.id
            team1.leader_id = admin.id

            demo_users = [
                ("zhangsan", "zhangsan@example.com", "张三", team1.id, "user"),
                ("lisi", "lisi@example.com", "李四", team1.id, "user"),
                ("wangwu", "wangwu@example.com", "王五", team1.id, "user"),
                ("zhaoliu", "zhaoliu@example.com", "赵六", team2.id, "user"),
                ("qianqi", "qianqi@example.com", "钱七", team2.id, "user"),
                ("sunba", "sunba@example.com", "孙八", team3.id, "user"),
                ("zhoujiu", "zhoujiu@example.com", "周九", team3.id, "user")
            ]
            for uname, email, fname, tid, role in demo_users:
                u = models.User(
                    username=uname,
                    email=email,
                    full_name=fname,
                    hashed_password=get_password_hash("123456"),
                    role=role,
                    team_id=tid,
                    is_active=True
                )
                db.add(u)

            for t in [team1, team2, team3]:
                setting = models.TeamNotificationSetting(
                    team_id=t.id,
                    notify_wecom_enabled=False,
                    notify_feishu_enabled=False,
                    notify_email_enabled=False
                )
                db.add(setting)

            db.commit()
            logger.info("Default data initialization complete.")
    except Exception as e:
        logger.error(f"Default data init error: {e}", exc_info=True)
        db.rollback()
    finally:
        db.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(f"Starting {settings.APP_NAME}...")
    Base.metadata.create_all(bind=engine)
    logger.info("Database tables created")
    _init_default_data()

    from app.scheduler.tasks import start_scheduler, stop_scheduler
    if settings.APP_ENV != "test":
        try:
            start_scheduler()
        except Exception as e:
            logger.error(f"Scheduler init failed: {e}")

    yield

    stop_scheduler()
    logger.info(f"{settings.APP_NAME} shutdown complete.")


app = FastAPI(
    title=settings.APP_NAME,
    description="周报自动汇总系统 - 模板管理、自动收报、智能汇总、统计分析、多渠道分发",
    version="1.0.0",
    lifespan=lifespan,
    debug=settings.APP_DEBUG
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router)
app.include_router(users.router)
app.include_router(teams.router)
app.include_router(templates.router)
app.include_router(reports.router)
app.include_router(summaries.router)
app.include_router(statistics.router)
app.include_router(export.router)


@app.get("/")
def root():
    return {
        "name": settings.APP_NAME,
        "version": "1.0.0",
        "docs": "/docs",
        "status": "running"
    }


@app.get("/api/health")
def health_check():
    from app.scheduler.tasks import list_scheduled_jobs
    return {
        "status": "ok",
        "app": settings.APP_NAME,
        "week_key": get_week_key(),
        "scheduler_jobs": list_scheduled_jobs()
    }


@app.post("/api/scheduler/trigger/{job_name}")
def trigger_job_manually(job_name: str):
    from app.scheduler.tasks import (
        _monday_first_reminder_job, _wednesday_followup_job,
        _friday_urgent_job, _generate_weekly_summary_job
    )
    job_map = {
        "monday_reminder": _monday_first_reminder_job,
        "wednesday_reminder": _wednesday_followup_job,
        "friday_reminder": _friday_urgent_job,
        "generate_summary": _generate_weekly_summary_job
    }
    if job_name not in job_map:
        return {"status": "error", "message": f"Unknown job: {job_name}", "available": list(job_map.keys())}
    job_map[job_name]()
    return {"status": "success", "message": f"Job {job_name} triggered"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=settings.APP_DEBUG
    )
