from sqlalchemy.orm import Session
from sqlalchemy.exc import SQLAlchemyError
from typing import Dict, Any, Optional, List
from datetime import datetime
import uuid

from app.core.database import ComputeTask, ComputeResult, SessionLocal
from app.config.settings import settings

class StorageError(Exception):
    pass

class TaskNotFoundError(StorageError):
    pass

class ResultNotFoundError(StorageError):
    pass

class StorageModule:
    
    def __init__(self):
        pass
    
    def _get_db(self) -> Session:
        return SessionLocal()
    
    def generate_task_id(self) -> str:
        return f"task_{uuid.uuid4().hex[:12]}"
    
    def generate_result_id(self) -> str:
        return f"result_{uuid.uuid4().hex[:12]}"
    
    def create_task(
        self,
        task_type: str,
        input_data: Dict[str, Any],
        priority: int = None
    ) -> str:
        if priority is None:
            priority = settings.DEFAULT_TASK_PRIORITY
        
        task_id = self.generate_task_id()
        
        db = self._get_db()
        try:
            task = ComputeTask(
                task_id=task_id,
                task_type=task_type,
                input_data=input_data,
                status="pending",
                progress=0,
                priority=priority
            )
            db.add(task)
            db.commit()
            db.refresh(task)
            return task_id
        except SQLAlchemyError as e:
            db.rollback()
            raise StorageError(f"Failed to create task: {str(e)}")
        finally:
            db.close()
    
    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        db = self._get_db()
        try:
            task = db.query(ComputeTask).filter(ComputeTask.task_id == task_id).first()
            if task is None:
                return None
            return self._task_to_dict(task)
        except SQLAlchemyError as e:
            raise StorageError(f"Failed to get task: {str(e)}")
        finally:
            db.close()
    
    def update_task_status(
        self,
        task_id: str,
        status: str,
        progress: int = None,
        error_message: str = None
    ) -> bool:
        db = self._get_db()
        try:
            task = db.query(ComputeTask).filter(ComputeTask.task_id == task_id).first()
            if task is None:
                raise TaskNotFoundError(f"Task not found: {task_id}")
            
            task.status = status
            if progress is not None:
                task.progress = progress
            if error_message is not None:
                task.error_message = error_message
            
            if status == "running" and task.started_at is None:
                task.started_at = datetime.utcnow()
            if status in ["completed", "failed", "error"]:
                task.completed_at = datetime.utcnow()
            
            db.commit()
            return True
        except TaskNotFoundError:
            raise
        except SQLAlchemyError as e:
            db.rollback()
            raise StorageError(f"Failed to update task status: {str(e)}")
        finally:
            db.close()
    
    def get_all_tasks(
        self,
        status: str = None,
        task_type: str = None,
        limit: int = 100,
        offset: int = 0
    ) -> List[Dict[str, Any]]:
        db = self._get_db()
        try:
            query = db.query(ComputeTask)
            
            if status:
                query = query.filter(ComputeTask.status == status)
            if task_type:
                query = query.filter(ComputeTask.task_type == task_type)
            
            tasks = query.order_by(ComputeTask.created_at.desc()).offset(offset).limit(limit).all()
            
            return [self._task_to_dict(task) for task in tasks]
        except SQLAlchemyError as e:
            raise StorageError(f"Failed to get tasks: {str(e)}")
        finally:
            db.close()
    
    def save_result(
        self,
        task_id: str,
        output_data: Dict[str, Any],
        execution_time_seconds: float = None
    ) -> str:
        result_id = self.generate_result_id()
        
        db = self._get_db()
        try:
            result = ComputeResult(
                result_id=result_id,
                task_id=task_id,
                output_data=output_data,
                computed_at=datetime.utcnow(),
                execution_time_seconds=execution_time_seconds
            )
            db.add(result)
            db.commit()
            db.refresh(result)
            
            return result_id
        except SQLAlchemyError as e:
            db.rollback()
            raise StorageError(f"Failed to save result: {str(e)}")
        finally:
            db.close()
    
    def get_result(self, task_id: str = None, result_id: str = None) -> Optional[Dict[str, Any]]:
        db = self._get_db()
        try:
            query = db.query(ComputeResult)
            
            if result_id:
                query = query.filter(ComputeResult.result_id == result_id)
            elif task_id:
                query = query.filter(ComputeResult.task_id == task_id)
            else:
                raise ValueError("Either task_id or result_id must be provided")
            
            result = query.first()
            if result is None:
                return None
            
            return self._result_to_dict(result)
        except SQLAlchemyError as e:
            raise StorageError(f"Failed to get result: {str(e)}")
        finally:
            db.close()
    
    def get_task_with_result(self, task_id: str) -> Optional[Dict[str, Any]]:
        task = self.get_task(task_id)
        if task is None:
            return None
        
        result = self.get_result(task_id=task_id)
        
        return {
            'task': task,
            'result': result
        }
    
    def delete_task(self, task_id: str) -> bool:
        db = self._get_db()
        try:
            task = db.query(ComputeTask).filter(ComputeTask.task_id == task_id).first()
            if task is None:
                raise TaskNotFoundError(f"Task not found: {task_id}")
            
            result = db.query(ComputeResult).filter(ComputeResult.task_id == task_id).first()
            if result:
                db.delete(result)
            
            db.delete(task)
            db.commit()
            return True
        except TaskNotFoundError:
            raise
        except SQLAlchemyError as e:
            db.rollback()
            raise StorageError(f"Failed to delete task: {str(e)}")
        finally:
            db.close()
    
    def get_task_statistics(self) -> Dict[str, Any]:
        db = self._get_db()
        try:
            from sqlalchemy import func
            
            total = db.query(func.count(ComputeTask.id)).scalar()
            pending = db.query(func.count(ComputeTask.id)).filter(ComputeTask.status == "pending").scalar()
            running = db.query(func.count(ComputeTask.id)).filter(ComputeTask.status == "running").scalar()
            completed = db.query(func.count(ComputeTask.id)).filter(ComputeTask.status == "completed").scalar()
            failed = db.query(func.count(ComputeTask.id)).filter(
                ComputeTask.status.in_(["failed", "error"])
            ).scalar()
            
            return {
                'total_tasks': int(total) if total else 0,
                'pending': int(pending) if pending else 0,
                'running': int(running) if running else 0,
                'completed': int(completed) if completed else 0,
                'failed': int(failed) if failed else 0
            }
        except SQLAlchemyError as e:
            raise StorageError(f"Failed to get statistics: {str(e)}")
        finally:
            db.close()
    
    def _task_to_dict(self, task: ComputeTask) -> Dict[str, Any]:
        return {
            'id': task.id,
            'task_id': task.task_id,
            'task_type': task.task_type,
            'input_data': task.input_data,
            'status': task.status,
            'progress': task.progress,
            'priority': task.priority,
            'error_message': task.error_message,
            'created_at': task.created_at.isoformat() if task.created_at else None,
            'started_at': task.started_at.isoformat() if task.started_at else None,
            'completed_at': task.completed_at.isoformat() if task.completed_at else None
        }
    
    def _result_to_dict(self, result: ComputeResult) -> Dict[str, Any]:
        return {
            'id': result.id,
            'result_id': result.result_id,
            'task_id': result.task_id,
            'output_data': result.output_data,
            'computed_at': result.computed_at.isoformat() if result.computed_at else None,
            'execution_time_seconds': result.execution_time_seconds
        }
