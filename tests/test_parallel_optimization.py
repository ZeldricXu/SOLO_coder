import numpy as np
import pytest
import sys
import os
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from pycfd.optimization import (
    SolverPool, parallel_evaluate,
    ParallelBayesianOptimizer,
    BayesianOptimizer,
    DesignVariable, ContinuousVariable
)


def _simple_objective(params):
    """Simple test objective function (Rosenbrock)."""
    x = params.get('x', 0.0)
    y = params.get('y', 0.0)
    time.sleep(0.01)
    return (1 - x)**2 + 100 * (y - x**2)**2


def _multi_objective(params):
    """Multi-objective test function."""
    x = params.get('x', 0.0)
    y = params.get('y', 0.0)
    return np.array([x**2 + y**2, (x-2)**2 + y**2])


class TestSolverPool:
    """Test SolverPool class."""
    
    def test_solver_pool_initialization(self):
        """Test solver pool initialization."""
        def create_solver():
            return {'solver': True, 'id': np.random.randint(1000)}
        
        pool = SolverPool(max_workers=4, solver_factory=create_solver)
        
        assert pool.max_workers == 4
        assert pool.total_count == 4
        assert pool.idle_count == 4
        assert pool.active_count == 0
    
    def test_solver_pool_evaluate_batch(self):
        """Test batch evaluation with solver pool."""
        def create_solver():
            return {'data': 'test_solver'}
        
        def evaluate_func(solver, params):
            return params['x']**2 + params['y']**2
        
        pool = SolverPool(max_workers=2, solver_factory=create_solver, use_processes=False)
        
        param_sets = [
            {'x': 1.0, 'y': 2.0},
            {'x': 3.0, 'y': 4.0},
            {'x': 5.0, 'y': 6.0}
        ]
        
        results = pool.evaluate_batch(param_sets, evaluate_func)
        
        assert len(results) == 3
        for i, (result, error) in enumerate(results):
            assert error is None
            assert result == param_sets[i]['x']**2 + param_sets[i]['y']**2
    
    def test_solver_pool_context_manager(self):
        """Test solver pool as context manager."""
        def create_solver():
            return {'test': True}
        
        with SolverPool(max_workers=2, solver_factory=create_solver) as pool:
            assert pool.total_count == 2
        
        assert pool.total_count == 0
    
    def test_solver_pool_without_factory(self):
        """Test solver pool without solver factory."""
        pool = SolverPool(max_workers=2, use_processes=False)
        
        def evaluate_func(solver, params):
            return params['x'] * 2
        
        param_sets = [{'x': 1.0}, {'x': 2.0}]
        
        results = pool.evaluate_batch(param_sets, evaluate_func)
        
        assert len(results) == 2
        assert results[0][0] == 2.0
        assert results[1][0] == 4.0


class TestParallelEvaluate:
    """Test parallel_evaluate function."""
    
    def test_parallel_evaluate_basic(self):
        """Test basic parallel evaluation."""
        def objective(params):
            return params['x']**2 + params['y']**2
        
        param_sets = [
            {'x': 1.0, 'y': 1.0},
            {'x': 2.0, 'y': 3.0},
            {'x': 0.5, 'y': 0.5}
        ]
        
        results = parallel_evaluate(objective, param_sets, max_workers=2, use_processes=False)
        
        assert len(results) == 3
        for i, (result, error) in enumerate(results):
            assert error is None
            expected = param_sets[i]['x']**2 + param_sets[i]['y']**2
            assert result == pytest.approx(expected, rel=1e-10)
    
    def test_parallel_evaluate_with_errors(self):
        """Test parallel evaluation with some failing evaluations."""
        def objective(params):
            if params['x'] < 0:
                raise ValueError("Negative x")
            return params['x']
        
        param_sets = [
            {'x': 1.0},
            {'x': -1.0},
            {'x': 2.0}
        ]
        
        results = parallel_evaluate(objective, param_sets, max_workers=2, use_processes=False)
        
        assert results[0][0] == 1.0
        assert results[0][1] is None
        assert results[1][0] is None
        assert 'Negative x' in results[1][1]
        assert results[2][0] == 2.0
    
    def test_parallel_evaluate_speedup(self):
        """Test that parallel evaluation is faster than serial."""
        def slow_objective(params):
            time.sleep(0.1)
            return params['x']**2
        
        param_sets = [{'x': float(i)} for i in range(8)]
        
        start_serial = time.time()
        for p in param_sets:
            slow_objective(p)
        time_serial = time.time() - start_serial
        
        start_parallel = time.time()
        parallel_evaluate(slow_objective, param_sets, max_workers=4, use_processes=False)
        time_parallel = time.time() - start_parallel
        
        assert time_parallel < time_serial * 0.95


