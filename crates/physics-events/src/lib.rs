pub mod event;
pub mod dispatcher;
mod tests;

pub use event::{CollisionEvent, CollisionEventType, EventData, TriggerEvent};
pub use dispatcher::EventDispatcher;
