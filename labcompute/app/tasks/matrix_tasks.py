from celery import current_task
from celery.utils.log import get_task_logger
import time
from typing import Dict, Any, Optional

from app.config.celery_config import celery_app
from app.engines.matrix_engine import MatrixComputeEngine, MatrixEngineError
from app.modules.storage import StorageModule, StorageError, TaskNotFoundError
from app.modules.validation_utils import (
    MatrixValidator, ValidationConfig, ValidationLevel,
    GlobalValidator
)

logger = get_task_logger(__name__)

@celery_app.task(bind=True, name='matrix.multiply')
def matrix_multiply_task(
    self,
    task_id: str,
    matrix_a: Any,
    matrix_b: Any,
    block_config: Optional[Dict[str, Any]] = None,
    use_boundary_correction: bool = True,
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
        logger.info(f"Starting matrix multiply task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        def progress_callback(progress: int):
            try:
                storage.update_task_status(task_id, "running", progress=progress)
                self.update_state(state='PROGRESS', meta={'progress': progress})
            except Exception as e:
                logger.warning(f"Failed to update progress: {str(e)}")
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = MatrixValidator(validator_config)
        
        logger.info(f"Validating matrix A for task {task_id}")
        validation_a = validator.validate_matrix(matrix_a, name="matrix_a")
        if not validation_a.passed:
            error_msg = f"Matrix A validation failed: {validation_a.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation_a': {
                    'passed': validation_a.passed,
                    'errors': validation_a.errors,
                    'warnings': validation_a.warnings,
                    'metrics': validation_a.metrics
                }
            }
        
        logger.info(f"Validating matrix B for task {task_id}")
        validation_b = validator.validate_matrix(matrix_b, name="matrix_b")
        if not validation_b.passed:
            error_msg = f"Matrix B validation failed: {validation_b.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation_b': {
                    'passed': validation_b.passed,
                    'errors': validation_b.errors,
                    'warnings': validation_b.warnings,
                    'metrics': validation_b.metrics
                }
            }
        
        if block_config:
            from app.engines.matrix_engine import BlockConfig, BlockStrategy, BoundaryCorrectionMethod
            strategy_map = {
                'auto': BlockStrategy.AUTO,
                'fixed': BlockStrategy.FIXED,
                'memory_aware': BlockStrategy.MEMORY_AWARE
            }
            strategy = strategy_map.get(block_config.get('strategy', 'auto'), BlockStrategy.AUTO)
            
            correction_method_map = {
                'weighted_average': BoundaryCorrectionMethod.WEIGHTED_AVERAGE,
                'overlap_average': BoundaryCorrectionMethod.OVERLAP_AVERAGE,
                'cubic_interpolation': BoundaryCorrectionMethod.CUBIC_INTERPOLATION
            }
            correction_method = correction_method_map.get(
                block_config.get('boundary_correction_method', 'weighted_average'),
                BoundaryCorrectionMethod.WEIGHTED_AVERAGE
            )
            
            config = BlockConfig(
                strategy=strategy,
                block_size=block_config.get('block_size', 128),
                max_memory_bytes=block_config.get('max_memory_bytes', 1024 * 1024 * 1024),
                use_memmap=block_config.get('use_memmap', True),
                enable_boundary_correction=block_config.get('enable_boundary_correction', True),
                boundary_overlap=block_config.get('boundary_overlap', 4),
                boundary_correction_method=correction_method,
                validation_level=validation_level_enum
            )
            engine = MatrixComputeEngine(block_config=config)
        else:
            engine = MatrixComputeEngine()
        
        logger.info(f"Executing matrix multiplication for task {task_id}")
        result_data = engine.multiply(matrix_a, matrix_b, use_boundary_correction=use_boundary_correction)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        result_data['validation_a'] = {
            'passed': validation_a.passed,
            'errors': validation_a.errors,
            'warnings': validation_a.warnings,
            'metrics': validation_a.metrics
        }
        result_data['validation_b'] = {
            'passed': validation_b.passed,
            'errors': validation_b.errors,
            'warnings': validation_b.warnings,
            'metrics': validation_b.metrics
        }
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        storage.update_task_status(task_id, "completed", progress=100)
        
        logger.info(f"Matrix multiply task {task_id} completed in {execution_time:.2f}s")
        
        engine.cleanup()
        
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
    except MatrixEngineError as e:
        error_msg = f"Matrix computation error: {str(e)}"
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