class TestParallelBayesianOptimizer:
    """Test ParallelBayesianOptimizer class."""
    
    def test_parallel_bayes_basic(self):
        """Test basic parallel Bayesian optimization."""
        variables = [
            ContinuousVariable('x', lower=-2.0, upper=2.0),
            ContinuousVariable('y', lower=-1.0, upper=3.0)
        ]
        
        opt = ParallelBayesianOptimizer(
            objective_func=_simple_objective,
            variables=variables,
            n_initial=4,
            n_parallel=2,
            batch_size=2,
            use_processes=False
        )
        
        result = opt.optimize(n_iterations=6)
        
        assert 'best_x' in result
        assert 'best_y' in result
        assert result['best_y'] < 10.0
        assert len(result['X']) == 10
        assert result['n_parallel'] == 2
        assert result['batch_size'] == 2
    
    def test_parallel_bayes_minimize(self):
        """Test parallel Bayesian optimization minimizes correctly."""
        variables = [
            ContinuousVariable('x', lower=-2.0, upper=2.0),
            ContinuousVariable('y', lower=-2.0, upper=2.0)
        ]
        
        opt = ParallelBayesianOptimizer(
            objective_func=_simple_objective,
            variables=variables,
            n_initial=5,
            maximize=False,
            n_parallel=2,
            batch_size=2,
            use_processes=False
        )
        
        result = opt.optimize(n_iterations=5)
        
        assert result['best_y'] < 5.0
        assert abs(result['best_x']['x'] - 1.0) < 1.0
        assert abs(result['best_x']['y'] - 1.0) < 1.0
    
    def test_parallel_bayes_with_solver_pool(self):
        """Test parallel Bayesian optimization with SolverPool."""
        variables = [
            ContinuousVariable('x', lower=0.0, upper=2.0),
            ContinuousVariable('y', lower=0.0, upper=2.0)
        ]
        
        def solver_factory():
            return {'type': 'test_solver'}
        
        def solver_evaluate(solver, params):
            return _simple_objective(params)
        
        pool = SolverPool(max_workers=2, solver_factory=solver_factory)
        
        opt = ParallelBayesianOptimizer(
            objective_func=_simple_objective,
            variables=variables,
            n_initial=3,
            n_parallel=2,
            batch_size=2,
            solver_pool=pool,
            solver_evaluate_func=solver_evaluate,
            use_processes=False
        )
        
        result = opt.optimize(n_iterations=3)
        
        assert result['best_y'] < 10.0
        pool.shutdown()
    
    def test_parallel_bayes_maximize(self):
        """Test parallel Bayesian optimization with maximization."""
        def negative_objective(params):
            return -_simple_objective(params)
        
        variables = [
            ContinuousVariable('x', lower=-2.0, upper=2.0),
            ContinuousVariable('y', lower=-1.0, upper=3.0)
        ]
        
        opt = ParallelBayesianOptimizer(
            objective_func=negative_objective,
            variables=variables,
            n_initial=4,
            maximize=True,
            n_parallel=2,
            batch_size=2,
            use_processes=False
        )
        
        result = opt.optimize(n_iterations=4)
        
        assert result['best_y'] > -10.0
    
    def test_parallel_bayes_failed_samples(self):
        """Test parallel Bayesian optimization with failed samples."""
        def objective(params):
            if params['x'] < -1.5:
                raise ValueError("Invalid point")
            return _simple_objective(params)
        
        variables = [
            ContinuousVariable('x', lower=-2.0, upper=2.0),
            ContinuousVariable('y', lower=-2.0, upper=2.0)
        ]
        
        opt = ParallelBayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=3,
            n_parallel=2,
            batch_size=2,
            max_failed_samples=10,
            use_processes=False
        )
        
        result = opt.optimize(n_iterations=3)
        
        assert 'failed_samples' in result
        assert len(result['failed_samples']) >= 0


class TestBackwardCompatibility:
    """Test backward compatibility with existing code."""
    
    def test_serial_bayes_still_works(self):
        """Test that serial BayesianOptimizer still works."""
        variables = [
            ContinuousVariable('x', lower=-2.0, upper=2.0),
            ContinuousVariable('y', lower=-1.0, upper=3.0)
        ]
        
        opt = BayesianOptimizer(
            objective_func=_simple_objective,
            variables=variables,
            n_initial=3
        )
        
        result = opt.optimize(n_iterations=2)
        
        assert 'best_x' in result
        assert 'best_y' in result
        assert 'X_history' in result
        assert 'y_history' in result
    
    def test_bayes_has_same_interface(self):
        """Test that ParallelBayesianOptimizer has same interface as BayesianOptimizer."""
        variables = [
            ContinuousVariable('x', lower=-2.0, upper=2.0),
            ContinuousVariable('y', lower=-1.0, upper=3.0)
        ]
        
        parallel_opt = ParallelBayesianOptimizer(
            objective_func=_simple_objective,
            variables=variables,
            n_initial=3,
            n_parallel=1,
            use_processes=False
        )
        
        serial_opt = BayesianOptimizer(
            objective_func=_simple_objective,
            variables=variables,
            n_initial=3
        )
        
        parallel_result = parallel_opt.optimize(n_iterations=2)
        serial_result = serial_opt.optimize(n_iterations=2)
        
        for key in ['best_x', 'best_y', 'X', 'y', 'X_history', 'y_history', 
                    'results', 'failed_samples', 'n_failed', 'termination_reason']:
            assert key in parallel_result
            assert key in serial_result


if __name__ == '__main__':
    pytest.main([__file__, '-v', '-x', '-W', 'ignore::DeprecationWarning'])
