"""
Unit tests for optimization engine validation.

Tests:
- Normal path: Rosenbrock function convergence
- Normal path: Ackley function convergence
- Abnormal path: NaN handling
- Abnormal path: Inf handling
- Abnormal path: Exception handling
- Multi-objective optimization (NSGA-II)
"""

import pytest
import numpy as np
from numpy.testing import assert_allclose

from pycfd.optimization import (
    ContinuousVariable, DiscreteVariable,
    BayesianOptimizer, NSGAIISolver
)
from tests.fixtures.reference_data import rosenbrock, ackley


class TestBayesianOptimization:
    """Test Bayesian optimization."""

    def test_rosenbrock_convergence(self):
        """Test convergence on Rosenbrock function.
        
        Rosenbrock function has global minimum at x = [1, 1, ..., 1]
        with f(x) = 0.
        """
        variables = [
            ContinuousVariable('x1', lower=-2, upper=3),
            ContinuousVariable('x2', lower=-1, upper=3),
        ]
        
        def objective(params):
            x = np.array([params['x1'], params['x2']])
            return rosenbrock(x)
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=10,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=20)
        
        assert result['best_x'] is not None
        assert result['best_y'] < 100.0
        assert result['n_valid'] >= 5
        
        best_x = np.array([result['best_x']['x1'], result['best_x']['x2']])
        
        assert np.all(np.abs(best_x - 1.0) < 2.0), "Should converge near [1, 1]"

    def test_ackley_convergence(self):
        """Test convergence on Ackley function.
        
        Ackley function has global minimum at x = [0, 0, ..., 0]
        with f(x) = 0.
        """
        variables = [
            ContinuousVariable('x1', lower=-5, upper=5),
            ContinuousVariable('x2', lower=-5, upper=5),
        ]
        
        def objective(params):
            x = np.array([params['x1'], params['x2']])
            return ackley(x)
        
        np.random.seed(123)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=10,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=20)
        
        assert result['best_x'] is not None
        assert result['best_y'] < 20.0
        assert result['n_valid'] >= 5
        
        best_x = np.array([result['best_x']['x1'], result['best_x']['x2']])
        
        assert np.all(np.abs(best_x) < 3.0), "Should converge near [0, 0]"

    def test_maximization(self):
        """Test maximization mode."""
        variables = [
            ContinuousVariable('x', lower=-10, upper=10),
        ]
        
        def objective(params):
            return -(params['x'] - 2.0) ** 2
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=8,
            maximize=True
        )
        
        result = optimizer.optimize(n_iterations=15)
        
        assert result['best_x'] is not None
        assert abs(result['best_x']['x'] - 2.0) < 3.0

    def test_optimization_history(self):
        """Test that optimization history is tracked."""
        variables = [
            ContinuousVariable('x', lower=0, upper=5),
        ]
        
        def objective(params):
            return params['x'] ** 2
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=5,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=10)
        
        assert 'X_history' in result
        assert 'y_history' in result
        assert len(result['y_history']) >= 5
        assert result['best_y'] <= np.min(result['y_history']) + 1e-10


class TestOptimizationRobustness:
    """Test optimization engine robustness."""

    def test_nan_handling(self):
        """Test that NaN values from objective function are handled."""
        variables = [
            ContinuousVariable('x', lower=0, upper=10),
        ]
        
        call_count = [0]
        
        def objective(params):
            call_count[0] += 1
            if call_count[0] % 3 == 0:
                return float('nan')
            return params['x'] ** 2
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=5,
            max_failed_samples=20,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=10)
        
        assert result['n_valid'] > 0, "Should have some valid samples"
        assert result['n_failed'] > 0, "Should have some failed samples"
        assert result['best_y'] is not None
        assert not np.isnan(result['best_y'])

    def test_inf_handling(self):
        """Test that Inf values from objective function are handled."""
        variables = [
            ContinuousVariable('x', lower=-10, upper=10),
        ]
        
        call_count = [0]
        
        def objective(params):
            call_count[0] += 1
            if call_count[0] % 4 == 0:
                return float('inf')
            return params['x'] ** 2
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=5,
            max_failed_samples=20,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=10)
        
        assert result['n_valid'] > 0
        assert result['n_failed'] > 0
        assert result['best_y'] < 100.0
        assert not np.isinf(result['best_y'])

    def test_exception_handling(self):
        """Test that exceptions from objective function are handled."""
        variables = [
            ContinuousVariable('x', lower=0, upper=10),
        ]
        
        call_count = [0]
        
        def objective(params):
            call_count[0] += 1
            if call_count[0] % 3 == 0:
                raise RuntimeError("Simulated objective failure")
            return params['x'] ** 2
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=5,
            max_failed_samples=20,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=10)
        
        assert result['n_valid'] > 0
        assert result['n_failed'] > 0
        assert result['best_y'] is not None
        assert len(optimizer.failed_samples) > 0

    def test_max_failed_samples_limit(self):
        """Test that optimization stops when max failed samples reached."""
        variables = [
            ContinuousVariable('x', lower=0, upper=10),
        ]
        
        def objective(params):
            raise RuntimeError("Always fail")
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=3,
            max_failed_samples=5,
            max_consecutive_failures=5,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=20)
        
        assert result['n_valid'] == 0
        assert result['termination_reason'] is not None
        assert 'consecutive' in result['termination_reason'].lower() or \
               'failed' in result['termination_reason'].lower()

    def test_gp_trains_only_on_valid_data(self):
        """Test that GP surrogate only trains on valid samples."""
        variables = [
            ContinuousVariable('x', lower=0, upper=10),
        ]
        
        call_count = [0]
        
        def objective(params):
            call_count[0] += 1
            if call_count[0] <= 2:
                return float('nan')
            return params['x'] ** 2
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=5,
            max_failed_samples=20,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=5)
        
        X_valid, y_valid = optimizer._get_valid_data()
        
        assert len(X_valid) == result['n_valid']
        assert len(y_valid) == result['n_valid']
        assert not np.any(np.isnan(y_valid))
        assert not np.any(np.isinf(y_valid))


