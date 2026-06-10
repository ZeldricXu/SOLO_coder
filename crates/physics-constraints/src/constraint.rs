use slotmap::new_key_type;

use physics_core::{Body, BodyHandle};
use physics_math::Vec2;

new_key_type! {
    pub struct ConstraintHandle;
}

pub struct ConstraintSolverData<'a> {
    pub bodies: &'a mut slotmap::SlotMap<BodyHandle, Body>,
    pub dt: f32,
    pub inv_dt: f32,
}

pub trait Constraint {
    fn body_a(&self) -> BodyHandle;
    fn body_b(&self) -> BodyHandle;
    fn prepare(&mut self, data: &ConstraintSolverData);
    fn solve_velocity(&mut self, data: &mut ConstraintSolverData);
    fn solve_position(&mut self, data: &mut ConstraintSolverData) -> bool;
}

#[derive(Clone, Copy, Debug)]
pub struct Jacobian {
    pub linear_a: Vec2,
    pub angular_a: f32,
    pub linear_b: Vec2,
    pub angular_b: f32,
}

impl Jacobian {
    pub fn new(linear_a: Vec2, angular_a: f32, linear_b: Vec2, angular_b: f32) -> Self {
        Jacobian {
            linear_a,
            angular_a,
            linear_b,
            angular_b,
        }
    }

    pub fn compute(&self, body_a: &Body, body_b: &Body) -> f32 {
        self.linear_a.dot(body_a.linear_velocity)
            + self.angular_a * body_a.angular_velocity
            + self.linear_b.dot(body_b.linear_velocity)
            + self.angular_b * body_b.angular_velocity
    }

    pub fn compute_effective_mass(&self, body_a: &Body, body_b: &Body) -> f32 {
        let mut mass = 0.0;

        if body_a.is_dynamic() {
            mass += self.linear_a.dot(self.linear_a) * body_a.inv_mass;
            mass += self.angular_a * self.angular_a * body_a.inv_inertia;
        }

        if body_b.is_dynamic() {
            mass += self.linear_b.dot(self.linear_b) * body_b.inv_mass;
            mass += self.angular_b * self.angular_b * body_b.inv_inertia;
        }

        mass
    }

    pub fn apply_impulse(&self, body_a: &mut Body, body_b: &mut Body, impulse: f32) {
        if body_a.is_dynamic() {
            body_a.linear_velocity += self.linear_a * impulse * body_a.inv_mass;
            body_a.angular_velocity += self.angular_a * impulse * body_a.inv_inertia;
        }

        if body_b.is_dynamic() {
            body_b.linear_velocity += self.linear_b * impulse * body_b.inv_mass;
            body_b.angular_velocity += self.angular_b * impulse * body_b.inv_inertia;
        }
    }
}
