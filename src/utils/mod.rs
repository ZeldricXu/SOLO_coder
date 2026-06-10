pub mod error;
pub mod response;
pub mod crypto;
pub mod diff_parser;
pub mod pagination;

pub use error::{AppError, AppResult};
pub use response::{ApiResponse, PaginatedResponse, PaginationQuery};
pub use crypto::{generate_random_string, generate_webhook_secret, sha256_hash, verify_hmac_signature, generate_csrf_token};
pub use diff_parser::{DiffParser, DiffFile, DiffHunk, DiffLine};
