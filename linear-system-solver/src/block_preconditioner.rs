use crate::block_matrix::{BlockCsrMatrix, Block4x4, BLOCK_SIZE};
use crate::preconditioner::Preconditioner;

pub struct BlockIlu0Preconditioner {
    l_blocks: Vec<Block4x4>,
    u_blocks: Vec<Block4x4>,
    inv_diag: Vec<Block4x4>,
    indptr: Vec<usize>,
    block_col_indices: Vec<usize>,
    n_block_rows: usize,
}

impl BlockIlu0Preconditioner {
    pub fn new(mat: &BlockCsrMatrix) -> Self {
        let n = mat.n_block_rows;
        let mut l_blocks = mat.blocks.clone();
        let mut u_blocks = mat.blocks.clone();
        let mut inv_diag: Vec<Block4x4> = vec![Block4x4::identity(); n];

        for k in 0..n {
            let k_diag = Self::get_block(&l_blocks, &mat.indptr, &mat.block_col_indices, k, k);
            let diag_block: Block4x4 = match k_diag {
                Some(blk) => blk.clone(),
                None => Block4x4::identity(),
            };

            let diag_inv = diag_block.inverse().unwrap_or_else(|| Block4x4::identity());
            inv_diag[k] = diag_inv;

            let k_start = mat.indptr[k];
            let k_end = mat.indptr[k + 1];

            for idx in k_start..k_end {
                let j = mat.block_col_indices[idx];
                if j > k {
                    let a_kj = l_blocks[idx].clone();
                    let diag_k_inv = inv_diag[k].clone();
                    let result = Self::mul_block_block(&diag_k_inv, &a_kj);
                    u_blocks[idx] = result;
                }
            }

            for i in (k + 1)..n {
                let a_ik_val = Self::get_block(&l_blocks, &mat.indptr, &mat.block_col_indices, i, k)
                    .cloned();

                if let Some(ref a_ik_val) = a_ik_val {
                    let updates: Vec<(usize, Block4x4)> = {
                        let mut ups = Vec::new();
                        for idx in mat.indptr[i]..mat.indptr[i + 1] {
                            let j = mat.block_col_indices[idx];
                            if j > k {
                                let u_kj = Self::get_block(&u_blocks, &mat.indptr, &mat.block_col_indices, k, j);
                                if let Some(ref u_kj_blk) = u_kj {
                                    let product = Self::mul_block_block(a_ik_val, u_kj_blk);
                                    let current = l_blocks[idx].clone();
                                    ups.push((idx, Self::sub_block(&current, &product)));
                                }
                            }
                        }
                        ups
                    };

                    for (idx, new_block) in updates {
                        l_blocks[idx] = new_block;
                    }
                }
            }
        }

        BlockIlu0Preconditioner {
            l_blocks,
            u_blocks,
            inv_diag,
            indptr: mat.indptr.clone(),
            block_col_indices: mat.block_col_indices.clone(),
            n_block_rows: n,
        }
    }

    fn get_block<'a>(
        blocks: &'a [Block4x4],
        indptr: &[usize],
        col_indices: &[usize],
        block_row: usize,
        block_col: usize,
    ) -> Option<&'a Block4x4> {
        let start = indptr[block_row];
        let end = indptr[block_row + 1];
        for i in start..end {
            if col_indices[i] == block_col {
                return Some(&blocks[i]);
            }
        }
        None
    }

    fn mul_block_block(a: &Block4x4, b: &Block4x4) -> Block4x4 {
        let mut result = Block4x4::zero();
        for i in 0..BLOCK_SIZE {
            for j in 0..BLOCK_SIZE {
                let mut sum = 0.0;
                for k in 0..BLOCK_SIZE {
                    sum += a.get(i, k) * b.get(k, j);
                }
                result.set(i, j, sum);
            }
        }
        result
    }

    fn sub_block(a: &Block4x4, b: &Block4x4) -> Block4x4 {
        let mut result = Block4x4::zero();
        for i in 0..16 {
            result.data[i] = a.data[i] - b.data[i];
        }
        result
    }
}

impl Preconditioner for BlockIlu0Preconditioner {
    fn apply(&self, r: &[f64], z: &mut [f64]) {
        let bs = BLOCK_SIZE;
        let n = self.n_block_rows;

        let mut y = vec![0.0; n * bs];

        for br in 0..n {
            let mut rhs_block = [0.0f64; BLOCK_SIZE];
            let offset = br * bs;
            rhs_block.copy_from_slice(&r[offset..offset + bs]);

            let start = self.indptr[br];
            let end = self.indptr[br + 1];

            for idx in start..end {
                let bc = self.block_col_indices[idx];
                if bc < br {
                    let l_block = &self.l_blocks[idx];
                    let y_block = [y[bc * bs], y[bc * bs + 1], y[bc * bs + 2], y[bc * bs + 3]];
                    let contrib = l_block.mul_vec4(&y_block);
                    for d in 0..bs {
                        rhs_block[d] -= contrib[d];
                    }
                }
            }

            let solved = self.inv_diag[br].mul_vec4(&rhs_block);
            for d in 0..bs {
                y[offset + d] = solved[d];
            }
        }

        for br in (0..n).rev() {
            let offset = br * bs;
            let mut rhs_block = [0.0f64; BLOCK_SIZE];
            rhs_block.copy_from_slice(&y[offset..offset + bs]);

            let start = self.indptr[br];
            let end = self.indptr[br + 1];

            for idx in start..end {
                let bc = self.block_col_indices[idx];
                if bc > br {
                    let u_block = &self.u_blocks[idx];
                    let z_block = [z[bc * bs], z[bc * bs + 1], z[bc * bs + 2], z[bc * bs + 3]];
                    let contrib = u_block.mul_vec4(&z_block);
                    for d in 0..bs {
                        rhs_block[d] -= contrib[d];
                    }
                }
            }

            for d in 0..bs {
                z[offset + d] = rhs_block[d];
            }
        }
    }
}
