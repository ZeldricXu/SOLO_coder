from celery import current_task
from celery.utils.log import get_task_logger
import time
from typing import Dict, Any, Optional

from app.config.celery_config import celery_app
from app.engines.stats_engine import StatsComputeEngine, StatsEngineError
from app.modules.storage import StorageModule, StorageError, TaskNotFoundError
from app.modules.validation_utils import (
    StatsValidator, ValidationConfig, ValidationLevel,
    GlobalValidator
)

logger = get_task_logger(__name__)

@celery_app.task(bind=True, name='stats.descriptive')
def stats_descriptive_task(
    self,
    task_id: str,
    data: Any,
    include_mode: bool = True,
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
        logger.info(f"Starting descriptive stats task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = StatsValidator(validator_config)
        
        logger.info(f"Validating data for task {task_id}")
        validation = validator.validate_numerical_data(data)
        if not validation.passed:
            error_msg = f"Data validation failed: {validation.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation': {
                    'passed': validation.passed,
                    'errors': validation.errors,
                    'warnings': validation.warnings,
                    'metrics': validation.metrics
                }
            }
        
        engine = StatsComputeEngine()
        
        logger.info(f"Executing descriptive statistics for task {task_id}")
        result_data = engine.descriptive_stats(data, include_mode=include_mode)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        result_data['validation'] = {
            'passed': validation.passed,
            'errors': validation.errors,
            'warnings': validation.warnings,
            'metrics': validation.metrics
        }
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        storage.update_task_status(task_id, "completed", progress=100)
        
        logger.info(f"Descriptive stats task {task_id} completed in {execution_time:.2f}s")
        
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
    except StatsEngineError as e:
        error_msg = f"Statistics computation error: {str(e)}"
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

@celery_app.task(bind=True, name='stats.regression')
def stats_regression_task(
    self,
    task_id: str,
    x_data: Any,
    y_data: Any,
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
        logger.info(f"Starting linear regression task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = StatsValidator(validator_config)
        
        logger.info(f"Validating X data for task {task_id}")
        validation_x = validator.validate_numerical_data(x_data)
        if not validation_x.passed:
            error_msg = f"X data validation failed: {validation_x.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation_x': {
                    'passed': validation_x.passed,
                    'errors': validation_x.errors,
                    'warnings': validation_x.warnings,
                    'metrics': validation_x.metrics
                }
            }
        
        logger.info(f"Validating Y data for task {task_id}")
        validation_y = validator.validate_numerical_data(y_data)
        if not validation_y.passed:
            error_msg = f"Y data validation failed: {validation_y.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation_y': {
                    'passed': validation_y.passed,
                    'errors': validation_y.errors,
                    'warnings': validation_y.warnings,
                    'metrics': validation_y.metrics
                }
            }
        
        engine = StatsComputeEngine()
        
        logger.info(f"Executing linear regression for task {task_id}")
        result_data = engine.linear_regression(x_data, y_data)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        result_data['validation_x'] = {
            'passed': validation_x.passed,
            'errors': validation_x.errors,
            'warnings': validation_x.warnings,
            'metrics': validation_x.metrics
        }
        result_data['validation_y'] = {
            'passed': validation_y.passed,
            'errors': validation_y.errors,
            'warnings': validation_y.warnings,
            'metrics': validation_y.metrics
        }
        
        if 'slope' in result_data and 'intercept' in result_data:
            try:
                import numpy as np
                x_arr = np.array(x_data, dtype=np.float64).flatten()
                y_arr = np.array(y_data, dtype=np.float64).flatten()
                
                regression_validation = validator.validate_regression_result(
                    x_arr, y_arr,
                    result_data['slope'],
                    result_data['intercept'],
                    result_data.get('r_squared', 0.0)
                )
                
                result_data['regression_validation'] = {
                    'passed': regression_validation.passed,
                    'errors': regression_validation.errors,
                    'warnings': regression_validation.warnings,
                    'metrics': regression_validation.metrics
                }
            except Exception as e:
                logger.warning(f"Regression validation failed: {str(e)}")
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        storage.update_task_status(task_id, "completed", progress=100)
        
        logger.info(f"Linear regression task {task_id} completed in {execution_time:.2f}s")
        
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
    except StatsEngineError as e:
        error_msg = f"Statistics computation error: {str(e)}"
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

