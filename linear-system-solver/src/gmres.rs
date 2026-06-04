use crate::matrix::{CsrMatrix, dot, norm2, axpy, waxpby, copy};
use crate::preconditioner::Preconditioner;
use crate::solver::{SolverResult, SolverConfig};

pub struct GMRES;

impl GMRES {
    pub fn solve<P: Preconditioner>(
        mat: &CsrMatrix,
        rhs: &[f64],
        x: &mut [f64],
        precond: &P,
        config: &SolverConfig,
    ) -> SolverResult {
        let n = mat.nrows;
        let restart = config.restart.unwrap_or(50);
        
        let mut r = vec![0.0; n];
        let mut w = vec![0.0; n];
        let mut z = vec![0.0; n];
        
        let mut v = vec![vec![0.0; n]; restart + 1];
        let mut h = vec![vec![0.0; restart + 1]; restart + 1];
        let mut c = vec![0.0; restart];
        let mut s = vec![0.0; restart];
        let mut g = vec![0.0; restart + 1];

        let b_norm = norm2(rhs);
        if b_norm < 1e-15 {
            for xi in x.iter_mut() {
                *xi = 0.0;
            }
            return SolverResult {
                converged: true,
                iterations: 0,
                residual: 0.0,
            };
        }

        let mut total_iter = 0;
        let mut residual;

        loop {
            mat.mul_vec_seq(x, &mut r);
            let mut temp = vec![0.0; r.len()];
            waxpby(1.0, rhs, -1.0, &r, &mut temp);
            copy(&mut r, &temp);
            
            precond.apply(&r, &mut z);
            let r_norm = norm2(&z);
            
            residual = r_norm / b_norm;
            if residual < config.tol || total_iter >= config.max_iter {
                break;
            }

            for vi in v[0].iter_mut() {
                *vi = 0.0;
            }
            waxpby(1.0 / r_norm, &z, 0.0, &z, &mut v[0]);

            g[0] = r_norm;
            for i in 1..g.len() {
                g[i] = 0.0;
            }

            let mut iter_restart = 0;
            for j in 0..restart {
                mat.mul_vec_seq(&v[j], &mut w);
                precond.apply(&w, &mut z);
                copy(&mut w, &z);

                let mut h_col = vec![0.0; j + 1];
                for i in 0..=j {
                    h_col[i] = dot(&w, &v[i]);
                }
                for i in 0..=j {
                    axpy(-h_col[i], &v[i], &mut w);
                }

                let mut h_col_2 = vec![0.0; j + 1];
                for i in 0..=j {
                    h_col_2[i] = dot(&w, &v[i]);
                }
                for i in 0..=j {
                    axpy(-h_col_2[i], &v[i], &mut w);
                    h_col[i] += h_col_2[i];
                }

                for i in 0..=j {
                    h[i][j] = h_col[i];
                }
                h[j + 1][j] = norm2(&w);

                if h[j + 1][j] > 1e-15 {
                    let inv_h = 1.0 / h[j + 1][j];
                    waxpby(inv_h, &w, 0.0, &w, &mut v[j + 1]);
                }

                for i in 0..j {
                    let temp = c[i] * h[i][j] + s[i] * h[i + 1][j];
                    h[i + 1][j] = -s[i] * h[i][j] + c[i] * h[i + 1][j];
                    h[i][j] = temp;
                }

                let nu = (h[j][j] * h[j][j] + h[j + 1][j] * h[j + 1][j]).sqrt();
                if nu > 1e-15 {
                    c[j] = h[j][j] / nu;
                    s[j] = h[j + 1][j] / nu;
                    h[j][j] = nu;

                    g[j + 1] = -s[j] * g[j];
                    g[j] = c[j] * g[j];
                }

                residual = g[j + 1].abs() / b_norm;
                total_iter += 1;
                iter_restart = j + 1;

                if residual < config.tol || total_iter >= config.max_iter {
                    Self::update_solution(x, &v, &h, &g, j, &mut z);
                    break;
                }
            }

            if residual >= config.tol && total_iter < config.max_iter {
                Self::update_solution(x, &v, &h, &g, iter_restart - 1, &mut z);
            } else {
                break;
            }
        }

        SolverResult {
            converged: residual < config.tol,
            iterations: total_iter,
            residual,
        }
    }

