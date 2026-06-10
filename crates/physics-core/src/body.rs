use slotmap::{new_key_type, Key};

use physics_math::{AABB, Rot2, Transform, Vec2};

use crate::{material::Material, shape::{CollisionFilter, Shape}};

new_key_type! {
    pub struct BodyHandle;
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum BodyType {
    Static,
    Kinematic,
    Dynamic,
}

impl Default for BodyType {
    fn default() -> Self {
        BodyType::Dynamic
    }
}

#[derive(Clone, Debug)]
pub struct Body {
    pub handle: BodyHandle,
    pub body_type: BodyType,
    pub shape: Shape,
    pub material: Material,

    pub transform: Transform,
    pub prev_transform: Transform,

    pub linear_velocity: Vec2,
    pub angular_velocity: f32,

    pub force: Vec2,
    pub torque: f32,

    pub mass: f32,
    pub inv_mass: f32,
    pub inertia: f32,
    pub inv_inertia: f32,

    pub gravity_scale: f32,
    pub linear_damping: f32,
    pub angular_damping: f32,

    pub is_sensor: bool,
    pub is_active: bool,

    pub collision_filter: CollisionFilter,

    pub user_data: u64,
}

impl Body {
    pub fn new(
        handle: BodyHandle,
        shape: Shape,
        position: Vec2,
        angle: f32,
        body_type: BodyType,
        material: Material,
    ) -> Self {
        let transform = Transform::new(position, Rot2::new(angle));
        let mass = if body_type == BodyType::Dynamic {
            shape.compute_mass(material.density)
        } else {
            0.0
        };
        let inv_mass = if mass > f32::EPSILON { 1.0 / mass } else { 0.0 };
        let inertia = if body_type == BodyType::Dynamic {
            shape.compute_inertia(mass)
        } else {
            0.0
        };
        let inv_inertia = if inertia > f32::EPSILON {
            1.0 / inertia
        } else {
            0.0
        };

        Body {
            handle,
            body_type,
            shape,
            material,
            transform,
            prev_transform: transform,
            linear_velocity: Vec2::ZERO,
            angular_velocity: 0.0,
            force: Vec2::ZERO,
            torque: 0.0,
            mass,
            inv_mass,
            inertia,
            inv_inertia,
            gravity_scale: 1.0,
            linear_damping: 0.0,
            angular_damping: 0.0,
            is_sensor: false,
            is_active: true,
            collision_filter: CollisionFilter::default(),
            user_data: 0,
        }
    }

    pub fn new_temp(shape: Shape, transform: Transform) -> Self {
        Body {
            handle: BodyHandle::null(),
            body_type: BodyType::Static,
            shape,
            material: Material::DEFAULT,
            transform,
            prev_transform: transform,
            linear_velocity: Vec2::ZERO,
            angular_velocity: 0.0,
            force: Vec2::ZERO,
            torque: 0.0,
            mass: 0.0,
            inv_mass: 0.0,
            inertia: 0.0,
            inv_inertia: 0.0,
            gravity_scale: 1.0,
            linear_damping: 0.0,
            angular_damping: 0.0,
            is_sensor: false,
            is_active: true,
            collision_filter: CollisionFilter::default(),
            user_data: 0,
        }
    }

    #[inline]
    pub fn with_collision_filter(mut self, filter: CollisionFilter) -> Self {
        self.collision_filter = filter;
        self
    }

    #[inline]
    pub fn set_collision_filter(&mut self, filter: CollisionFilter) {
        self.collision_filter = filter;
    }

    #[inline]
    pub fn should_collide_with(&self, other: &Body) -> bool {
        self.collision_filter.should_collide(&other.collision_filter)
    }

    #[inline]
    pub fn handle(&self) -> BodyHandle {
        self.handle
    }

    #[inline]
    pub fn body_type(&self) -> BodyType {
        self.body_type
    }

    #[inline]
    pub fn is_dynamic(&self) -> bool {
        self.body_type == BodyType::Dynamic
    }

    #[inline]
    pub fn is_kinematic(&self) -> bool {
        self.body_type == BodyType::Kinematic
    }

    #[inline]
    pub fn is_static(&self) -> bool {
        self.body_type == BodyType::Static
    }

    #[inline]
    pub fn position(&self) -> Vec2 {
        self.transform.position
    }

    #[inline]
    pub fn angle(&self) -> f32 {
        self.transform.rotation.angle()
    }

    #[inline]
    pub fn set_position(&mut self, position: Vec2) {
        self.transform.position = position;
    }

    #[inline]
    pub fn set_angle(&mut self, angle: f32) {
        self.transform.rotation.set_angle(angle);
    }

    #[inline]
    pub fn set_transform(&mut self, position: Vec2, angle: f32) {
        self.transform.position = position;
        self.transform.rotation.set_angle(angle);
    }

    #[inline]
    pub fn apply_force(&mut self, force: Vec2) {
        if self.is_dynamic() {
            self.force += force;
        }
    }

    #[inline]
    pub fn apply_force_at_point(&mut self, force: Vec2, point: Vec2) {
        if self.is_dynamic() {
            self.force += force;
            self.torque += (point - self.transform.position).cross(force);
        }
    }

    #[inline]
    pub fn apply_torque(&mut self, torque: f32) {
        if self.is_dynamic() {
            self.torque += torque;
        }
    }

    #[inline]
    pub fn apply_impulse(&mut self, impulse: Vec2) {
        if self.is_dynamic() {
            self.linear_velocity += impulse * self.inv_mass;
        }
    }

    #[inline]
    pub fn apply_impulse_at_point(&mut self, impulse: Vec2, point: Vec2) {
        if self.is_dynamic() {
            self.linear_velocity += impulse * self.inv_mass;
            self.angular_velocity += (point - self.transform.position).cross(impulse) * self.inv_inertia;
        }
    }

    #[inline]
    pub fn apply_angular_impulse(&mut self, angular_impulse: f32) {
        if self.is_dynamic() {
            self.angular_velocity += angular_impulse * self.inv_inertia;
        }
    }

    #[inline]
    pub fn get_point_velocity(&self, point: Vec2) -> Vec2 {
        let r = point - self.transform.position;
        self.linear_velocity + Vec2::new(-self.angular_velocity * r.y, self.angular_velocity * r.x)
    }

    #[inline]
    pub fn clear_forces(&mut self) {
        self.force = Vec2::ZERO;
        self.torque = 0.0;
    }

    #[inline]
    pub fn compute_aabb(&self) -> AABB {
        self.shape.compute_aabb(&self.transform)
    }

    #[inline]
    pub fn update_mass_properties(&mut self) {
        if self.body_type == BodyType::Dynamic {
            self.mass = self.shape.compute_mass(self.material.density);
            self.inv_mass = if self.mass > f32::EPSILON {
                1.0 / self.mass
            } else {
                0.0
            };
            self.inertia = self.shape.compute_inertia(self.mass);
            self.inv_inertia = if self.inertia > f32::EPSILON {
                1.0 / self.inertia
            } else {
                0.0
            };
        } else {
            self.mass = 0.0;
            self.inv_mass = 0.0;
            self.inertia = 0.0;
            self.inv_inertia = 0.0;
        }
    }

    #[inline]
    pub fn set_body_type(&mut self, body_type: BodyType) {
        self.body_type = body_type;
        self.update_mass_properties();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shape::Circle;
    use approx::assert_abs_diff_eq;

    fn create_test_body() -> Body {
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        Body::new(
            BodyHandle::from(slotmap::KeyData::from_ffi(1)),
            shape,
            Vec2::ZERO,
            0.0,
            BodyType::Dynamic,
            material,
        )
    }

    #[test]
    fn test_body_creation() {
        let body = create_test_body();
        assert!(body.is_dynamic());
        assert_abs_diff_eq!(body.position(), Vec2::ZERO);
        assert!(body.mass > 0.0);
        assert!(body.inv_mass > 0.0);
    }

    #[test]
    fn test_apply_force() {
        let mut body = create_test_body();
        body.apply_force(Vec2::new(10.0, 0.0));
        assert_abs_diff_eq!(body.force.x, 10.0);
    }

    #[test]
    fn test_apply_impulse() {
        let mut body = create_test_body();
        let mass = body.mass;
        body.apply_impulse(Vec2::new(mass, 0.0));
        assert_abs_diff_eq!(body.linear_velocity.x, 1.0);
    }

    #[test]
    fn test_static_body() {
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        let body = Body::new(
            BodyHandle::from(slotmap::KeyData::from_ffi(2)),
            shape,
            Vec2::ZERO,
            0.0,
            BodyType::Static,
            material,
        );
        assert!(body.is_static());
        assert_abs_diff_eq!(body.inv_mass, 0.0);
        assert_abs_diff_eq!(body.inv_inertia, 0.0);
    }
}
