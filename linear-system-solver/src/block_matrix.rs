use rayon::prelude::*;

pub const BLOCK_SIZE: usize = 4;

#[derive(Debug, Clone)]
pub struct Block4x4 {
    pub data: [f64; 16],
}

impl Block4x4 {
    pub fn zero() -> Self {
        Block4x4 { data: [0.0; 16] }
    }

    pub fn identity() -> Self {
        let mut b = Block4x4::zero();
        b.data[0] = 1.0;
        b.data[5] = 1.0;
        b.data[10] = 1.0;
        b.data[15] = 1.0;
        b
    }

    #[inline(always)]
    pub fn get(&self, r: usize, c: usize) -> f64 {
        self.data[r * BLOCK_SIZE + c]
    }

    #[inline(always)]
    pub fn set(&mut self, r: usize, c: usize, val: f64) {
        self.data[r * BLOCK_SIZE + c] = val;
    }

    #[inline(always)]
    pub fn mul_vec4(&self, x: &[f64; BLOCK_SIZE]) -> [f64; BLOCK_SIZE] {
        let mut result = [0.0; BLOCK_SIZE];
        result[0] = self.data[0] * x[0] + self.data[1] * x[1] + self.data[2] * x[2] + self.data[3] * x[3];
        result[1] = self.data[4] * x[0] + self.data[5] * x[1] + self.data[6] * x[2] + self.data[7] * x[3];
        result[2] = self.data[8] * x[0] + self.data[9] * x[1] + self.data[10] * x[2] + self.data[11] * x[3];
        result[3] = self.data[12] * x[0] + self.data[13] * x[1] + self.data[14] * x[2] + self.data[15] * x[3];
        result
    }

    pub fn inverse(&self) -> Option<Block4x4> {
        let mut m = self.data;
        let mut inv = [0.0; 16];
        inv[0] = 1.0; inv[5] = 1.0; inv[10] = 1.0; inv[15] = 1.0;

        for col in 0..BLOCK_SIZE {
            let mut max_val = m[col * BLOCK_SIZE + col].abs();
            let mut max_row = col;
            for row in (col + 1)..BLOCK_SIZE {
                let val = m[row * BLOCK_SIZE + col].abs();
                if val > max_val {
                    max_val = val;
                    max_row = row;
                }
            }

            if max_val < 1e-15 {
                return None;
            }

            if max_row != col {
                for j in 0..BLOCK_SIZE {
                    m.swap(col * BLOCK_SIZE + j, max_row * BLOCK_SIZE + j);
                    inv.swap(col * BLOCK_SIZE + j, max_row * BLOCK_SIZE + j);
                }
            }

            let pivot = m[col * BLOCK_SIZE + col];
            for j in 0..BLOCK_SIZE {
                m[col * BLOCK_SIZE + j] /= pivot;
                inv[col * BLOCK_SIZE + j] /= pivot;
            }

            for row in 0..BLOCK_SIZE {
                if row == col {
                    continue;
                }
                let factor = m[row * BLOCK_SIZE + col];
                for j in 0..BLOCK_SIZE {
                    m[row * BLOCK_SIZE + j] -= factor * m[col * BLOCK_SIZE + j];
                    inv[row * BLOCK_SIZE + j] -= factor * inv[col * BLOCK_SIZE + j];
                }
            }
        }

        Some(Block4x4 { data: inv })
    }

    pub fn lu_decompose(&self) -> Option<(Block4x4, Block4x4)> {
        let mut l = Block4x4::identity();
        let mut u = Block4x4::zero();

        for i in 0..BLOCK_SIZE {
            for j in 0..BLOCK_SIZE {
                if j >= i {
                    let mut sum = 0.0;
                    for k in 0..i {
                        sum += l.get(i, k) * u.get(k, j);
                    }
                    u.set(i, j, self.get(i, j) - sum);
                } else {
                    let mut sum = 0.0;
                    for k in 0..j {
                        sum += l.get(i, k) * u.get(k, j);
                    }
                    let u_jj = u.get(j, j);
                    if u_jj.abs() < 1e-15 {
                        return None;
                    }
                    l.set(i, j, (self.get(i, j) - sum) / u_jj);
                }
            }
        }

        Some((l, u))
    }

    pub fn forward_solve(&self, b: &[f64; BLOCK_SIZE]) -> [f64; BLOCK_SIZE] {
        let mut y = [0.0; BLOCK_SIZE];
        y[0] = b[0];
        y[1] = b[1] - self.data[4] * y[0];
        y[2] = b[2] - self.data[8] * y[0] - self.data[9] * y[1];
        y[3] = b[3] - self.data[12] * y[0] - self.data[13] * y[1] - self.data[14] * y[2];
        y
    }

    pub fn back_solve(&self, y: &[f64; BLOCK_SIZE]) -> [f64; BLOCK_SIZE] {
        let n = BLOCK_SIZE;
        let mut x = [0.0; BLOCK_SIZE];

        x[3] = y[3] / self.data[15];
        x[2] = (y[2] - self.data[11] * x[3]) / self.data[10];
        x[1] = (y[1] - self.data[7] * x[3] - self.data[6] * x[2]) / self.data[5];
        x[0] = (y[0] - self.data[3] * x[3] - self.data[2] * x[2] - self.data[1] * x[1]) / self.data[0];

        x
    }
}

impl std::ops::Sub for &Block4x4 {
    type Output = Block4x4;

    fn sub(self, other: &Block4x4) -> Block4x4 {
        let mut result = Block4x4::zero();
        for i in 0..16 {
            result.data[i] = self.data[i] - other.data[i];
        }
        result
    }
}