    fn update_solution(
        x: &mut [f64],
        v: &[Vec<f64>],
        h: &[Vec<f64>],
        g: &[f64],
        k: usize,
        y: &mut [f64],
    ) {
        let n = x.len();
        
        for i in 0..=k {
            y[i] = g[i];
        }
        
        for i in (0..=k).rev() {
            if h[i][i].abs() > 1e-15 {
                y[i] /= h[i][i];
                for j in 0..i {
                    y[j] -= h[j][i] * y[i];
                }
            }
        }

        for i in 0..=k {
            for j in 0..n {
                x[j] += y[i] * v[i][j];
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::matrix::CsrMatrix;
    use crate::preconditioner::JacobiPreconditioner;

    #[test]
    fn test_gmres_diagonally_dominant_convergence() {
        let n = 100;
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut vals = Vec::new();

        for i in 0..n {
            rows.push(i);
            cols.push(i);
            vals.push(4.0);

            if i > 0 {
                rows.push(i);
                cols.push(i - 1);
                vals.push(-1.0);
            }
            if i < n - 1 {
                rows.push(i);
                cols.push(i + 1);
                vals.push(-1.0);
            }
        }

        let mat = CsrMatrix::from_triplets(n, n, &rows, &cols, &vals);

        let mut x_true = vec![0.0; n];
        for i in 0..n {
            x_true[i] = (i as f64) / (n as f64);
        }

        let mut b = vec![0.0; n];
        mat.mul_vec_seq(&x_true, &mut b);

        let mut x = vec![0.0; n];
        let precond = JacobiPreconditioner::new(&mat);
        let config = SolverConfig::new(1e-10, 500);

        let result = GMRES::solve(&mat, &b, &mut x, &precond, &config);

        assert!(result.converged, "GMRES should converge for diagonally dominant matrix");
        assert!(result.iterations < 100, "Should converge in fewer than 100 iterations, got {}", result.iterations);

        let mut error = 0.0;
        for i in 0..n {
            error += (x[i] - x_true[i]).powi(2);
        }
        error = error.sqrt();
        assert!(error < 1e-6, "Solution error should be small, got {}", error);
    }

    #[test]
    fn test_gmres_convection_dominated_problem() {
        let n = 50;
        let pecell = 20.0;
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut vals = Vec::new();

        for i in 0..n {
            let aw = 1.0 + 0.5 * pecell;
            let ae = 1.0 - 0.5 * pecell;
            let ap = aw + ae;

            rows.push(i);
            cols.push(i);
            vals.push(ap);

            if i > 0 {
                rows.push(i);
                cols.push(i - 1);
                vals.push(-aw);
            }
            if i < n - 1 {
                rows.push(i);
                cols.push(i + 1);
                vals.push(-ae);
            }
        }

        let mat = CsrMatrix::from_triplets(n, n, &rows, &cols, &vals);

        let mut b = vec![0.0; n];
        b[0] = (1.0 + 0.5 * pecell) * 0.0;
        b[n - 1] = (1.0 - 0.5 * pecell) * 1.0;

        let mut x = vec![0.5; n];
        x[0] = 0.0;
        x[n - 1] = 1.0;

        let precond = JacobiPreconditioner::new(&mat);
        let config = SolverConfig::new(1e-10, 500);

        let result = GMRES::solve(&mat, &b, &mut x, &precond, &config);

        assert!(result.converged, "GMRES should converge for convection-dominated problem");
        assert!(result.residual < 1e-6, "Residual should be small, got {}", result.residual);

        for i in 0..n {
            assert!(!x[i].is_nan(), "Solution should not be NaN, got x[{}]={}", i, x[i]);
            assert!(x[i].is_finite(), "Solution should be finite, got x[{}]={}", i, x[i]);
        }
    }

    #[test]
    fn test_gmres_orthogonality_maintained() {
        let n = 30;
        let mut rows = Vec::new();
        let mut cols = Vec::new();
        let mut vals = Vec::new();

        for i in 0..n {
            for j in 0..n {
                let val = if i == j {
                    100.0
                } else if (i as isize - j as isize).abs() == 1 {
                    50.0
                } else {
                    1.0 / ((i as f64 - j as f64).abs() + 1.0)
                };
                rows.push(i);
                cols.push(j);
                vals.push(val);
            }
        }

        let mat = CsrMatrix::from_triplets(n, n, &rows, &cols, &vals);

        let x_true: Vec<f64> = (0..n).map(|i| (i as f64).sin()).collect();
        let mut b = vec![0.0; n];
        mat.mul_vec_seq(&x_true, &mut b);

        let mut x = vec![0.0; n];
        let precond = JacobiPreconditioner::new(&mat);
        let config = SolverConfig::new(1e-12, 200);

        let result = GMRES::solve(&mat, &b, &mut x, &precond, &config);

        assert!(result.converged, "GMRES should converge and maintain orthogonality");

        let mut error = 0.0;
        for i in 0..n {
            error += (x[i] - x_true[i]).powi(2);
        }
        error = error.sqrt();
        assert!(error < 1e-8, "Solution should be accurate, error={}", error);
    }
}
