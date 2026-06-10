import numpy as np
from typing import Callable, List, Dict, Optional, Tuple, Any
from concurrent.futures import ThreadPoolExecutor, ProcessPoolExecutor, Future, as_completed
import copy
import logging
import functools

logger = logging.getLogger(__name__)


def _evaluate_with_objective(objective_func: Callable, params: Dict) -> Tuple[Optional[float], Optional[str]]:
    """Module-level helper for safe evaluation (pickle-friendly)."""
    try:
        y = objective_func(params)
        y = float(y)
        if np.isnan(y) or np.isinf(y):
            return None, f"Invalid value: {y}"
        return y, None
    except Exception as e:
        return None, str(e)


def _evaluate_with_solver(solver: Any, evaluate_func: Callable, params: Dict) -> Tuple[Optional[float], Optional[str]]:
    """Module-level helper for solver-based evaluation (pickle-friendly)."""
    try:
        result = evaluate_func(solver, params)
        if result is not None:
            result = float(result)
            if np.isnan(result) or np.isinf(result):
                return None, f"Invalid value: {result}"
        return result, None
    except Exception as e:
        return None, str(e)


class SolverPool:
    """Pool of solver instances for parallel evaluation of multiple design points.
    
    Each solver in the pool runs independently, allowing concurrent evaluation
    of multiple design configurations.
    
    Example:
        ```python
        pool = SolverPool(max_workers=8, solver_factory=create_solver)
        results = pool.evaluate_batch([params1, params2, params3], evaluate_func)
        ```
    """
    
    def __init__(self, max_workers: int = 4, solver_factory: Optional[Callable] = None,
                 use_processes: bool = True):
        """Initialize solver pool.
        
        Args:
            max_workers: Maximum number of concurrent workers
            solver_factory: Function that creates a new solver instance
            use_processes: Use ProcessPoolExecutor (True) or ThreadPoolExecutor (False)
        """
        self.max_workers = max_workers
        self.solver_factory = solver_factory
        self.use_processes = use_processes
        self._solvers: List[Any] = []
        self._idle_solvers: List[Any] = []
        self._active_count: int = 0
        
        if solver_factory is not None:
            self._initialize_solvers()
    
    def _initialize_solvers(self):
        """Pre-initialize solver instances."""
        for i in range(self.max_workers):
            solver = self.solver_factory()
            self._solvers.append(solver)
            self._idle_solvers.append(solver)
    
    def _get_solver(self) -> Any:
        """Get an idle solver from the pool."""
        if len(self._idle_solvers) > 0:
            solver = self._idle_solvers.pop(0)
            self._active_count += 1
            return solver
        elif len(self._solvers) < self.max_workers and self.solver_factory is not None:
            solver = self.solver_factory()
            self._solvers.append(solver)
            self._active_count += 1
            return solver
        else:
            return None
    
    def _return_solver(self, solver: Any):
        """Return a solver to the idle pool."""
        self._active_count -= 1
        self._idle_solvers.append(solver)
    
    def evaluate_batch(self, parameter_sets: List[Dict], 
                       evaluate_func: Callable,
                       timeout: Optional[float] = None) -> List[Tuple[Optional[float], Optional[str]]]:
        """Evaluate a batch of parameter sets in parallel.
        
        Args:
            parameter_sets: List of parameter dictionaries to evaluate
            evaluate_func: Function that takes (solver, params) and returns result
            timeout: Timeout for each evaluation in seconds
            
        Returns:
            List of (result, error_message) tuples
        """
        results = [(None, "Not evaluated")] * len(parameter_sets)
        
        if self.use_processes:
            return self._evaluate_batch_process(parameter_sets, evaluate_func, timeout)
        else:
            return self._evaluate_batch_thread(parameter_sets, evaluate_func, timeout)
    
    def _evaluate_batch_thread(self, parameter_sets: List[Dict],
                               evaluate_func: Callable,
                               timeout: Optional[float] = None) -> List[Tuple[Optional[float], Optional[str]]]:
        """Evaluate batch using ThreadPoolExecutor (shares solver pool)."""
        results = [(None, "Not evaluated")] * len(parameter_sets)
        
        import threading
        pool_lock = threading.Lock()
        
        def _thread_worker(params, idx):
            solver = None
            try:
                with pool_lock:
                    solver = self._get_solver()
                
                if solver is not None:
                    result = evaluate_func(solver, params)
                else:
                    result = evaluate_func(None, params)
                
                if result is not None:
                    result = float(result)
                    if np.isnan(result) or np.isinf(result):
                        return None, f"Invalid result: {result}"
                
                return result, None
            except Exception as e:
                logger.warning(f"Evaluation {idx} failed: {e}")
                return None, str(e)
            finally:
                if solver is not None:
                    with pool_lock:
                        self._return_solver(solver)
        
        with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
            futures = {}
            
            for i, params in enumerate(parameter_sets):
                future = executor.submit(_thread_worker, params, i)
                futures[future] = i
            
            for future in as_completed(futures, timeout=timeout):
                idx = futures[future]
                try:
                    result, error = future.result(timeout=timeout)
                    results[idx] = (result, error)
                except Exception as e:
                    results[idx] = (None, f"Evaluation failed: {str(e)}")
        
        return results
    
    def _evaluate_batch_process(self, parameter_sets: List[Dict],
                                evaluate_func: Callable,
                                timeout: Optional[float] = None) -> List[Tuple[Optional[float], Optional[str]]]:
        """Evaluate batch using ProcessPoolExecutor (each process creates its own solver)."""
        results = [(None, "Not evaluated")] * len(parameter_sets)
        
        factory = self.solver_factory
        
        def _process_worker(args):
            params, idx = args
            solver = None
            try:
                if factory is not None:
                    solver = factory()
                result = evaluate_func(solver if solver is not None else None, params)
                if result is not None:
                    result = float(result)
                    if np.isnan(result) or np.isinf(result):
                        return idx, None, f"Invalid result: {result}"
                return idx, result, None
            except Exception as e:
                return idx, None, str(e)
            finally:
                if solver is not None and hasattr(solver, 'close'):
                    try:
                        solver.close()
                    except:
                        pass
        
        with ProcessPoolExecutor(max_workers=self.max_workers) as executor:
            futures = {}
            
            for i, params in enumerate(parameter_sets):
                future = executor.submit(_process_worker, (params, i))
                futures[future] = i
            
            for future in as_completed(futures, timeout=timeout):
                try:
                    idx, result, error = future.result(timeout=timeout)
                    results[idx] = (result, error)
                except Exception as e:
                    idx = futures[future]
                    results[idx] = (None, f"Evaluation failed: {str(e)}")
        
        return results
    
    def shutdown(self):
        """Shutdown the pool and clean up solvers."""
        for solver in self._solvers:
            if hasattr(solver, 'close'):
                solver.close()
            elif hasattr(solver, '__del__'):
                try:
                    solver.__del__()
                except:
                    pass
        
        self._solvers.clear()
        self._idle_solvers.clear()
    
    def __enter__(self):
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.shutdown()
    
    @property
    def active_count(self) -> int:
        """Number of currently active solvers."""
        return self._active_count
    
    @property
    def idle_count(self) -> int:
        """Number of idle solvers."""
        return len(self._idle_solvers)
    
    @property
    def total_count(self) -> int:
        """Total number of solvers in pool."""
        return len(self._solvers)


