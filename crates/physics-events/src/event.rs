use physics_collision::ContactPoint;
use physics_types::{BodyHandle, Shape};
use physics_math::Vec2;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CollisionEventType {
    Begin,
    Stay,
    End,
}

#[derive(Clone, Debug)]
pub struct EventData {
    pub contact_point: ContactPoint,
    pub normal_impulse: f32,
    pub tangent_impulse: f32,
}

#[derive(Clone, Debug)]
pub struct CollisionEvent {
    pub body_a: BodyHandle,
    pub body_b: BodyHandle,
    pub event_type: CollisionEventType,
    pub data: Option<EventData>,
}

#[derive(Clone, Debug)]
pub struct TriggerEvent {
    pub trigger_handle: BodyHandle,
    pub body_handle: BodyHandle,
    pub event_type: CollisionEventType,
    pub trigger_shape: Shape,
    pub body_position: Vec2,
}
