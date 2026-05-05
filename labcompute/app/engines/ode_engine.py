import numpy as np
from typing import Dict, Any, List, Callable, Union, Optional, Tuple
from enum import Enum
from dataclasses import dataclass, field
import time

from app.modules.validation_utils import (
    ODEValidator, ValidationConfig, ValidationLevel,
    ValidationResult, StatsValidator
)

class ODEMethod(str, Enum):
    EULER = "euler"
    RK4 = "rk4"
    RK45 = "rk45"
    RK23 = "rk23"
    DOP853 = "dop853"
    RK45_ADAPTIVE = "rk45_adaptive"
    RK_FEHLBERG = "rk_fehlberg"
    CASH_KARP = "cash_karp"
    DORMAND_PRINCE = "dormand_prince"

class ODEStatus(str, Enum):
    STABLE = "stable"
    DIVERGED = "diverged"
    TIMEOUT = "timeout"
    PARAM_ERROR = "param_error"
    MAX_STEPS_EXCEEDED = "max_steps_exceeded"

@dataclass
class ODEResult:
    status: ODEStatus
    trajectory: List[Dict[str, float]] = field(default_factory=list)
    total_steps: int = 0
    actual_steps: int = 0
    rejected_steps: int = 0
    critical_time: Optional[float] = None
    error_message: Optional[str] = None
    execution_time: float = 0.0
    min_step_used: Optional[float] = None
    max_step_used: Optional[float] = None
    avg_step_used: Optional[float] = None
    adaptive_steps_used: List[float] = field(default_factory=list)
    error_estimates: List[float] = field(default_factory=list)
    max_error_estimate: Optional[float] = None
    avg_error_estimate: Optional[float] = None

class ODEEngineError(Exception):
    pass

class ParameterError(ODEEngineError):
    pass

class AdaptiveStepController:
    
    def __init__(
        self,
        min_step: float = 1e-10,
        max_step: float = 1e2,
        initial_step: float = 0.01,
        rtol: float = 1e-6,
        atol: float = 1e-9,
        safety_factor: float = 0.9,
        max_increase: float = 10.0,
        max_decrease: float = 0.1
    ):
        self.min_step = min_step
        self.max_step = max_step
        self.current_step = initial_step
        self.rtol = rtol
        self.atol = atol
        self.safety_factor = safety_factor
        self.max_increase = max_increase
        self.max_decrease = max_decrease
        self.step_history: List[float] = []
        self.error_history: List[float] = []
    
    def update_step(self, error_estimate: float, order: int) -> bool:
        if error_estimate <= 0.0 or np.isnan(error_estimate):
            self.step_history.append(self.current_step)
            return True
        
        self.error_history.append(float(error_estimate))
        
        scale = self.safety_factor * (1.0 / error_estimate) ** (1.0 / (order + 1))
        scale = max(self.max_decrease, min(scale, self.max_increase))
        
        new_step = self.current_step * scale
        new_step = max(self.min_step, min(new_step, self.max_step))
        
        if error_estimate <= 1.0:
            self.step_history.append(self.current_step)
            self.current_step = new_step
            return True
        else:
            self.current_step = new_step
            return False
    
    def get_current_step(self) -> float:
        return self.current_step
    
    def get_stats(self) -> Dict[str, Any]:
        stats = {
            'min_step': None,
            'max_step': None,
            'avg_step': None,
            'step_count': 0,
            'steps': [],
            'min_error': None,
            'max_error': None,
            'avg_error': None,
            'error_count': 0,
            'errors': []
        }
        
        if self.step_history:
            stats.update({
                'min_step': min(self.step_history),
                'max_step': max(self.step_history),
                'avg_step': float(np.mean(self.step_history)),
                'step_count': len(self.step_history),
                'steps': self.step_history.copy()
            })
        
        if self.error_history:
            stats.update({
                'min_error': min(self.error_history),
                'max_error': max(self.error_history),
                'avg_error': float(np.mean(self.error_history)),
                'error_count': len(self.error_history),
                'errors': self.error_history.copy()
            })
        
        return stats

class EmbeddedRKSolver:
    
    ORDER: int
    EMBEDDED_ORDER: int
    
    def __init__(self, step_controller: AdaptiveStepController):
        self.step_controller = step_controller
        self.k: List[np.ndarray] = []
    
    def compute_stages(
        self,
        ode_func: Callable,
        t: float,
        y: np.ndarray,
        coefficients: Dict,
        h: float
    ) -> List[np.ndarray]:
        raise NotImplementedError
    
    def compute_estimate(self, k: List[np.ndarray], h: float) -> Tuple[np.ndarray, np.ndarray]:
        raise NotImplementedError
    
    def compute_error_scale(self, y: np.ndarray, y_new: np.ndarray) -> np.ndarray:
        scale = self.step_controller.atol + self.step_controller.rtol * np.maximum(np.abs(y), np.abs(y_new))
        return scale

