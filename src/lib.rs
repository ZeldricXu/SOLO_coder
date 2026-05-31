pub mod monitoring;
pub mod data_access;
pub mod shamir;
pub mod api_gateway;
pub mod core_processing;
pub mod audit_log;
pub mod federated_learning;
pub mod tee;
pub mod data_masking;

pub mod types;
pub mod error;
pub mod utils;

pub use error::PlatformError;
pub use types::*;
