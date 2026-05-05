import numpy as np
from typing import Dict, Any, Tuple, List, Union, Optional
import os
import tempfile
import gc
from dataclasses import dataclass, field
from enum import Enum

from app.modules.validation_utils import (
    MatrixValidator, ValidationConfig, ValidationLevel, ValidationResult
)

class MatrixEngineError(Exception):
    pass

class InvalidMatrixError(MatrixEngineError):
    pass

class DimensionMismatchError(MatrixEngineError):
    pass

class MemoryLimitExceededError(MatrixEngineError):
    pass

class BoundaryCorrectionError(MatrixEngineError):
    pass

class BlockStrategy(str, Enum):
    AUTO = "auto"
    FIXED = "fixed"
    MEMORY_AWARE = "memory_aware"

class BoundaryCorrectionMethod(str, Enum):
    WEIGHTED_AVERAGE = "weighted_average"
    OVERLAP_AVERAGE = "overlap_average"
    CUBIC_INTERPOLATION = "cubic_interpolation"

@dataclass
class BlockConfig:
    strategy: BlockStrategy = BlockStrategy.AUTO
    block_size: int = 128
    max_memory_bytes: int = 1024 * 1024 * 1024
    use_memmap: bool = True
    temp_dir: Optional[str] = None
    enable_boundary_correction: bool = True
    boundary_overlap: int = 4
    boundary_correction_method: BoundaryCorrectionMethod = BoundaryCorrectionMethod.WEIGHTED_AVERAGE
    validation_level: ValidationLevel = ValidationLevel.STANDARD

@dataclass
class BlockInfo:
    row_start: int
    row_end: int
    col_start: int
    col_end: int
    block_index: Tuple[int, int]
    memory_estimate: int = 0
    is_boundary_block: bool = False
    row_overlap_start: int = 0
    row_overlap_end: int = 0
    col_overlap_start: int = 0
    col_overlap_end: int = 0

@dataclass
class BoundaryCorrectionResult:
    corrected_matrix: np.ndarray
    original_matrix: np.ndarray
    corrections_applied: int
    max_boundary_error: float
    correction_method: str
    metrics: Dict[str, Any]

