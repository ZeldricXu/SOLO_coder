#[derive(Debug, thiserror::Error)]
pub enum DiffError {
    #[error("Git repository error: {0}")]
    Repository(String),
    #[error("Diff parse error: {0}")]
    Parse(String),
    #[error("Object not found: {0}")]
    NotFound(String),
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
}

pub type DiffResult<T> = Result<T, DiffError>;
