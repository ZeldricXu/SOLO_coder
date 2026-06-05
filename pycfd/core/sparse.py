import numpy as np
from scipy.sparse import csr_matrix, lil_matrix, issparse
from .jit import njit, prange

class SparseMatrix:
    def __init__(self, n_rows, n_cols=None):
        self.n_rows = n_rows
        self.n_cols = n_cols if n_cols else n_rows
        self._matrix = lil_matrix((self.n_rows, self.n_cols), dtype=np.float64)

    @property
    def shape(self):
        return (self.n_rows, self.n_cols)

    @property
    def dtype(self):
        return self._matrix.dtype

    def add_value(self, row, col, value):
        self._matrix[row, col] += value

    def set_value(self, row, col, value):
        self._matrix[row, col] = value

    def to_csr(self):
        return self._matrix.tocsr()

    def tocsr(self):
        return self.to_csr()

    def tocsc(self):
        return self._matrix.tocsc()

    def tocoo(self):
        return self._matrix.tocoo()

    def to_array(self):
        return self._matrix.toarray()

    def multiply_vector(self, x):
        return self._matrix @ x

    def get_diagonal(self):
        return self._matrix.diagonal()

    def __mul__(self, other):
        if isinstance(other, np.ndarray):
            return self.multiply_vector(other)
        return NotImplemented

    def __rmul__(self, other):
        if isinstance(other, np.ndarray):
            return other @ self._matrix
        return NotImplemented

    def __getattr__(self, name):
        if name.startswith('_'):
            raise AttributeError(name)
        return getattr(self._matrix, name)

    def solve(self, b, method='spsolve'):
        from scipy.sparse.linalg import spsolve, gmres, bicgstab
        A = self.to_csr()
        if method == 'spsolve':
            return spsolve(A, b)
        elif method == 'gmres':
            x, info = gmres(A, b, tol=1e-8, maxiter=1000)
            return x
        elif method == 'bicgstab':
            x, info = bicgstab(A, b, tol=1e-8, maxiter=1000)
            return x
        else:
            raise ValueError(f"Unknown solver method: {method}")

def assemble_sparse(n, stencil_generator, *args):
    mat = SparseMatrix(n)
    for i in range(n):
        cols, values = stencil_generator(i, *args)
        for j, v in zip(cols, values):
            if 0 <= j < n:
                mat.add_value(i, j, v)
    return mat

@njit
def sparse_matvec_csr(data, indices, indptr, x, y):
    n = len(indptr) - 1
    for i in prange(n):
        s = 0.0
        for j in range(indptr[i], indptr[i+1]):
            s += data[j] * x[indices[j]]
        y[i] = s
    return y

@njit
def sparse_matvec_coo(rows, cols, data, x, y):
    n = len(y)
    y[:] = 0.0
    for k in prange(len(data)):
        y[rows[k]] += data[k] * x[cols[k]]
    return y

def create_laplacian_2d(nx, ny, dx, dy):
    n = nx * ny
    mat = SparseMatrix(n)
    for i in range(nx):
        for j in range(ny):
            idx = i * ny + j
            coef_x = 1.0 / (dx * dx)
            coef_y = 1.0 / (dy * dy)
            mat.set_value(idx, idx, 2 * coef_x + 2 * coef_y)
            if i > 0:
                mat.add_value(idx, (i-1) * ny + j, -coef_x)
            if i < nx - 1:
                mat.add_value(idx, (i+1) * ny + j, -coef_x)
            if j > 0:
                mat.add_value(idx, i * ny + (j-1), -coef_y)
            if j < ny - 1:
                mat.add_value(idx, i * ny + (j+1), -coef_y)
    return mat
