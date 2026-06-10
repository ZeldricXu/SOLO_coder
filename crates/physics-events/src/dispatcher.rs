use std::collections::HashMap;
use std::any::Any;

use physics_collision::ContactManifold;
use physics_core::BodyHandle;

use crate::event::{CollisionEvent, CollisionEventType, EventData, TriggerEvent};

pub type CollisionCallback = Box<dyn FnMut(&CollisionEvent) + Send + Sync>;
pub type TriggerCallback = Box<dyn FnMut(&TriggerEvent) + Send + Sync>;

pub struct EventDispatcher {
    collision_callbacks: Vec<CollisionCallback>,
    trigger_callbacks: Vec<TriggerCallback>,
    previous_collisions: HashMap<(BodyHandle, BodyHandle), usize>,
    current_collisions: HashMap<(BodyHandle, BodyHandle), usize>,
}

impl Default for EventDispatcher {
    fn default() -> Self {
        EventDispatcher::new()
    }
}

impl EventDispatcher {
    pub fn new() -> Self {
        EventDispatcher {
            collision_callbacks: Vec::new(),
            trigger_callbacks: Vec::new(),
            previous_collisions: HashMap::new(),
            current_collisions: HashMap::new(),
        }
    }

    pub fn register_collision_callback<F>(&mut self, callback: F)
    where
        F: FnMut(&CollisionEvent) + Send + Sync + 'static,
    {
        self.collision_callbacks.push(Box::new(callback));
    }

    pub fn register_trigger_callback<F>(&mut self, callback: F)
    where
        F: FnMut(&TriggerEvent) + Send + Sync + 'static,
    {
        self.trigger_callbacks.push(Box::new(callback));
    }

    pub fn begin_frame(&mut self) {
        self.previous_collisions = std::mem::take(&mut self.current_collisions);
        self.current_collisions.clear();
    }

    pub fn dispatch_collisions(&mut self, manifolds: &[ContactManifold]) {
        for manifold in manifolds {
            let key = (manifold.body_a, manifold.body_b);

            let point_count = manifold.point_count;
            self.current_collisions.insert(key, point_count);

            let event_type = if self.previous_collisions.contains_key(&key) {
                CollisionEventType::Stay
            } else {
                CollisionEventType::Begin
            };

            let data = if point_count > 0 {
                Some(EventData {
                    contact_point: manifold.points[0].clone(),
                    normal_impulse: 0.0,
                    tangent_impulse: 0.0,
                })
            } else {
                None
            };

            let event = CollisionEvent {
                body_a: manifold.body_a,
                body_b: manifold.body_b,
                event_type,
                data,
            };

            for callback in &mut self.collision_callbacks {
                callback(&event);
            }
        }

        for (key, _) in &self.previous_collisions {
            if !self.current_collisions.contains_key(key) {
                let event = CollisionEvent {
                    body_a: key.0,
                    body_b: key.1,
                    event_type: CollisionEventType::End,
                    data: None,
                };

                for callback in &mut self.collision_callbacks {
                    callback(&event);
                }
            }
        }
    }

    pub fn dispatch_trigger(&mut self, event: TriggerEvent) {
        for callback in &mut self.trigger_callbacks {
            callback(&event);
        }
    }

    pub fn is_colliding(&self, a: BodyHandle, b: BodyHandle) -> bool {
        let key = (a, b);
        self.current_collisions.contains_key(&key)
            || self.previous_collisions.contains_key(&key)
    }

    pub fn clear(&mut self) {
        self.collision_callbacks.clear();
        self.trigger_callbacks.clear();
        self.previous_collisions.clear();
        self.current_collisions.clear();
    }

    pub fn as_any(&self) -> &dyn Any {
        self
    }
}
