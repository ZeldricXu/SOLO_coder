import os
from celery import Celery
from celery.schedules import crontab


def make_celery(app):
    celery = Celery(
        app.import_name,
        broker=app.config['CELERY_BROKER_URL'],
        backend=app.config['CELERY_RESULT_BACKEND']
    )

    celery.conf.update(
        task_serializer='json',
        accept_content=['json'],
        result_serializer='json',
        timezone='Asia/Shanghai',
        enable_utc=True,
        beat_schedule={
            'process-scheduled-reports-every-minute': {
                'task': 'app.tasks.report_tasks.process_scheduled_reports',
                'schedule': crontab(minute='*'),
            },
            'cleanup-expired-share-links': {
                'task': 'app.tasks.maintenance_tasks.cleanup_expired_share_links',
                'schedule': crontab(hour=0, minute=0),
            },
            'cleanup-old-reports': {
                'task': 'app.tasks.maintenance_tasks.cleanup_old_reports',
                'schedule': crontab(hour=1, minute=0),
            },
        }
    )

    class ContextTask(celery.Task):
        def __call__(self, *args, **kwargs):
            with app.app_context():
                return self.run(*args, **kwargs)

    celery.Task = ContextTask

    return celery
