use std::collections::HashMap;

use slotmap::SlotMap;

use physics_collision::{AABBTreeBroadPhase, BroadPhase, ContactManifold, NarrowPhase};
use physics_constraints::{ConstraintSolver, ContactConstraint, DistanceJoint, RevoluteJoint, PrismaticJoint, WeldJoint};
use physics_dynamics::integrator::{Integrator, IntegratorDefault};
use physics_core::{Body, BodyHandle, BodyType, Material, Shape};
use physics_events::{CollisionEvent, EventDispatcher, TriggerEvent};
use physics_math::{AABB, Vec2};
use physics_particles::{FluidParams, FluidSystem, Particle, ParticleSolver};


#[derive(Clone, Debug)]
pub struct SolverConfig {
    pub velocity_iterations: usize,
    pub position_iterations: usize,
    pub time_step: f32,
    pub max_sub_steps: usize,
}

impl Default for SolverConfig {
    fn default() -> Self {
        SolverConfig {
            velocity_iterations: 8,
            position_iterations: 3,
            time_step: 1.0 / 60.0,
            max_sub_steps: 10,
        }
    }
}

pub struct PhysicsWorld<BP: BroadPhase = AABBTreeBroadPhase, I: Integrator = IntegratorDefault> {
    pub gravity: Vec2,
    pub bodies: SlotMap<BodyHandle, Body>,
    pub body_shapes: HashMap<BodyHandle, Vec<Shape>>,
    pub aabb_margin: f32,
    pub min_body_size: f32,
    pub max_body_size: f32,

    pub broad_phase: BP,
    pub narrow_phase: NarrowPhase,
    pub constraint_solver: ConstraintSolver,
    pub integrator: I,

    pub contact_manifolds: Vec<ContactManifold>,
    pub contact_constraints: Vec<ContactConstraint>,

    pub revolute_joints: Vec<RevoluteJoint>,
    pub distance_joints: Vec<DistanceJoint>,
    pub prismatic_joints: Vec<PrismaticJoint>,
    pub weld_joints: Vec<WeldJoint>,

    pub event_dispatcher: EventDispatcher,

    pub particles: Vec<Particle>,
    pub particle_solver: ParticleSolver,
    pub fluid_system: Option<FluidSystem>,

    pub solver_config: SolverConfig,

    accumulator: f32,

    previous_contacts: HashMap<(BodyHandle, BodyHandle), ContactManifold>,
}

impl<BP: BroadPhase + Default, I: Integrator + Default> PhysicsWorld<BP, I> {
    pub fn new() -> Self {
        PhysicsWorld {
            gravity: Vec2::new(0.0, -9.81),
            bodies: SlotMap::with_key(),
            body_shapes: HashMap::new(),
            aabb_margin: 0.1,
            min_body_size: 0.01,
            max_body_size: 100.0,

            broad_phase: BP::default(),
            narrow_phase: NarrowPhase::new(),
            constraint_solver: ConstraintSolver::new(8, 3),
            integrator: I::default(),

            contact_manifolds: Vec::new(),
            contact_constraints: Vec::new(),

            revolute_joints: Vec::new(),
            distance_joints: Vec::new(),
            prismatic_joints: Vec::new(),
            weld_joints: Vec::new(),

            event_dispatcher: EventDispatcher::new(),

            particles: Vec::new(),
            particle_solver: ParticleSolver::new(Vec2::new(0.0, -9.81)),
            fluid_system: None,

            solver_config: SolverConfig::default(),

            accumulator: 0.0,

            previous_contacts: HashMap::new(),
        }
    }

    #[inline]
    pub fn with_gravity(mut self, gravity: Vec2) -> Self {
        self.gravity = gravity;
        self
    }

    #[inline]
    pub fn with_solver_config(mut self, config: SolverConfig) -> Self {
        let vi = config.velocity_iterations;
        let pi = config.position_iterations;
        self.solver_config = config;
        self.constraint_solver = ConstraintSolver::with_iterations(vi, pi);
        self
    }

    #[inline]
    pub fn with_broad_phase(mut self, broad_phase: BP) -> Self {
        self.broad_phase = broad_phase;
        self
    }

    #[inline]
    pub fn with_integrator(mut self, integrator: I) -> Self {
        self.integrator = integrator;
        self
    }
}

impl<BP: BroadPhase, I: Integrator> PhysicsWorld<BP, I> {

