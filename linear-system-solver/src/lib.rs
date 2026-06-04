pub mod matrix;
pub mod block_matrix;
pub mod preconditioner;
pub mod block_preconditioner;
pub mod bicgstab;
pub mod gmres;
pub mod solver;

pub use matrix::CsrMatrix;
pub use block_matrix::{BlockCsrMatrix, Block4x4, BLOCK_SIZE};
pub use preconditioner::{Preconditioner, Ilu0Preconditioner, JacobiPreconditioner};
pub use block_preconditioner::BlockIlu0Preconditioner;
pub use bicgstab::BiCGSTAB;
pub use gmres::GMRES;
pub use solver::{LinearSolver, SolverResult, SolverConfig};
