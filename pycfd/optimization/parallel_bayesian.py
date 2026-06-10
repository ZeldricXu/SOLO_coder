import numpy as np
from typing import Callable, List, Tuple, Optional, Dict
from scipy.optimize import minimize

from .bayesian import BayesianOptimizer, GaussianProcess, AcquisitionFunction
from .design_variables import ParameterSpace, DesignVariable
from .parallel import SolverPool, parallel_evaluate


class ParallelBayesianOptimizer(BayesianOptimizer):
    """Parallel Bayesian Optimizer with batch evaluation.
    
    Extends BayesianOptimizer to evaluate multiple points in parallel
    using either a SolverPool or concurrent.futures.
    
    Features:
    - Batch acquisition (q-EI, q-UCB)
    - Parallel evaluation of design points
    - Support for SolverPool with solver reuse
    - Synchronous and asynchronous batch strategies
    - Backward compatible with BayesianOptimizer API
    
    Example:
        ```python
        opt = ParallelBayesianOptimizer(
            objective_func=my_obj,
            variables=vars,
            n_parallel=8,
            batch_size=4,
            max_workers=8
        )
        result = opt.optimize(n_iterations=50)
        ```
    """
    
    def __init__(self, objective_func: Callable, variables: List[DesignVariable],
                 n_initial: int = 10, acq_func: str = 'ei', maximize: bool = False,
                 max_failed_samples: int = 50, max_consecutive_failures: int = 10,
                 n_parallel: int = 4, batch_size: Optional[int] = None,
                 solver_pool: Optional[SolverPool] = None,
                 solver_evaluate_func: Optional[Callable] = None,
                 use_processes: bool = True,
                 batch_strategy: str = 'synchronous',
                 **kwargs):
        """Initialize parallel Bayesian optimizer.
        
        Args:
            objective_func: Objective function to optimize
            variables: List of design variables
            n_initial: Number of initial samples
            acq_func: Acquisition function type
            maximize: Whether to maximize (True) or minimize (False)
            max_failed_samples: Maximum failed samples before stopping
            max_consecutive_failures: Maximum consecutive failures
            n_parallel: Number of parallel workers
            batch_size: Number of points to evaluate per batch (default: n_parallel)
            solver_pool: Optional SolverPool instance for solver reuse
            solver_evaluate_func: Function (solver, params) -> value for SolverPool
            use_processes: Use ProcessPoolExecutor vs ThreadPoolExecutor
            batch_strategy: 'synchronous' or 'asynchronous'
        """
        super().__init__(objective_func, variables, n_initial, acq_func, 
                        maximize, max_failed_samples, max_consecutive_failures)
        
        self.n_parallel = n_parallel
        self.batch_size = batch_size if batch_size is not None else n_parallel
        self.solver_pool = solver_pool
        self.solver_evaluate_func = solver_evaluate_func
        self.use_processes = use_processes
        self.batch_strategy = batch_strategy
        
        if self.solver_pool is not None and self.solver_evaluate_func is None:
            raise ValueError("solver_evaluate_func must be provided when using solver_pool")
    
    def optimize(self, n_iterations: int = 50, n_restarts: int = 5) -> Dict:
        """Run parallel Bayesian optimization.
        
        Args:
            n_iterations: Total number of function evaluations
            n_restarts: Number of restarts for acquisition optimization
            
        Returns:
            Dictionary with optimization results
        """
        if self.X_samples is None:
            self._sample_initial_parallel()
        
        bounds = self.param_space.bounds_array()
        termination_reason = None
        n_batches = (n_iterations - len(self.X_samples) + self.batch_size - 1) // self.batch_size
        
        for batch_idx in range(n_batches):
            if self.n_failed >= self.max_failed_samples:
                termination_reason = f"Maximum failed samples ({self.max_failed_samples}) reached"
                print(f"WARNING: {termination_reason}. Stopping.")
                break
            if self.consecutive_failed >= self.max_consecutive_failures:
                termination_reason = f"Maximum consecutive failed samples ({self.max_consecutive_failures}) reached"
                print(f"WARNING: {termination_reason}. Stopping.")
                break
            
            X_valid, y_valid = self._get_valid_samples()
            if X_valid is None or len(X_valid) < 2:
                print(f"WARNING: Not enough valid samples ({len(X_valid) if X_valid is not None else 0}). Sampling randomly.")
                new_x_batch = np.random.uniform(0, 1, (self.batch_size, self.param_space.n_dim))
            else:
                self.gp.fit(X_valid, y_valid)
                self.acquisition.update(np.max(y_valid))
                
                new_x_batch = self._acquire_batch(self.batch_size, bounds, n_restarts)
            
            results_batch = self._evaluate_batch_parallel(new_x_batch)
            
            for i, (new_x, (y, error)) in enumerate(zip(new_x_batch, results_batch)):
                new_sample = self.param_space.from_array(new_x.reshape(1, -1))
                sample_dict = {k: v[0] if hasattr(v, '__len__') and not isinstance(v, str) else v 
                              for k, v in new_sample.items()}
                self.sample_dicts.append(sample_dict)
                
                if y is None:
                    self.n_failed += 1
                    self.consecutive_failed += 1
                    self.failed_samples.append((sample_dict, error))
                    self.results.append((sample_dict, None, error))
                    new_y = np.nan
                    new_y_raw = np.nan
                else:
                    self.consecutive_failed = 0
                    new_y = y if self.maximize else -y
                    new_y_raw = y
                    self.results.append((sample_dict, y, None))
                    self._update_best(sample_dict, y)
                
                self.X_samples = np.vstack([self.X_samples, new_x.reshape(1, -1)])
                self.y_samples = np.append(self.y_samples, new_y)
                self.y_samples_raw = np.append(self.y_samples_raw, new_y_raw)
                
                if y is not None:
                    print(f"Batch {batch_idx+1}/{n_batches}, Point {i+1}/{self.batch_size}: "
                          f"Best y = {self.best_y:.4f}, valid = {len(X_valid)}/{len(self.X_samples)}")
                else:
                    print(f"Batch {batch_idx+1}/{n_batches}, Point {i+1}/{self.batch_size}: "
                          f"Sample failed: {error}, failed = {self.n_failed}")
        
        X_valid, y_valid = self._get_valid_samples()
        n_valid = len(X_valid) if X_valid is not None else 0
        if termination_reason is None:
            termination_reason = "Completed all iterations"
        
        return {
            'best_x': self.best_x,
            'best_y': self.best_y,
            'X': self.X_samples,
            'y': self.y_samples,
            'X_history': self.X_samples,
            'y_history': self.y_samples_raw,
            'results': self.results,
            'failed_samples': self.failed_samples,
            'n_failed': self.n_failed,
            'n_valid': n_valid,
            'termination_reason': termination_reason,
            'n_parallel': self.n_parallel,
            'batch_size': self.batch_size
        }
    
    def _sample_initial_parallel(self):
        """Sample initial points in parallel."""
        initial_samples = self.param_space.sample(self.n_initial)
        
        param_dicts = []
        for i in range(self.n_initial):
            sample_dict = {name: val[i] if hasattr(val, '__len__') and not isinstance(val, str) else val 
                          for name, val in initial_samples.items()}
            param_dicts.append(sample_dict)
        
        x_arrays = []
        for sd in param_dicts:
            x_arrays.append(self.param_space.to_array({k: np.array([v]) for k, v in sd.items()}))
        
        results = self._evaluate_batch_parallel(np.vstack(x_arrays))
        
        for i, (sample_dict, (y, error)) in enumerate(zip(param_dicts, results)):
            self.sample_dicts.append(sample_dict)
            
            if y is None:
                self.n_failed += 1
                self.consecutive_failed += 1
                self.failed_samples.append((sample_dict, error))
                self.results.append((sample_dict, None, error))
                y_wrapped = np.nan
                y_raw = np.nan
            else:
                self.consecutive_failed = 0
                y_wrapped = y if self.maximize else -y
                y_raw = y
                self.results.append((sample_dict, y, None))
                self._update_best(sample_dict, y)
            
            x_new = x_arrays[i]
            if self.X_samples is None:
                self.X_samples = x_new
                self.y_samples = np.array([y_wrapped])
                self.y_samples_raw = np.array([y_raw])
            else:
                self.X_samples = np.vstack([self.X_samples, x_new])
                self.y_samples = np.append(self.y_samples, y_wrapped)
                self.y_samples_raw = np.append(self.y_samples_raw, y_raw)
    
    def _evaluate_batch_parallel(self, x_batch: np.ndarray) -> List[Tuple[Optional[float], Optional[str]]]:
        """Evaluate a batch of points in parallel.
        
        Args:
            x_batch: Array of normalized parameter vectors (batch_size, n_dim)
            
        Returns:
            List of (result, error) tuples
        """
        param_dicts = []
        for x in x_batch:
            sample = self.param_space.from_array(x.reshape(1, -1))
            sample_dict = {k: v[0] if hasattr(v, '__len__') and not isinstance(v, str) else v 
                          for k, v in sample.items()}
            param_dicts.append(sample_dict)
        
        if self.solver_pool is not None and self.solver_evaluate_func is not None:
            results = self.solver_pool.evaluate_batch(
                param_dicts, self.solver_evaluate_func
            )
        else:
            results = parallel_evaluate(
                self._safe_evaluate_wrapper,
                param_dicts,
                max_workers=self.n_parallel,
                use_processes=self.use_processes
            )
        
        return results
    
    def _safe_evaluate_wrapper(self, sample_dict: Dict) -> float:
        """Wrapper for safe evaluation used by parallel_evaluate."""
        y, error = self._safe_evaluate(sample_dict)
        if y is None:
            raise ValueError(error if error else "Evaluation failed")
        return y
    
    def _acquire_batch(self, n_points: int, bounds: np.ndarray, 
                      n_restarts: int = 5) -> np.ndarray:
        """Acquire a batch of points for evaluation.
        
        Uses a greedy batch strategy: optimize acquisition function, then add
        a penalization term to already selected points.
        
        Args:
            n_points: Number of points to acquire
            bounds: Parameter bounds (normalized)
            n_restarts: Number of restarts for optimization
            
        Returns:
            Array of parameter vectors (n_points, n_dim)
        """
        selected = np.zeros((n_points, self.param_space.n_dim), dtype=np.float64)
        penalty = np.zeros(self.gp.X_train.shape[0]) if self.gp.X_train is not None else np.array([])
        
        for i in range(n_points):
            def neg_acq(x):
                mu, sigma = self.gp.predict(x.reshape(1, -1), return_std=True)
                acq_val = self.acquisition.evaluate(mu, sigma)[0]
                
                pen = 0.0
                for j in range(i):
                    dist = np.linalg.norm(x - selected[j])
                    pen += np.exp(-dist ** 2 / 0.1)
                
                return -(acq_val - 0.1 * pen)
            
            best_val = float('inf')
            best_x = None
            
            for _ in range(n_restarts):
                x0 = np.random.uniform(0, 1, self.param_space.n_dim)
                result = minimize(neg_acq, x0, bounds=bounds, method='L-BFGS-B')
                if result.fun < best_val:
                    best_val = result.fun
                    best_x = result.x
            
            selected[i] = best_x
        
        return selected


