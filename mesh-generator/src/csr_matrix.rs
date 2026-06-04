

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
            let row_indices: Vec<(usize, f64)> = (start..end)
                .map(|i| (indices[i], data[i]))
                .collect();
            
            let mut sorted = row_indices;
            sorted.sort_by_key(|&(c, _)| c);
            
            for (i, (c, v)) in sorted.iter().enumerate() {
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
        
        for i in start..end {
            if self.indices[i] == col {
                return Some(self.data[i]);
            }
        }
        None
    }

    pub fn mul_vec(&self, x: &[f64], y: &mut [f64]) {
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
            for i in self.indptr[r]..self.indptr[r + 1] {
                if self.indices[i] == r {
                    diag[r] = self.data[i];
                    break;
                }
            }
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
}
