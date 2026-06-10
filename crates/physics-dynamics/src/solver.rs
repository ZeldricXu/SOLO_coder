use physics_core::{BodyType, World};
use physics_math::Vec2;

pub struct DynamicsSolver {
    pub gravity: Vec2,
    pub velocity_iterations: usize,
    pub position_iterations: usize,
    pub linear_damping: f32,
    pub angular_damping: f32,
}

impl Default for DynamicsSolver {
    fn default() -> Self {
        DynamicsSolver {
            gravity: Vec2::new(0.0, -9.81),
            velocity_iterations: 8,
            position_iterations: 3,
            linear_damping: 0.0,
            angular_damping: 0.0,
        }
    }
}

impl DynamicsSolver {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn step(&self, world: &mut World, dt: f32) {
        self.save_transforms(world);
        self.apply_gravity(world);
        self.integrate_velocities(world, dt);
        self.apply_damping(world, dt);
        self.integrate_positions(world, dt);
    }

    fn save_transforms(&self, world: &mut World) {
        for (_, body) in world.bodies.iter_mut() {
            body.prev_transform = body.transform;
        }
    }

    fn apply_gravity(&self, world: &mut World) {
        for (_, body) in world.bodies.iter_mut() {
            if body.is_dynamic() && !body.is_sensor {
                let gravity_force = self.gravity * body.mass * body.gravity_scale;
                body.apply_force(gravity_force);
            }
        }
    }

    pub fn integrate_velocities(&self, world: &mut World, dt: f32) {
        for (_, body) in world.bodies.iter_mut() {
            if !body.is_dynamic() {
                continue;
            }

            body.linear_velocity += body.force * body.inv_mass * dt;
            body.angular_velocity += body.torque * body.inv_inertia * dt;

            body.force = Vec2::ZERO;
            body.torque = 0.0;
        }
    }

    fn apply_damping(&self, world: &mut World, dt: f32) {
        for (_, body) in world.bodies.iter_mut() {
            if !body.is_dynamic() {
                continue;
            }

            let linear_damping = body.linear_damping.max(self.linear_damping);
            let angular_damping = body.angular_damping.max(self.angular_damping);

            body.linear_velocity *= 1.0 - linear_damping * dt;
            body.angular_velocity *= 1.0 - angular_damping * dt;
        }
    }

    pub fn integrate_positions(&self, world: &mut World, dt: f32) {
        for (_, body) in world.bodies.iter_mut() {
            if body.body_type == BodyType::Static {
                continue;
            }

            body.transform.position += body.linear_velocity * dt;
            let new_angle = body.transform.rotation.angle() + body.angular_velocity * dt;
            body.transform.rotation.set_angle(new_angle);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;
    use physics_core::{BodyHandle, Circle, Material, Shape};
    use physics_math::Vec2;

    fn create_dynamic_body(world: &mut World, shape: Shape, position: Vec2) -> BodyHandle {
        world.add_body(shape, position, 0.0, BodyType::Dynamic, Material::DEFAULT)
    }

    #[test]
    fn test_gravity_application() {
        let mut world = World::new();
        let solver = DynamicsSolver::new();

        let shape = Shape::Circle(Circle { radius: 1.0 });
        let handle = create_dynamic_body(&mut world, shape, Vec2::ZERO);

        solver.step(&mut world, 1.0);

        let body = world.bodies.get(handle).unwrap();
        assert!(body.linear_velocity.y < 0.0);
        assert_abs_diff_eq!(body.linear_velocity.y, -9.81, epsilon = 1e-5);
    }

    #[test]
    fn test_position_integration() {
        let mut world = World::new();
        let mut solver = DynamicsSolver::new();
        solver.gravity = Vec2::ZERO;

        let shape = Shape::Circle(Circle { radius: 1.0 });
        let handle = create_dynamic_body(&mut world, shape, Vec2::ZERO);

        world.bodies.get_mut(handle).unwrap().linear_velocity = Vec2::new(1.0, 0.0);

        solver.step(&mut world, 1.0);

        let body = world.bodies.get(handle).unwrap();
        assert_abs_diff_eq!(body.transform.position.x, 1.0, epsilon = 1e-5);
    }

    #[test]
    fn test_static_body_not_moved() {
        let mut world = World::new();
        let solver = DynamicsSolver::new();

        let shape = Shape::Circle(Circle { radius: 1.0 });
        let handle = world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Static, Material::DEFAULT);

        solver.step(&mut world, 1.0);

        let body = world.bodies.get(handle).unwrap();
        assert_abs_diff_eq!(body.transform.position.x, 0.0, epsilon = 1e-5);
        assert_abs_diff_eq!(body.linear_velocity.length(), 0.0, epsilon = 1e-5);
    }
}
