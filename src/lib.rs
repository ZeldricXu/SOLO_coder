pub mod models;
pub mod utils;
pub mod cdc;
pub mod data_lineage;
pub mod data_quality;
pub mod metadata_crawler;
pub mod streaming_sql;
pub mod vector_index;
pub mod lifecycle;
pub mod timeseries_compression;

#[cfg(test)]
pub mod test_builder;

pub use models::*;
pub use utils::*;

pub const VERSION: &str = "0.1.0";
pub const NAME: &str = "StreamSQL";

pub fn hello() -> &'static str {
    "StreamSQL - 流式SQL计算执行引擎"
}