    #[inline]
    pub fn add_body(
        &mut self,
        shape: Shape,
        position: Vec2,
        angle: f32,
        body_type: BodyType,
        material: Material,
    ) -> BodyHandle {
        let shapes = vec![shape];
        self.add_body_with_shapes(shapes, position, angle, body_type, material)
    }

    #[inline]
    pub fn add_body_with_shapes(
        &mut self,
        shapes: Vec<Shape>,
        position: Vec2,
        angle: f32,
        body_type: BodyType,
        material: Material,
    ) -> BodyHandle {
        let first_shape = shapes
            .first()
            .cloned()
            .unwrap_or(Shape::Circle(physics_core::shape::Circle::new(1.0)));

        let handle = self.bodies.insert_with_key(|handle| {
            Body::new(handle, first_shape, position, angle, body_type, material)
        });

        self.body_shapes.insert(handle, shapes.clone());

        if body_type != BodyType::Static {
            if let Some(body) = self.bodies.get(handle) {
                self.broad_phase.add_body(handle, body);
            }
        }

        handle
    }

    fn compute_combined_aabb(&self, body: &Body, shapes: &[Shape]) -> Option<AABB> {
        let mut combined_aabb: Option<AABB> = None;
        for shape in shapes {
            let aabb = shape.compute_aabb(&body.transform).expand(self.aabb_margin);
            combined_aabb = match combined_aabb {
                Some(existing) => Some(existing.merged(&aabb)),
                None => Some(aabb),
            };
        }
        combined_aabb
    }

    fn update_broad_phase(&mut self, handle: BodyHandle) {
        let body = match self.bodies.get(handle) {
            Some(b) => b,
            None => return,
        };

        self.broad_phase.update_body(handle, body);
    }

    #[inline]
    pub fn remove_body(&mut self, handle: BodyHandle) -> Option<Body> {
        self.body_shapes.remove(&handle);
        self.broad_phase.remove_body(handle);
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
    pub fn get_body_shapes(&self, handle: BodyHandle) -> Option<&[Shape]> {
        self.body_shapes.get(&handle).map(|v| v.as_slice())
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
    pub fn add_revolute_joint(&mut self, joint: RevoluteJoint) {
        self.revolute_joints.push(joint);
    }

    #[inline]
    pub fn add_distance_joint(&mut self, joint: DistanceJoint) {
        self.distance_joints.push(joint);
    }

    #[inline]
    pub fn add_prismatic_joint(&mut self, joint: PrismaticJoint) {
        self.prismatic_joints.push(joint);
    }

    #[inline]
    pub fn add_weld_joint(&mut self, joint: WeldJoint) {
        self.weld_joints.push(joint);
    }

    #[inline]
    pub fn add_particle(&mut self, particle: Particle) {
        if let Some(fluid) = &mut self.fluid_system {
            fluid.add_particle(particle);
        } else {
            self.particles.push(particle);
        }
    }

    #[inline]
    pub fn enable_fluid(&mut self, smoothing_radius: f32, rest_density: f32) {
        let params = FluidParams {
            smoothing_radius,
            rest_density,
            pressure_stiffness: 200.0,
            viscosity: 0.1,
            gravity: self.gravity,
        };
        self.fluid_system = Some(FluidSystem::new(params));
    }

    #[inline]
    pub fn clear(&mut self) {
        self.bodies.clear();
        self.body_shapes.clear();
        self.broad_phase.clear();
        self.contact_manifolds.clear();
        self.contact_constraints.clear();
        self.revolute_joints.clear();
        self.distance_joints.clear();
        self.prismatic_joints.clear();
        self.weld_joints.clear();
        self.particles.clear();
        self.fluid_system = None;
        self.accumulator = 0.0;
        self.previous_contacts.clear();
        self.event_dispatcher.clear();
    }

    #[inline]
    pub fn apply_gravity(&mut self) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.apply_gravity(&mut body_refs, self.gravity);
    }

    #[inline]
    pub fn save_transforms(&mut self) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.pre_step(&mut body_refs);
    }

