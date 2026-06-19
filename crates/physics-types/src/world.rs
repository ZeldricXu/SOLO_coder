use slotmap::SlotMap;

use physics_math::Vec2;

use crate::body::{Body, BodyHandle, BodyType};
use crate::material::Material;
use crate::shape::Shape;

#[derive(Clone, Debug)]
pub struct World {
    pub gravity: Vec2,
    pub bodies: SlotMap<BodyHandle, Body>,
    pub aabb_margin: f32,
    pub min_body_size: f32,
    pub max_body_size: f32,
}

impl World {
    pub fn new() -> Self {
        World {
            gravity: Vec2::new(0.0, -9.81),
            bodies: SlotMap::with_key(),
            aabb_margin: 0.1,
            min_body_size: 0.01,
            max_body_size: 100.0,
        }
    }

    #[inline]
    pub fn with_gravity(mut self, gravity: Vec2) -> Self {
        self.gravity = gravity;
        self
    }

    #[inline]
    pub fn add_body(
        &mut self,
        shape: Shape,
        position: Vec2,
        angle: f32,
        body_type: BodyType,
        material: Material,
    ) -> BodyHandle {
        let handle = self.bodies.insert_with_key(|handle| {
            Body::new(handle, shape, position, angle, body_type, material)
        });
        handle
    }

    #[inline]
    pub fn remove_body(&mut self, handle: BodyHandle) -> Option<Body> {
        self.bodies.remove(handle)
    }

    #[inline]
    pub fn get_body(&self, handle: BodyHandle) -> Option<&Body> {
        self.bodies.get(handle)
    }

    #[inline]
    pub fn get_body_mut(&mut self, handle: BodyHandle) -> Option<&mut Body> {
        self.bodies.get_mut(handle)
    }

    #[inline]
    pub fn bodies(&self) -> impl Iterator<Item = &Body> {
        self.bodies.values()
    }

    #[inline]
    pub fn bodies_mut(&mut self) -> impl Iterator<Item = &mut Body> {
        self.bodies.values_mut()
    }

    #[inline]
    pub fn dynamic_bodies(&self) -> impl Iterator<Item = &Body> {
        self.bodies.values().filter(|b| b.is_dynamic())
    }

    #[inline]
    pub fn body_count(&self) -> usize {
        self.bodies.len()
    }

    #[inline]
    pub fn dynamic_body_count(&self) -> usize {
        self.bodies.values().filter(|b| b.is_dynamic()).count()
    }

    #[inline]
    pub fn clear(&mut self) {
        self.bodies.clear();
    }

    #[inline]
    pub fn apply_gravity(&mut self) {
        for body in self.bodies.values_mut() {
            if body.is_dynamic() && body.is_active {
                let gravity_force = self.gravity * body.mass * body.gravity_scale;
                body.apply_force(gravity_force);
            }
        }
    }

    #[inline]
    pub fn save_transforms(&mut self) {
        for body in self.bodies.values_mut() {
            body.prev_transform = body.transform;
        }
    }
}

impl Default for World {
    fn default() -> Self {
        World::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shape::Circle;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_world_creation() {
        let world = World::new();
        assert_eq!(world.body_count(), 0);
        assert_abs_diff_eq!(world.gravity.y, -9.81);
    }

    #[test]
    fn test_add_body() {
        let mut world = World::new();
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        let handle = world.add_body(shape, Vec2::new(0.0, 5.0), 0.0, BodyType::Dynamic, material);

        assert_eq!(world.body_count(), 1);
        let body = world.get_body(handle).unwrap();
        assert_abs_diff_eq!(body.position().y, 5.0);
    }

    #[test]
    fn test_remove_body() {
        let mut world = World::new();
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        let handle = world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, material);

        assert_eq!(world.body_count(), 1);
        let removed = world.remove_body(handle);
        assert!(removed.is_some());
        assert_eq!(world.body_count(), 0);
    }

    #[test]
    fn test_apply_gravity() {
        let mut world = World::new().with_gravity(Vec2::new(0.0, -10.0));
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0);
        world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, material);

        world.apply_gravity();

        let body = world.bodies().next().unwrap();
        let expected_force = body.mass * -10.0;
        assert_abs_diff_eq!(body.force.y, expected_force);
    }
}
