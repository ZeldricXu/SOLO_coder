pub mod state;
pub mod router;
pub mod handlers;

pub mod inference {
    pub mod v1 {
        include!("inference.v1.rs");
    }
}

pub use inference::v1::*;
pub use state::AppState;
pub use router::build_app;
