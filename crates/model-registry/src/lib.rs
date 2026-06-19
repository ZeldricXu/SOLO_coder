pub mod grpc;
pub mod minio;
pub mod service;

pub mod google {
    pub mod protobuf {
        #![allow(clippy::all)]
        #![allow(dead_code)]
        include!(concat!(env!("OUT_DIR"), "/google.protobuf.rs"));
    }
}

pub mod inference {
    pub mod v1 {
        #![allow(clippy::all)]
        #![allow(dead_code)]
        include!(concat!(env!("OUT_DIR"), "/inference.v1.rs"));
    }
}

pub use minio::{MinioConfig, MinioStorage, ObjectInfo};
pub use service::ModelRegistryService;

pub mod pb {
    #![allow(clippy::all)]
    #![allow(dead_code)]
    pub use super::inference::v1::*;
    pub use super::inference::v1::registry_service_server as registry_server;
}
