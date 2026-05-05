from celery import current_task
from celery.utils.log import get_task_logger
import time
from typing import Dict, Any, Optional

from app.config.celery_config import celery_app
from app.tasks.matrix_tasks import (
    matrix_multiply_task, matrix_inverse_task, matrix_eigenvalues_task,
    matrix_transpose_task, matrix_add_task
)
from app.tasks.ode_tasks import ode_solve_task
from app.tasks.stats_tasks import (
    stats_descriptive_task, stats_regression_task, stats_ttest_task,
    stats_correlation_task, stats_distribution_task
)
from app.modules.storage import StorageModule, StorageError, TaskNotFoundError

logger = get_task_logger(__name__)

TASK_TYPE_MAP = {
    'matrix_multiply': 'matrix',
    'matrix_inverse': 'matrix',
    'matrix_eigenvalues': 'matrix',
    'matrix_transpose': 'matrix',
    'matrix_add': 'matrix',
    'ode_solve': 'ode',
    'stats_descriptive': 'stats',
    'stats_regression': 'stats',
    'stats_ttest': 'stats',
    'stats_correlation': 'stats',
    'stats_distribution': 'stats'
}

@celery_app.task(bind=True, name='execute_compute_task')
def execute_compute_task(self, task_id: str, task_type: str, input_data: Dict[str, Any]) -> Dict[str, Any]:
    storage = StorageModule()
    start_time = time.time()
    
    try:
        logger.info(f"Starting task {task_id} of type {task_type}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        def progress_callback(progress: int):
            try:
                storage.update_task_status(task_id, "running", progress=progress)
                self.update_state(state='PROGRESS', meta={'progress': progress})
            except Exception as e:
                logger.warning(f"Failed to update progress: {str(e)}")
        
        result_data = _execute_task_logic(task_type, input_data, task_id, progress_callback)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        storage.update_task_status(task_id, "completed", progress=100)
        
        logger.info(f"Task {task_id} completed in {execution_time:.2f}s")
        
        return {
            'task_id': task_id,
            'status': 'completed',
            'result': result_data
        }
        
    except TaskNotFoundError:
        error_msg = f"Task {task_id} not found in database"
        logger.error(error_msg)
        return {
            'task_id': task_id,
            'status': 'error',
            'error': error_msg
        }
    except Exception as e:
        error_msg = f"Unexpected error: {str(e)}"
        logger.exception(f"Task {task_id} failed with exception")
        try:
            storage.update_task_status(task_id, "error", error_message=error_msg)
        except Exception:
            pass
        return {
            'task_id': task_id,
            'status': 'error',
            'error': error_msg
        }

def _execute_task_logic(
    task_type: str, 
    input_data: Dict[str, Any],
    task_id: str,
    progress_callback: Optional[callable] = None
) -> Dict[str, Any]:
    category = TASK_TYPE_MAP.get(task_type)
    
    if category == 'matrix':
        return _execute_matrix_task(task_type, input_data, task_id)
    elif category == 'ode':
        return _execute_ode_task(task_type, input_data, task_id, progress_callback)
    elif category == 'stats':
        return _execute_stats_task(task_type, input_data, task_id)
    else:
        raise ValueError(f"Unknown task type: {task_type}")

def _execute_matrix_task(task_type: str, input_data: Dict[str, Any], task_id: str) -> Dict[str, Any]:
    from app.engines.matrix_engine import MatrixComputeEngine
    
    engine = MatrixComputeEngine()
    
    if task_type == 'matrix_multiply':
        matrix_a = input_data.get('matrix_a')
        matrix_b = input_data.get('matrix_b')
        if matrix_a is None or matrix_b is None:
            raise ValueError("matrix_multiply requires 'matrix_a' and 'matrix_b'")
        result = engine.multiply(matrix_a, matrix_b)
        engine.cleanup()
        return result
    
    elif task_type == 'matrix_inverse':
        matrix = input_data.get('matrix')
        if matrix is None:
            raise ValueError("matrix_inverse requires 'matrix'")
        result = engine.inverse(matrix)
        engine.cleanup()
        return result
    
    elif task_type == 'matrix_eigenvalues':
        matrix = input_data.get('matrix')
        if matrix is None:
            raise ValueError("matrix_eigenvalues requires 'matrix'")
        result = engine.eigenvalues(matrix)
        engine.cleanup()
        return result
    
    elif task_type == 'matrix_transpose':
        matrix = input_data.get('matrix')
        if matrix is None:
            raise ValueError("matrix_transpose requires 'matrix'")
        result = engine.transpose(matrix)
        engine.cleanup()
        return result
    
    elif task_type == 'matrix_add':
        matrix_a = input_data.get('matrix_a')
        matrix_b = input_data.get('matrix_b')
        if matrix_a is None or matrix_b is None:
            raise ValueError("matrix_add requires 'matrix_a' and 'matrix_b'")
        result = engine.add(matrix_a, matrix_b)
        engine.cleanup()
        return result
    
    else:
        raise ValueError(f"Unknown matrix task type: {task_type}")

def _execute_ode_task(
    task_type: str, 
    input_data: Dict[str, Any],
    task_id: str,
    progress_callback: Optional[callable] = None
) -> Dict[str, Any]:
    from app.engines.ode_engine import ODEComputeEngine
    
    engine = ODEComputeEngine()
    
    if task_type == 'ode_solve':
        return engine.solve(input_data, progress_callback=progress_callback)
    else:
        raise ValueError(f"Unknown ODE task type: {task_type}")

def _execute_stats_task(task_type: str, input_data: Dict[str, Any], task_id: str) -> Dict[str, Any]:
    from app.engines.stats_engine import StatsComputeEngine
    
    engine = StatsComputeEngine()
    
    if task_type == 'stats_descriptive':
        data = input_data.get('data')
        if data is None:
            raise ValueError("stats_descriptive requires 'data'")
        include_mode = input_data.get('include_mode', True)
        return engine.descriptive_stats(data, include_mode=include_mode)
    
    elif task_type == 'stats_regression':
        x_data = input_data.get('x_data')
        y_data = input_data.get('y_data')
        if x_data is None or y_data is None:
            raise ValueError("stats_regression requires 'x_data' and 'y_data'")
        return engine.linear_regression(x_data, y_data)
    
    elif task_type == 'stats_ttest':
        data1 = input_data.get('data1')
        data2 = input_data.get('data2')
        popmean = input_data.get('popmean')
        test_type = input_data.get('test_type', 'independent')
        
        if test_type == 'one_sample':
            if data1 is None or popmean is None:
                raise ValueError("one_sample ttest requires 'data1' and 'popmean'")
            return engine.t_test(data1, popmean=popmean, test_type='one_sample')
        else:
            if data1 is None or data2 is None:
                raise ValueError("independent/paired ttest requires 'data1' and 'data2'")
            return engine.t_test(data1, data2, test_type=test_type)
    
    elif task_type == 'stats_correlation':
        x_data = input_data.get('x_data')
        y_data = input_data.get('y_data')
        method = input_data.get('method', 'pearson')
        if x_data is None or y_data is None:
            raise ValueError("stats_correlation requires 'x_data' and 'y_data'")
        return engine.correlation(x_data, y_data, method=method)
    
    elif task_type == 'stats_distribution':
        data = input_data.get('data')
        distribution = input_data.get('distribution', 'normal')
        if data is None:
            raise ValueError("stats_distribution requires 'data'")
        return engine.probability_distribution(data, distribution=distribution)
    
    else:
        raise ValueError(f"Unknown stats task type: {task_type}")

@celery_app.task(name='submit_task')
def submit_task(task_type: str, input_data: Dict[str, Any], priority: int = None) -> str:
    storage = StorageModule()
    
    task_id = storage.create_task(task_type, input_data, priority=priority)
    
    execute_compute_task.apply_async(
        args=[task_id, task_type, input_data],
        task_id=task_id,
        priority=priority if priority is not None else 5
    )
    
    return task_id

@celery_app.task(name='get_task_status')
def get_task_status(task_id: str) -> Dict[str, Any]:
    storage = StorageModule()
    task = storage.get_task(task_id)
    
    if task is None:
        return {'task_id': task_id, 'status': 'not_found'}
    
    result = storage.get_result(task_id=task_id)
    
    return {
        'task': task,
        'result': result
    }
