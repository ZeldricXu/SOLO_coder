#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    use physics_collision::{ContactManifold, ContactPoint};
    use physics_types::{Body, BodyHandle, BodyType, Circle, Material, Shape};
    use physics_math::Vec2;
    use slotmap::{Key, SlotMap};

    use crate::dispatcher::EventDispatcher;
    use crate::event::{CollisionEventType, CollisionEvent};

    fn create_manifold(a: BodyHandle, b: BodyHandle) -> ContactManifold {
        let mut points = [ContactPoint::zero(), ContactPoint::zero()];
        points[0].point = Vec2::new(0.0, 0.0);
        ContactManifold {
            body_a: a,
            body_b: b,
            normal: Vec2::new(0.0, 1.0),
            points,
            point_count: 1,
        }
    }

    #[test]
    fn test_event_dispatcher_creation() {
        let dispatcher = EventDispatcher::new();
        assert!(!dispatcher.is_colliding(BodyHandle::null(), BodyHandle::null()));
    }

    #[test]
    fn test_collision_event_begin() {
        let mut dispatcher = EventDispatcher::new();
        let mut bodies: SlotMap<BodyHandle, Body> = SlotMap::with_key();

        let handle_a = bodies.insert_with_key(|h| {
            Body::new(
                h,
                Shape::Circle(Circle::new(1.0)),
                Vec2::new(0.0, 0.0),
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            )
        });
        let handle_b = bodies.insert_with_key(|h| {
            Body::new(
                h,
                Shape::Circle(Circle::new(1.0)),
                Vec2::new(2.0, 0.0),
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            )
        });

        let event_count = Arc::new(AtomicUsize::new(0));
        let event_count_clone = event_count.clone();

        dispatcher.register_collision_callback(move |event: &CollisionEvent| {
            if event.event_type == CollisionEventType::Begin {
                event_count_clone.fetch_add(1, Ordering::SeqCst);
            }
        });

        dispatcher.begin_frame();
        let manifolds = vec![create_manifold(handle_a, handle_b)];
        dispatcher.dispatch_collisions(&manifolds);

        assert_eq!(event_count.load(Ordering::SeqCst), 1);
        assert!(dispatcher.is_colliding(handle_a, handle_b));
    }

    #[test]
    fn test_collision_event_stay() {
        let mut dispatcher = EventDispatcher::new();
        let mut bodies: SlotMap<BodyHandle, Body> = SlotMap::with_key();

        let handle_a = bodies.insert_with_key(|h| {
            Body::new(
                h,
                Shape::Circle(Circle::new(1.0)),
                Vec2::new(0.0, 0.0),
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            )
        });
        let handle_b = bodies.insert_with_key(|h| {
            Body::new(
                h,
                Shape::Circle(Circle::new(1.0)),
                Vec2::new(2.0, 0.0),
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            )
        });

        let stay_count = Arc::new(AtomicUsize::new(0));
        let stay_count_clone = stay_count.clone();

        dispatcher.register_collision_callback(move |event: &CollisionEvent| {
            if event.event_type == CollisionEventType::Stay {
                stay_count_clone.fetch_add(1, Ordering::SeqCst);
            }
        });

        dispatcher.begin_frame();
        let manifolds = vec![create_manifold(handle_a, handle_b)];
        dispatcher.dispatch_collisions(&manifolds);

        dispatcher.begin_frame();
        dispatcher.dispatch_collisions(&manifolds);

        assert_eq!(stay_count.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn test_collision_event_end() {
        let mut dispatcher = EventDispatcher::new();
        let mut bodies: SlotMap<BodyHandle, Body> = SlotMap::with_key();

        let handle_a = bodies.insert_with_key(|h| {
            Body::new(
                h,
                Shape::Circle(Circle::new(1.0)),
                Vec2::new(0.0, 0.0),
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            )
        });
        let handle_b = bodies.insert_with_key(|h| {
            Body::new(
                h,
                Shape::Circle(Circle::new(1.0)),
                Vec2::new(2.0, 0.0),
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            )
        });

        let end_count = Arc::new(AtomicUsize::new(0));
        let end_count_clone = end_count.clone();

        dispatcher.register_collision_callback(move |event: &CollisionEvent| {
            if event.event_type == CollisionEventType::End {
                end_count_clone.fetch_add(1, Ordering::SeqCst);
            }
        });

        dispatcher.begin_frame();
        let manifolds = vec![create_manifold(handle_a, handle_b)];
        dispatcher.dispatch_collisions(&manifolds);

        dispatcher.begin_frame();
        dispatcher.dispatch_collisions(&[]);

        assert_eq!(end_count.load(Ordering::SeqCst), 1);
    }
}
