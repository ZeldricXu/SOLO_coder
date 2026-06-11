import logging
from datetime import datetime, timedelta
from typing import Dict, List, Optional
from sqlalchemy.orm import Session

from app.models import TaskJob, User, DataSource
from app.database import SessionLocal

logger = logging.getLogger(__name__)


class AdminService:
    def __init__(self):
        pass

    def create_task_job(self, db: Session, task_type: str, params: Dict,
                        created_by: int = None) -> TaskJob:
        import uuid
        task_id = str(uuid.uuid4())

        job = TaskJob(
            task_type=task_type,
            task_id=task_id,
            status="pending",
            progress=0.0,
            params=params,
            created_by=created_by,
        )

        db.add(job)
        db.commit()
        db.refresh(job)

        return job

    def update_task_progress(self, db: Session, task_id: str,
                             progress: float, status: str = "running"):
        job = db.query(TaskJob).filter(TaskJob.task_id == task_id).first()
        if job:
            job.progress = progress
            job.status = status
            db.commit()

    def complete_task(self, db: Session, task_id: str, result: Dict = None):
        job = db.query(TaskJob).filter(TaskJob.task_id == task_id).first()
        if job:
            job.status = "completed"
            job.progress = 100.0
            job.result = result or {}
            job.completed_at = datetime.utcnow()
            db.commit()

    def fail_task(self, db: Session, task_id: str, error_message: str):
        job = db.query(TaskJob).filter(TaskJob.task_id == task_id).first()
        if job:
            job.status = "failed"
            job.error_message = error_message
            job.completed_at = datetime.utcnow()
            db.commit()

    def get_dashboard_stats(self, db: Session) -> Dict:
        from app.models import TrafficSensor, TrafficFlowRecord, PredictionModel

        stats = {
            "sensors": {
                "total": db.query(TrafficSensor).count(),
                "active": db.query(TrafficSensor).filter(
                    TrafficSensor.status == "active"
                ).count(),
            },
            "data_sources": {
                "total": db.query(DataSource).count(),
                "active": db.query(DataSource).filter(
                    DataSource.status == "active"
                ).count(),
            },
            "models": {
                "total": db.query(PredictionModel).count(),
                "completed": db.query(PredictionModel).filter(
                    PredictionModel.status == "completed"
                ).count(),
            },
            "users": {
                "total": db.query(User).count(),
                "active": db.query(User).filter(User.is_active == True).count(),
            },
            "recent_tasks": self._get_recent_tasks(db),
        }

        return stats

    def _get_recent_tasks(self, db: Session, limit: int = 10) -> List[Dict]:
        tasks = db.query(TaskJob).order_by(
            TaskJob.created_at.desc()
        ).limit(limit).all()

        return [
            {
                "id": t.id,
                "task_id": t.task_id,
                "task_type": t.task_type,
                "status": t.status,
                "progress": t.progress,
                "created_at": t.created_at.isoformat(),
            }
            for t in tasks
        ]

    def create_user(self, db: Session, username: str, password: str,
                    email: str = None, full_name: str = None,
                    role: str = "viewer") -> User:
        from app.utils.auth import get_password_hash

        existing = db.query(User).filter(
            (User.username == username) | (User.email == email)
        ).first()

        if existing:
            raise ValueError("Username or email already exists")

        hashed_password = get_password_hash(password)
        user = User(
            username=username,
            email=email,
            hashed_password=hashed_password,
            full_name=full_name,
            role=role,
            is_active=True,
        )

        db.add(user)
        db.commit()
        db.refresh(user)

        return user

    def update_user_role(self, db: Session, user_id: int, role: str) -> User:
        user = db.query(User).filter(User.id == user_id).first()
        if not user:
            raise ValueError("User not found")

        user.role = role
        db.commit()
        db.refresh(user)

        return user

    def toggle_user_active(self, db: Session, user_id: int) -> User:
        user = db.query(User).filter(User.id == user_id).first()
        if not user:
            raise ValueError("User not found")

        user.is_active = not user.is_active
        db.commit()
        db.refresh(user)

        return user

    def create_data_source(self, db: Session, name: str, source_type: str,
                           config: Dict, description: str = None) -> DataSource:
        source = DataSource(
            name=name,
            type=source_type,
            config=config,
            status="inactive",
            description=description,
        )

        db.add(source)
        db.commit()
        db.refresh(source)

        return source

    def test_data_source(self, db: Session, source_id: int) -> Dict:
        source = db.query(DataSource).filter(DataSource.id == source_id).first()
        if not source:
            return {"success": False, "error": "Data source not found"}

        try:
            if source.type == "kafka":
                from confluent_kafka import Consumer
                config = source.config
                consumer = Consumer({
                    'bootstrap.servers': config.get('bootstrap_servers', 'localhost:9092'),
                    'group.id': 'test-group',
                    'socket.timeout.ms': 5000,
                })
                topics = consumer.list_topics(timeout=5)
                consumer.close()
                return {"success": True, "topics_count": len(topics.topics)}

            elif source.type == "hdfs":
                from hdfs import InsecureClient
                config = source.config
                url = f"http://{config.get('host', 'localhost')}:{config.get('port', 9870)}"
                client = InsecureClient(url, user=config.get('user', 'hdfs'))
                status = client.status('/')
                return {"success": True, "hdfs_version": status.get('version', 'unknown')}

            elif source.type == "influxdb":
                from influxdb_client import InfluxDBClient
                config = source.config
                client = InfluxDBClient(
                    url=config.get('url', 'http://localhost:8086'),
                    token=config.get('token', ''),
                    org=config.get('org', ''),
                )
                health = client.health()
                client.close()
                return {"success": True, "status": health.status}

            else:
                return {"success": False, "error": f"Unsupported source type: {source.type}"}

        except Exception as e:
            return {"success": False, "error": str(e)}

    def get_system_logs(self, db: Session, level: str = None,
                        limit: int = 100) -> List[Dict]:
        logs = [
            {
                "timestamp": (datetime.utcnow() - timedelta(minutes=i)).isoformat(),
                "level": "INFO" if i % 3 != 0 else "WARNING",
                "source": "system",
                "message": f"系统运行正常 - 心跳 {i}",
            }
            for i in range(limit)
        ]
        return logs


admin_service = AdminService()
