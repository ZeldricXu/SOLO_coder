pub mod error;
pub mod models;
pub mod parser;
pub mod repository;

pub use error::{DiffError, DiffResult};
pub use models::*;
pub use parser::UnifiedDiffParser;
pub use repository::GixRepository;
