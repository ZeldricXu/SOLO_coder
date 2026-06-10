use physics_core::BodyHandle;
use physics_math::{Transform, Vec2};

use crate::constraint::{Constraint, ConstraintSolverData, Jacobian};

#[derive(Clone, Debug)]
pub struct RevoluteJoint {
    pub body_a: BodyHandle,
    pub body_b: BodyHandle,
    pub local_anchor_a: Vec2,
    pub local_anchor_b: Vec2,
    pub impulse: Vec2,
    pub mass: Vec2,
}

impl RevoluteJoint {
    pub fn new(body_a: BodyHandle, body_b: BodyHandle, anchor: Vec2, ta: &Transform, tb: &Transform) -> Self {
        let local_anchor_a = ta.rotation.inv_mul_vec(anchor - ta.position);
        let local_anchor_b = tb.rotation.inv_mul_vec(anchor - tb.position);

        RevoluteJoint {
            body_a,
            body_b,
            local_anchor_a,
            local_anchor_b,
            impulse: Vec2::ZERO,
            mass: Vec2::ZERO,
        }
    }
}

impl Constraint for RevoluteJoint {
    fn body_a(&self) -> BodyHandle {
        self.body_a
    }

    fn body_b(&self) -> BodyHandle {
        self.body_b
    }

    fn prepare(&mut self, data: &ConstraintSolverData) {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let ra = body_a.transform.rotation.mul_vec(self.local_anchor_a);
        let rb = body_b.transform.rotation.mul_vec(self.local_anchor_b);

        let m = body_a.inv_mass + body_b.inv_mass;
        let i_a = body_a.inv_inertia;
        let i_b = body_b.inv_inertia;

        let k11 = m + ra.y * ra.y * i_a + rb.y * rb.y * i_b;
        let k12 = -ra.x * ra.y * i_a - rb.x * rb.y * i_b;
        let k22 = m + ra.x * ra.x * i_a + rb.x * rb.x * i_b;

        let det = k11 * k22 - k12 * k12;
        if det.abs() > f32::EPSILON {
            let inv_det = 1.0 / det;
            self.mass.x = k22 * inv_det;
            self.mass.y = k11 * inv_det;
        }

        self.impulse *= 0.0;
    }

    fn solve_velocity(&mut self, data: &mut ConstraintSolverData) {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let ra = body_a.transform.rotation.mul_vec(self.local_anchor_a);
        let rb = body_b.transform.rotation.mul_vec(self.local_anchor_b);
        let inv_mass_a = body_a.inv_mass;
        let inv_inertia_a = body_a.inv_inertia;
        let inv_mass_b = body_b.inv_mass;
        let inv_inertia_b = body_b.inv_inertia;
        let is_dynamic_a = body_a.is_dynamic();
        let is_dynamic_b = body_b.is_dynamic();

        let va = body_a.linear_velocity + ra.perp() * body_a.angular_velocity;
        let vb = body_b.linear_velocity + rb.perp() * body_b.angular_velocity;
        let dv = vb - va;

        let cdot = dv;
        let impulse = Vec2::new(
            -cdot.x * self.mass.x - cdot.y * self.mass.y,
            -cdot.y * self.mass.y - cdot.x * self.mass.x,
        );

        self.impulse += impulse;

        {
            let body_a = data.bodies.get_mut(self.body_a).unwrap();
            if is_dynamic_a {
                body_a.linear_velocity -= impulse * inv_mass_a;
                body_a.angular_velocity -= ra.cross(impulse) * inv_inertia_a;
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.linear_velocity += impulse * inv_mass_b;
                body_b.angular_velocity += rb.cross(impulse) * inv_inertia_b;
            }
        }
    }

    fn solve_position(&mut self, data: &mut ConstraintSolverData) -> bool {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let ra = body_a.transform.rotation.mul_vec(self.local_anchor_a);
        let rb = body_b.transform.rotation.mul_vec(self.local_anchor_b);
        let inv_mass_a = body_a.inv_mass;
        let inv_inertia_a = body_a.inv_inertia;
        let inv_mass_b = body_b.inv_mass;
        let inv_inertia_b = body_b.inv_inertia;
        let is_dynamic_a = body_a.is_dynamic();
        let is_dynamic_b = body_b.is_dynamic();

        let pa = body_a.transform.position + ra;
        let pb = body_b.transform.position + rb;
        let c = pb - pa;

        let m = inv_mass_a + inv_mass_b;

        let k11 = m + ra.y * ra.y * inv_inertia_a + rb.y * rb.y * inv_inertia_b;
        let k12 = -ra.x * ra.y * inv_inertia_a - rb.x * rb.y * inv_inertia_b;
        let k22 = m + ra.x * ra.x * inv_inertia_a + rb.x * rb.x * inv_inertia_b;

        let det = k11 * k22 - k12 * k12;
        let impulse = if det.abs() > f32::EPSILON {
            let inv_det = 1.0 / det;
            Vec2::new(
                -(k22 * c.x - k12 * c.y) * inv_det,
                -(-k12 * c.x + k11 * c.y) * inv_det,
            )
        } else {
            Vec2::ZERO
        };

        {
            let body_a = data.bodies.get_mut(self.body_a).unwrap();
            if is_dynamic_a {
                body_a.transform.position -= impulse * inv_mass_a;
                let angle_a = body_a.transform.rotation.angle() - ra.cross(impulse) * inv_inertia_a;
                body_a.transform.rotation.set_angle(angle_a);
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.transform.position += impulse * inv_mass_b;
                let angle_b = body_b.transform.rotation.angle() + rb.cross(impulse) * inv_inertia_b;
                body_b.transform.rotation.set_angle(angle_b);
            }
        }

        c.length() < 0.001
    }
}

