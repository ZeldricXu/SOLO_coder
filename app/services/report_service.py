import os
import json
from datetime import datetime
from app import db, celery
from app.models import Report, ReportSchedule, Dashboard


def create_report_schedule(user_id, name, dashboard_id, recipients, cron_expression=None,
                          interval_minutes=None, report_type='pdf', include_snapshot=True,
                          include_data=False, timezone='Asia/Shanghai'):
    if not cron_expression and not interval_minutes:
        raise ValueError('必须指定cron表达式或间隔时间')

    dashboard = Dashboard.query.get(dashboard_id)
    if not dashboard:
        raise ValueError('看板不存在')

    schedule = ReportSchedule(
        name=name,
        dashboard_id=dashboard_id,
        owner_id=user_id,
        cron_expression=cron_expression,
        interval_minutes=interval_minutes,
        report_type=report_type,
        include_snapshot=include_snapshot,
        include_data=include_data,
        timezone=timezone,
        is_active=True
    )
    schedule.set_recipients(recipients)
    schedule.next_run_at = schedule.get_next_run_time()

    db.session.add(schedule)
    db.session.commit()

    return schedule


def update_report_schedule(schedule_id, **kwargs):
    schedule = ReportSchedule.query.get(schedule_id)
    if not schedule:
        raise ValueError('定时任务不存在')

    allowed_fields = ['name', 'cron_expression', 'interval_minutes', 'report_type',
                      'include_snapshot', 'include_data', 'timezone', 'is_active']
    for field, value in kwargs.items():
        if field in allowed_fields:
            setattr(schedule, field, value)

    if 'recipients' in kwargs:
        schedule.set_recipients(kwargs['recipients'])

    schedule.next_run_at = schedule.get_next_run_time()
    schedule.updated_at = datetime.utcnow()
    db.session.add(schedule)
    db.session.commit()
    return schedule


def delete_report_schedule(schedule_id):
    schedule = ReportSchedule.query.get(schedule_id)
    if not schedule:
        raise ValueError('定时任务不存在')

    db.session.delete(schedule)
    db.session.commit()


def get_report_schedule(schedule_id):
    return ReportSchedule.query.get(schedule_id)


def get_report_schedules(user_id, dashboard_id=None, page=1, per_page=20):
    query = ReportSchedule.query.filter_by(owner_id=user_id)
    if dashboard_id:
        query = query.filter_by(dashboard_id=dashboard_id)
    return query.order_by(ReportSchedule.created_at.desc()).paginate(page=page, per_page=per_page)


def trigger_report_schedule(schedule_id):
    schedule = ReportSchedule.query.get(schedule_id)
    if not schedule:
        raise ValueError('定时任务不存在')

    report = generate_report(
        dashboard_id=schedule.dashboard_id,
        user_id=schedule.owner_id,
        report_type=schedule.report_type,
        include_snapshot=schedule.include_snapshot,
        include_data=schedule.include_data,
        title=schedule.name,
        schedule_id=schedule.id
    )

    schedule.last_run_at = datetime.utcnow()
    schedule.next_run_at = schedule.get_next_run_time()
    db.session.add(schedule)
    db.session.commit()

    return report


def generate_report(dashboard_id, user_id, report_type='pdf', include_snapshot=True,
                   include_data=False, title=None, schedule_id=None):
    dashboard = Dashboard.query.get(dashboard_id)
    if not dashboard:
        raise ValueError('看板不存在')

    report = Report(
        schedule_id=schedule_id,
        dashboard_id=dashboard_id,
        owner_id=user_id,
        title=title or f'{dashboard.name} - {datetime.now().strftime("%Y-%m-%d %H:%M")}',
        file_type=report_type,
        status='pending'
    )

    db.session.add(report)
    db.session.commit()

    from app.tasks.report_tasks import generate_report_task
    generate_report_task.delay(report.id)

    return report


def get_report(report_id):
    return Report.query.get(report_id)


def get_reports(user_id, dashboard_id=None, schedule_id=None, status=None, page=1, per_page=20):
    query = Report.query.filter_by(owner_id=user_id)
    if dashboard_id:
        query = query.filter_by(dashboard_id=dashboard_id)
    if schedule_id:
        query = query.filter_by(schedule_id=schedule_id)
    if status:
        query = query.filter_by(status=status)
    return query.order_by(Report.created_at.desc()).paginate(page=page, per_page=per_page)


def delete_report(report_id):
    report = Report.query.get(report_id)
    if not report:
        raise ValueError('报表不存在')

    if report.file_path and os.path.exists(report.file_path):
        try:
            os.remove(report.file_path)
        except Exception:
            pass

    db.session.delete(report)
    db.session.commit()
