use crate::matrix::{CsrMatrix, dot, norm2, axpy, waxpby, copy};
use crate::block_matrix::BlockCsrMatrix;
use crate::preconditioner::Preconditioner;
use crate::solver::{SolverResult, SolverConfig};

pub struct BiCGSTAB;

impl BiCGSTAB {
    pub fn solve<P: Preconditioner>(
        mat: &CsrMatrix,
        rhs: &[f64],
        x: &mut [f64],
        precond: &P,
        config: &SolverConfig,
    ) -> SolverResult {
        let n = mat.nrows;
        let mut r = vec![0.0; n];
        let mut r0 = vec![0.0; n];
        let mut p = vec![0.0; n];
        let mut v = vec![0.0; n];
        let mut s = vec![0.0; n];
        let mut t = vec![0.0; n];
        let mut z = vec![0.0; n];
        let mut y = vec![0.0; n];

        mat.mul_vec_seq(x, &mut r);
        let mut temp = vec![0.0; n];
        waxpby(1.0, rhs, -1.0, &r, &mut temp);
        copy(&mut r, &temp);
        copy(&mut r0, &r);

        let b_norm = norm2(rhs);
        if b_norm < 1e-15 {
            return SolverResult {
                converged: true,
                iterations: 0,
                residual: 0.0,
            };
        }

        let mut rho: f64 = 1.0;
        let mut alpha: f64 = 1.0;
        let mut omega: f64 = 1.0;
        let mut beta;

        let mut residual = norm2(&r) / b_norm;
        if residual < config.tol {
            return SolverResult {
                converged: true,
                iterations: 0,
                residual,
            };
        }

        for iter in 1..=config.max_iter {
            let rho_new = dot(&r0, &r);
            
            if rho.abs() < 1e-30 {
                return SolverResult {
                    converged: false,
                    iterations: iter,
                    residual,
                };
            }

            beta = (rho_new / rho) * (alpha / omega);
            waxpby(1.0, &r, -omega, &v, &mut p);
            let mut temp_p = vec![0.0; n];
            waxpby(1.0, &p, beta, &p, &mut temp_p);
            copy(&mut p, &temp_p);

            precond.apply(&p, &mut y);
            mat.mul_vec_seq(&y, &mut v);

            let r0_dot_v = dot(&r0, &v);
            if r0_dot_v.abs() < 1e-30 {
                return SolverResult {
                    converged: false,
                    iterations: iter,
                    residual,
                };
            }

            alpha = rho_new / r0_dot_v;
            waxpby(1.0, &r, -alpha, &v, &mut s);

            residual = norm2(&s) / b_norm;
            if residual < config.tol {
                axpy(alpha, &y, x);
                return SolverResult {
                    converged: true,
                    iterations: iter,
                    residual,
                };
            }

            precond.apply(&s, &mut z);
            mat.mul_vec_seq(&z, &mut t);

            let t_dot_t = dot(&t, &t);
            if t_dot_t < 1e-30 {
                axpy(alpha, &y, x);
                return SolverResult {
                    converged: false,
                    iterations: iter,
                    residual,
                };
            }

            omega = dot(&t, &s) / t_dot_t;

            axpy(alpha, &y, x);
            axpy(omega, &z, x);

            waxpby(1.0, &s, -omega, &t, &mut r);

            rho = rho_new;
            residual = norm2(&r) / b_norm;

            if residual < config.tol {
                return SolverResult {
                    converged: true,
                    iterations: iter,
                    residual,
                };
            }
        }

        SolverResult {
            converged: false,
            iterations: config.max_iter,
            residual,
        }
    }

    pub fn solve_block<P: Preconditioner>(
        block_mat: &BlockCsrMatrix,
        rhs: &[f64],
        x: &mut [f64],
        precond: &P,
        config: &SolverConfig,
    ) -> SolverResult {
        let n = block_mat.n_block_rows * crate::block_matrix::BLOCK_SIZE;

        let mut r = vec![0.0; n];
        let mut r0 = vec![0.0; n];
        let mut p = vec![0.0; n];
        let mut v = vec![0.0; n];
        let mut s = vec![0.0; n];
        let mut t = vec![0.0; n];
        let mut z = vec![0.0; n];
        let mut y = vec![0.0; n];

        block_mat.block_mul_vec_seq(x, &mut r);
        let mut temp = vec![0.0; n];
        waxpby(1.0, rhs, -1.0, &r, &mut temp);
        copy(&mut r, &temp);
        copy(&mut r0, &r);

        let b_norm = norm2(rhs);
        if b_norm < 1e-15 {
            return SolverResult {
                converged: true,
                iterations: 0,
                residual: 0.0,
            };
        }

        let mut rho: f64 = 1.0;
        let mut alpha: f64 = 1.0;
        let mut omega: f64 = 1.0;
        let mut beta;

        let mut residual = norm2(&r) / b_norm;
        if residual < config.tol {
            return SolverResult {
                converged: true,
                iterations: 0,
                residual,
            };
        }

        for iter in 1..=config.max_iter {
            let rho_new = dot(&r0, &r);

            if rho.abs() < 1e-30 {
                return SolverResult {
                    converged: false,
                    iterations: iter,
                    residual,
                };
            }

            beta = (rho_new / rho) * (alpha / omega);
            waxpby(1.0, &r, -omega, &v, &mut p);
            let mut temp_p = vec![0.0; n];
            waxpby(1.0, &p, beta, &p, &mut temp_p);
            copy(&mut p, &temp_p);

            precond.apply(&p, &mut y);
            block_mat.block_mul_vec_seq(&y, &mut v);

            let r0_dot_v = dot(&r0, &v);
            if r0_dot_v.abs() < 1e-30 {
                return SolverResult {
                    converged: false,
                    iterations: iter,
                    residual,
                };
            }

            alpha = rho_new / r0_dot_v;
            waxpby(1.0, &r, -alpha, &v, &mut s);

            residual = norm2(&s) / b_norm;
            if residual < config.tol {
                axpy(alpha, &y, x);
                return SolverResult {
                    converged: true,
                    iterations: iter,
                    residual,
                };
            }

            precond.apply(&s, &mut z);
            block_mat.block_mul_vec_seq(&z, &mut t);

            let t_dot_t = dot(&t, &t);
            if t_dot_t < 1e-30 {
                axpy(alpha, &y, x);
                return SolverResult {
                    converged: false,
                    iterations: iter,
                    residual,
                };
            }

            omega = dot(&t, &s) / t_dot_t;

            axpy(alpha, &y, x);
            axpy(omega, &z, x);

            waxpby(1.0, &s, -omega, &t, &mut r);

            rho = rho_new;
            residual = norm2(&r) / b_norm;

            if residual < config.tol {
                return SolverResult {
                    converged: true,
                    iterations: iter,
                    residual,
                };
            }
        }

        SolverResult {
            converged: false,
            iterations: config.max_iter,
            residual,
        }
    }
}