    fn integrate_velocities(&mut self, dt: f32) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.integrate_velocities(&mut body_refs, self.gravity, dt);
    }

    fn integrate_positions(&mut self, dt: f32) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.integrate_positions(&mut body_refs, dt);
    }

    fn apply_damping(&mut self, dt: f32) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.apply_damping(&mut body_refs, dt, 0.0, 0.0);
    }

    pub fn step(&mut self, delta_time: f32) {
        let fixed_dt = self.solver_config.time_step;
        let max_sub_steps = self.solver_config.max_sub_steps;
        
        self.accumulator += delta_time;
        
        let mut sub_steps = 0;
        while self.accumulator >= fixed_dt && sub_steps < max_sub_steps {
            self.step_single(fixed_dt);
            self.accumulator -= fixed_dt;
            sub_steps += 1;
        }
        
        if sub_steps >= max_sub_steps {
            self.accumulator = 0.0;
        }
    }

    pub fn step_single(&mut self, dt: f32) {
        if dt <= 0.0 {
            return;
        }

        self.save_transforms();
        self.apply_gravity();

        self.integrate_velocities(dt);
        self.apply_damping(dt);

        let handles: Vec<BodyHandle> = self.bodies.keys().collect();
        for handle in handles {
            if let Some(body) = self.bodies.get(handle) {
                if body.is_dynamic() {
                    self.update_broad_phase(handle);
                }
            }
        }

        self.contact_manifolds.clear();
        self.contact_constraints.clear();

        let broad_pairs = self.broad_phase.get_potential_pairs();

        for (handle_a, handle_b) in &broad_pairs {
            let body_a = match self.bodies.get(*handle_a) {
                Some(b) => b,
                None => continue,
            };
            let body_b = match self.bodies.get(*handle_b) {
                Some(b) => b,
                None => continue,
            };

            if body_a.is_static() && body_b.is_static() {
                continue;
            }

            let shapes_a = match self.body_shapes.get(handle_a) {
                Some(s) => s,
                None => continue,
            };
            let shapes_b = match self.body_shapes.get(handle_b) {
                Some(s) => s,
                None => continue,
            };

            for shape_a in shapes_a {
                for shape_b in shapes_b {
                    let temp_body_a = Body::new_temp(shape_a.clone(), body_a.transform);
                    let temp_body_b = Body::new_temp(shape_b.clone(), body_b.transform);

                    if let Some(mut manifold) =
                        self.narrow_phase
                            .collide(&temp_body_a, *handle_a, &temp_body_b, *handle_b)
                    {
                        manifold.body_a = *handle_a;
                        manifold.body_b = *handle_b;
                        self.contact_manifolds.push(manifold);
                    }
                }
            }
        }

        {
            for manifold in &self.contact_manifolds {
                if manifold.point_count == 0 {
                    continue;
                }

                let body_a = self.bodies.get(manifold.body_a).unwrap();
                let body_b = self.bodies.get(manifold.body_b).unwrap();

                let constraint = ContactConstraint::new(manifold, body_a, body_b);
                self.contact_constraints.push(constraint);
            }

            self.constraint_solver.solve_all(
                &mut self.contact_constraints,
                &mut self.revolute_joints,
                &mut self.distance_joints,
                &mut self.prismatic_joints,
                &mut self.weld_joints,
                &mut self.bodies,
                dt,
            );
        }

        self.integrate_positions(dt);

        for body in self.bodies.values_mut() {
            body.clear_forces();
        }

        self.detect_collision_events();

        if !self.particles.is_empty() {
            self.step_particles(dt);
        }
    }

    fn detect_collision_events(&mut self) {
        let mut current_contacts: HashMap<(BodyHandle, BodyHandle), ContactManifold> = HashMap::new();

        for manifold in &self.contact_manifolds {
            if manifold.point_count == 0 {
                continue;
            }

            let key = (manifold.body_a, manifold.body_b);
            let ordered_key = if key.0 < key.1 { key } else { (key.1, key.0) };
            current_contacts.insert(ordered_key, manifold.clone());
        }

        self.event_dispatcher.begin_frame();
        self.event_dispatcher.dispatch_collisions(&self.contact_manifolds);

        self.previous_contacts = current_contacts;
    }

    fn step_particles(&mut self, dt: f32) {
        if let Some(fluid) = &mut self.fluid_system {
            fluid.step(dt);
            self.particle_solver.step_fluid(fluid, dt);
        } else if !self.particles.is_empty() {
            self.particle_solver.step_simple(&mut self.particles, dt);
        }
    }

    #[inline]
    pub fn register_collision_callback<F>(&mut self, callback: F)
    where
        F: FnMut(&CollisionEvent) + Send + Sync + 'static,
    {
        self.event_dispatcher.register_collision_callback(callback);
    }

    #[inline]
    pub fn register_trigger_callback<F>(&mut self, callback: F)
    where
        F: FnMut(&TriggerEvent) + Send + Sync + 'static,
    {
        self.event_dispatcher.register_trigger_callback(callback);
    }

    #[inline]
    pub fn contact_manifolds(&self) -> &[ContactManifold] {
        &self.contact_manifolds
    }

    #[inline]
    pub fn contact_constraints(&self) -> &[ContactConstraint] {
        &self.contact_constraints
    }

    #[inline]
    pub fn revolute_joints(&self) -> &[RevoluteJoint] {
        &self.revolute_joints
    }

    #[inline]
    pub fn distance_joints(&self) -> &[DistanceJoint] {
        &self.distance_joints
    }

    #[inline]
    pub fn prismatic_joints(&self) -> &[PrismaticJoint] {
        &self.prismatic_joints
    }

    #[inline]
    pub fn weld_joints(&self) -> &[WeldJoint] {
        &self.weld_joints
    }

    #[inline]
    pub fn particles(&self) -> &[Particle] {
        &self.particles
    }

    #[inline]
    pub fn fluid_particles(&self) -> Option<&[Particle]> {
        self.fluid_system.as_ref().map(|_| &self.particles[..])
    }
}