@celery_app.task(bind=True, name='stats.ttest')
def stats_ttest_task(
    self,
    task_id: str,
    data1: Any,
    data2: Optional[Any] = None,
    popmean: Optional[float] = None,
    test_type: str = "independent",
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
        logger.info(f"Starting t-test task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = StatsValidator(validator_config)
        
        logger.info(f"Validating data1 for task {task_id}")
        validation1 = validator.validate_numerical_data(data1)
        if not validation1.passed:
            error_msg = f"Data1 validation failed: {validation1.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation1': {
                    'passed': validation1.passed,
                    'errors': validation1.errors,
                    'warnings': validation1.warnings,
                    'metrics': validation1.metrics
                }
            }
        
        validation2 = None
        if data2 is not None:
            logger.info(f"Validating data2 for task {task_id}")
            validation2 = validator.validate_numerical_data(data2)
            if not validation2.passed:
                error_msg = f"Data2 validation failed: {validation2.errors}"
                logger.error(error_msg)
                try:
                    storage.update_task_status(task_id, "failed", error_message=error_msg)
                except Exception:
                    pass
                return {
                    'task_id': task_id,
                    'status': 'failed',
                    'error': error_msg,
                    'validation2': {
                        'passed': validation2.passed,
                        'errors': validation2.errors,
                        'warnings': validation2.warnings,
                        'metrics': validation2.metrics
                    }
                }
        
        engine = StatsComputeEngine()
        
        logger.info(f"Executing t-test for task {task_id}")
        result_data = engine.t_test(data1, data2, popmean, test_type)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        result_data['validation1'] = {
            'passed': validation1.passed,
            'errors': validation1.errors,
            'warnings': validation1.warnings,
            'metrics': validation1.metrics
        }
        
        if validation2:
            result_data['validation2'] = {
                'passed': validation2.passed,
                'errors': validation2.errors,
                'warnings': validation2.warnings,
                'metrics': validation2.metrics
            }
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        storage.update_task_status(task_id, "completed", progress=100)
        
        logger.info(f"T-test task {task_id} completed in {execution_time:.2f}s")
        
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
    except StatsEngineError as e:
        error_msg = f"Statistics computation error: {str(e)}"
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

@celery_app.task(bind=True, name='stats.correlation')
def stats_correlation_task(
    self,
    task_id: str,
    x_data: Any,
    y_data: Any,
    method: str = "pearson",
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
        logger.info(f"Starting correlation task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = StatsValidator(validator_config)
        
        logger.info(f"Validating X data for task {task_id}")
        validation_x = validator.validate_numerical_data(x_data)
        if not validation_x.passed:
            error_msg = f"X data validation failed: {validation_x.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation_x': {
                    'passed': validation_x.passed,
                    'errors': validation_x.errors,
                    'warnings': validation_x.warnings,
                    'metrics': validation_x.metrics
                }
            }
        
        logger.info(f"Validating Y data for task {task_id}")
        validation_y = validator.validate_numerical_data(y_data)
        if not validation_y.passed:
            error_msg = f"Y data validation failed: {validation_y.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation_y': {
                    'passed': validation_y.passed,
                    'errors': validation_y.errors,
                    'warnings': validation_y.warnings,
                    'metrics': validation_y.metrics
                }
            }
        
        engine = StatsComputeEngine()
        
        logger.info(f"Executing correlation for task {task_id}")
        result_data = engine.correlation(x_data, y_data, method=method)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        result_data['validation_x'] = {
            'passed': validation_x.passed,
            'errors': validation_x.errors,
            'warnings': validation_x.warnings,
            'metrics': validation_x.metrics
        }
        result_data['validation_y'] = {
            'passed': validation_y.passed,
            'errors': validation_y.errors,
            'warnings': validation_y.warnings,
            'metrics': validation_y.metrics
        }
        
        if 'correlation' in result_data:
            p_value = result_data.get('p_value')
            correlation_validation = validator.validate_correlation(
                result_data['correlation'],
                p_value
            )
            
            result_data['correlation_validation'] = {
                'passed': correlation_validation.passed,
                'errors': correlation_validation.errors,
                'warnings': correlation_validation.warnings,
                'metrics': correlation_validation.metrics
            }
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        storage.update_task_status(task_id, "completed", progress=100)
        
        logger.info(f"Correlation task {task_id} completed in {execution_time:.2f}s")
        
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
    except StatsEngineError as e:
        error_msg = f"Statistics computation error: {str(e)}"
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

@celery_app.task(bind=True, name='stats.distribution')
def stats_distribution_task(
    self,
    task_id: str,
    data: Any,
    distribution: str = "normal",
    fit_params: bool = True,
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
        logger.info(f"Starting distribution fit task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = StatsValidator(validator_config)
        
        logger.info(f"Validating data for task {task_id}")
        validation = validator.validate_numerical_data(data)
        if not validation.passed:
            error_msg = f"Data validation failed: {validation.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation': {
                    'passed': validation.passed,
                    'errors': validation.errors,
                    'warnings': validation.warnings,
                    'metrics': validation.metrics
                }
            }
        
        engine = StatsComputeEngine()
        
        logger.info(f"Executing distribution fitting for task {task_id}")
        result_data = engine.probability_distribution(data, distribution=distribution, fit_params=fit_params)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        result_data['validation'] = {
            'passed': validation.passed,
            'errors': validation.errors,
            'warnings': validation.warnings,
            'metrics': validation.metrics
        }
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        storage.update_task_status(task_id, "completed", progress=100)
        
        logger.info(f"Distribution fit task {task_id} completed in {execution_time:.2f}s")
        
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
    except StatsEngineError as e:
        error_msg = f"Statistics computation error: {str(e)}"
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
