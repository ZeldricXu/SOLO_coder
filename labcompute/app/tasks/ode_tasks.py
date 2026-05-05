from celery import current_task
from celery.utils.log import get_task_logger
import time
import numpy as np
from typing import Dict, Any, Optional

from app.config.celery_config import celery_app
from app.engines.ode_engine import ODEComputeEngine, ODEStatus, ParameterError
from app.modules.storage import StorageModule, StorageError, TaskNotFoundError
from app.modules.validation_utils import (
    ODEValidator, ValidationConfig, ValidationLevel,
    StatsValidator
)

logger = get_task_logger(__name__)

@celery_app.task(bind=True, name='ode.solve')
def ode_solve_task(
    self,
    task_id: str,
    config: Dict[str, Any],
    validation_level: str = "standard"
) -> Dict[str, Any]:
    storage = StorageModule()
    start_time = time.time()
    
    level_map = {
        "strict": ValidationLevel.STRICT,
        "standard": ValidationLevel.STANDARD,
        "lenient": ValidationLevel.LENIENT
    }
    validation_level_enum = level_map.get(validation_level, ValidationLevel.STANDARD)
    
    try:
        logger.info(f"Starting ODE solve task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        def progress_callback(progress: int):
            try:
                storage.update_task_status(task_id, "running", progress=progress)
                self.update_state(state='PROGRESS', meta={'progress': progress})
            except Exception as e:
                logger.warning(f"Failed to update progress: {str(e)}")
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = ODEValidator(validator_config)
        stats_validator = StatsValidator(validator_config)
        
        initial_value = config.get('initial_value')
        validation_initial = None
        if initial_value is not None:
            try:
                if isinstance(initial_value, (int, float)):
                    initial_arr = np.array([initial_value], dtype=np.float64)
                else:
                    initial_arr = np.array(initial_value, dtype=np.float64)
                
                validation_initial = stats_validator.validate_numerical_data(initial_arr)
                if not validation_initial.passed:
                    error_msg = f"Initial value validation failed: {validation_initial.errors}"
                    logger.error(error_msg)
                    try:
                        storage.update_task_status(task_id, "failed", error_message=error_msg)
                    except Exception:
                        pass
                    return {
                        'task_id': task_id,
                        'status': 'failed',
                        'error': error_msg,
                        'validation_initial': {
                            'passed': validation_initial.passed,
                            'errors': validation_initial.errors,
                            'warnings': validation_initial.warnings,
                            'metrics': validation_initial.metrics
                        }
                    }
            except Exception as e:
                logger.warning(f"Initial value validation error: {str(e)}")
        
        engine = ODEComputeEngine(validation_level=validation_level_enum)
        
        result_data = engine.solve(config, progress_callback=progress_callback)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        if validation_initial:
            result_data['validation_initial'] = {
                'passed': validation_initial.passed,
                'errors': validation_initial.errors,
                'warnings': validation_initial.warnings,
                'metrics': validation_initial.metrics
            }
        
        result_data['validation_level'] = validation_level
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        
        status = result_data.get('status', 'stable')
        if status in [ODEStatus.PARAM_ERROR.value, ODEStatus.DIVERGED.value, ODEStatus.MAX_STEPS_EXCEEDED.value]:
            error_msg = result_data.get('error_message', f"Solver returned status: {status}")
            storage.update_task_status(task_id, "failed", progress=100, error_message=error_msg)
            logger.warning(f"ODE solve task {task_id} completed with status: {status}")
        else:
            storage.update_task_status(task_id, "completed", progress=100)
            logger.info(f"ODE solve task {task_id} completed in {execution_time:.2f}s")
        
        return {
            'task_id': task_id,
            'status': 'completed' if status == ODEStatus.STABLE.value else 'failed',
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
    except ParameterError as e:
        error_msg = f"Parameter error: {str(e)}"
        logger.error(f"Task {task_id} failed: {error_msg}")
        try:
            storage.update_task_status(task_id, "failed", error_message=error_msg)
        except Exception:
            pass
        return {
            'task_id': task_id,
            'status': 'failed',
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