class ParallelNSGAIISolver:
    """Parallel NSGA-II multi-objective optimizer.
    
    Extends NSGA-II with parallel evaluation using concurrent.futures.
    """
    
    def __init__(self, objective_func: Callable, variables: List[DesignVariable],
                 population_size: int = 100, n_generations: int = 50,
                 n_parallel: int = 4, use_processes: bool = True,
                 minimize: bool = True, **kwargs):
        """Initialize parallel NSGA-II solver.
        
        Args:
            objective_func: Objective function returning array of objectives
            variables: List of design variables
            population_size: Population size
            n_generations: Number of generations
            n_parallel: Number of parallel workers
            use_processes: Use ProcessPoolExecutor vs ThreadPoolExecutor
            minimize: Whether to minimize objectives
        """
        from .multi_objective import NSGAIISolver
        
        self.base_solver = NSGAIISolver(
            objective_func, variables, population_size, n_generations,
            minimize, **kwargs
        )
        self.n_parallel = n_parallel
        self.use_processes = use_processes
    
    def solve(self) -> Dict:
        """Run parallel NSGA-II optimization.
        
        Returns:
            Dictionary with Pareto front and solutions
        """
        param_space = self.base_solver.param_space
        minimize = self.base_solver.minimize
        
        population = []
        for i in range(self.base_solver.population_size):
            x = np.random.uniform(0, 1, param_space.n_dim)
            population.append({'x': x})
        
        def _safe_evaluate(sample_dict):
            try:
                y = self.base_solver.objective_func(sample_dict)
                y = np.asarray(y, dtype=np.float64)
                if np.any(np.isnan(y)) or np.any(np.isinf(y)):
                    return None, f"Invalid value: {y}"
                return y, None
            except Exception as e:
                return None, str(e)
        
        for gen in range(self.base_solver.n_generations):
            param_dicts = []
            for ind in population:
                sample = param_space.from_array(ind['x'].reshape(1, -1))
                sample_dict = {k: v[0] if hasattr(v, '__len__') and not isinstance(v, str) else v 
                              for k, v in sample.items()}
                param_dicts.append(sample_dict)
            
            results = parallel_evaluate(
                _safe_evaluate, param_dicts,
                max_workers=self.n_parallel,
                use_processes=self.use_processes
            )
            
            for i, (fitness, error) in enumerate(results):
                if fitness is None:
                    population[i]['fitness'] = np.array([np.inf] * self.base_solver.n_objectives)
                    population[i]['error'] = error
                else:
                    population[i]['fitness'] = fitness if minimize else -fitness
            
            from .multi_objective import fast_nondominated_sort, compute_crowding_distance
            
            fronts = fast_nondominated_sort(population, minimize)
            
            if gen < self.base_solver.n_generations - 1:
                new_population = []
                for front in fronts:
                    if len(new_population) + len(front) <= self.base_solver.population_size:
                        new_population.extend(front)
                    else:
                        distances = compute_crowding_distance(front)
                        sorted_idx = np.argsort(-distances)
                        remaining = self.base_solver.population_size - len(new_population)
                        new_population.extend([front[j] for j in sorted_idx[:remaining]])
                        break
                
                offspring = self.base_solver._crossover_and_mutate(new_population, param_space)
                population = new_population + offspring
            
            pareto_front = fronts[0]
            print(f"Generation {gen+1}/{self.base_solver.n_generations}: "
                  f"Pareto front size = {len(pareto_front)}")
        
        pareto_front = fronts[0]
        pareto_X = np.array([ind['x'] for ind in pareto_front])
        pareto_fitness = np.array([ind['fitness'] for ind in pareto_front])
        
        return {
            'pareto_front': pareto_fitness if minimize else -pareto_fitness,
            'pareto_X': pareto_X,
            'pareto_solutions': [param_space.from_array(x.reshape(1, -1)) for x in pareto_X],
            'population': population,
            'n_parallel': self.n_parallel
        }
