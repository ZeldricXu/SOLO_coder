use physics_types::{Body, BodyHandle};

use crate::contact::{Collide, ContactManifold};

pub struct NarrowPhase;

impl NarrowPhase {
    pub fn new() -> Self {
        NarrowPhase
    }

    pub fn collide(
        &self,
        body_a: &Body,
        handle_a: BodyHandle,
        body_b: &Body,
        handle_b: BodyHandle,
    ) -> Option<ContactManifold> {
        if !should_collide(body_a, body_b) {
            return None;
        }

        let mut manifold =
            body_a.shape
                .collide(&body_a.transform, &body_b.shape, &body_b.transform)?;

        manifold.body_a = handle_a;
        manifold.body_b = handle_b;

        Some(manifold)
    }
}

impl Default for NarrowPhase {
    fn default() -> Self {
        Self::new()
    }
}

fn should_collide(a: &Body, b: &Body) -> bool {
    use physics_types::BodyType::*;

    match (a.body_type, b.body_type) {
        (Static, Static) => return false,
        (Static, Kinematic) => return false,
        (Kinematic, Static) => return false,
        (Kinematic, Kinematic) => return false,
        _ => {}
    }

    if !a.should_collide_with(b) {
        return false;
    }

    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use physics_types::{BodyType, Material, Shape, Circle, Rectangle};
    use physics_math::Vec2;
    use slotmap::Key;

    fn create_body(shape: Shape, position: Vec2, body_type: BodyType) -> Body {
        Body::new(
            Key::null(),
            shape,
            position,
            0.0,
            body_type,
            Material::default(),
        )
    }

    #[test]
    fn test_should_collide_static_dynamic() {
        let a = create_body(Shape::Circle(Circle { radius: 1.0 }), Vec2::ZERO, BodyType::Static);
        let b = create_body(Shape::Circle(Circle { radius: 1.0 }), Vec2::ZERO, BodyType::Dynamic);
        assert!(should_collide(&a, &b));
    }

    #[test]
    fn test_should_not_collide_static_static() {
        let a = create_body(Shape::Circle(Circle { radius: 1.0 }), Vec2::ZERO, BodyType::Static);
        let b = create_body(Shape::Circle(Circle { radius: 1.0 }), Vec2::ZERO, BodyType::Static);
        assert!(!should_collide(&a, &b));
    }

    #[test]
    fn test_narrow_phase_collision() {
        let np = NarrowPhase::new();

        let shape_a = Shape::Rectangle(Rectangle::new(2.0, 2.0));
        let shape_b = Shape::Rectangle(Rectangle::new(2.0, 2.0));

        let ha = Key::null();
        let hb = Key::null();

        let a = create_body(shape_a, Vec2::new(0.0, 0.0), BodyType::Dynamic);
        let b = create_body(shape_b, Vec2::new(1.5, 0.0), BodyType::Dynamic);

        let manifold = np.collide(&a, ha, &b, hb);
        assert!(manifold.is_some());
    }
}
