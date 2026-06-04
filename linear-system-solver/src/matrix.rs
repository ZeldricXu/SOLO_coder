
use rayon::prelude::*;

#[derive(Debug, Clone)]
pub struct CsrMatrix {
    pub nrows: usize,
    pub ncols: usize,
    pub indptr: Vec<usize>,
    pub indices: Vec<usize>,
    pub data: Vec<f64>,
}

impl CsrMatrix {
    pub fn new(nrows: usize, ncols: usize) -> Self {
        CsrMatrix {
            nrows,
            ncols,
            indptr: vec![0; nrows + 1],
            indices: Vec::new(),
            data: Vec::new(),
        }
    }

    pub fn from_triplets(nrows: usize, ncols: usize, rows: &[usize], cols: &[usize], values: &[f64]) -> Self {
        assert_eq!(rows.len(), cols.len());
        assert_eq!(rows.len(), values.len());

        let mut nnz_per_row = vec![0; nrows];
        for &r in rows {
            nnz_per_row[r] += 1;
        }

        let mut indptr = vec![0; nrows + 1];
        for i in 0..nrows {
            indptr[i + 1] = indptr[i] + nnz_per_row[i];
        }

        let mut indices = vec![0; rows.len()];
        let mut data = vec![0.0; rows.len()];
        let mut counter = vec![0; nrows];

        for i in 0..rows.len() {
            let r = rows[i];
            let pos = indptr[r] + counter[r];
            indices[pos] = cols[i];
            data[pos] = values[i];
            counter[r] += 1;
        }

        for r in 0..nrows {
            let start = indptr[r];
            let end = indptr[r + 1];
            let mut row_pairs: Vec<(usize, f64)> = (start..end)
                .map(|i| (indices[i], data[i]))
                .collect();
            
            row_pairs.sort_by_key(|&(c, _)| c);
            
            for (i, (c, v)) in row_pairs.iter().enumerate() {
                indices[start + i] = *c;
                data[start + i] = *v;
            }
        }

        CsrMatrix {
            nrows,
            ncols,
            indptr,
            indices,
            data,
        }
    }

    pub fn nnz(&self) -> usize {
        self.data.len()
    }

    pub fn get(&self, row: usize, col: usize) -> Option<f64> {
        let start = self.indptr[row];
        let end = self.indptr[row + 1];
        self.indices[start..end].binary_search(&col)
            .ok()
            .map(|i| self.data[start + i])
    }

    pub fn mul_vec(&self, x: &[f64], y: &mut [f64]) {
        assert_eq!(x.len(), self.ncols);
        assert_eq!(y.len(), self.nrows);

        y.par_iter_mut().enumerate().for_each(|(r, y_r)| {
            let mut sum = 0.0;
            for i in self.indptr[r]..self.indptr[r + 1] {
                sum += self.data[i] * x[self.indices[i]];
            }
            *y_r = sum;
        });
    }

    pub fn mul_vec_seq(&self, x: &[f64], y: &mut [f64]) {
        assert_eq!(x.len(), self.ncols);
        assert_eq!(y.len(), self.nrows);

        for r in 0..self.nrows {
            let mut sum = 0.0;
            for i in self.indptr[r]..self.indptr[r + 1] {
                sum += self.data[i] * x[self.indices[i]];
            }
            y[r] = sum;
        }
    }

    pub fn diagonal(&self) -> Vec<f64> {
        let mut diag = vec![0.0; self.nrows];
        for r in 0..self.nrows {
            diag[r] = self.get(r, r).unwrap_or(0.0);
        }
        diag
    }

    pub fn transpose(&self) -> Self {
        let mut nnz_per_col = vec![0; self.ncols];
        for &c in &self.indices {
            nnz_per_col[c] += 1;
        }

        let mut indptr_t = vec![0; self.ncols + 1];
        for i in 0..self.ncols {
            indptr_t[i + 1] = indptr_t[i] + nnz_per_col[i];
        }

        let mut indices_t = vec![0; self.nnz()];
        let mut data_t = vec![0.0; self.nnz()];
        let mut counter = vec![0; self.ncols];

        for r in 0..self.nrows {
            for i in self.indptr[r]..self.indptr[r + 1] {
                let c = self.indices[i];
                let pos = indptr_t[c] + counter[c];
                indices_t[pos] = r;
                data_t[pos] = self.data[i];
                counter[c] += 1;
            }
        }

        CsrMatrix {
            nrows: self.ncols,
            ncols: self.nrows,
            indptr: indptr_t,
            indices: indices_t,
            data: data_t,
        }
    }

    pub fn add(&self, other: &CsrMatrix) -> CsrMatrix {
        assert_eq!(self.nrows, other.nrows);
        assert_eq!(self.ncols, other.ncols);

        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut values = Vec::new();

        for r in 0..self.nrows {
            let mut i = self.indptr[r];
            let mut j = other.indptr[r];
            let i_end = self.indptr[r + 1];
            let j_end = other.indptr[r + 1];

            while i < i_end || j < j_end {
                if j >= j_end || (i < i_end && self.indices[i] < other.indices[j]) {
                    rows.push(r);
                    cols.push(self.indices[i]);
                    values.push(self.data[i]);
                    i += 1;
                } else if i >= i_end || (j < j_end && other.indices[j] < self.indices[i]) {
                    rows.push(r);
                    cols.push(other.indices[j]);
                    values.push(other.data[j]);
                    j += 1;
                } else {
                    rows.push(r);
                    cols.push(self.indices[i]);
                    values.push(self.data[i] + other.data[j]);
                    i += 1;
                    j += 1;
                }
            }
        }

        CsrMatrix::from_triplets(self.nrows, self.ncols, &rows, &cols, &values)
    }
}

pub fn dot(a: &[f64], b: &[f64]) -> f64 {
    a.par_iter().zip(b.par_iter()).map(|(x, y)| x * y).sum()
}

pub fn norm2(v: &[f64]) -> f64 {
    dot(v, v).sqrt()
}

pub fn axpy(a: f64, x: &[f64], y: &mut [f64]) {
    y.par_iter_mut().zip(x.par_iter()).for_each(|(yi, xi)| *yi += a * xi);
}

pub fn waxpby(a: f64, x: &[f64], b: f64, y: &[f64], z: &mut [f64]) {
    z.par_iter_mut().enumerate().for_each(|(i, zi)| *zi = a * x[i] + b * y[i]);
}

pub fn scale(v: &mut [f64], alpha: f64) {
    v.par_iter_mut().for_each(|x| *x *= alpha);
}

pub fn copy(dst: &mut [f64], src: &[f64]) {
    dst.par_iter_mut().zip(src.par_iter()).for_each(|(d, s)| *d = *s);
}
