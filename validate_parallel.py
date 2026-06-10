#!/usr/bin/env python3
"""Quick validation of parallel optimization features."""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np

print("=== Testing parallel optimization features ===")

# Test 1: SolverPool
print("\n1. Testing SolverPool...")
from pycfd.optimization.parallel import SolverPool

def create_solver():
    return {'data': 'test_solver', 'id': np.random.randint(1000)}

def evaluate_func(solver, params):
    return params['x']**2 + params['y']**2

with SolverPool(max_workers=2, solver_factory=create_solver, use_processes=False) as pool:
    print(f"  Pool created: {pool.total_count} solvers total, {pool.idle_count} idle")
    
    param_sets = [
        {'x': 1.0, 'y': 2.0},
        {'x': 3.0, 'y': 4.0},
    ]
    
    results = pool.evaluate_batch(param_sets, evaluate_func)
    for i, (result, error) in enumerate(results):
        expected = param_sets[i]['x']**2 + param_sets[i]['y']**2
        status = "PASS" if result == expected else f"FAIL (got {result}, expected {expected})"
        print(f"  Result {i}: {status}, error={error}")

# Test 2: parallel_evaluate
print("\n2. Testing parallel_evaluate...")
from pycfd.optimization.parallel import parallel_evaluate

def objective(params):
    return params['x']**2 + params['y']**2

param_sets = [
    {'x': 1.0, 'y': 1.0},
    {'x': 2.0, 'y': 3.0},
]

results = parallel_evaluate(objective, param_sets, max_workers=2, use_processes=False)
for i, (result, error) in enumerate(results):
    expected = param_sets[i]['x']**2 + param_sets[i]['y']**2
    status = "PASS" if abs(result - expected) < 1e-10 else f"FAIL (got {result}, expected {expected})"
    print(f"  Result {i}: {status}, error={error}")

# Test 3: ParallelBayesianOptimizer
print("\n3. Testing ParallelBayesianOptimizer...")
from pycfd.optimization.parallel_bayesian import ParallelBayesianOptimizer
from pycfd.optimization.design_variables import ContinuousVariable

def rosenbrock(params):
    x = params['x']
    y = params['y']
    return (1 - x)**2 + 100 * (y - x**2)**2

variables = [
    ContinuousVariable('x', lower=-2.0, upper=2.0),
    ContinuousVariable('y', lower=-1.0, upper=3.0)
]

opt = ParallelBayesianOptimizer(
    objective_func=rosenbrock,
    variables=variables,
    n_initial=5,
    n_parallel=2,
    batch_size=2,
    use_processes=False
)

result = opt.optimize(n_iterations=6)
print(f"  Best y: {result['best_y']:.4f}")
print(f"  Best x: x={result['best_x']['x']:.4f}, y={result['best_x']['y']:.4f}")
print(f"  Total samples: {len(result['X'])}")
print(f"  Failed samples: {result['n_failed']}")
status = "PASS" if result['best_y'] < 10.0 else f"FAIL (best_y={result['best_y']})"
print(f"  Optimization: {status}")

# Test 4: Backward compatibility
print("\n4. Testing backward compatibility...")
from pycfd.optimization.bayesian import BayesianOptimizer

opt_serial = BayesianOptimizer(
    objective_func=rosenbrock,
    variables=variables,
    n_initial=5
)
result_serial = opt_serial.optimize(n_iterations=3)
print(f"  Serial optimizer works: {len(result_serial['X_history']) > 0}")
print(f"  Best y (serial): {result_serial['best_y']:.4f}")

# Verify same result keys
keys_match = all(k in result for k in result_serial)
print(f"  Result keys match: {keys_match}")

print("\n=== All tests completed ===")
