pub mod adapter;
pub mod context;
pub mod grouping;
pub mod models;

pub use adapter::DiffAdapter;
pub use adapter::FilePositioned;
pub use context::DiffContextProvider;
pub use models::{DiffFile, DiffHunk, DiffLine};
pub use git_diff_core::DiffError;
