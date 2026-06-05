import numpy as np
import math
from typing import Callable, Dict, Any

class UDFExpression:
    def __init__(self, expression: str, variables: list = None):
        self.expression = expression
        self.variables = variables or ['x', 'y', 'z', 't']
        self._compiled = None
        self._namespace = self._build_namespace()
        self._compile()

    def _build_namespace(self) -> Dict[str, Any]:
        ns = {
            'sin': np.sin, 'cos': np.cos, 'tan': np.tan,
            'asin': np.arcsin, 'acos': np.arccos, 'atan': np.arctan,
            'sinh': np.sinh, 'cosh': np.cosh, 'tanh': np.tanh,
            'exp': np.exp, 'log': np.log, 'log10': np.log10,
            'sqrt': np.sqrt, 'abs': np.abs, 'fabs': np.fabs,
            'pow': np.power, 'pi': np.pi, 'e': np.e,
            'min': np.minimum, 'max': np.maximum,
            'floor': np.floor, 'ceil': np.ceil, 'round': np.round,
            'sign': np.sign, 'step': lambda x: np.where(x > 0, 1.0, 0.0),
            'rect': lambda x, a, b: np.where((x >= a) & (x <= b), 1.0, 0.0),
            'tanh_profile': lambda y, delta, U0: U0 * np.tanh(2.0 * y / delta),
            'blasius': lambda y, nu, U0, x: U0 * _blasius_profile(y, nu, U0, x),
            'parabolic': lambda y, H, Umax: Umax * (1 - (2.0 * y / H - 1.0) ** 2),
            'uniform': lambda value, shape: np.full(shape, value),
            'gaussian': lambda x, mu, sigma, amp: amp * np.exp(-((x - mu) ** 2) / (2 * sigma ** 2)),
            'np': np,
        }
        return ns

    def _compile(self):
        try:
            self._compiled = compile(self.expression, '<udf>', 'eval')
        except SyntaxError as e:
            raise ValueError(f"Invalid UDF expression: {e}")

    def evaluate(self, positions: np.ndarray, time: float = 0.0) -> np.ndarray:
        positions = np.asarray(positions, dtype=np.float64)
        local_vars = {}
        if positions.ndim == 1:
            positions = positions.reshape(1, -1)
        ndim = positions.shape[1]
        local_vars['x'] = positions[:, 0]
        if ndim >= 2:
            local_vars['y'] = positions[:, 1]
        else:
            local_vars['y'] = np.zeros_like(local_vars['x'])
        if ndim >= 3:
            local_vars['z'] = positions[:, 2]
        else:
            local_vars['z'] = np.zeros_like(local_vars['x'])
        local_vars['t'] = np.full_like(local_vars['x'], time)
        for var in self.variables:
            if var not in local_vars:
                local_vars[var] = np.zeros_like(local_vars['x'])
        try:
            result = eval(self._compiled, self._namespace, local_vars)
            if np.isscalar(result):
                result = np.full(positions.shape[0], result, dtype=np.float64)
            return np.asarray(result, dtype=np.float64)
        except Exception as e:
            raise RuntimeError(f"Error evaluating UDF: {e}")

    def __call__(self, positions: np.ndarray, time: float = 0.0) -> np.ndarray:
        return self.evaluate(positions, time)

def compile_udf(expression: str, variables: list = None) -> UDFExpression:
    return UDFExpression(expression, variables)

def _blasius_profile(y, nu, U0, x):
    if x <= 0 or U0 <= 0:
        return np.zeros_like(y)
    eta = y * np.sqrt(U0 / (nu * x))
    f = 0.0
    if eta < 8:
        f = _blasius_solution(eta)
    else:
        f = 1.0
    return U0 * f

def _blasius_solution(eta):
    coeffs = [0.0, 0.0, 0.33206, 0.0, -0.00011, 0.0, 0.000002, 
              0.0, -0.00000001, 0.0, 0.0]
    result = 0.0
    for i, c in enumerate(coeffs):
        result += c * (eta ** i) / math.factorial(i)
    if eta > 5:
        result = 1.0 - 0.33206 * np.exp(-0.33206 * (eta - 5))
    return result

class VelocityUDF(UDFExpression):
    def __init__(self, expression: str, components: list = None):
        super().__init__(expression)
        self.components = components or ['u', 'v', 'w']

    def evaluate_vector(self, positions: np.ndarray, time: float = 0.0) -> np.ndarray:
        positions = np.asarray(positions, dtype=np.float64)
        if positions.ndim == 1:
            positions = positions.reshape(1, -1)
        n_points = positions.shape[0]
        ndim = min(positions.shape[1], len(self.components))
        result = np.zeros((n_points, ndim), dtype=np.float64)
        for d in range(ndim):
            comp_expr = self.components[d]
            if isinstance(comp_expr, str):
                udf = UDFExpression(comp_expr)
                result[:, d] = udf.evaluate(positions, time)
            else:
                result[:, d] = self.evaluate(positions, time)
        return result

class ScalarUDF(UDFExpression):
    def __init__(self, expression: str):
        super().__init__(expression)

def create_udf_from_function(func: Callable, variables: list = None) -> UDFExpression:
    udf = UDFExpression('0', variables)
    udf.evaluate = lambda positions, time=0.0: np.asarray([func(pos, time) for pos in positions], dtype=np.float64)
    return udf

def parse_udf_string(udf_str: str):
    if udf_str.startswith('udf:'):
        expr = udf_str[4:]
        return UDFExpression(expr)
    elif udf_str.startswith('file:'):
        filename = udf_str[5:]
        data = np.loadtxt(filename)
        return lambda positions, t=0.0: np.interp(positions[:, 0], data[:, 0], data[:, 1])
    else:
        try:
            value = float(udf_str)
            return lambda positions, t=0.0: np.full(len(positions), value, dtype=np.float64)
        except ValueError:
            return UDFExpression(udf_str)
