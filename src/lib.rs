pub mod types;
pub mod config;
pub mod logging;
pub mod storage;
pub mod core;
pub mod cdc;
pub mod data_quality;
pub mod metadata_crawler;
pub mod lineage;
pub mod notification;
pub mod api;
#[cfg(test)]
pub mod test_utils;

pub use types::*;
pub use config::*;
pub use logging::*;
pub use storage::*;
pub use core::*;
pub use cdc::*;
pub use data_quality::*;
pub use metadata_crawler::*;
pub use lineage::*;
pub use notification::*;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
pub const NAME: &str = env!("CARGO_PKG_NAME");

pub fn version() -> &'static str {
    VERSION
}

pub fn name() -> &'static str {
    NAME
}
