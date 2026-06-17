use crate::integrator::Integrator;
use physics_core::{BodyType, World};
use physics_math::Vec2;

pub struct DynamicsSolver<I: Integrator = crate::integrator::IntegratorDefault> {
    pub gravity: Vec2,
    pub velocity_iterations: usize,
    pub position_iterations: usize,
    pub integrator: I,
    pub linear_damping: f32,
    pub angular_damping: f32,
}

impl<I: Integrator + Default> Default for DynamicsSolver<I> {
    fn default() -> Self {
        DynamicsSolver {
            gravity: Vec2::new(0.0, -9.81),
            velocity_iterations: 8,
            position_iterations: 3,
            integrator: I::default(),
            linear_damping: 0.0,
            angular_damping: 0.0,
        }
    }
}

impl<I: Integrator + Default> DynamicsSolver<I> {
    pub fn new() -> Self {
        Self::new_with_integrator(I::default())
    }
}

impl<I: Integrator> DynamicsSolver<I> {
    pub fn new_with_integrator(integrator: I) -> Self {
        DynamicsSolver {
            gravity: Vec2::new(0.0, -9.81),
            velocity_iterations: 8,
            position_iterations: 3,
            integrator,
            linear_damping: 0.0,
            angular_damping: 0.0,
        }
    }

    pub fn step(&mut self, world: &mut World, dt: f32) {
        self.save_transforms(world);
        self.apply_gravity(world);
        self.integrate_velocities(world, dt);
        self.apply_damping(world, dt);
        self.integrate_positions(world, dt);
    }

    fn save_transforms(&mut self, world: &mut World) {
        let mut body_refs: Vec<&mut _> = world.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.pre_step(&mut body_refs);
    }

    fn apply_gravity(&mut self, world: &mut World) {
        let mut body_refs: Vec<&mut _> = world.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.apply_gravity(&mut body_refs, self.gravity);
    }

    pub fn integrate_velocities(&mut self, world: &mut World, dt: f32) {
        let mut body_refs: Vec<&mut _> = world.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.integrate_velocities(&mut body_refs, self.gravity, dt);
    }

    fn apply_damping(&mut self, world: &mut World, dt: f32) {
        let mut body_refs: Vec<&mut _> = world.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.apply_damping(&mut body_refs, dt, self.linear_damping, self.angular_damping);
    }

    pub fn integrate_positions(&mut self, world: &mut World, dt: f32) {
        let mut body_refs: Vec<&mut _> = world.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.integrate_positions(&mut body_refs, dt);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;
    use physics_core::{BodyHandle, Circle, Material, Shape};
    use physics_math::Vec2;

    type TestSolver = DynamicsSolver;

    fn create_dynamic_body(world: &mut World, shape: Shape, position: Vec2) -> BodyHandle {
        world.add_body(shape, position, 0.0, BodyType::Dynamic, Material::DEFAULT)
    }

    #[test]
    fn test_gravity_application() {
        let mut world = World::new();
        let mut solver: TestSolver = DynamicsSolver::new();

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
        let mut solver: TestSolver = DynamicsSolver::new();
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
        let mut solver: TestSolver = DynamicsSolver::new();

        let shape = Shape::Circle(Circle { radius: 1.0 });
        let handle = world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Static, Material::DEFAULT);

        solver.step(&mut world, 1.0);

        let body = world.bodies.get(handle).unwrap();
        assert_abs_diff_eq!(body.transform.position.x, 0.0, epsilon = 1e-5);
        assert_abs_diff_eq!(body.linear_velocity.length(), 0.0, epsilon = 1e-5);
    }
}
