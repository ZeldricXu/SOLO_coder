use crate::matrix::CsrMatrix;

pub trait Preconditioner {
    fn apply(&self, r: &[f64], z: &mut [f64]);
}

pub struct JacobiPreconditioner {
    diag_inv: Vec<f64>,
}

impl JacobiPreconditioner {
    pub fn new(mat: &CsrMatrix) -> Self {
        let diag = mat.diagonal();
        let diag_inv: Vec<f64> = diag.iter().map(|&d| if d.abs() > 1e-15 { 1.0 / d } else { 1.0 }).collect();
        JacobiPreconditioner { diag_inv }
    }
}

impl Preconditioner for JacobiPreconditioner {
    fn apply(&self, r: &[f64], z: &mut [f64]) {
        for i in 0..r.len() {
            z[i] = self.diag_inv[i] * r[i];
        }
    }
}

pub struct Ilu0Preconditioner {
    l: CsrMatrix,
    u: CsrMatrix,
    inv_diag: Vec<f64>,
}

impl Ilu0Preconditioner {
    pub fn new(mat: &CsrMatrix) -> Self {
        let n = mat.nrows;
        let mut l_data = mat.data.clone();
        let mut u_data = mat.data.clone();
        let mut inv_diag = vec![0.0; n];

        for k in 0..n {
            let k_start = mat.indptr[k];
            let k_end = mat.indptr[k + 1];
            
            let mut diag_val = 0.0;
            for i in k_start..k_end {
                if mat.indices[i] == k {
                    diag_val = l_data[i];
                    break;
                }
            }
            
            if diag_val.abs() < 1e-15 {
                inv_diag[k] = 1.0;
            } else {
                inv_diag[k] = 1.0 / diag_val;
            }

            for i in k_start..k_end {
                let j = mat.indices[i];
                if j > k {
                    u_data[i] = diag_val * inv_diag[k];
                }
            }

            for i in k + 1..n {
                let i_start = mat.indptr[i];
                let i_end = mat.indptr[i + 1];
                
                let mut a_ik = 0.0;
                for j in i_start..i_end {
                    if mat.indices[j] == k {
                        a_ik = l_data[j];
                        break;
                    }
                }
                
                if a_ik.abs() > 1e-15 {
                    for j in i_start..i_end {
                        let col_j = mat.indices[j];
                        if col_j > k {
                            for l in k_start..k_end {
                                if mat.indices[l] == col_j {
                                    l_data[j] -= a_ik * u_data[l];
                                }
                            }
                        }
                    }
                }
            }
        }

        let l = CsrMatrix {
            nrows: mat.nrows,
            ncols: mat.ncols,
            indptr: mat.indptr.clone(),
            indices: mat.indices.clone(),
            data: l_data,
        };

        let u = CsrMatrix {
            nrows: mat.nrows,
            ncols: mat.ncols,
            indptr: mat.indptr.clone(),
            indices: mat.indices.clone(),
            data: u_data,
        };

        Ilu0Preconditioner { l, u, inv_diag }
    }

    pub fn new_simplified(mat: &CsrMatrix) -> Self {
        let _n = mat.nrows;
        let diag = mat.diagonal();
        let inv_diag: Vec<f64> = diag.iter().map(|&d| if d.abs() > 1e-15 { 1.0 / d } else { 1.0 }).collect();
        
        Ilu0Preconditioner {
            l: mat.clone(),
            u: mat.clone(),
            inv_diag,
        }
    }
}

impl Preconditioner for Ilu0Preconditioner {
    fn apply(&self, r: &[f64], z: &mut [f64]) {
        let n = r.len();
        let mut y = vec![0.0; n];

        for i in 0..n {
            let mut sum = r[i];
            for j in self.l.indptr[i]..self.l.indptr[i + 1] {
                let col = self.l.indices[j];
                if col < i {
                    sum -= self.l.data[j] * y[col];
                }
            }
            y[i] = sum * self.inv_diag[i];
        }

        for i in (0..n).rev() {
            let mut sum = y[i];
            for j in self.u.indptr[i]..self.u.indptr[i + 1] {
                let col = self.u.indices[j];
                if col > i {
                    sum -= self.u.data[j] * z[col];
                }
            }
            z[i] = sum;
        }
    }
}