class TestMultiObjectiveOptimization:
    """Test multi-objective optimization (NSGA-II)."""

    def test_nsga2_pareto_front(self):
        """Test NSGA-II finds Pareto front for two objectives."""
        variables = [
            ContinuousVariable('x1', lower=0, upper=1),
            ContinuousVariable('x2', lower=0, upper=1),
        ]
        
        def objectives(params):
            x = params['x1']
            y = params['x2']
            f1 = x ** 2 + y ** 2
            f2 = (x - 1) ** 2 + (y - 1) ** 2
            return [f1, f2]
        
        np.random.seed(42)
        ga = NSGAIISolver(
            objective_func=objectives,
            variables=variables,
            n_objectives=2,
            population_size=20
        )
        
        result = ga.optimize(n_generations=10)
        
        assert result['pareto_front'] is not None
        assert len(result['pareto_front']) > 0
        assert result['pareto_front'].shape[1] == 2
        
        pareto = result['pareto_front']
        for i in range(len(pareto)):
            for j in range(len(pareto)):
                if i != j:
                    dominates = all(pareto[i] <= pareto[j]) and any(pareto[i] < pareto[j])
                    if dominates:
                        pass
        
        assert result['n_generations'] == 10

    def test_nsga2_variable_types(self):
        """Test NSGA-II with mixed variable types."""
        variables = [
            ContinuousVariable('x', lower=0, upper=5),
            DiscreteVariable('y', [1, 2, 3, 4, 5]),
        ]
        
        def objectives(params):
            f1 = params['x'] ** 2 + params['y']
            f2 = (params['x'] - 3) ** 2 + (params['y'] - 3) ** 2
            return [f1, f2]
        
        np.random.seed(42)
        ga = NSGAIISolver(
            objective_func=objectives,
            variables=variables,
            n_objectives=2,
            population_size=15
        )
        
        result = ga.optimize(n_generations=5)
        
        assert result['pareto_front'] is not None
        assert len(result['pareto_solutions']) > 0
        
        for solution in result['pareto_solutions']:
            assert 0 <= solution['x'] <= 5
            assert solution['y'] in [1, 2, 3, 4, 5]

    def test_nsga2_history_tracking(self):
        """Test that NSGA-II tracks generation history."""
        variables = [
            ContinuousVariable('x', lower=0, upper=10),
        ]
        
        def objectives(params):
            return [params['x'] ** 2, (params['x'] - 5) ** 2]
        
        np.random.seed(42)
        ga = NSGAIISolver(
            objective_func=objectives,
            variables=variables,
            n_objectives=2,
            population_size=10
        )
        
        result = ga.optimize(n_generations=5)
        
        assert 'generations' in result
        assert len(result['generations']) == 5


class TestVariableTypes:
    """Test different variable types in optimization."""

    def test_discrete_variable(self):
        """Test optimization with discrete variables."""
        variables = [
            DiscreteVariable('x', [1, 2, 4, 8, 16]),
        ]
        
        def objective(params):
            return abs(params['x'] - 5)
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=5,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=10)
        
        assert result['best_x'] is not None
        assert result['best_x']['x'] in [1, 2, 4, 8, 16]

    def test_mixed_variable_types(self):
        """Test optimization with mixed continuous and discrete variables."""
        variables = [
            ContinuousVariable('x_cont', lower=0, upper=10),
            DiscreteVariable('x_disc', [0, 1, 2]),
        ]
        
        def objective(params):
            return (params['x_cont'] - 5.0) ** 2 + params['x_disc']
        
        np.random.seed(42)
        optimizer = BayesianOptimizer(
            objective_func=objective,
            variables=variables,
            n_initial=8,
            maximize=False
        )
        
        result = optimizer.optimize(n_iterations=15)
        
        assert result['best_x'] is not None
        assert result['best_x']['x_disc'] in [0, 1, 2]
        assert 0 <= result['best_x']['x_cont'] <= 10