impl Default for PhysicsWorld {
    fn default() -> Self {
        PhysicsWorld::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use physics_core::shape::{Circle, Rectangle, HalfSpace, CollisionFilter};
    use approx::assert_abs_diff_eq;

    type TestWorld = PhysicsWorld;

    #[test]
    fn test_world_creation() {
        let world: TestWorld = PhysicsWorld::new();
        assert_eq!(world.body_count(), 0);
        assert_abs_diff_eq!(world.gravity.y, -9.81);
    }

    #[test]
    fn test_add_body() {
        let mut world: TestWorld = PhysicsWorld::new();
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        let handle = world.add_body(
            shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        assert_eq!(world.body_count(), 1);
        let body = world.get_body(handle).unwrap();
        assert_abs_diff_eq!(body.position().y, 5.0);
    }

    #[test]
    fn test_remove_body() {
        let mut world: TestWorld = PhysicsWorld::new();
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
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0);
        world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, material);

        world.apply_gravity();

        let body = world.bodies().next().unwrap();
        let expected_force = body.mass * -10.0;
        assert_abs_diff_eq!(body.force.y, expected_force);
    }

    #[test]
    fn test_simple_fall() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0).with_restitution(0.0);
        world.add_body(
            shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        for _ in 0..30 {
            world.step(1.0 / 60.0);
        }

        let body = world.bodies().next().unwrap();
        assert!(body.position().y < 5.0);
    }

    #[test]
    fn test_collision_detection() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));

        let ground_shape = Shape::Rectangle(Rectangle::new(10.0, 1.0));
        let ground_material = Material::DEFAULT.with_restitution(0.5);
        world.add_body(
            ground_shape,
            Vec2::new(0.0, -5.0),
            0.0,
            BodyType::Static,
            ground_material,
        );

        let ball_shape = Shape::Circle(Circle::new(1.0));
        let ball_material = Material::DEFAULT.with_restitution(0.5);
        world.add_body(
            ball_shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            ball_material,
        );

        for _ in 0..60 {
            world.step(1.0 / 60.0);
        }

        let ball = world.bodies().nth(1).unwrap();
        assert!(ball.position().y > -4.5);
    }

    #[test]
    fn test_half_space_collision() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));

        let ground_shape = Shape::HalfSpace(HalfSpace::ground());
        let ground_material = Material::DEFAULT.with_restitution(0.0);
        world.add_body(
            ground_shape,
            Vec2::new(0.0, 0.0),
            0.0,
            BodyType::Static,
            ground_material,
        );

        let ball_shape = Shape::Circle(Circle::new(1.0));
        let ball_material = Material::DEFAULT.with_restitution(0.0);
        world.add_body(
            ball_shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            ball_material,
        );

        for _ in 0..60 {
            world.step(1.0 / 60.0);
        }

        let ball = world.bodies().nth(1).unwrap();
        assert!(ball.position().y < 1.0);
        assert!(ball.position().y > -0.5);
    }

    #[test]
    fn test_collision_filter() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::ZERO);

        let filter1 = CollisionFilter::new(0x0001, 0x0001);
        let filter2 = CollisionFilter::new(0x0002, 0x0002);

        let shape1 = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        
        let handle1 = world.add_body(
            shape1.clone(),
            Vec2::new(-0.5, 0.0),
            0.0,
            BodyType::Dynamic,
            material,
        );
        let handle2 = world.add_body(
            shape1,
            Vec2::new(0.5, 0.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        world.get_body_mut(handle1).unwrap().set_collision_filter(filter1);
        world.get_body_mut(handle2).unwrap().set_collision_filter(filter2);

        world.step(1.0 / 60.0);

        assert_eq!(world.contact_manifolds().len(), 0);
    }

    #[test]
    fn test_adaptive_time_step() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));

        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0);
        world.add_body(
            shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let dt = 1.0 / 60.0;
        world.step(dt * 2.5);

        let body = world.bodies().next().unwrap();
        assert!(body.position().y < 5.0);
    }

    #[test]
    fn test_max_sub_steps() {
        let mut config = SolverConfig::default();
        config.max_sub_steps = 3;
        let mut world: TestWorld = PhysicsWorld::new()
            .with_gravity(Vec2::new(0.0, -10.0))
            .with_solver_config(config);

        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0);
        world.add_body(
            shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let dt = 1.0 / 60.0 * 10.0;
        world.step(dt);

        let body = world.bodies().next().unwrap();
        assert!(body.position().y < 5.0);
    }

    #[test]
    fn test_revolute_joint_with_motor() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::ZERO);

        let shape = Shape::Circle(Circle::new(0.5));
        let material = Material::DEFAULT.with_density(1.0);
        let ta = physics_math::Transform::new(Vec2::new(0.0, 0.0), physics_math::Rot2::new(0.0));
        let tb = physics_math::Transform::new(Vec2::new(0.0, 0.0), physics_math::Rot2::new(0.0));
        
        let handle_a = world.add_body(
            shape.clone(),
            Vec2::new(0.0, 0.0),
            0.0,
            BodyType::Static,
            material,
        );
        let handle_b = world.add_body(
            shape,
            Vec2::new(0.0, 0.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let anchor = Vec2::new(0.0, 0.0);
        let joint = RevoluteJoint::new(handle_a, handle_b, anchor, &ta, &tb)
            .with_motor(2.0, 100.0);
        world.add_revolute_joint(joint);

        for _ in 0..60 {
            world.step(1.0 / 60.0);
        }

        let body_b = world.get_body(handle_b).unwrap();
        assert!(body_b.angular_velocity.abs() > 0.5);
    }

    #[test]
    fn test_distance_joint() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::ZERO);

        let shape = Shape::Circle(Circle::new(0.5));
        let material = Material::DEFAULT.with_density(1.0);
        let ta = physics_math::Transform::new(Vec2::new(-1.0, 0.0), physics_math::Rot2::new(0.0));
        let tb = physics_math::Transform::new(Vec2::new(1.0, 0.0), physics_math::Rot2::new(0.0));
        
        let handle_a = world.add_body(
            shape.clone(),
            Vec2::new(-1.0, 0.0),
            0.0,
            BodyType::Static,
            material,
        );
        let handle_b = world.add_body(
            shape,
            Vec2::new(1.0, 0.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let joint = DistanceJoint::new(handle_a, handle_b, Vec2::new(-1.0, 0.0), Vec2::new(1.0, 0.0), &ta, &tb);
        world.add_distance_joint(joint);

        world.get_body_mut(handle_b).unwrap().linear_velocity = Vec2::new(0.0, 5.0);

        for _ in 0..10 {
            world.step(1.0 / 60.0);
        }

        let body_a = world.get_body(handle_a).unwrap();
        let body_b = world.get_body(handle_b).unwrap();
        let distance = (body_b.position() - body_a.position()).length();
        assert_abs_diff_eq!(distance, 2.0, epsilon = 0.1);
    }

    #[test]
    fn test_weld_joint() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));

        let shape = Shape::Circle(Circle::new(0.5));
        let material = Material::DEFAULT.with_density(1.0);
        let ta = physics_math::Transform::new(Vec2::new(-0.5, 0.0), physics_math::Rot2::new(0.0));
        let tb = physics_math::Transform::new(Vec2::new(0.5, 0.0), physics_math::Rot2::new(0.0));
        
        let handle_a = world.add_body(
            shape.clone(),
            Vec2::new(-0.5, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );
        let handle_b = world.add_body(
            shape,
            Vec2::new(0.5, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let anchor = Vec2::new(0.0, 5.0);
        let joint = WeldJoint::new(handle_a, handle_b, anchor, &ta, &tb);
        world.add_weld_joint(joint);

        for _ in 0..30 {
            world.step(1.0 / 60.0);
        }

        let body_a = world.get_body(handle_a).unwrap();
        let body_b = world.get_body(handle_b).unwrap();
        let angle_diff = (body_b.angle() - body_a.angle()).abs();
        assert!(angle_diff < 0.1);
    }
}
