#[derive(Debug, Clone)]
pub struct SolverConfig {
    pub tol: f64,
    pub max_iter: usize,
    pub restart: Option<usize>,
}

impl Default for SolverConfig {
    fn default() -> Self {
        SolverConfig {
            tol: 1e-6,
            max_iter: 1000,
            restart: Some(50),
        }
    }
}

impl SolverConfig {
    pub fn new(tol: f64, max_iter: usize) -> Self {
        SolverConfig {
            tol,
            max_iter,
            restart: Some(50),
        }
    }

    pub fn with_restart(mut self, restart: usize) -> Self {
        self.restart = Some(restart);
        self
    }
}

#[derive(Debug, Clone)]
pub struct SolverResult {
    pub converged: bool,
    pub iterations: usize,
    pub residual: f64,
}

pub enum SolverType {
    BiCGSTAB,
    GMRES,
}

pub trait LinearSolver {
    fn solve(
        &self,
        mat: &crate::matrix::CsrMatrix,
        rhs: &[f64],
        x: &mut [f64],
    ) -> SolverResult;
}