class BlockBoundaryCorrector:
    
    DEFAULT_OVERLAP = 4
    MAX_OVERLAP = 16
    
    def __init__(
        self,
        overlap: int = DEFAULT_OVERLAP,
        method: BoundaryCorrectionMethod = BoundaryCorrectionMethod.WEIGHTED_AVERAGE,
        config: Optional[ValidationConfig] = None
    ):
        self.overlap = min(max(overlap, 1), self.MAX_OVERLAP)
        self.method = method
        self.validator = MatrixValidator(config or ValidationConfig(level=ValidationLevel.STANDARD))
    
    def _generate_weight_kernel(self, size: int) -> np.ndarray:
        if self.method == BoundaryCorrectionMethod.WEIGHTED_AVERAGE:
            x = np.linspace(0, 1, size)
            weights = 1.0 - np.abs(2 * x - 1)
            return weights / weights.sum()
        elif self.method == BoundaryCorrectionMethod.OVERLAP_AVERAGE:
            return np.ones(size) / size
        elif self.method == BoundaryCorrectionMethod.CUBIC_INTERPOLATION:
            x = np.linspace(0, 1, size)
            weights = 1.0 - 3 * x**2 + 2 * x**3
            weights = np.minimum(weights, 1.0 - np.flip(weights))
            return weights / weights.sum()
        else:
            return np.ones(size) / size
    
    def compute_row_boundary_weights(
        self,
        block_size: int,
        overlap: int
    ) -> Tuple[np.ndarray, np.ndarray]:
        weights_upper = np.ones(block_size, dtype=np.float64)
        weights_lower = np.ones(block_size, dtype=np.float64)
        
        decay = np.linspace(0, 1, overlap)
        decay_upper = 1.0 - decay
        decay_lower = decay
        
        weights_upper[-overlap:] = decay_upper
        weights_lower[:overlap] = decay_lower
        
        return weights_upper, weights_lower
    
    def compute_col_boundary_weights(
        self,
        block_size: int,
        overlap: int
    ) -> Tuple[np.ndarray, np.ndarray]:
        weights_left = np.ones(block_size, dtype=np.float64)
        weights_right = np.ones(block_size, dtype=np.float64)
        
        decay = np.linspace(0, 1, overlap)
        decay_left = 1.0 - decay
        decay_right = decay
        
        weights_left[-overlap:] = decay_left
        weights_right[:overlap] = decay_right
        
        return weights_left, weights_right
    
    def correct_row_boundary(
        self,
        matrix: np.ndarray,
        boundary_row: int,
        overlap: int
    ) -> np.ndarray:
        rows, cols = matrix.shape
        
        if boundary_row < overlap or boundary_row >= rows - overlap:
            return matrix.copy()
        
        region_start = boundary_row - overlap
        region_end = boundary_row + overlap
        
        region = matrix[region_start:region_end, :].copy()
        
        weights = self._generate_weight_kernel(2 * overlap)
        weights = weights.reshape(-1, 1)
        
        corrected_region = np.zeros_like(region)
        for i in range(2 * overlap):
            for j in range(max(0, i - overlap + 1), min(2 * overlap, i + overlap)):
                weight = weights[j - i + overlap] if (j - i + overlap) >= 0 and (j - i + overlap) < len(weights) else 0.0
                corrected_region[i, :] += weight * region[j, :]
        
        result = matrix.copy()
        result[region_start:region_end, :] = corrected_region
        
        return result
    
    def correct_col_boundary(
        self,
        matrix: np.ndarray,
        boundary_col: int,
        overlap: int
    ) -> np.ndarray:
        rows, cols = matrix.shape
        
        if boundary_col < overlap or boundary_col >= cols - overlap:
            return matrix.copy()
        
        region_start = boundary_col - overlap
        region_end = boundary_col + overlap
        
        region = matrix[:, region_start:region_end].copy()
        
        weights = self._generate_weight_kernel(2 * overlap)
        weights = weights.reshape(1, -1)
        
        corrected_region = np.zeros_like(region)
        for i in range(2 * overlap):
            for j in range(max(0, i - overlap + 1), min(2 * overlap, i + overlap)):
                weight = weights[:, j - i + overlap] if (j - i + overlap) >= 0 and (j - i + overlap) < len(weights.flatten()) else 0.0
                corrected_region[:, i] += weight.flatten()[0] * region[:, j]
        
        result = matrix.copy()
        result[:, region_start:region_end] = corrected_region
        
        return result
    
    def correct_all_boundaries(
        self,
        matrix: np.ndarray,
        block_size: int,
        overlap: Optional[int] = None
    ) -> BoundaryCorrectionResult:
        rows, cols = matrix.shape
        overlap = overlap or self.overlap
        original = matrix.copy()
        
        corrections_count = 0
        max_error = 0.0
        corrected = matrix.copy()
        
        row_boundaries = []
        for i in range(1, (rows + block_size - 1) // block_size):
            boundary = i * block_size
            if boundary < rows and boundary > overlap:
                row_boundaries.append(boundary)
        
        col_boundaries = []
        for j in range(1, (cols + block_size - 1) // block_size):
            boundary = j * block_size
            if boundary < cols and boundary > overlap:
                col_boundaries.append(boundary)
        
        for boundary in row_boundaries:
            before = corrected[boundary - overlap:boundary + overlap, :].copy()
            corrected = self.correct_row_boundary(corrected, boundary, overlap)
            after = corrected[boundary - overlap:boundary + overlap, :]
            
            error = np.max(np.abs(after - before))
            max_error = max(max_error, error)
            corrections_count += 1
        
        for boundary in col_boundaries:
            before = corrected[:, boundary - overlap:boundary + overlap].copy()
            corrected = self.correct_col_boundary(corrected, boundary, overlap)
            after = corrected[:, boundary - overlap:boundary + overlap]
            
            error = np.max(np.abs(after - before))
            max_error = max(max_error, error)
            corrections_count += 1
        
        metrics = {
            'row_boundaries': len(row_boundaries),
            'col_boundaries': len(col_boundaries),
            'overlap_used': overlap,
            'correction_method': self.method.value
        }
        
        return BoundaryCorrectionResult(
            corrected_matrix=corrected,
            original_matrix=original,
            corrections_applied=corrections_count,
            max_boundary_error=float(max_error),
            correction_method=self.method.value,
            metrics=metrics
        )
    
    def validate_correction(
        self,
        original: np.ndarray,
        corrected: np.ndarray,
        block_size: int
    ) -> ValidationResult:
        validation_result = self.validator.validate_block_boundaries(
            corrected, [], block_size
        )
        
        diff = np.abs(corrected - original)
        max_diff = np.max(diff)
        
        validation_result.metrics['max_correction_magnitude'] = float(max_diff)
        validation_result.metrics['mean_correction_magnitude'] = float(np.mean(diff))
        
        return validation_result

class MatrixBlockManager:
    
    DEFAULT_BLOCK_SIZE = 128
    SMALL_MATRIX_THRESHOLD = 2000
    
    def __init__(self, config: Optional[BlockConfig] = None):
        self.config = config or BlockConfig()
        self._temp_files: List[str] = []
        self.boundary_corrector = BlockBoundaryCorrector(
            overlap=self.config.boundary_overlap,
            method=self.config.boundary_correction_method,
            config=ValidationConfig(level=self.config.validation_level)
        )
    
    def __del__(self):
        self.cleanup()
    
    def cleanup(self):
        for temp_file in self._temp_files:
            try:
                if os.path.exists(temp_file):
                    os.remove(temp_file)
            except Exception:
                pass
        self._temp_files.clear()
    
    def estimate_memory(self, shape: Tuple[int, ...], dtype=np.float64) -> int:
        element_size = np.dtype(dtype).itemsize
        return int(np.prod(shape) * element_size)
    
    def should_use_blocking(self, matrix_shape: Tuple[int, ...], other_shape: Tuple[int, ...] = None) -> bool:
        if self.config.strategy == BlockStrategy.FIXED:
            return True
        
        if self.config.strategy == BlockStrategy.AUTO:
            total_size = self.estimate_memory(matrix_shape)
            if other_shape:
                total_size += self.estimate_memory(other_shape)
                result_shape = (matrix_shape[0], other_shape[1]) if len(matrix_shape) == 2 else (matrix_shape[0],)
                total_size += self.estimate_memory(result_shape)
            
            return total_size > self.config.max_memory_bytes * 0.5
        
        return False
    
    def compute_block_size(self, matrix_shape: Tuple[int, ...], operation: str = "multiply") -> int:
        if self.config.strategy == BlockStrategy.FIXED:
            return self.config.block_size
        
        max_rows = matrix_shape[0] if len(matrix_shape) >= 1 else 0
        max_cols = matrix_shape[1] if len(matrix_shape) >= 2 else max_rows
        
        elements_per_block = int(np.sqrt(self.config.max_memory_bytes / np.dtype(np.float64).itemsize / 10))
        elements_per_block = max(64, min(elements_per_block, 1024))
        
        if operation == "multiply":
            elements_per_block = int(elements_per_block * 0.7)
        
        return int(elements_per_block)
    
    def generate_blocks_with_overlap(
        self,
        rows: int,
        cols: int,
        block_size: int,
        overlap: int = 0
    ) -> List[BlockInfo]:
        blocks = []
        
        row_blocks = (rows + block_size - 1) // block_size
        col_blocks = (cols + block_size - 1) // block_size
        
        for i in range(row_blocks):
            row_start = i * block_size
            row_end = min((i + 1) * block_size, rows)
            
            is_first_row_block = (i == 0)
            is_last_row_block = (i == row_blocks - 1)
            
            row_overlap_start = 0 if is_first_row_block else min(overlap, row_start)
            row_overlap_end = 0 if is_last_row_block else min(overlap, rows - row_end)
            
            for j in range(col_blocks):
                col_start = j * block_size
                col_end = min((j + 1) * block_size, cols)
                
                is_first_col_block = (j == 0)
                is_last_col_block = (j == col_blocks - 1)
                
                col_overlap_start = 0 if is_first_col_block else min(overlap, col_start)
                col_overlap_end = 0 if is_last_col_block else min(overlap, cols - col_end)
                
                is_boundary_block = (
                    (not is_first_row_block) or (not is_last_row_block) or
                    (not is_first_col_block) or (not is_last_col_block)
                )
                
                effective_row_start = row_start - row_overlap_start
                effective_row_end = row_end + row_overlap_end
                effective_col_start = col_start - col_overlap_start
                effective_col_end = col_end + col_overlap_end
                
                block_memory = self.estimate_memory(
                    (effective_row_end - effective_row_start, effective_col_end - effective_col_start)
                )
                
                blocks.append(BlockInfo(
                    row_start=row_start,
                    row_end=row_end,
                    col_start=col_start,
                    col_end=col_end,
                    block_index=(i, j),
                    memory_estimate=block_memory,
                    is_boundary_block=is_boundary_block,
                    row_overlap_start=row_overlap_start,
                    row_overlap_end=row_overlap_end,
                    col_overlap_start=col_overlap_start,
                    col_overlap_end=col_overlap_end
                ))
        
        return blocks
    
    def create_memmap_array(
        self,
        shape: Tuple[int, ...],
        dtype=np.float64,
        mode: str = 'w+'
    ) -> Tuple[np.ndarray, str]:
        temp_dir = self.config.temp_dir or tempfile.gettempdir()
        
        fd, temp_path = tempfile.mkstemp(suffix='.npy', dir=temp_dir)
        os.close(fd)
        
        self._temp_files.append(temp_path)
        
        arr = np.memmap(temp_path, dtype=dtype, mode=mode, shape=shape)
        
        return arr, temp_path
    
    def array_to_memmap(
        self,
        arr: np.ndarray,
        dtype=np.float64
    ) -> Tuple[np.ndarray, str]:
        temp_dir = self.config.temp_dir or tempfile.gettempdir()
        
        fd, temp_path = tempfile.mkstemp(suffix='.npy', dir=temp_dir)
        os.close(fd)
        
        self._temp_files.append(temp_path)
        
        memmap_arr = np.memmap(temp_path, dtype=dtype, mode='w+', shape=arr.shape)
        memmap_arr[:] = arr[:]
        memmap_arr.flush()
        
        return memmap_arr, temp_path

class BlockMatrixOperations:
    
    def __init__(self, block_manager: Optional[MatrixBlockManager] = None):
        self.block_manager = block_manager or MatrixBlockManager()
        self.validator = MatrixValidator(
            ValidationConfig(level=self.block_manager.config.validation_level)
        )
    
    def multiply_blocked_with_correction(
        self,
        a: np.ndarray,
        b: np.ndarray,
        block_size: Optional[int] = None,
        use_boundary_correction: bool = True
    ) -> Tuple[np.ndarray, Dict[str, Any]]:
        if a.ndim == 1:
            a = a.reshape(1, -1)
        if b.ndim == 1:
            b = b.reshape(-1, 1)
        
        m, k1 = a.shape
        k2, n = b.shape
        
        if k1 != k2:
            raise DimensionMismatchError(
                f"Matrix dimensions mismatch for multiplication: "
                f"A is ({m}, {k1}), B is ({k2}, {n})"
            )
        
        use_memmap = self.block_manager.config.use_memmap
        result_memory = self.block_manager.estimate_memory((m, n))
        
        if block_size is None:
            block_size = self.block_manager.compute_block_size((m, n), "multiply")
        
        if not self.block_manager.should_use_blocking(a.shape, b.shape):
            result = np.dot(a, b)
            return result, {
                'used_blocking': False,
                'used_boundary_correction': False,
                'block_size': block_size
            }
        
        if use_memmap and result_memory > self.block_manager.config.max_memory_bytes * 0.3:
            result, _ = self.block_manager.create_memmap_array((m, n), dtype=np.float64)
        else:
            result = np.zeros((m, n), dtype=np.float64)
        
        overlap = self.block_manager.config.boundary_overlap if use_boundary_correction else 0
        
        row_blocks = (m + block_size - 1) // block_size
        k_blocks = (k1 + block_size - 1) // block_size
        col_blocks = (n + block_size - 1) // block_size
        
        for i in range(row_blocks):
            row_start = i * block_size
            row_end = min((i + 1) * block_size, m)
            
            for j in range(col_blocks):
                col_start = j * block_size
                col_end = min((j + 1) * block_size, n)
                
                block_sum = np.zeros((row_end - row_start, col_end - col_start), dtype=np.float64)
                
                for k in range(k_blocks):
                    k_start = k * block_size
                    k_end = min((k + 1) * block_size, k1)
                    
                    a_block = a[row_start:row_end, k_start:k_end]
                    b_block = b[k_start:k_end, col_start:col_end]
                    
                    block_sum += np.dot(a_block, b_block)
                    
                    del a_block, b_block
                    gc.collect()
                
                result[row_start:row_end, col_start:col_end] = block_sum
                
                del block_sum
                gc.collect()
        
        correction_info = {}
        if use_boundary_correction and self.block_manager.config.enable_boundary_correction:
            try:
                correction_result = self.block_manager.boundary_corrector.correct_all_boundaries(
                    result, block_size, overlap
                )
                result = correction_result.corrected_matrix
                
                correction_info = {
                    'used_boundary_correction': True,
                    'corrections_applied': correction_result.corrections_applied,
                    'max_boundary_error': correction_result.max_boundary_error,
                    'correction_method': correction_result.correction_method,
                    'overlap_used': overlap,
                    **correction_result.metrics
                }
            except Exception as e:
                correction_info = {
                    'used_boundary_correction': False,
                    'correction_error': str(e)
                }
        else:
            correction_info = {'used_boundary_correction': False}
        
        if isinstance(result, np.memmap):
            result.flush()
        
        return result, {
            'used_blocking': True,
            'block_size': block_size,
            'used_memmap': use_memmap and result_memory > self.block_manager.config.max_memory_bytes * 0.3,
            **correction_info
        }
    
    def inverse_blocked(
        self,
        matrix: np.ndarray,
        block_size: Optional[int] = None
    ) -> np.ndarray:
        n = matrix.shape[0]
        
        if matrix.shape[0] != matrix.shape[1]:
            raise InvalidMatrixError(
                f"Matrix must be square for inverse calculation, got shape {matrix.shape}"
            )
        
        det = np.linalg.det(matrix)
        if abs(det) < 1e-10:
            raise InvalidMatrixError("Matrix is singular (determinant near zero), cannot compute inverse")
        
        if not self.block_manager.should_use_blocking(matrix.shape):
            return np.linalg.inv(matrix)
        
        if n <= 2000:
            return np.linalg.inv(matrix)
        
        identity = np.eye(n, dtype=np.float64)
        augmented = np.hstack([matrix.astype(np.float64), identity])
        
        for col in range(n):
            pivot_row = col + np.argmax(np.abs(augmented[col:n, col]))
            
            if pivot_row != col:
                augmented[[col, pivot_row]] = augmented[[pivot_row, col]]
            
            pivot_val = augmented[col, col]
            if abs(pivot_val) < 1e-10:
                raise InvalidMatrixError("Matrix is singular, cannot compute inverse")
            
            augmented[col, :] = augmented[col, :] / pivot_val
            
            for row in range(n):
                if row != col:
                    factor = augmented[row, col]
                    augmented[row, :] = augmented[row, :] - factor * augmented[col, :]
        
        inverse = augmented[:, n:]
        
        return inverse
    
    def eigenvalues_blocked(
        self,
        matrix: np.ndarray,
        block_size: Optional[int] = None
    ) -> Tuple[np.ndarray, np.ndarray]:
        n = matrix.shape[0]
        
        if matrix.shape[0] != matrix.shape[1]:
            raise InvalidMatrixError(
                f"Matrix must be square for eigenvalue decomposition, got shape {matrix.shape}"
            )
        
        if not self.block_manager.should_use_blocking(matrix.shape):
            eigenvalues, eigenvectors = np.linalg.eig(matrix)
            return eigenvalues, eigenvectors
        
        if n <= 2000:
            eigenvalues, eigenvectors = np.linalg.eig(matrix)
            return eigenvalues, eigenvectors
        
        try:
            from scipy.linalg import eigh
            is_symmetric = np.allclose(matrix, matrix.T)
            
            if is_symmetric:
                eigenvalues, eigenvectors = eigh(matrix.astype(np.float64))
                return eigenvalues, eigenvectors
        except ImportError:
            pass
        
        eigenvalues, eigenvectors = np.linalg.eig(matrix.astype(np.float64))
        
        return eigenvalues, eigenvectors

class MatrixComputeEngine:
    
    MAX_MATRIX_SIZE = 100000
    DEFAULT_BLOCK_CONFIG = BlockConfig(
        strategy=BlockStrategy.AUTO,
        max_memory_bytes=1024 * 1024 * 1024,
        use_memmap=True,
        enable_boundary_correction=True,
        boundary_overlap=4,
        boundary_correction_method=BoundaryCorrectionMethod.WEIGHTED_AVERAGE,
        validation_level=ValidationLevel.STANDARD
    )
    
    def __init__(self, block_config: Optional[BlockConfig] = None):
        self.block_config = block_config or self.DEFAULT_BLOCK_CONFIG
        self.block_manager = MatrixBlockManager(self.block_config)
        self.block_ops = BlockMatrixOperations(self.block_manager)
        self.validator = MatrixValidator(
            ValidationConfig(level=self.block_config.validation_level)
        )
    
    def __del__(self):
        if hasattr(self, 'block_manager') and self.block_manager:
            self.block_manager.cleanup()
    
    def _validate_matrix(self, matrix: Any, name: str = "matrix") -> np.ndarray:
        try:
            if isinstance(matrix, np.ndarray):
                arr = matrix
            elif isinstance(matrix, np.memmap):
                arr = matrix
            elif isinstance(matrix, (list, tuple)):
                arr = np.array(matrix, dtype=np.float64)
            else:
                raise InvalidMatrixError(f"{name} must be a list, tuple, or numpy array")
            
            if arr.ndim not in (1, 2):
                raise InvalidMatrixError(f"{name} must be 1D or 2D array, got {arr.ndim}D")
            
            if arr.ndim == 2 and (arr.shape[0] > self.MAX_MATRIX_SIZE or arr.shape[1] > self.MAX_MATRIX_SIZE):
                raise InvalidMatrixError(
                    f"{name} dimensions exceed maximum allowed: "
                    f"{arr.shape} > ({self.MAX_MATRIX_SIZE}, {self.MAX_MATRIX_SIZE})"
                )
            
            return arr
        except Exception as e:
            if isinstance(e, MatrixEngineError):
                raise
            raise InvalidMatrixError(f"Failed to validate {name}: {str(e)}")
    
    def _to_memmap_if_needed(self, matrix: np.ndarray) -> np.ndarray:
        if not self.block_config.use_memmap:
            return matrix
        
        memory_needed = self.block_manager.estimate_memory(matrix.shape)
        
        if memory_needed > self.block_config.max_memory_bytes * 0.3:
            memmap_arr, _ = self.block_manager.array_to_memmap(matrix)
            return memmap_arr
        
        return matrix
    
    def multiply(
        self, 
        matrix_a: Union[List[List[float]], np.ndarray], 
        matrix_b: Union[List[List[float]], np.ndarray],
        use_boundary_correction: bool = True
    ) -> Dict[str, Any]:
        try:
            a = self._validate_matrix(matrix_a, "matrix_a")
            b = self._validate_matrix(matrix_b, "matrix_b")
            
            original_a_ndim = a.ndim
            original_b_ndim = b.ndim
            
            if a.ndim == 1:
                a = a.reshape(1, -1)
            if b.ndim == 1:
                b = b.reshape(-1, 1)
            
            if a.shape[1] != b.shape[0]:
                raise DimensionMismatchError(
                    f"Matrix dimensions mismatch for multiplication: "
                    f"A is {a.shape}, B is {b.shape}"
                )
            
            a = self._to_memmap_if_needed(a)
            b = self._to_memmap_if_needed(b)
            
            block_size = self.block_manager.compute_block_size(a.shape, "multiply")
            
            result, extra_info = self.block_ops.multiply_blocked_with_correction(
                a, b, block_size, 
                use_boundary_correction=use_boundary_correction
            )
            
            if original_a_ndim == 1 and original_b_ndim == 1:
                result = result[0, 0]
                result_matrix = [[float(result)]]
            elif original_a_ndim == 1:
                result = result[0, :]
                result_matrix = [result.tolist()]
            elif original_b_ndim == 1:
                result = result[:, 0]
                result_matrix = [[r] for r in result.tolist()]
            else:
                result_matrix = result.tolist()
            
            validation_result = None
            if self.block_config.validation_level != ValidationLevel.LENIENT:
                if a.shape[0] <= 50 and b.shape[1] <= 50 and a.shape[1] <= 50:
                    if not isinstance(a, np.memmap) and not isinstance(b, np.memmap):
                        validation_result = self.validator.validate_multiplication_consistency(a, b, result)
            
            output = {
                'result_matrix': result_matrix,
                'shape': list(result.shape) if isinstance(result, np.ndarray) else [1, 1],
                'operation': 'multiply',
                'used_blocking': extra_info.get('used_blocking', False),
                'block_size': extra_info.get('block_size', block_size),
                'used_memmap': isinstance(a, np.memmap) or isinstance(b, np.memmap),
                **extra_info
            }
            
            if validation_result:
                output['validation'] = {
                    'passed': validation_result.passed,
                    'errors': validation_result.errors,
                    'warnings': validation_result.warnings,
                    'metrics': validation_result.metrics
                }
            
            return output
        except MatrixEngineError:
            raise
        except Exception as e:
            raise MatrixEngineError(f"Matrix multiplication failed: {str(e)}")
        finally:
            gc.collect()
    
    def inverse(self, matrix: Union[List[List[float]], np.ndarray]) -> Dict[str, Any]:
        try:
            mat = self._validate_matrix(matrix, "matrix")
            
            if mat.ndim != 2:
                raise InvalidMatrixError("Matrix must be 2D for inverse calculation")
            
            if mat.shape[0] != mat.shape[1]:
                raise InvalidMatrixError(
                    f"Matrix must be square for inverse calculation, got shape {mat.shape}"
                )
            
            det = np.linalg.det(mat)
            if abs(det) < 1e-10:
                raise InvalidMatrixError("Matrix is singular (determinant near zero), cannot compute inverse")
            
            mat = self._to_memmap_if_needed(mat)
            
            block_size = self.block_manager.compute_block_size(mat.shape, "inverse")
            inv = self.block_ops.inverse_blocked(mat, block_size)
            
            return {
                'inverse_matrix': inv.tolist(),
                'shape': list(inv.shape),
                'determinant': float(det),
                'operation': 'inverse',
                'used_blocking': self.block_manager.should_use_blocking(mat.shape),
                'block_size': block_size,
                'used_memmap': isinstance(mat, np.memmap)
            }
        except MatrixEngineError:
            raise
        except np.linalg.LinAlgError as e:
            raise InvalidMatrixError(f"Linear algebra error: {str(e)}")
        except Exception as e:
            raise MatrixEngineError(f"Matrix inverse failed: {str(e)}")
        finally:
            gc.collect()
    
    def eigenvalues(self, matrix: Union[List[List[float]], np.ndarray]) -> Dict[str, Any]:
        try:
            mat = self._validate_matrix(matrix, "matrix")
            
            if mat.ndim != 2:
                raise InvalidMatrixError("Matrix must be 2D for eigenvalue decomposition")
            
            if mat.shape[0] != mat.shape[1]:
                raise InvalidMatrixError(
                    f"Matrix must be square for eigenvalue decomposition, got shape {mat.shape}"
                )
            
            mat = self._to_memmap_if_needed(mat)
            
            block_size = self.block_manager.compute_block_size(mat.shape, "eigenvalues")
            eigenvalues, eigenvectors = self.block_ops.eigenvalues_blocked(mat, block_size)
            
            sorted_indices = np.argsort(np.abs(eigenvalues))[::-1]
            eigenvalues_sorted = eigenvalues[sorted_indices]
            eigenvectors_sorted = eigenvectors[:, sorted_indices]
            
            real_eigenvalues = []
            imag_eigenvalues = []
            for val in eigenvalues_sorted:
                real_eigenvalues.append(float(np.real(val)))
                imag_eigenvalues.append(float(np.imag(val)))
            
            return {
                'eigenvalues_real': real_eigenvalues,
                'eigenvalues_imaginary': imag_eigenvalues,
                'eigenvectors_matrix': eigenvectors_sorted.tolist(),
                'shape': list(mat.shape),
                'is_real': all(abs(im) < 1e-10 for im in imag_eigenvalues),
                'operation': 'eigenvalues',
                'used_blocking': self.block_manager.should_use_blocking(mat.shape),
                'block_size': block_size,
                'used_memmap': isinstance(mat, np.memmap)
            }
        except MatrixEngineError:
            raise
        except np.linalg.LinAlgError as e:
            raise InvalidMatrixError(f"Linear algebra error: {str(e)}")
        except Exception as e:
            raise MatrixEngineError(f"Eigenvalue decomposition failed: {str(e)}")
        finally:
            gc.collect()
    
    def transpose(self, matrix: Union[List[List[float]], np.ndarray]) -> Dict[str, Any]:
        try:
            mat = self._validate_matrix(matrix, "matrix")
            
            if mat.ndim == 1:
                result = mat
            else:
                if self.block_manager.should_use_blocking(mat.shape) and self.block_config.use_memmap:
                    mat = self._to_memmap_if_needed(mat)
                result = np.transpose(mat)
            
            return {
                'transposed_matrix': result.tolist(),
                'original_shape': list(mat.shape),
                'transposed_shape': list(result.shape),
                'operation': 'transpose',
                'used_blocking': False,
                'used_memmap': isinstance(mat, np.memmap)
            }
        except MatrixEngineError:
            raise
        except Exception as e:
            raise MatrixEngineError(f"Matrix transpose failed: {str(e)}")
        finally:
            gc.collect()
    
    def add(
        self, 
        matrix_a: Union[List[List[float]], np.ndarray], 
        matrix_b: Union[List[List[float]], np.ndarray]
    ) -> Dict[str, Any]:
        try:
            a = self._validate_matrix(matrix_a, "matrix_a")
            b = self._validate_matrix(matrix_b, "matrix_b")
            
            if a.shape != b.shape:
                raise DimensionMismatchError(
                    f"Matrix dimensions mismatch for addition: "
                    f"A is {a.shape}, B is {b.shape}"
                )
            
            if self.block_manager.should_use_blocking(a.shape, b.shape) and self.block_config.use_memmap:
                a = self._to_memmap_if_needed(a)
                b = self._to_memmap_if_needed(b)
            
            result = np.add(a, b)
            
            return {
                'result_matrix': result.tolist(),
                'shape': list(result.shape),
                'operation': 'add',
                'used_blocking': False,
                'used_memmap': isinstance(a, np.memmap) or isinstance(b, np.memmap)
            }
        except MatrixEngineError:
            raise
        except Exception as e:
            raise MatrixEngineError(f"Matrix addition failed: {str(e)}")
        finally:
            gc.collect()
    
    def cleanup(self):
        if hasattr(self, 'block_manager') and self.block_manager:
            self.block_manager.cleanup()