#[derive(Debug, Clone)]
pub struct BlockCsrMatrix {
    pub n_block_rows: usize,
    pub n_block_cols: usize,
    pub indptr: Vec<usize>,
    pub block_col_indices: Vec<usize>,
    pub blocks: Vec<Block4x4>,
}

impl BlockCsrMatrix {
    pub fn new(n_block_rows: usize, n_block_cols: usize) -> Self {
        BlockCsrMatrix {
            n_block_rows,
            n_block_cols,
            indptr: vec![0; n_block_rows + 1],
            block_col_indices: Vec::new(),
            blocks: Vec::new(),
        }
    }

    pub fn from_scalar_csr(mat: &crate::matrix::CsrMatrix, block_size: usize) -> Self {
        let n_block_rows = mat.nrows / block_size;
        let n_block_cols = mat.ncols / block_size;

        let mut block_rows: Vec<std::collections::HashMap<usize, Block4x4>> =
            vec![std::collections::HashMap::new(); n_block_rows];

        for scalar_row in 0..mat.nrows {
            let block_row = scalar_row / block_size;
            let local_row = scalar_row % block_size;

            for i in mat.indptr[scalar_row]..mat.indptr[scalar_row + 1] {
                let scalar_col = mat.indices[i];
                let val = mat.data[i];
                let block_col = scalar_col / block_size;
                let local_col = scalar_col % block_size;

                let block = block_rows[block_row].entry(block_col).or_insert_with(Block4x4::zero);
                block.set(local_row, local_col, val);
            }
        }

        let mut indptr = vec![0; n_block_rows + 1];
        let mut block_col_indices = Vec::new();
        let mut blocks = Vec::new();

        for (br, row_map) in block_rows.iter_mut().enumerate() {
            let mut sorted_entries: Vec<_> = row_map.drain().collect();
            sorted_entries.sort_by_key(|(col, _)| *col);

            indptr[br + 1] = indptr[br] + sorted_entries.len();

            for (col, block) in sorted_entries {
                block_col_indices.push(col);
                blocks.push(block);
            }
        }

        BlockCsrMatrix {
            n_block_rows,
            n_block_cols,
            indptr,
            block_col_indices,
            blocks,
        }
    }

    pub fn to_scalar_csr(&self) -> crate::matrix::CsrMatrix {
        let nrows = self.n_block_rows * BLOCK_SIZE;
        let ncols = self.n_block_cols * BLOCK_SIZE;

        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut vals = Vec::new();

        for br in 0..self.n_block_rows {
            for bi in 0..BLOCK_SIZE {
                for blk_idx in self.indptr[br]..self.indptr[br + 1] {
                    let bc = self.block_col_indices[blk_idx];
                    let block = &self.blocks[blk_idx];
                    for bj in 0..BLOCK_SIZE {
                        let val = block.get(bi, bj);
                        if val.abs() > 0.0 {
                            rows.push(br * BLOCK_SIZE + bi);
                            cols.push(bc * BLOCK_SIZE + bj);
                            vals.push(val);
                        }
                    }
                }
            }
        }

        crate::matrix::CsrMatrix::from_triplets(nrows, ncols, &rows, &cols, &vals)
    }

    #[inline]
    pub fn block_mul_vec(&self, x: &[f64], y: &mut [f64]) {
        let bs = BLOCK_SIZE;
        debug_assert_eq!(x.len(), self.n_block_cols * bs);
        debug_assert_eq!(y.len(), self.n_block_rows * bs);

        y.par_chunks_exact_mut(bs)
            .enumerate()
            .for_each(|(block_row, y_chunk)| {
                let start = self.indptr[block_row];
                let end = self.indptr[block_row + 1];

                let mut result = [0.0f64; BLOCK_SIZE];
                for blk_idx in start..end {
                    let bc = self.block_col_indices[blk_idx];
                    let block = &self.blocks[blk_idx];

                    let mut x_block = [0.0f64; BLOCK_SIZE];
                    x_block.copy_from_slice(&x[bc * bs..bc * bs + bs]);

                    let block_result = block.mul_vec4(&x_block);
                    for d in 0..bs {
                        result[d] += block_result[d];
                    }
                }

                y_chunk.copy_from_slice(&result);
            });
    }

    pub fn block_mul_vec_seq(&self, x: &[f64], y: &mut [f64]) {
        let bs = BLOCK_SIZE;
        for br in 0..self.n_block_rows {
            let start = self.indptr[br];
            let end = self.indptr[br + 1];

            let mut result = [0.0f64; BLOCK_SIZE];
            for blk_idx in start..end {
                let bc = self.block_col_indices[blk_idx];
                let block = &self.blocks[blk_idx];

                let mut x_block = [0.0f64; BLOCK_SIZE];
                x_block.copy_from_slice(&x[bc * bs..bc * bs + bs]);

                let block_result = block.mul_vec4(&x_block);
                for d in 0..bs {
                    result[d] += block_result[d];
                }
            }

            let offset = br * bs;
            y[offset] = result[0];
            y[offset + 1] = result[1];
            y[offset + 2] = result[2];
            y[offset + 3] = result[3];
        }
    }

    pub fn n_blocks(&self) -> usize {
        self.blocks.len()
    }

    pub fn get_block(&self, block_row: usize, block_col: usize) -> Option<&Block4x4> {
        let start = self.indptr[block_row];
        let end = self.indptr[block_row + 1];
        for i in start..end {
            if self.block_col_indices[i] == block_col {
                return Some(&self.blocks[i]);
            }
        }
        None
    }

    pub fn get_block_mut(&mut self, block_row: usize, block_col: usize) -> Option<&mut Block4x4> {
        let start = self.indptr[block_row];
        let end = self.indptr[block_row + 1];
        for i in start..end {
            if self.block_col_indices[i] == block_col {
                return Some(&mut self.blocks[i]);
            }
        }
        None
    }
}