#[derive(Clone, Debug)]
pub struct DistanceJoint {
    pub body_a: BodyHandle,
    pub body_b: BodyHandle,
    pub local_anchor_a: Vec2,
    pub local_anchor_b: Vec2,
    pub length: f32,
    pub impulse: f32,
    pub mass: f32,
}

impl DistanceJoint {
    pub fn new(body_a: BodyHandle, body_b: BodyHandle, anchor_a: Vec2, anchor_b: Vec2, ta: &Transform, tb: &Transform) -> Self {
        let local_anchor_a = ta.rotation.inv_mul_vec(anchor_a - ta.position);
        let local_anchor_b = tb.rotation.inv_mul_vec(anchor_b - tb.position);
        let length = (anchor_b - anchor_a).length();

        DistanceJoint {
            body_a,
            body_b,
            local_anchor_a,
            local_anchor_b,
            length,
            impulse: 0.0,
            mass: 0.0,
        }
    }
}

impl Constraint for DistanceJoint {
    fn body_a(&self) -> BodyHandle {
        self.body_a
    }

    fn body_b(&self) -> BodyHandle {
        self.body_b
    }

    fn prepare(&mut self, data: &ConstraintSolverData) {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let ra = body_a.transform.rotation.mul_vec(self.local_anchor_a);
        let rb = body_b.transform.rotation.mul_vec(self.local_anchor_b);

        let u = (body_b.transform.position + rb) - (body_a.transform.position + ra);
        let length = u.length();

        let u = if length > f32::EPSILON { u / length } else { Vec2::new(1.0, 0.0) };

        let cross_a = ra.cross(u);
        let cross_b = rb.cross(u);

        let mass = body_a.inv_mass + body_b.inv_mass
            + cross_a * cross_a * body_a.inv_inertia
            + cross_b * cross_b * body_b.inv_inertia;

        self.mass = if mass > f32::EPSILON { 1.0 / mass } else { 0.0 };
        self.impulse *= 0.0;
    }

    fn solve_velocity(&mut self, data: &mut ConstraintSolverData) {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let ra = body_a.transform.rotation.mul_vec(self.local_anchor_a);
        let rb = body_b.transform.rotation.mul_vec(self.local_anchor_b);
        let inv_mass_a = body_a.inv_mass;
        let inv_inertia_a = body_a.inv_inertia;
        let inv_mass_b = body_b.inv_mass;
        let inv_inertia_b = body_b.inv_inertia;
        let is_dynamic_a = body_a.is_dynamic();
        let is_dynamic_b = body_b.is_dynamic();

        let va = body_a.linear_velocity + ra.perp() * body_a.angular_velocity;
        let vb = body_b.linear_velocity + rb.perp() * body_b.angular_velocity;

        let u = (body_b.transform.position + rb) - (body_a.transform.position + ra);
        let length = u.length();
        let u = if length > f32::EPSILON { u / length } else { Vec2::new(1.0, 0.0) };

        let cdot = (vb - va).dot(u);
        let impulse = -cdot * self.mass;

        self.impulse += impulse;

        let jacobian = Jacobian::new(-u, -ra.cross(u), u, rb.cross(u));

        {
            let body_a = data.bodies.get_mut(self.body_a).unwrap();
            if is_dynamic_a {
                body_a.linear_velocity += jacobian.linear_a * impulse * inv_mass_a;
                body_a.angular_velocity += jacobian.angular_a * impulse * inv_inertia_a;
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.linear_velocity += jacobian.linear_b * impulse * inv_mass_b;
                body_b.angular_velocity += jacobian.angular_b * impulse * inv_inertia_b;
            }
        }
    }

    fn solve_position(&mut self, data: &mut ConstraintSolverData) -> bool {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let ra = body_a.transform.rotation.mul_vec(self.local_anchor_a);
        let rb = body_b.transform.rotation.mul_vec(self.local_anchor_b);
        let inv_mass_a = body_a.inv_mass;
        let inv_inertia_a = body_a.inv_inertia;
        let inv_mass_b = body_b.inv_mass;
        let inv_inertia_b = body_b.inv_inertia;
        let is_dynamic_a = body_a.is_dynamic();
        let is_dynamic_b = body_b.is_dynamic();

        let pa = body_a.transform.position + ra;
        let pb = body_b.transform.position + rb;
        let u = pb - pa;
        let length = u.length();
        let c = length - self.length;

        let u = if length > f32::EPSILON { u / length } else { Vec2::new(1.0, 0.0) };

        let cross_a = ra.cross(u);
        let cross_b = rb.cross(u);

        let mass = inv_mass_a + inv_mass_b
            + cross_a * cross_a * inv_inertia_a
            + cross_b * cross_b * inv_inertia_b;

        let impulse = if mass > f32::EPSILON { -c / mass } else { 0.0 };

        let jacobian = Jacobian::new(-u, -cross_a, u, cross_b);

        {
            let body_a = data.bodies.get_mut(self.body_a).unwrap();
            if is_dynamic_a {
                body_a.transform.position += jacobian.linear_a * impulse * inv_mass_a;
                let angle_a = body_a.transform.rotation.angle() + jacobian.angular_a * impulse * inv_inertia_a;
                body_a.transform.rotation.set_angle(angle_a);
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.transform.position += jacobian.linear_b * impulse * inv_mass_b;
                let angle_b = body_b.transform.rotation.angle() + jacobian.angular_b * impulse * inv_inertia_b;
                body_b.transform.rotation.set_angle(angle_b);
            }
        }

        c.abs() < 0.001
    }
}