class RKFehlbergSolver(EmbeddedRKSolver):
    
    A = [
        [],
        [1/4],
        [3/32, 9/32],
        [1932/2197, -7200/2197, 7296/2197],
        [439/216, -8, 3680/513, -845/4104],
        [-8/27, 2, -3544/2565, 1859/4104, -11/40]
    ]
    
    C = [0, 1/4, 3/8, 12/13, 1, 1/2]
    
    B4 = [25/216, 0, 1408/2565, 2197/4104, -1/5, 0]
    B5 = [16/135, 0, 6656/12825, 28561/56430, -9/50, 2/55]
    
    ORDER = 5
    EMBEDDED_ORDER = 4
    
    def __init__(self, step_controller: AdaptiveStepController):
        super().__init__(step_controller)
    
    def compute_stages(
        self,
        ode_func: Callable,
        t: float,
        y: np.ndarray,
        coefficients: Dict,
        h: float
    ) -> List[np.ndarray]:
        k = []
        
        for i in range(6):
            ti = t + self.C[i] * h
            yi = y.copy()
            for j in range(i):
                yi = yi + h * self.A[i][j] * k[j]
            ki = ode_func(ti, yi, coefficients)
            k.append(ki)
        
        return k
    
    def compute_estimate(self, k: List[np.ndarray], h: float) -> Tuple[np.ndarray, np.ndarray]:
        y4 = np.zeros_like(k[0])
        y5 = np.zeros_like(k[0])
        
        for i in range(6):
            y4 = y4 + self.B4[i] * k[i]
            y5 = y5 + self.B5[i] * k[i]
        
        y4 = y4 * h
        y5 = y5 * h
        
        error = np.abs(y5 - y4)
        
        return y5, error

class CashKarpSolver(EmbeddedRKSolver):
    
    A = [
        [],
        [1/5],
        [3/40, 9/40],
        [3/10, -9/10, 6/5],
        [-11/54, 5/2, -70/27, 35/27],
        [1631/55296, 175/512, 575/13824, 44275/110592, 253/4096]
    ]
    
    C = [0, 1/5, 3/10, 3/5, 1, 7/8]
    
    B4 = [2825/27648, 0, 18575/48384, 13525/55296, 277/14336, 1/4]
    B5 = [37/378, 0, 250/621, 125/594, 0, 512/1771]
    
    ORDER = 5
    EMBEDDED_ORDER = 4
    
    def __init__(self, step_controller: AdaptiveStepController):
        super().__init__(step_controller)
    
    def compute_stages(
        self,
        ode_func: Callable,
        t: float,
        y: np.ndarray,
        coefficients: Dict,
        h: float
    ) -> List[np.ndarray]:
        k = []
        
        for i in range(6):
            ti = t + self.C[i] * h
            yi = y.copy()
            for j in range(i):
                yi = yi + h * self.A[i][j] * k[j]
            ki = ode_func(ti, yi, coefficients)
            k.append(ki)
        
        return k
    
    def compute_estimate(self, k: List[np.ndarray], h: float) -> Tuple[np.ndarray, np.ndarray]:
        y4 = np.zeros_like(k[0])
        y5 = np.zeros_like(k[0])
        
        for i in range(6):
            y4 = y4 + self.B4[i] * k[i]
            y5 = y5 + self.B5[i] * k[i]
        
        y4 = y4 * h
        y5 = y5 * h
        
        error = np.abs(y5 - y4)
        
        return y5, error

class DormandPrinceSolver(EmbeddedRKSolver):
    
    A = [
        [],
        [1/5],
        [3/40, 9/40],
        [44/45, -56/15, 32/9],
        [19372/6561, -25360/2187, 64448/6561, -212/729],
        [9017/3168, -355/33, 46732/5247, 49/176, -5103/18656],
        [35/384, 0, 500/1113, 125/192, -2187/6784, 11/84]
    ]
    
    C = [0, 1/5, 3/10, 4/5, 8/9, 1, 1]
    
    B5 = [35/384, 0, 500/1113, 125/192, -2187/6784, 11/84, 0]
    B4 = [5179/57600, 0, 7571/16695, 393/640, -92097/339200, 187/2100, 1/40]
    
    ORDER = 5
    EMBEDDED_ORDER = 4
    
    def __init__(self, step_controller: AdaptiveStepController):
        super().__init__(step_controller)
    
    def compute_stages(
        self,
        ode_func: Callable,
        t: float,
        y: np.ndarray,
        coefficients: Dict,
        h: float
    ) -> List[np.ndarray]:
        k = []
        
        for i in range(7):
            ti = t + self.C[i] * h
            yi = y.copy()
            for j in range(i):
                yi = yi + h * self.A[i][j] * k[j]
            ki = ode_func(ti, yi, coefficients)
            k.append(ki)
        
        return k
    
    def compute_estimate(self, k: List[np.ndarray], h: float) -> Tuple[np.ndarray, np.ndarray]:
        y4 = np.zeros_like(k[0])
        y5 = np.zeros_like(k[0])
        
        for i in range(7):
            y4 = y4 + self.B4[i] * k[i]
            y5 = y5 + self.B5[i] * k[i]
        
        y4 = y4 * h
        y5 = y5 * h
        
        error = np.abs(y5 - y4)
        
        return y5, error

