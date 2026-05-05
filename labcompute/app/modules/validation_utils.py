import numpy as np
from typing import Dict, Any, List, Optional, Union, Tuple
from dataclasses import dataclass
from enum import Enum

class ValidationError(Exception):
    pass

class PrecisionError(ValidationError):
    pass

class ConsistencyError(ValidationError):
    pass

class BoundaryError(ValidationError):
    pass

class ValidationLevel(str, Enum):
    STRICT = "strict"
    STANDARD = "standard"
    LENIENT = "lenient"

@dataclass
class ValidationConfig:
    level: ValidationLevel = ValidationLevel.STANDARD
    rtol: float = 1e-6
    atol: float = 1e-9
    check_finite: bool = True
    check_shape: bool = True
    check_dtype: bool = False
    max_allowed_error: float = 1e-6

@dataclass
class ValidationResult:
    passed: bool
    errors: List[str]
    warnings: List[str]
    metrics: Dict[str, Any]

class MatrixValidator:
    
    DEFAULT_CONFIG = ValidationConfig(
        level=ValidationLevel.STANDARD,
        rtol=1e-6,
        atol=1e-9,
        check_finite=True,
        check_shape=True
    )
    
    def __init__(self, config: Optional[ValidationConfig] = None):
        self.config = config or self.DEFAULT_CONFIG
    
    def validate_matrix(
        self,
        matrix: Any,
        expected_shape: Optional[Tuple[int, ...]] = None,
        min_value: Optional[float] = None,
        max_value: Optional[float] = None
    ) -> ValidationResult:
        errors = []
        warnings = []
        metrics = {}
        
        try:
            if isinstance(matrix, (list, tuple)):
                matrix = np.array(matrix, dtype=np.float64)
            
            if not isinstance(matrix, (np.ndarray, np.memmap)):
                errors.append(f"Matrix must be numpy array, list, or tuple, got {type(matrix)}")
                return ValidationResult(passed=False, errors=errors, warnings=warnings, metrics=metrics)
            
            if self.config.check_finite:
                if not np.all(np.isfinite(matrix)):
                    num_infinite = np.sum(~np.isfinite(matrix))
                    errors.append(f"Matrix contains {num_infinite} non-finite values (NaN or Inf)")
            
            if self.config.check_shape and expected_shape:
                if matrix.shape != expected_shape:
                    errors.append(f"Shape mismatch: expected {expected_shape}, got {matrix.shape}")
            
            if min_value is not None:
                if np.any(matrix < min_value):
                    min_actual = np.min(matrix)
                    warnings.append(f"Matrix contains values below minimum threshold: {min_actual} < {min_value}")
            
            if max_value is not None:
                if np.any(matrix > max_value):
                    max_actual = np.max(matrix)
                    warnings.append(f"Matrix contains values above maximum threshold: {max_actual} > {max_value}")
            
            metrics['shape'] = list(matrix.shape)
            metrics['dtype'] = str(matrix.dtype)
            metrics['min_value'] = float(np.min(matrix)) if matrix.size > 0 else None
            metrics['max_value'] = float(np.max(matrix)) if matrix.size > 0 else None
            metrics['mean_value'] = float(np.mean(matrix)) if matrix.size > 0 else None
            metrics['has_nan'] = bool(np.any(np.isnan(matrix)))
            metrics['has_inf'] = bool(np.any(np.isinf(matrix)))
            
        except Exception as e:
            errors.append(f"Validation failed with exception: {str(e)}")
        
        return ValidationResult(
            passed=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            metrics=metrics
        )
    
    def validate_multiplication_consistency(
        self,
        matrix_a: np.ndarray,
        matrix_b: np.ndarray,
        result: np.ndarray
    ) -> ValidationResult:
        errors = []
        warnings = []
        metrics = {}
        
        try:
            if matrix_a.shape[1] != matrix_b.shape[0]:
                errors.append(
                    f"Inner dimensions mismatch: A is {matrix_a.shape}, B is {matrix_b.shape}"
                )
            
            expected_shape = (matrix_a.shape[0], matrix_b.shape[1])
            if result.shape != expected_shape:
                errors.append(
                    f"Result shape mismatch: expected {expected_shape}, got {result.shape}"
                )
            
            if matrix_a.shape[0] <= 50 and matrix_b.shape[1] <= 50 and matrix_a.shape[1] <= 50:
                reference = np.dot(matrix_a, matrix_b)
                max_diff = np.max(np.abs(result - reference))
                rel_error = max_diff / (np.max(np.abs(reference)) + 1e-10)
                
                metrics['max_absolute_error'] = float(max_diff)
                metrics['relative_error'] = float(rel_error)
                
                if self.config.level == ValidationLevel.STRICT:
                    if not np.allclose(result, reference, rtol=self.config.rtol, atol=self.config.atol):
                        errors.append(
                            f"Result differs from reference by max {max_diff} "
                            f"(rel {rel_error:.2e}), exceeds tolerance "
                            f"(rtol={self.config.rtol}, atol={self.config.atol})"
                        )
                elif self.config.level == ValidationLevel.STANDARD:
                    if rel_error > self.config.max_allowed_error:
                        warnings.append(
                            f"Result relative error {rel_error:.2e} exceeds "
                            f"warning threshold {self.config.max_allowed_error}"
                        )
            
            if not np.all(np.isfinite(result)):
                errors.append("Result contains non-finite values")
            
        except Exception as e:
            errors.append(f"Consistency check failed: {str(e)}")
        
        return ValidationResult(
            passed=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            metrics=metrics
        )
    
    def validate_block_boundaries(
        self,
        full_matrix: np.ndarray,
        block_results: List[Dict[str, Any]],
        block_size: int
    ) -> ValidationResult:
        errors = []
        warnings = []
        metrics = {}
        
        try:
            rows, cols = full_matrix.shape
            
            row_boundary_errors = []
            col_boundary_errors = []
            
            for i in range(1, (rows + block_size - 1) // block_size):
                boundary_row = i * block_size - 1
                if boundary_row >= rows - 1:
                    continue
                
                if boundary_row < rows - 1:
                    row_diff = np.abs(
                        full_matrix[boundary_row, :] - full_matrix[boundary_row + 1, :]
                    )
                    max_row_diff = np.max(row_diff)
                    
                    if max_row_diff > self.config.max_allowed_error:
                        row_boundary_errors.append({
                            'row': boundary_row,
                            'max_diff': float(max_row_diff)
                        })
            
            for j in range(1, (cols + block_size - 1) // block_size):
                boundary_col = j * block_size - 1
                if boundary_col >= cols - 1:
                    continue
                
                if boundary_col < cols - 1:
                    col_diff = np.abs(
                        full_matrix[:, boundary_col] - full_matrix[:, boundary_col + 1]
                    )
                    max_col_diff = np.max(col_diff)
                    
                    if max_col_diff > self.config.max_allowed_error:
                        col_boundary_errors.append({
                            'col': boundary_col,
                            'max_diff': float(max_col_diff)
                        })
            
            metrics['row_boundary_issues'] = len(row_boundary_errors)
            metrics['col_boundary_issues'] = len(col_boundary_errors)
            
            if row_boundary_errors:
                warnings.append(
                    f"Found {len(row_boundary_errors)} row boundary discontinuities"
                )
            if col_boundary_errors:
                warnings.append(
                    f"Found {len(col_boundary_errors)} column boundary discontinuities"
                )
            
            if self.config.level == ValidationLevel.STRICT:
                if row_boundary_errors or col_boundary_errors:
                    errors.append(
                        f"Boundary consistency check failed: "
                        f"{len(row_boundary_errors)} row issues, {len(col_boundary_errors)} col issues"
                    )
                    
        except Exception as e:
            errors.append(f"Boundary validation failed: {str(e)}")
        
        return ValidationResult(
            passed=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            metrics=metrics
        )

class StatsValidator:
    
    DEFAULT_CONFIG = ValidationConfig(
        level=ValidationLevel.STANDARD,
        rtol=1e-6,
        atol=1e-9
    )
    
    def __init__(self, config: Optional[ValidationConfig] = None):
        self.config = config or self.DEFAULT_CONFIG
    
    def validate_numerical_data(self, data: Any) -> ValidationResult:
        errors = []
        warnings = []
        metrics = {}
        
        try:
            if isinstance(data, (list, tuple)):
                data = np.array(data, dtype=np.float64)
            
            if not isinstance(data, (np.ndarray, np.memmap)):
                errors.append(f"Data must be numpy array, list, or tuple, got {type(data)}")
                return ValidationResult(passed=False, errors=errors, warnings=warnings, metrics=metrics)
            
            if data.ndim not in (1, 2):
                errors.append(f"Data must be 1D or 2D array, got {data.ndim}D")
            
            if data.size == 0:
                errors.append("Data array is empty")
                return ValidationResult(passed=False, errors=errors, warnings=warnings, metrics=metrics)
            
            if self.config.check_finite:
                finite_mask = np.isfinite(data)
                num_infinite = np.sum(~finite_mask)
                
                if num_infinite > 0:
                    if self.config.level == ValidationLevel.STRICT:
                        errors.append(f"Data contains {num_infinite} non-finite values")
                    else:
                        warnings.append(
                            f"Data contains {num_infinite} non-finite values - will be excluded from analysis"
                        )
            
            metrics['shape'] = list(data.shape)
            metrics['total_samples'] = data.size
            metrics['finite_samples'] = int(np.sum(np.isfinite(data)))
            metrics['nan_count'] = int(np.sum(np.isnan(data)))
            metrics['inf_count'] = int(np.sum(np.isinf(data)))
            
            if metrics['finite_samples'] > 0:
                finite_data = data[np.isfinite(data)]
                metrics['min_value'] = float(np.min(finite_data))
                metrics['max_value'] = float(np.max(finite_data))
                metrics['mean_value'] = float(np.mean(finite_data))
                metrics['std_value'] = float(np.std(finite_data))
            
        except Exception as e:
            errors.append(f"Data validation failed: {str(e)}")
        
        return ValidationResult(
            passed=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            metrics=metrics
        )
    
    def validate_regression_result(
        self,
        x: np.ndarray,
        y: np.ndarray,
        slope: float,
        intercept: float,
        r_squared: float
    ) -> ValidationResult:
        errors = []
        warnings = []
        metrics = {}
        
        try:
            if len(x) != len(y):
                errors.append(f"X and Y length mismatch: {len(x)} vs {len(y)}")
            
            if not np.isfinite(slope) or not np.isfinite(intercept):
                errors.append("Regression parameters contain non-finite values")
            
            if r_squared < 0 or r_squared > 1:
                warnings.append(f"R-squared value {r_squared} is outside valid range [0, 1]")
            
            if len(x) > 2:
                try:
                    y_pred = slope * x + intercept
                    residuals = y - y_pred
                    mse = np.mean(residuals ** 2)
                    rmse = np.sqrt(mse)
                    
                    metrics['mse'] = float(mse)
                    metrics['rmse'] = float(rmse)
                    metrics['max_residual'] = float(np.max(np.abs(residuals)))
                    
                    if self.config.level == ValidationLevel.STRICT:
                        max_residual = metrics['max_residual']
                        if max_residual > self.config.max_allowed_error * np.max(np.abs(y)):
                            warnings.append(
                                f"Large residual detected: {max_residual:.4e}"
                            )
                            
                except Exception as e:
                    warnings.append(f"Could not compute residual metrics: {str(e)}")
            
        except Exception as e:
            errors.append(f"Regression validation failed: {str(e)}")
        
        return ValidationResult(
            passed=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            metrics=metrics
        )
    
    def validate_correlation(
        self,
        corr: float,
        p_value: Optional[float] = None
    ) -> ValidationResult:
        errors = []
        warnings = []
        metrics = {}
        
        try:
            if corr < -1 or corr > 1:
                errors.append(f"Correlation coefficient {corr} is outside valid range [-1, 1]")
            
            if p_value is not None:
                if p_value < 0 or p_value > 1:
                    warnings.append(f"P-value {p_value} is outside valid range [0, 1]")
                
                metrics['significant'] = bool(p_value < 0.05)
            
            metrics['correlation'] = float(corr)
            metrics['magnitude'] = float(abs(corr))
            metrics['sign'] = 'positive' if corr > 0 else 'negative' if corr < 0 else 'zero'
            
        except Exception as e:
            errors.append(f"Correlation validation failed: {str(e)}")
        
        return ValidationResult(
            passed=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            metrics=metrics
        )

class ODEValidator:
    
    DEFAULT_CONFIG = ValidationConfig(
        level=ValidationLevel.STANDARD,
        rtol=1e-6,
        atol=1e-9
    )
    
    def __init__(self, config: Optional[ValidationConfig] = None):
        self.config = config or self.DEFAULT_CONFIG
    
    def validate_trajectory(
        self,
        trajectory: List[Dict[str, Any]]
    ) -> ValidationResult:
        errors = []
        warnings = []
        metrics = {}
        
        try:
            if not trajectory:
                errors.append("Trajectory is empty")
                return ValidationResult(passed=False, errors=errors, warnings=warnings, metrics=metrics)
            
            timestamps = []
            values_list = []
            
            for i, point in enumerate(trajectory):
                if 't' not in point:
                    errors.append(f"Trajectory point {i} missing 't' field")
                    continue
                
                if 'y' not in point:
                    errors.append(f"Trajectory point {i} missing 'y' field")
                    continue
                
                timestamps.append(float(point['t']))
                values_list.append(point['y'])
            
            if len(timestamps) < 2:
                warnings.append("Trajectory has fewer than 2 points")
            
            if timestamps:
                metrics['start_time'] = timestamps[0]
                metrics['end_time'] = timestamps[-1]
                metrics['num_points'] = len(timestamps)
                
                time_diffs = np.diff(timestamps)
                metrics['min_time_step'] = float(np.min(time_diffs)) if len(time_diffs) > 0 else None
                metrics['max_time_step'] = float(np.max(time_diffs)) if len(time_diffs) > 0 else None
                metrics['mean_time_step'] = float(np.mean(time_diffs)) if len(time_diffs) > 0 else None
                
                if np.any(time_diffs <= 0):
                    errors.append("Trajectory contains non-increasing time steps")
            
            if values_list:
                try:
                    values_array = np.array(values_list, dtype=np.float64)
                    
                    metrics['num_dimensions'] = values_array.shape[1] if values_array.ndim > 1 else 1
                    metrics['min_value'] = float(np.min(values_array))
                    metrics['max_value'] = float(np.max(values_array))
                    
                    if self.config.check_finite:
                        finite_mask = np.isfinite(values_array)
                        num_infinite = np.sum(~finite_mask)
                        
                        if num_infinite > 0:
                            if self.config.level == ValidationLevel.STRICT:
                                errors.append(
                                    f"Trajectory contains {num_infinite} non-finite values"
                                )
                            else:
                                warnings.append(
                                    f"Trajectory contains {num_infinite} non-finite values"
                                )
                            
                except Exception as e:
                    warnings.append(f"Could not analyze trajectory values: {str(e)}")
                    
        except Exception as e:
            errors.append(f"Trajectory validation failed: {str(e)}")
        
        return ValidationResult(
            passed=len(errors) == 0,
            errors=errors,
            warnings=warnings,
            metrics=metrics
        )
    
    def validate_error_estimates(
        self,
        errors: List[float],
        rtol: float,
        atol: float
    ) -> ValidationResult:
        errors_list = []
        warnings = []
        metrics = {}
        
        try:
            if not errors:
                warnings.append("No error estimates provided")
                return ValidationResult(
                    passed=True,
                    errors=errors_list,
                    warnings=warnings,
                    metrics=metrics
                )
            
            errors_array = np.array(errors, dtype=np.float64)
            
            metrics['min_error'] = float(np.min(errors_array))
            metrics['max_error'] = float(np.max(errors_array))
            metrics['mean_error'] = float(np.mean(errors_array))
            metrics['std_error'] = float(np.std(errors_array))
            
            tolerance = rtol * (np.abs(errors_array) + atol)
            violations = np.sum(errors_array > tolerance)
            
            metrics['violation_count'] = int(violations)
            metrics['violation_ratio'] = float(violations / len(errors_array)) if len(errors_array) > 0 else 0
            
            if violations > 0:
                if self.config.level == ValidationLevel.STRICT:
                    errors_list.append(
                        f"{violations} error estimates exceed tolerance "
                        f"({violations/len(errors_array)*100:.1f}%)"
                    )
                else:
                    warnings.append(
                        f"{violations} error estimates exceed tolerance "
                        f"({violations/len(errors_array)*100:.1f}%)"
                    )
                    
        except Exception as e:
            errors_list.append(f"Error validation failed: {str(e)}")
        
        return ValidationResult(
            passed=len(errors_list) == 0,
            errors=errors_list,
            warnings=warnings,
            metrics=metrics
        )

class GlobalValidator:
    
    _matrix_validator: Optional[MatrixValidator] = None
    _stats_validator: Optional[StatsValidator] = None
    _ode_validator: Optional[ODEValidator] = None
    
    @classmethod
    def get_matrix_validator(cls, config: Optional[ValidationConfig] = None) -> MatrixValidator:
        if cls._matrix_validator is None or config is not None:
            cls._matrix_validator = MatrixValidator(config)
        return cls._matrix_validator
    
    @classmethod
    def get_stats_validator(cls, config: Optional[ValidationConfig] = None) -> StatsValidator:
        if cls._stats_validator is None or config is not None:
            cls._stats_validator = StatsValidator(config)
        return cls._stats_validator
    
    @classmethod
    def get_ode_validator(cls, config: Optional[ValidationConfig] = None) -> ODEValidator:
        if cls._ode_validator is None or config is not None:
            cls._ode_validator = ODEValidator(config)
        return cls._ode_validator
    
    @classmethod
    def quick_validate_matrix(
        cls,
        matrix: Any,
        level: ValidationLevel = ValidationLevel.STANDARD
    ) -> Tuple[bool, List[str], List[str]]:
        config = ValidationConfig(level=level)
        validator = MatrixValidator(config)
        result = validator.validate_matrix(matrix)
        return result.passed, result.errors, result.warnings
    
    @classmethod
    def quick_validate_data(
        cls,
        data: Any,
        level: ValidationLevel = ValidationLevel.STANDARD
    ) -> Tuple[bool, List[str], List[str]]:
        config = ValidationConfig(level=level)
        validator = StatsValidator(config)
        result = validator.validate_numerical_data(data)
        return result.passed, result.errors, result.warnings