@celery_app.task(bind=True, name='matrix.inverse')
def matrix_inverse_task(
    self,
    task_id: str,
    matrix: Any,
    block_config: Optional[Dict[str, Any]] = None,
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
        logger.info(f"Starting matrix inverse task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = MatrixValidator(validator_config)
        
        logger.info(f"Validating matrix for task {task_id}")
        validation = validator.validate_matrix(matrix)
        if not validation.passed:
            error_msg = f"Matrix validation failed: {validation.errors}"
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
        
        if block_config:
            from app.engines.matrix_engine import BlockConfig, BlockStrategy, BoundaryCorrectionMethod
            strategy_map = {
                'auto': BlockStrategy.AUTO,
                'fixed': BlockStrategy.FIXED,
                'memory_aware': BlockStrategy.MEMORY_AWARE
            }
            strategy = strategy_map.get(block_config.get('strategy', 'auto'), BlockStrategy.AUTO)
            
            correction_method_map = {
                'weighted_average': BoundaryCorrectionMethod.WEIGHTED_AVERAGE,
                'overlap_average': BoundaryCorrectionMethod.OVERLAP_AVERAGE,
                'cubic_interpolation': BoundaryCorrectionMethod.CUBIC_INTERPOLATION
            }
            correction_method = correction_method_map.get(
                block_config.get('boundary_correction_method', 'weighted_average'),
                BoundaryCorrectionMethod.WEIGHTED_AVERAGE
            )
            
            config = BlockConfig(
                strategy=strategy,
                block_size=block_config.get('block_size', 128),
                max_memory_bytes=block_config.get('max_memory_bytes', 1024 * 1024 * 1024),
                use_memmap=block_config.get('use_memmap', True),
                enable_boundary_correction=block_config.get('enable_boundary_correction', True),
                boundary_overlap=block_config.get('boundary_overlap', 4),
                boundary_correction_method=correction_method,
                validation_level=validation_level_enum
            )
            engine = MatrixComputeEngine(block_config=config)
        else:
            engine = MatrixComputeEngine()
        
        logger.info(f"Executing matrix inverse for task {task_id}")
        result_data = engine.inverse(matrix)
        
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
        
        logger.info(f"Matrix inverse task {task_id} completed in {execution_time:.2f}s")
        
        engine.cleanup()
        
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
    except MatrixEngineError as e:
        error_msg = f"Matrix computation error: {str(e)}"
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

@celery_app.task(bind=True, name='matrix.eigenvalues')
def matrix_eigenvalues_task(
    self,
    task_id: str,
    matrix: Any,
    block_config: Optional[Dict[str, Any]] = None,
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
        logger.info(f"Starting matrix eigenvalues task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = MatrixValidator(validator_config)
        
        logger.info(f"Validating matrix for task {task_id}")
        validation = validator.validate_matrix(matrix)
        if not validation.passed:
            error_msg = f"Matrix validation failed: {validation.errors}"
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
        
        if block_config:
            from app.engines.matrix_engine import BlockConfig, BlockStrategy, BoundaryCorrectionMethod
            strategy_map = {
                'auto': BlockStrategy.AUTO,
                'fixed': BlockStrategy.FIXED,
                'memory_aware': BlockStrategy.MEMORY_AWARE
            }
            strategy = strategy_map.get(block_config.get('strategy', 'auto'), BlockStrategy.AUTO)
            
            config = BlockConfig(
                strategy=strategy,
                block_size=block_config.get('block_size', 128),
                max_memory_bytes=block_config.get('max_memory_bytes', 1024 * 1024 * 1024),
                use_memmap=block_config.get('use_memmap', True),
                enable_boundary_correction=block_config.get('enable_boundary_correction', True),
                boundary_overlap=block_config.get('boundary_overlap', 4),
                validation_level=validation_level_enum
            )
            engine = MatrixComputeEngine(block_config=config)
        else:
            engine = MatrixComputeEngine()
        
        logger.info(f"Executing matrix eigenvalues for task {task_id}")
        result_data = engine.eigenvalues(matrix)
        
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
        
        logger.info(f"Matrix eigenvalues task {task_id} completed in {execution_time:.2f}s")
        
        engine.cleanup()
        
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
    except MatrixEngineError as e:
        error_msg = f"Matrix computation error: {str(e)}"
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

@celery_app.task(bind=True, name='matrix.transpose')
def matrix_transpose_task(
    self,
    task_id: str,
    matrix: Any,
    block_config: Optional[Dict[str, Any]] = None,
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
        logger.info(f"Starting matrix transpose task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = MatrixValidator(validator_config)
        
        logger.info(f"Validating matrix for task {task_id}")
        validation = validator.validate_matrix(matrix)
        if not validation.passed:
            error_msg = f"Matrix validation failed: {validation.errors}"
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
        
        if block_config:
            from app.engines.matrix_engine import BlockConfig, BlockStrategy
            strategy_map = {
                'auto': BlockStrategy.AUTO,
                'fixed': BlockStrategy.FIXED,
                'memory_aware': BlockStrategy.MEMORY_AWARE
            }
            strategy = strategy_map.get(block_config.get('strategy', 'auto'), BlockStrategy.AUTO)
            
            config = BlockConfig(
                strategy=strategy,
                block_size=block_config.get('block_size', 128),
                max_memory_bytes=block_config.get('max_memory_bytes', 1024 * 1024 * 1024),
                use_memmap=block_config.get('use_memmap', True),
                validation_level=validation_level_enum
            )
            engine = MatrixComputeEngine(block_config=config)
        else:
            engine = MatrixComputeEngine()
        
        logger.info(f"Executing matrix transpose for task {task_id}")
        result_data = engine.transpose(matrix)
        
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
        
        logger.info(f"Matrix transpose task {task_id} completed in {execution_time:.2f}s")
        
        engine.cleanup()
        
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
    except MatrixEngineError as e:
        error_msg = f"Matrix computation error: {str(e)}"
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

@celery_app.task(bind=True, name='matrix.add')
def matrix_add_task(
    self,
    task_id: str,
    matrix_a: Any,
    matrix_b: Any,
    block_config: Optional[Dict[str, Any]] = None,
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
        logger.info(f"Starting matrix add task {task_id}")
        
        storage.update_task_status(task_id, "running", progress=0)
        
        validator_config = ValidationConfig(level=validation_level_enum)
        validator = MatrixValidator(validator_config)
        
        logger.info(f"Validating matrix A for task {task_id}")
        validation_a = validator.validate_matrix(matrix_a, name="matrix_a")
        if not validation_a.passed:
            error_msg = f"Matrix A validation failed: {validation_a.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation_a': {
                    'passed': validation_a.passed,
                    'errors': validation_a.errors,
                    'warnings': validation_a.warnings,
                    'metrics': validation_a.metrics
                }
            }
        
        logger.info(f"Validating matrix B for task {task_id}")
        validation_b = validator.validate_matrix(matrix_b, name="matrix_b")
        if not validation_b.passed:
            error_msg = f"Matrix B validation failed: {validation_b.errors}"
            logger.error(error_msg)
            try:
                storage.update_task_status(task_id, "failed", error_message=error_msg)
            except Exception:
                pass
            return {
                'task_id': task_id,
                'status': 'failed',
                'error': error_msg,
                'validation_b': {
                    'passed': validation_b.passed,
                    'errors': validation_b.errors,
                    'warnings': validation_b.warnings,
                    'metrics': validation_b.metrics
                }
            }
        
        if block_config:
            from app.engines.matrix_engine import BlockConfig, BlockStrategy
            strategy_map = {
                'auto': BlockStrategy.AUTO,
                'fixed': BlockStrategy.FIXED,
                'memory_aware': BlockStrategy.MEMORY_AWARE
            }
            strategy = strategy_map.get(block_config.get('strategy', 'auto'), BlockStrategy.AUTO)
            
            config = BlockConfig(
                strategy=strategy,
                block_size=block_config.get('block_size', 128),
                max_memory_bytes=block_config.get('max_memory_bytes', 1024 * 1024 * 1024),
                use_memmap=block_config.get('use_memmap', True),
                validation_level=validation_level_enum
            )
            engine = MatrixComputeEngine(block_config=config)
        else:
            engine = MatrixComputeEngine()
        
        logger.info(f"Executing matrix addition for task {task_id}")
        result_data = engine.add(matrix_a, matrix_b)
        
        execution_time = time.time() - start_time
        result_data['execution_time_seconds'] = execution_time
        
        result_data['validation_a'] = {
            'passed': validation_a.passed,
            'errors': validation_a.errors,
            'warnings': validation_a.warnings,
            'metrics': validation_a.metrics
        }
        result_data['validation_b'] = {
            'passed': validation_b.passed,
            'errors': validation_b.errors,
            'warnings': validation_b.warnings,
            'metrics': validation_b.metrics
        }
        
        storage.save_result(task_id, result_data, execution_time_seconds=execution_time)
        storage.update_task_status(task_id, "completed", progress=100)
        
        logger.info(f"Matrix add task {task_id} completed in {execution_time:.2f}s")
        
        engine.cleanup()
        
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
    except MatrixEngineError as e:
        error_msg = f"Matrix computation error: {str(e)}"
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