class SimplifiedRK45Solver(EmbeddedRKSolver):
    
    ORDER = 4
    EMBEDDED_ORDER = 3
    
    def __init__(self, step_controller: AdaptiveStepController):
        super().__init__(step_controller)
    
    def compute_stages(
        self,
        ode_func: Callable,
        t: float,
        y: np.ndarray,
        coefficients: Dict,
        h: float
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        k1 = ode_func(t, y, coefficients)
        k2 = ode_func(t + h/2, y + h/2 * k1, coefficients)
        k3 = ode_func(t + h/2, y + h/2 * k2, coefficients)
        k4 = ode_func(t + h, y + h * k3, coefficients)
        k5 = ode_func(t + h, y + h * (k1 + k2 + k3 + k4) / 4, coefficients)
        
        return k1, k2, k3, k4, k5
    
    def compute_estimate(
        self,
        k1: np.ndarray,
        k2: np.ndarray,
        k3: np.ndarray,
        k4: np.ndarray,
        k5: np.ndarray,
        h: float
    ) -> Tuple[np.ndarray, np.ndarray]:
        y_rk4 = h * (k1 + 2*k2 + 2*k3 + k4) / 6
        y_est = h * (k1 + k2 + k3 + k4 + 4*k5) / 8
        
        error = np.abs(y_rk4 - y_est)
        
        return y_rk4, error

class ODEComputeEngine:
    
    CONVERGENCE_THRESHOLD = 1e10
    MAX_STEPS = 1000000
    MAX_ADAPTIVE_STEPS = 500000
    
    DEFAULT_RTOL = 1e-6
    DEFAULT_ATOL = 1e-9
    DEFAULT_MIN_STEP = 1e-10
    DEFAULT_MAX_STEP = 1e2
    DEFAULT_SAFETY_FACTOR = 0.9
    
    def __init__(self, validation_level: ValidationLevel = ValidationLevel.STANDARD):
        self._equation_registry = self._build_equation_registry()
        self.validator = ODEValidator(ValidationConfig(level=validation_level))
        self.stats_validator = StatsValidator(ValidationConfig(level=validation_level))
    
    def _build_equation_registry(self) -> Dict[str, Callable]:
        return {
            'first_order_linear': self._first_order_linear,
            'first_order_nonlinear': self._first_order_nonlinear,
            'second_order_linear': self._second_order_linear,
            'lotka_volterra': self._lotka_volterra,
            'lorenz': self._lorenz_system,
            'pendulum': self._simple_pendulum,
            'exponential_growth': self._exponential_growth,
            'logistic_growth': self._logistic_growth,
        }
    
    def _first_order_linear(self, t: float, y: np.ndarray, coefficients: Dict) -> np.ndarray:
        a = coefficients.get('a', 0.0)
        b = coefficients.get('b', 0.0)
        return np.array([a * y[0] + b], dtype=np.float64)
    
    def _first_order_nonlinear(self, t: float, y: np.ndarray, coefficients: Dict) -> np.ndarray:
        a = coefficients.get('a', 0.0)
        b = coefficients.get('b', 0.0)
        c = coefficients.get('c', 0.0)
        return np.array([a * y[0]**2 + b * y[0] + c], dtype=np.float64)
    
    def _second_order_linear(self, t: float, y: np.ndarray, coefficients: Dict) -> np.ndarray:
        a = coefficients.get('a', 0.0)
        b = coefficients.get('b', 0.0)
        c = coefficients.get('c', 0.0)
        d = coefficients.get('d', 0.0)
        return np.array([
            y[1],
            a * y[0] + b * y[1] + c * t + d
        ], dtype=np.float64)
    
    def _lotka_volterra(self, t: float, y: np.ndarray, coefficients: Dict) -> np.ndarray:
        alpha = coefficients.get('alpha', 1.0)
        beta = coefficients.get('beta', 0.1)
        gamma = coefficients.get('gamma', 1.0)
        delta = coefficients.get('delta', 0.1)
        return np.array([
            alpha * y[0] - beta * y[0] * y[1],
            -gamma * y[1] + delta * y[0] * y[1]
        ], dtype=np.float64)
    
    def _lorenz_system(self, t: float, y: np.ndarray, coefficients: Dict) -> np.ndarray:
        sigma = coefficients.get('sigma', 10.0)
        rho = coefficients.get('rho', 28.0)
        beta = coefficients.get('beta', 8.0/3.0)
        return np.array([
            sigma * (y[1] - y[0]),
            y[0] * (rho - y[2]) - y[1],
            y[0] * y[1] - beta * y[2]
        ], dtype=np.float64)
    
    def _simple_pendulum(self, t: float, y: np.ndarray, coefficients: Dict) -> np.ndarray:
        g = coefficients.get('g', 9.81)
        L = coefficients.get('L', 1.0)
        b = coefficients.get('b', 0.0)
        return np.array([
            y[1],
            -b * y[1] - (g / L) * np.sin(y[0])
        ], dtype=np.float64)
    
    def _exponential_growth(self, t: float, y: np.ndarray, coefficients: Dict) -> np.ndarray:
        r = coefficients.get('r', 0.1)
        return np.array([r * y[0]], dtype=np.float64)
    
    def _logistic_growth(self, t: float, y: np.ndarray, coefficients: Dict) -> np.ndarray:
        r = coefficients.get('r', 0.1)
        K = coefficients.get('K', 1000.0)
        return np.array([r * y[0] * (1 - y[0] / K)], dtype=np.float64)
    
    def _validate_parameters(self, config: Dict[str, Any]) -> None:
        equation_type = config.get('equation_type')
        if not equation_type:
            raise ParameterError("equation_type is required")
        
        if equation_type not in self._equation_registry:
            raise ParameterError(
                f"Unknown equation type: {equation_type}. "
                f"Supported types: {list(self._equation_registry.keys())}"
            )
        
        initial_value = config.get('initial_value')
        if initial_value is None:
            raise ParameterError("initial_value is required")
        
        if isinstance(initial_value, (int, float)):
            initial_value = [initial_value]
        
        if not isinstance(initial_value, (list, tuple)):
            raise ParameterError("initial_value must be a number or list of numbers")
        
        solve_range = config.get('solve_range')
        if not solve_range or 'start' not in solve_range or 'end' not in solve_range:
            raise ParameterError("solve_range with 'start' and 'end' is required")
        
        if solve_range['end'] <= solve_range['start']:
            raise ParameterError("solve_range end must be greater than start")
        
        method = config.get('method', ODEMethod.RK4.value)
        adaptive_methods = [
            ODEMethod.RK45_ADAPTIVE.value,
            ODEMethod.RK_FEHLBERG.value,
            ODEMethod.CASH_KARP.value,
            ODEMethod.DORMAND_PRINCE.value
        ]
        
        if method not in adaptive_methods:
            step_size = config.get('step_size')
            if step_size is None or step_size <= 0:
                raise ParameterError("step_size must be a positive number for fixed-step methods")
    
    def _check_divergence(self, y: np.ndarray, threshold: float = None) -> bool:
        if threshold is None:
            threshold = self.CONVERGENCE_THRESHOLD
        return np.any(np.abs(y) > threshold)
    
    def _compute_scaled_error(self, error: np.ndarray, scale: np.ndarray) -> float:
        if np.all(scale == 0):
            return 0.0 if np.all(error == 0) else float('inf')
        
        scaled = error / scale
        return float(np.sqrt(np.mean(scaled ** 2)))
    
    def _solve_euler(
        self,
        ode_func: Callable,
        coefficients: Dict,
        y0: np.ndarray,
        t_start: float,
        t_end: float,
        step_size: float,
        progress_callback: Optional[Callable] = None
    ) -> ODEResult:
        num_steps = int(np.ceil((t_end - t_start) / step_size))
        
        if num_steps > self.MAX_STEPS:
            return ODEResult(
                status=ODEStatus.PARAM_ERROR,
                error_message=f"Number of steps ({num_steps}) exceeds maximum allowed ({self.MAX_STEPS})"
            )
        
        trajectory = []
        t = t_start
        y = y0.copy()
        
        trajectory.append({'t': float(t), 'y': y.tolist()})
        
        for step in range(num_steps):
            try:
                dy = ode_func(t, y, coefficients)
                y = y + step_size * dy
                t = t + step_size
                
                if self._check_divergence(y):
                    return ODEResult(
                        status=ODEStatus.DIVERGED,
                        trajectory=trajectory,
                        total_steps=num_steps,
                        actual_steps=step + 1,
                        critical_time=float(t),
                        error_message="Solution diverged beyond convergence threshold"
                    )
                
                trajectory.append({'t': float(t), 'y': y.tolist()})
                
                if progress_callback and step % max(1, num_steps // 100) == 0:
                    progress = int((step + 1) / num_steps * 100)
                    progress_callback(progress)
                    
            except Exception as e:
                return ODEResult(
                    status=ODEStatus.PARAM_ERROR,
                    trajectory=trajectory,
                    total_steps=num_steps,
                    actual_steps=step + 1,
                    error_message=f"Computation error at step {step}: {str(e)}"
                )
        
        return ODEResult(
            status=ODEStatus.STABLE,
            trajectory=trajectory,
            total_steps=num_steps,
            actual_steps=num_steps,
            adaptive_steps_used=[step_size] * num_steps,
            min_step_used=step_size,
            max_step_used=step_size,
            avg_step_used=step_size
        )
    
    def _solve_rk4(
        self,
        ode_func: Callable,
        coefficients: Dict,
        y0: np.ndarray,
        t_start: float,
        t_end: float,
        step_size: float,
        progress_callback: Optional[Callable] = None
    ) -> ODEResult:
        num_steps = int(np.ceil((t_end - t_start) / step_size))
        
        if num_steps > self.MAX_STEPS:
            return ODEResult(
                status=ODEStatus.PARAM_ERROR,
                error_message=f"Number of steps ({num_steps}) exceeds maximum allowed ({self.MAX_STEPS})"
            )
        
        trajectory = []
        t = t_start
        y = y0.copy()
        
        trajectory.append({'t': float(t), 'y': y.tolist()})
        
        for step in range(num_steps):
            try:
                k1 = ode_func(t, y, coefficients)
                k2 = ode_func(t + step_size/2, y + step_size/2 * k1, coefficients)
                k3 = ode_func(t + step_size/2, y + step_size/2 * k2, coefficients)
                k4 = ode_func(t + step_size, y + step_size * k3, coefficients)
                
                y = y + step_size * (k1 + 2*k2 + 2*k3 + k4) / 6
                t = t + step_size
                
                if self._check_divergence(y):
                    return ODEResult(
                        status=ODEStatus.DIVERGED,
                        trajectory=trajectory,
                        total_steps=num_steps,
                        actual_steps=step + 1,
                        critical_time=float(t),
                        error_message="Solution diverged beyond convergence threshold"
                    )
                
                trajectory.append({'t': float(t), 'y': y.tolist()})
                
                if progress_callback and step % max(1, num_steps // 100) == 0:
                    progress = int((step + 1) / num_steps * 100)
                    progress_callback(progress)
                    
            except Exception as e:
                return ODEResult(
                    status=ODEStatus.PARAM_ERROR,
                    trajectory=trajectory,
                    total_steps=num_steps,
                    actual_steps=step + 1,
                    error_message=f"Computation error at step {step}: {str(e)}"
                )
        
        return ODEResult(
            status=ODEStatus.STABLE,
            trajectory=trajectory,
            total_steps=num_steps,
            actual_steps=num_steps,
            adaptive_steps_used=[step_size] * num_steps,
            min_step_used=step_size,
            max_step_used=step_size,
            avg_step_used=step_size
        )
    
    def _solve_embedded_rk(
        self,
        solver_class: type,
        order: int,
        ode_func: Callable,
        coefficients: Dict,
        y0: np.ndarray,
        t_start: float,
        t_end: float,
        initial_step: float,
        rtol: float,
        atol: float,
        progress_callback: Optional[Callable] = None
    ) -> ODEResult:
        step_controller = AdaptiveStepController(
            initial_step=initial_step,
            rtol=rtol,
            atol=atol,
            min_step=self.DEFAULT_MIN_STEP,
            max_step=min(self.DEFAULT_MAX_STEP, (t_end - t_start) / 10),
            safety_factor=self.DEFAULT_SAFETY_FACTOR
        )
        
        solver = solver_class(step_controller)
        
        trajectory = []
        t = t_start
        y = y0.copy()
        
        trajectory.append({'t': float(t), 'y': y.tolist()})
        
        actual_steps = 0
        rejected_steps = 0
        max_attempts = self.MAX_ADAPTIVE_STEPS
        
        total_interval = t_end - t_start
        last_progress = 0
        
        while t < t_end and actual_steps < max_attempts:
            h = step_controller.get_current_step()
            h = min(h, t_end - t)
            
            try:
                k = solver.compute_stages(ode_func, t, y, coefficients, h)
                y_new, error = solver.compute_estimate(k, h)
                
                scale = solver.compute_error_scale(y, y + y_new)
                scaled_error = self._compute_scaled_error(error, scale)
                
                step_accepted = step_controller.update_step(scaled_error, order)
                
                if step_accepted:
                    y = y + y_new
                    t = t + h
                    actual_steps += 1
                    
                    if self._check_divergence(y):
                        stats = step_controller.get_stats()
                        return ODEResult(
                            status=ODEStatus.DIVERGED,
                            trajectory=trajectory,
                            total_steps=max_attempts,
                            actual_steps=actual_steps,
                            rejected_steps=rejected_steps,
                            critical_time=float(t),
                            error_message="Solution diverged beyond convergence threshold",
                            min_step_used=stats.get('min_step'),
                            max_step_used=stats.get('max_step'),
                            avg_step_used=stats.get('avg_step'),
                            adaptive_steps_used=stats.get('steps', []),
                            error_estimates=stats.get('errors', []),
                            max_error_estimate=stats.get('max_error'),
                            avg_error_estimate=stats.get('avg_error')
                        )
                    
                    trajectory.append({'t': float(t), 'y': y.tolist()})
                    
                    if progress_callback:
                        progress = int(min(99, (t - t_start) / total_interval * 100))
                        if progress > last_progress:
                            last_progress = progress
                            progress_callback(progress)
                else:
                    rejected_steps += 1
                    
                    if h <= step_controller.min_step + 1e-15:
                        stats = step_controller.get_stats()
                        return ODEResult(
                            status=ODEStatus.PARAM_ERROR,
                            trajectory=trajectory,
                            total_steps=max_attempts,
                            actual_steps=actual_steps,
                            rejected_steps=rejected_steps,
                            critical_time=float(t),
                            error_message="Step size reached minimum limit, cannot meet error tolerance",
                            min_step_used=stats.get('min_step'),
                            max_step_used=stats.get('max_step'),
                            avg_step_used=stats.get('avg_step'),
                            adaptive_steps_used=stats.get('steps', []),
                            error_estimates=stats.get('errors', []),
                            max_error_estimate=stats.get('max_error'),
                            avg_error_estimate=stats.get('avg_error')
                        )
                    
            except Exception as e:
                stats = step_controller.get_stats()
                return ODEResult(
                    status=ODEStatus.PARAM_ERROR,
                    trajectory=trajectory,
                    total_steps=max_attempts,
                    actual_steps=actual_steps,
                    rejected_steps=rejected_steps,
                    error_message=f"Computation error at t={t}: {str(e)}",
                    min_step_used=stats.get('min_step'),
                    max_step_used=stats.get('max_step'),
                    avg_step_used=stats.get('avg_step'),
                    adaptive_steps_used=stats.get('steps', []),
                    error_estimates=stats.get('errors', []),
                    max_error_estimate=stats.get('max_error'),
                    avg_error_estimate=stats.get('avg_error')
                )
        
        stats = step_controller.get_stats()
        
        if actual_steps >= max_attempts:
            return ODEResult(
                status=ODEStatus.MAX_STEPS_EXCEEDED,
                trajectory=trajectory,
                total_steps=max_attempts,
                actual_steps=actual_steps,
                rejected_steps=rejected_steps,
                error_message="Maximum adaptive steps exceeded",
                min_step_used=stats.get('min_step'),
                max_step_used=stats.get('max_step'),
                avg_step_used=stats.get('avg_step'),
                adaptive_steps_used=stats.get('steps', []),
                error_estimates=stats.get('errors', []),
                max_error_estimate=stats.get('max_error'),
                avg_error_estimate=stats.get('avg_error')
            )
        
        if progress_callback:
            progress_callback(100)
        
        return ODEResult(
            status=ODEStatus.STABLE,
            trajectory=trajectory,
            total_steps=actual_steps + rejected_steps,
            actual_steps=actual_steps,
            rejected_steps=rejected_steps,
            min_step_used=stats.get('min_step'),
            max_step_used=stats.get('max_step'),
            avg_step_used=stats.get('avg_step'),
            adaptive_steps_used=stats.get('steps', []),
            error_estimates=stats.get('errors', []),
            max_error_estimate=stats.get('max_error'),
            avg_error_estimate=stats.get('avg_error')
        )
    
    def _solve_simplified_rk45(
        self,
        ode_func: Callable,
        coefficients: Dict,
        y0: np.ndarray,
        t_start: float,
        t_end: float,
        initial_step: float,
        rtol: float,
        atol: float,
        progress_callback: Optional[Callable] = None
    ) -> ODEResult:
        step_controller = AdaptiveStepController(
            initial_step=initial_step,
            rtol=rtol,
            atol=atol,
            min_step=self.DEFAULT_MIN_STEP,
            max_step=min(self.DEFAULT_MAX_STEP, (t_end - t_start) / 10),
            safety_factor=self.DEFAULT_SAFETY_FACTOR
        )
        
        solver = SimplifiedRK45Solver(step_controller)
        
        trajectory = []
        t = t_start
        y = y0.copy()
        
        trajectory.append({'t': float(t), 'y': y.tolist()})
        
        actual_steps = 0
        rejected_steps = 0
        max_attempts = self.MAX_ADAPTIVE_STEPS
        
        total_interval = t_end - t_start
        last_progress = 0
        
        while t < t_end and actual_steps < max_attempts:
            h = step_controller.get_current_step()
            h = min(h, t_end - t)
            
            try:
                k1, k2, k3, k4, k5 = solver.compute_stages(ode_func, t, y, coefficients, h)
                y_new, error = solver.compute_estimate(k1, k2, k3, k4, k5, h)
                
                scale = solver.compute_error_scale(y, y + y_new)
                scaled_error = self._compute_scaled_error(error, scale)
                
                step_accepted = step_controller.update_step(scaled_error, solver.ORDER)
                
                if step_accepted:
                    y = y + y_new
                    t = t + h
                    actual_steps += 1
                    
                    if self._check_divergence(y):
                        stats = step_controller.get_stats()
                        return ODEResult(
                            status=ODEStatus.DIVERGED,
                            trajectory=trajectory,
                            total_steps=max_attempts,
                            actual_steps=actual_steps,
                            rejected_steps=rejected_steps,
                            critical_time=float(t),
                            error_message="Solution diverged beyond convergence threshold",
                            min_step_used=stats.get('min_step'),
                            max_step_used=stats.get('max_step'),
                            avg_step_used=stats.get('avg_step'),
                            adaptive_steps_used=stats.get('steps', []),
                            error_estimates=stats.get('errors', []),
                            max_error_estimate=stats.get('max_error'),
                            avg_error_estimate=stats.get('avg_error')
                        )
                    
                    trajectory.append({'t': float(t), 'y': y.tolist()})
                    
                    if progress_callback:
                        progress = int(min(99, (t - t_start) / total_interval * 100))
                        if progress > last_progress:
                            last_progress = progress
                            progress_callback(progress)
                else:
                    rejected_steps += 1
                    
                    if h <= step_controller.min_step + 1e-15:
                        stats = step_controller.get_stats()
                        return ODEResult(
                            status=ODEStatus.PARAM_ERROR,
                            trajectory=trajectory,
                            total_steps=max_attempts,
                            actual_steps=actual_steps,
                            rejected_steps=rejected_steps,
                            critical_time=float(t),
                            error_message="Step size reached minimum limit, cannot meet error tolerance",
                            min_step_used=stats.get('min_step'),
                            max_step_used=stats.get('max_step'),
                            avg_step_used=stats.get('avg_step'),
                            adaptive_steps_used=stats.get('steps', []),
                            error_estimates=stats.get('errors', []),
                            max_error_estimate=stats.get('max_error'),
                            avg_error_estimate=stats.get('avg_error')
                        )
                    
            except Exception as e:
                stats = step_controller.get_stats()
                return ODEResult(
                    status=ODEStatus.PARAM_ERROR,
                    trajectory=trajectory,
                    total_steps=max_attempts,
                    actual_steps=actual_steps,
                    rejected_steps=rejected_steps,
                    error_message=f"Computation error at t={t}: {str(e)}",
                    min_step_used=stats.get('min_step'),
                    max_step_used=stats.get('max_step'),
                    avg_step_used=stats.get('avg_step'),
                    adaptive_steps_used=stats.get('steps', []),
                    error_estimates=stats.get('errors', []),
                    max_error_estimate=stats.get('max_error'),
                    avg_error_estimate=stats.get('avg_error')
                )
        
        stats = step_controller.get_stats()
        
        if actual_steps >= max_attempts:
            return ODEResult(
                status=ODEStatus.MAX_STEPS_EXCEEDED,
                trajectory=trajectory,
                total_steps=max_attempts,
                actual_steps=actual_steps,
                rejected_steps=rejected_steps,
                error_message="Maximum adaptive steps exceeded",
                min_step_used=stats.get('min_step'),
                max_step_used=stats.get('max_step'),
                avg_step_used=stats.get('avg_step'),
                adaptive_steps_used=stats.get('steps', []),
                error_estimates=stats.get('errors', []),
                max_error_estimate=stats.get('max_error'),
                avg_error_estimate=stats.get('avg_error')
            )
        
        if progress_callback:
            progress_callback(100)
        
        return ODEResult(
            status=ODEStatus.STABLE,
            trajectory=trajectory,
            total_steps=actual_steps + rejected_steps,
            actual_steps=actual_steps,
            rejected_steps=rejected_steps,
            min_step_used=stats.get('min_step'),
            max_step_used=stats.get('max_step'),
            avg_step_used=stats.get('avg_step'),
            adaptive_steps_used=stats.get('steps', []),
            error_estimates=stats.get('errors', []),
            max_error_estimate=stats.get('max_error'),
            avg_error_estimate=stats.get('avg_error')
        )
    
    def _solve_scipy(
        self,
        ode_func: Callable,
        coefficients: Dict,
        y0: np.ndarray,
        t_start: float,
        t_end: float,
        step_size: float,
        method: str,
        progress_callback: Optional[Callable] = None
    ) -> ODEResult:
        from scipy.integrate import solve_ivp
        
        def wrapped_ode(t, y):
            return ode_func(t, y, coefficients)
        
        num_steps = int(np.ceil((t_end - t_start) / step_size))
        t_eval = np.linspace(t_start, t_end, num_steps + 1)
        
        try:
            solution = solve_ivp(
                wrapped_ode,
                [t_start, t_end],
                y0,
                method=method.upper() if method in ['rk45', 'rk23', 'dop853'] else method,
                t_eval=t_eval,
                rtol=self.DEFAULT_RTOL,
                atol=self.DEFAULT_ATOL
            )
            
            if not solution.success:
                return ODEResult(
                    status=ODEStatus.PARAM_ERROR,
                    error_message=f"Solver failed: {solution.message}"
                )
            
            trajectory = []
            for i, (t, y) in enumerate(zip(solution.t, solution.y.T)):
                if self._check_divergence(y):
                    return ODEResult(
                        status=ODEStatus.DIVERGED,
                        trajectory=trajectory,
                        total_steps=len(trajectory),
                        actual_steps=i,
                        critical_time=float(t),
                        error_message="Solution diverged beyond convergence threshold"
                    )
                trajectory.append({'t': float(t), 'y': y.tolist()})
                
                if progress_callback and i % max(1, num_steps // 100) == 0:
                    progress = int(i / len(solution.t) * 100)
                    progress_callback(progress)
            
            return ODEResult(
                status=ODEStatus.STABLE,
                trajectory=trajectory,
                total_steps=len(trajectory) - 1,
                actual_steps=len(trajectory) - 1,
                adaptive_steps_used=[step_size] * len(trajectory),
                min_step_used=step_size,
                max_step_used=step_size,
                avg_step_used=step_size
            )
            
        except Exception as e:
            return ODEResult(
                status=ODEStatus.PARAM_ERROR,
                error_message=f"Solver error: {str(e)}"
            )
    
    def solve(
        self,
        config: Dict[str, Any],
        progress_callback: Optional[Callable] = None
    ) -> Dict[str, Any]:
        start_time = time.time()
        
        try:
            self._validate_parameters(config)
        except ParameterError as e:
            return {
                'status': ODEStatus.PARAM_ERROR.value,
                'error_message': str(e),
                'total_steps': 0,
                'actual_steps': 0,
                'rejected_steps': 0,
                'trajectory': []
            }
        
        equation_type = config['equation_type']
        ode_func = self._equation_registry[equation_type]
        
        initial_value = config['initial_value']
        if isinstance(initial_value, (int, float)):
            y0 = np.array([initial_value], dtype=np.float64)
        else:
            y0 = np.array(initial_value, dtype=np.float64)
        
        t_start = config['solve_range']['start']
        t_end = config['solve_range']['end']
        step_size = config.get('step_size', 0.01)
        method = config.get('method', ODEMethod.RK4.value)
        coefficients = config.get('coefficients', {})
        
        rtol = config.get('rtol', self.DEFAULT_RTOL)
        atol = config.get('atol', self.DEFAULT_ATOL)
        
        result: ODEResult
        
        adaptive_methods = {
            ODEMethod.RK45_ADAPTIVE.value: (self._solve_simplified_rk45, None, 4),
            ODEMethod.RK_FEHLBERG.value: (self._solve_embedded_rk, RKFehlbergSolver, 5),
            ODEMethod.CASH_KARP.value: (self._solve_embedded_rk, CashKarpSolver, 5),
            ODEMethod.DORMAND_PRINCE.value: (self._solve_embedded_rk, DormandPrinceSolver, 5)
        }
        
        if method in adaptive_methods:
            solver_func, solver_class, order = adaptive_methods[method]
            
            if solver_class is not None:
                result = solver_func(
                    solver_class, order,
                    ode_func, coefficients, y0, t_start, t_end,
                    step_size, rtol, atol, progress_callback
                )
            else:
                result = solver_func(
                    ode_func, coefficients, y0, t_start, t_end,
                    step_size, rtol, atol, progress_callback
                )
        elif method == ODEMethod.EULER.value:
            result = self._solve_euler(
                ode_func, coefficients, y0, t_start, t_end, step_size, progress_callback
            )
        elif method == ODEMethod.RK4.value:
            result = self._solve_rk4(
                ode_func, coefficients, y0, t_start, t_end, step_size, progress_callback
            )
        elif method in [ODEMethod.RK45.value, ODEMethod.RK23.value, ODEMethod.DOP853.value]:
            result = self._solve_scipy(
                ode_func, coefficients, y0, t_start, t_end, step_size, method, progress_callback
            )
        else:
            result = ODEResult(
                status=ODEStatus.PARAM_ERROR,
                error_message=f"Unknown method: {method}"
            )
        
        result.execution_time = time.time() - start_time
        
        is_adaptive = method in adaptive_methods
        
        validation_result = None
        if result.trajectory and len(result.trajectory) > 0:
            validation_result = self.validator.validate_trajectory(result.trajectory)
        
        error_validation = None
        if result.error_estimates and len(result.error_estimates) > 0:
            error_validation = self.validator.validate_error_estimates(
                result.error_estimates, rtol, atol
            )
        
        output = {
            'status': result.status.value,
            'trajectory': result.trajectory,
            'total_steps': result.total_steps,
            'actual_steps': result.actual_steps,
            'rejected_steps': result.rejected_steps,
            'critical_time': result.critical_time,
            'error_message': result.error_message,
            'execution_time_seconds': result.execution_time,
            'method': method,
            'equation_type': equation_type,
            'is_adaptive': is_adaptive,
            'min_step_used': result.min_step_used,
            'max_step_used': result.max_step_used,
            'avg_step_used': result.avg_step_used,
            'adaptive_step_count': len(result.adaptive_steps_used) if result.adaptive_steps_used else 0,
            'error_estimates': result.error_estimates,
            'max_error_estimate': result.max_error_estimate,
            'avg_error_estimate': result.avg_error_estimate
        }
        
        if validation_result:
            output['trajectory_validation'] = {
                'passed': validation_result.passed,
                'errors': validation_result.errors,
                'warnings': validation_result.warnings,
                'metrics': validation_result.metrics
            }
        
        if error_validation:
            output['error_validation'] = {
                'passed': error_validation.passed,
                'errors': error_validation.errors,
                'warnings': error_validation.warnings,
                'metrics': error_validation.metrics
            }
        
        return output
