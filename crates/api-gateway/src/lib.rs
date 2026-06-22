pub mod google;
pub mod state;
pub mod handlers;
pub mod router;

pub mod inference {
    pub mod v1 {
        include!("inference.v1.rs");
    }
}

pub use inference::v1::*;
pub use state::AppState;
pub use router::build_app;