def parallel_evaluate(objective_func: Callable, 
                      parameter_sets: List[Dict],
                      max_workers: int = 4,
                      use_processes: bool = True,
                      timeout: Optional[float] = None) -> List[Tuple[Optional[float], Optional[str]]]:
    """Evaluate multiple parameter sets in parallel without a solver pool.
    
    Args:
        objective_func: Function that takes params dict and returns float
        parameter_sets: List of parameter dictionaries
        max_workers: Maximum concurrent evaluations
        use_processes: Use processes vs threads
        timeout: Timeout per evaluation
        
    Returns:
        List of (result, error) tuples
    """
    results = [(None, "Not evaluated")] * len(parameter_sets)
    
    ExecutorClass = ProcessPoolExecutor if use_processes else ThreadPoolExecutor
    
    evaluator = functools.partial(_evaluate_with_objective, objective_func)
    
    with ExecutorClass(max_workers=max_workers) as executor:
        futures = {}
        
        for i, params in enumerate(parameter_sets):
            future = executor.submit(evaluator, params)
            futures[future] = i
        
        for future in as_completed(futures, timeout=timeout):
            idx = futures[future]
            try:
                result, error = future.result(timeout=timeout)
                results[idx] = (result, error)
            except Exception as e:
                results[idx] = (None, f"Evaluation failed: {str(e)}")
    
    return results
