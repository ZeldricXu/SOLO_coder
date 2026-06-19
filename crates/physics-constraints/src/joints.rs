use physics_types::BodyHandle;
use physics_math::{Transform, Vec2};

use crate::constraint::{Constraint, ConstraintSolverData, Jacobian};

const BAUMGARTE_POSITION: f32 = 0.2;

pub trait Joint: Constraint {
    fn set_motor_enabled(&mut self, enabled: bool) {}
    fn set_motor_speed(&mut self, speed: f32) {}
    fn set_max_motor_force(&mut self, force: f32) {}
    fn set_limits_enabled(&mut self, enabled: bool) {}
    fn set_limits(&mut self, lower: f32, upper: f32) {}
}

#[derive(Clone, Debug)]
pub struct RevoluteJoint {
    pub body_a: BodyHandle,
    pub body_b: BodyHandle,
    pub local_anchor_a: Vec2,
    pub local_anchor_b: Vec2,
    pub impulse: Vec2,
    pub mass: Vec2,
    pub mass_off_diag: f32,
    pub lower_angle: f32,
    pub upper_angle: f32,
    pub motor_speed: f32,
    pub max_torque: f32,
    pub enable_limit: bool,
    pub enable_motor: bool,
    pub angle_impulse: f32,
    pub angle_mass: f32,
    pub motor_impulse: f32,
    pub motor_mass: f32,
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
            mass_off_diag: 0.0,
            lower_angle: -std::f32::consts::PI,
            upper_angle: std::f32::consts::PI,
            motor_speed: 0.0,
            max_torque: 0.0,
            enable_limit: false,
            enable_motor: false,
            angle_impulse: 0.0,
            angle_mass: 0.0,
            motor_impulse: 0.0,
            motor_mass: 0.0,
        }
    }

    pub fn with_angle_limit(mut self, lower: f32, upper: f32) -> Self {
        self.lower_angle = lower;
        self.upper_angle = upper;
        self.enable_limit = true;
        self
    }

    pub fn with_motor(mut self, speed: f32, max_torque: f32) -> Self {
        self.motor_speed = speed;
        self.max_torque = max_torque;
        self.enable_motor = true;
        self
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
            self.mass_off_diag = -k12 * inv_det;
        } else {
            self.mass = Vec2::ZERO;
            self.mass_off_diag = 0.0;
        }

        self.angle_mass = i_a + i_b;
        if self.angle_mass > f32::EPSILON {
            self.angle_mass = 1.0 / self.angle_mass;
        }

        self.motor_mass = i_a + i_b;
        if self.motor_mass > f32::EPSILON {
            self.motor_mass = 1.0 / self.motor_mass;
        }

        self.impulse *= 0.0;
        self.angle_impulse *= 0.0;
        self.motor_impulse *= 0.0;
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
        let ang_vel_a = body_a.angular_velocity;
        let ang_vel_b = body_b.angular_velocity;

        let va = body_a.linear_velocity + ra.perp() * ang_vel_a;
        let vb = body_b.linear_velocity + rb.perp() * ang_vel_b;
        let dv = vb - va;

        let cdot = dv;
        let impulse = Vec2::new(
            -(self.mass.x * cdot.x + self.mass_off_diag * cdot.y),
            -(self.mass_off_diag * cdot.x + self.mass.y * cdot.y),
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

        if self.enable_motor {
            let cdot_motor = ang_vel_b - ang_vel_a - self.motor_speed;
            let mut motor_impulse = -cdot_motor * self.motor_mass;
            let old_motor_impulse = self.motor_impulse;
            let max_impulse = self.max_torque * data.dt;
            self.motor_impulse = (self.motor_impulse + motor_impulse).clamp(-max_impulse, max_impulse);
            motor_impulse = self.motor_impulse - old_motor_impulse;

            {
                let body_a = data.bodies.get_mut(self.body_a).unwrap();
                if is_dynamic_a {
                    body_a.angular_velocity -= motor_impulse * inv_inertia_a;
                }
            }
            {
                let body_b = data.bodies.get_mut(self.body_b).unwrap();
                if is_dynamic_b {
                    body_b.angular_velocity += motor_impulse * inv_inertia_b;
                }
            }
        }

        if self.enable_limit {
            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();
            let current_angle = body_b.transform.rotation.angle() - body_a.transform.rotation.angle();
            
            let c = current_angle - self.upper_angle;
            let cdot = body_b.angular_velocity - body_a.angular_velocity;
            
            if c > 0.0 {
                let impulse = -(cdot + c * data.inv_dt) * self.angle_mass;
                let old_impulse = self.angle_impulse;
                self.angle_impulse = (self.angle_impulse + impulse).min(0.0);
                let delta_impulse = self.angle_impulse - old_impulse;

                {
                    let body_a = data.bodies.get_mut(self.body_a).unwrap();
                    if is_dynamic_a {
                        body_a.angular_velocity -= delta_impulse * inv_inertia_a;
                    }
                }
                {
                    let body_b = data.bodies.get_mut(self.body_b).unwrap();
                    if is_dynamic_b {
                        body_b.angular_velocity += delta_impulse * inv_inertia_b;
                    }
                }
            } else {
                let c = current_angle - self.lower_angle;
                if c < 0.0 {
                    let impulse = -(cdot + c * data.inv_dt) * self.angle_mass;
                    let old_impulse = self.angle_impulse;
                    self.angle_impulse = (self.angle_impulse + impulse).max(0.0);
                    let delta_impulse = self.angle_impulse - old_impulse;

                    {
                        let body_a = data.bodies.get_mut(self.body_a).unwrap();
                        if is_dynamic_a {
                            body_a.angular_velocity -= delta_impulse * inv_inertia_a;
                        }
                    }
                    {
                        let body_b = data.bodies.get_mut(self.body_b).unwrap();
                        if is_dynamic_b {
                            body_b.angular_velocity += delta_impulse * inv_inertia_b;
                        }
                    }
                }
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
                body_a.transform.position -= impulse * inv_mass_a * (1.0 + BAUMGARTE_POSITION);
                let angle_a = body_a.transform.rotation.angle() - ra.cross(impulse) * inv_inertia_a * (1.0 + BAUMGARTE_POSITION);
                body_a.transform.rotation.set_angle(angle_a);
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.transform.position += impulse * inv_mass_b * (1.0 + BAUMGARTE_POSITION);
                let angle_b = body_b.transform.rotation.angle() + rb.cross(impulse) * inv_inertia_b * (1.0 + BAUMGARTE_POSITION);
                body_b.transform.rotation.set_angle(angle_b);
            }
        }

        c.length() < 0.001
    }
}

impl Joint for RevoluteJoint {
    fn set_motor_enabled(&mut self, enabled: bool) {
        self.enable_motor = enabled;
    }

    fn set_motor_speed(&mut self, speed: f32) {
        self.motor_speed = speed;
    }

    fn set_max_motor_force(&mut self, force: f32) {
        self.max_torque = force;
    }

    fn set_limits_enabled(&mut self, enabled: bool) {
        self.enable_limit = enabled;
    }

    fn set_limits(&mut self, lower: f32, upper: f32) {
        self.lower_angle = lower;
        self.upper_angle = upper;
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
                body_a.transform.position += jacobian.linear_a * impulse * inv_mass_a * (1.0 + BAUMGARTE_POSITION);
                let angle_a = body_a.transform.rotation.angle() + jacobian.angular_a * impulse * inv_inertia_a * (1.0 + BAUMGARTE_POSITION);
                body_a.transform.rotation.set_angle(angle_a);
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.transform.position += jacobian.linear_b * impulse * inv_mass_b * (1.0 + BAUMGARTE_POSITION);
                let angle_b = body_b.transform.rotation.angle() + jacobian.angular_b * impulse * inv_inertia_b * (1.0 + BAUMGARTE_POSITION);
                body_b.transform.rotation.set_angle(angle_b);
            }
        }

        c.abs() < 0.001
    }
}

impl Joint for DistanceJoint {}

#[derive(Clone, Debug)]
pub struct PrismaticJoint {
    pub body_a: BodyHandle,
    pub body_b: BodyHandle,
    pub local_anchor_a: Vec2,
    pub local_anchor_b: Vec2,
    pub local_axis: Vec2,
    pub impulse: Vec2,
    pub mass: Vec2,
    pub lower_limit: f32,
    pub upper_limit: f32,
    pub motor_speed: f32,
    pub max_force: f32,
    pub enable_limit: bool,
    pub enable_motor: bool,
    pub limit_impulse: f32,
    pub limit_mass: f32,
    pub motor_impulse: f32,
    pub motor_mass: f32,
}

impl PrismaticJoint {
    pub fn new(body_a: BodyHandle, body_b: BodyHandle, anchor: Vec2, axis: Vec2, ta: &Transform, tb: &Transform) -> Self {
        let local_anchor_a = ta.rotation.inv_mul_vec(anchor - ta.position);
        let local_anchor_b = tb.rotation.inv_mul_vec(anchor - tb.position);
        let local_axis = ta.rotation.inv_mul_vec(axis.normalize());

        PrismaticJoint {
            body_a,
            body_b,
            local_anchor_a,
            local_anchor_b,
            local_axis,
            impulse: Vec2::ZERO,
            mass: Vec2::ZERO,
            lower_limit: f32::NEG_INFINITY,
            upper_limit: f32::INFINITY,
            motor_speed: 0.0,
            max_force: 0.0,
            enable_limit: false,
            enable_motor: false,
            limit_impulse: 0.0,
            limit_mass: 0.0,
            motor_impulse: 0.0,
            motor_mass: 0.0,
        }
    }

    pub fn with_limit(mut self, lower: f32, upper: f32) -> Self {
        self.lower_limit = lower;
        self.upper_limit = upper;
        self.enable_limit = true;
        self
    }

    pub fn with_motor(mut self, speed: f32, max_force: f32) -> Self {
        self.motor_speed = speed;
        self.max_force = max_force;
        self.enable_motor = true;
        self
    }
}

impl Constraint for PrismaticJoint {
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
        let axis = body_a.transform.rotation.mul_vec(self.local_axis);
        let perp = axis.perp();

        let m = body_a.inv_mass + body_b.inv_mass;
        let i_a = body_a.inv_inertia;
        let i_b = body_b.inv_inertia;

        let cross_a_perp = ra.cross(perp);
        let cross_b_perp = rb.cross(perp);
        let cross_a_axis = ra.cross(axis);
        let cross_b_axis = rb.cross(axis);

        let k_perp = m + cross_a_perp * cross_a_perp * i_a + cross_b_perp * cross_b_perp * i_b;
        let k_axis = m + cross_a_axis * cross_a_axis * i_a + cross_b_axis * cross_b_axis * i_b;

        self.mass.x = if k_perp > f32::EPSILON { 1.0 / k_perp } else { 0.0 };
        self.mass.y = if k_axis > f32::EPSILON { 1.0 / k_axis } else { 0.0 };

        self.limit_mass = self.mass.y;
        self.motor_mass = self.mass.y;

        self.impulse *= 0.0;
        self.limit_impulse *= 0.0;
        self.motor_impulse *= 0.0;
    }

    fn solve_velocity(&mut self, data: &mut ConstraintSolverData) {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let ra = body_a.transform.rotation.mul_vec(self.local_anchor_a);
        let rb = body_b.transform.rotation.mul_vec(self.local_anchor_b);
        let axis = body_a.transform.rotation.mul_vec(self.local_axis);
        let perp = axis.perp();
        let inv_mass_a = body_a.inv_mass;
        let inv_inertia_a = body_a.inv_inertia;
        let inv_mass_b = body_b.inv_mass;
        let inv_inertia_b = body_b.inv_inertia;
        let is_dynamic_a = body_a.is_dynamic();
        let is_dynamic_b = body_b.is_dynamic();

        let va = body_a.linear_velocity + ra.perp() * body_a.angular_velocity;
        let vb = body_b.linear_velocity + rb.perp() * body_b.angular_velocity;
        let dv = vb - va;

        let cdot_perp = dv.dot(perp);
        let impulse_perp = -cdot_perp * self.mass.x;

        self.impulse.x += impulse_perp;

        {
            let body_a = data.bodies.get_mut(self.body_a).unwrap();
            if is_dynamic_a {
                body_a.linear_velocity -= perp * impulse_perp * inv_mass_a;
                body_a.angular_velocity -= ra.cross(perp) * impulse_perp * inv_inertia_a;
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.linear_velocity += perp * impulse_perp * inv_mass_b;
                body_b.angular_velocity += rb.cross(perp) * impulse_perp * inv_inertia_b;
            }
        }

        if self.enable_motor {
            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();
            let va = body_a.linear_velocity + ra.perp() * body_a.angular_velocity;
            let vb = body_b.linear_velocity + rb.perp() * body_b.angular_velocity;
            let dv = vb - va;
            let cdot_motor = dv.dot(axis) - self.motor_speed;
            let mut motor_impulse = -cdot_motor * self.motor_mass;
            let old_motor_impulse = self.motor_impulse;
            let max_impulse = self.max_force * data.dt;
            self.motor_impulse = (self.motor_impulse + motor_impulse).clamp(-max_impulse, max_impulse);
            motor_impulse = self.motor_impulse - old_motor_impulse;

            {
                let body_a = data.bodies.get_mut(self.body_a).unwrap();
                if is_dynamic_a {
                    body_a.linear_velocity -= axis * motor_impulse * inv_mass_a;
                    body_a.angular_velocity -= ra.cross(axis) * motor_impulse * inv_inertia_a;
                }
            }
            {
                let body_b = data.bodies.get_mut(self.body_b).unwrap();
                if is_dynamic_b {
                    body_b.linear_velocity += axis * motor_impulse * inv_mass_b;
                    body_b.angular_velocity += rb.cross(axis) * motor_impulse * inv_inertia_b;
                }
            }
        }

        if self.enable_limit {
            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();
            let pa = body_a.transform.position + ra;
            let pb = body_b.transform.position + rb;
            let current_translation = (pb - pa).dot(axis);
            let va = body_a.linear_velocity + ra.perp() * body_a.angular_velocity;
            let vb = body_b.linear_velocity + rb.perp() * body_b.angular_velocity;
            let cdot = (vb - va).dot(axis);

            if current_translation >= self.upper_limit {
                let c = current_translation - self.upper_limit;
                let impulse = -(cdot + c * data.inv_dt) * self.limit_mass;
                let old_impulse = self.limit_impulse;
                self.limit_impulse = (self.limit_impulse + impulse).min(0.0);
                let delta_impulse = self.limit_impulse - old_impulse;

                {
                    let body_a = data.bodies.get_mut(self.body_a).unwrap();
                    if is_dynamic_a {
                        body_a.linear_velocity -= axis * delta_impulse * inv_mass_a;
                        body_a.angular_velocity -= ra.cross(axis) * delta_impulse * inv_inertia_a;
                    }
                }
                {
                    let body_b = data.bodies.get_mut(self.body_b).unwrap();
                    if is_dynamic_b {
                        body_b.linear_velocity += axis * delta_impulse * inv_mass_b;
                        body_b.angular_velocity += rb.cross(axis) * delta_impulse * inv_inertia_b;
                    }
                }
            } else if current_translation <= self.lower_limit {
                let c = current_translation - self.lower_limit;
                let impulse = -(cdot + c * data.inv_dt) * self.limit_mass;
                let old_impulse = self.limit_impulse;
                self.limit_impulse = (self.limit_impulse + impulse).max(0.0);
                let delta_impulse = self.limit_impulse - old_impulse;

                {
                    let body_a = data.bodies.get_mut(self.body_a).unwrap();
                    if is_dynamic_a {
                        body_a.linear_velocity -= axis * delta_impulse * inv_mass_a;
                        body_a.angular_velocity -= ra.cross(axis) * delta_impulse * inv_inertia_a;
                    }
                }
                {
                    let body_b = data.bodies.get_mut(self.body_b).unwrap();
                    if is_dynamic_b {
                        body_b.linear_velocity += axis * delta_impulse * inv_mass_b;
                        body_b.angular_velocity += rb.cross(axis) * delta_impulse * inv_inertia_b;
                    }
                }
            }
        }
    }

    fn solve_position(&mut self, data: &mut ConstraintSolverData) -> bool {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let ra = body_a.transform.rotation.mul_vec(self.local_anchor_a);
        let rb = body_b.transform.rotation.mul_vec(self.local_anchor_b);
        let axis = body_a.transform.rotation.mul_vec(self.local_axis);
        let perp = axis.perp();
        let inv_mass_a = body_a.inv_mass;
        let inv_inertia_a = body_a.inv_inertia;
        let inv_mass_b = body_b.inv_mass;
        let inv_inertia_b = body_b.inv_inertia;
        let is_dynamic_a = body_a.is_dynamic();
        let is_dynamic_b = body_b.is_dynamic();

        let pa = body_a.transform.position + ra;
        let pb = body_b.transform.position + rb;
        let c = pb - pa;
        let c_perp = c.dot(perp);

        let cross_a_perp = ra.cross(perp);
        let cross_b_perp = rb.cross(perp);

        let k_perp = inv_mass_a + inv_mass_b
            + cross_a_perp * cross_a_perp * inv_inertia_a
            + cross_b_perp * cross_b_perp * inv_inertia_b;

        let impulse_perp = if k_perp > f32::EPSILON { -c_perp / k_perp } else { 0.0 };

        {
            let body_a = data.bodies.get_mut(self.body_a).unwrap();
            if is_dynamic_a {
                body_a.transform.position -= perp * impulse_perp * inv_mass_a * (1.0 + BAUMGARTE_POSITION);
                let angle_a = body_a.transform.rotation.angle() - ra.cross(perp) * impulse_perp * inv_inertia_a * (1.0 + BAUMGARTE_POSITION);
                body_a.transform.rotation.set_angle(angle_a);
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.transform.position += perp * impulse_perp * inv_mass_b * (1.0 + BAUMGARTE_POSITION);
                let angle_b = body_b.transform.rotation.angle() + rb.cross(perp) * impulse_perp * inv_inertia_b * (1.0 + BAUMGARTE_POSITION);
                body_b.transform.rotation.set_angle(angle_b);
            }
        }

        c_perp.abs() < 0.001
    }
}

impl Joint for PrismaticJoint {
    fn set_motor_enabled(&mut self, enabled: bool) {
        self.enable_motor = enabled;
    }

    fn set_motor_speed(&mut self, speed: f32) {
        self.motor_speed = speed;
    }

    fn set_max_motor_force(&mut self, force: f32) {
        self.max_force = force;
    }

    fn set_limits_enabled(&mut self, enabled: bool) {
        self.enable_limit = enabled;
    }

    fn set_limits(&mut self, lower: f32, upper: f32) {
        self.lower_limit = lower;
        self.upper_limit = upper;
    }
}

#[derive(Clone, Debug)]
pub struct WeldJoint {
    pub body_a: BodyHandle,
    pub body_b: BodyHandle,
    pub local_anchor_a: Vec2,
    pub local_anchor_b: Vec2,
    pub ref_angle: f32,
    pub impulse: Vec2,
    pub angular_impulse: f32,
    pub mass: Vec2,
    pub mass_off_diag: f32,
    pub angular_mass: f32,
}

impl WeldJoint {
    pub fn new(body_a: BodyHandle, body_b: BodyHandle, anchor: Vec2, ta: &Transform, tb: &Transform) -> Self {
        let local_anchor_a = ta.rotation.inv_mul_vec(anchor - ta.position);
        let local_anchor_b = tb.rotation.inv_mul_vec(anchor - tb.position);
        let ref_angle = tb.rotation.angle() - ta.rotation.angle();

        WeldJoint {
            body_a,
            body_b,
            local_anchor_a,
            local_anchor_b,
            ref_angle,
            impulse: Vec2::ZERO,
            angular_impulse: 0.0,
            mass: Vec2::ZERO,
            mass_off_diag: 0.0,
            angular_mass: 0.0,
        }
    }
}

impl Constraint for WeldJoint {
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
            self.mass_off_diag = -k12 * inv_det;
        } else {
            self.mass = Vec2::ZERO;
            self.mass_off_diag = 0.0;
        }

        self.angular_mass = i_a + i_b;
        if self.angular_mass > f32::EPSILON {
            self.angular_mass = 1.0 / self.angular_mass;
        }

        self.impulse *= 0.0;
        self.angular_impulse *= 0.0;
    }

    fn solve_velocity(&mut self, data: &mut ConstraintSolverData) {
        let (ra, rb, inv_mass_a, inv_inertia_a, inv_mass_b, inv_inertia_b, is_dynamic_a, is_dynamic_b, ang_vel_a, ang_vel_b, lin_vel_a, lin_vel_b) = {
            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();
            (
                body_a.transform.rotation.mul_vec(self.local_anchor_a),
                body_b.transform.rotation.mul_vec(self.local_anchor_b),
                body_a.inv_mass,
                body_a.inv_inertia,
                body_b.inv_mass,
                body_b.inv_inertia,
                body_a.is_dynamic(),
                body_b.is_dynamic(),
                body_a.angular_velocity,
                body_b.angular_velocity,
                body_a.linear_velocity,
                body_b.linear_velocity,
            )
        };

        let va = lin_vel_a + ra.perp() * ang_vel_a;
        let vb = lin_vel_b + rb.perp() * ang_vel_b;
        let dv = vb - va;

        let cdot = dv;
        let impulse = Vec2::new(
            -(self.mass.x * cdot.x + self.mass_off_diag * cdot.y),
            -(self.mass_off_diag * cdot.x + self.mass.y * cdot.y),
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

        let (ang_vel_a, ang_vel_b) = {
            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();
            (body_a.angular_velocity, body_b.angular_velocity)
        };

        let cdot_angular = ang_vel_b - ang_vel_a;
        let angular_impulse = -cdot_angular * self.angular_mass;
        self.angular_impulse += angular_impulse;

        {
            let body_a = data.bodies.get_mut(self.body_a).unwrap();
            if is_dynamic_a {
                body_a.angular_velocity -= angular_impulse * inv_inertia_a;
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.angular_velocity += angular_impulse * inv_inertia_b;
            }
        }
    }

    fn solve_position(&mut self, data: &mut ConstraintSolverData) -> bool {
        let (ra, rb, inv_mass_a, inv_inertia_a, inv_mass_b, inv_inertia_b, is_dynamic_a, is_dynamic_b, pos_a, pos_b) = {
            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();
            (
                body_a.transform.rotation.mul_vec(self.local_anchor_a),
                body_b.transform.rotation.mul_vec(self.local_anchor_b),
                body_a.inv_mass,
                body_a.inv_inertia,
                body_b.inv_mass,
                body_b.inv_inertia,
                body_a.is_dynamic(),
                body_b.is_dynamic(),
                body_a.transform.position,
                body_b.transform.position,
            )
        };

        let pa = pos_a + ra;
        let pb = pos_b + rb;
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
                body_a.transform.position -= impulse * inv_mass_a * (1.0 + BAUMGARTE_POSITION);
                let angle_a = body_a.transform.rotation.angle() - ra.cross(impulse) * inv_inertia_a * (1.0 + BAUMGARTE_POSITION);
                body_a.transform.rotation.set_angle(angle_a);
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                body_b.transform.position += impulse * inv_mass_b * (1.0 + BAUMGARTE_POSITION);
                let angle_b = body_b.transform.rotation.angle() + rb.cross(impulse) * inv_inertia_b * (1.0 + BAUMGARTE_POSITION);
                body_b.transform.rotation.set_angle(angle_b);
            }
        }

        let (angle_a, angle_b) = {
            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();
            (
                body_a.transform.rotation.angle(),
                body_b.transform.rotation.angle(),
            )
        };

        let angular_error = (angle_b - angle_a) - self.ref_angle;
        let angular_impulse = if inv_inertia_a + inv_inertia_b > f32::EPSILON {
            -angular_error / (inv_inertia_a + inv_inertia_b)
        } else {
            0.0
        };

        {
            let body_a = data.bodies.get_mut(self.body_a).unwrap();
            if is_dynamic_a {
                let angle_a = body_a.transform.rotation.angle() - angular_impulse * inv_inertia_a * (1.0 + BAUMGARTE_POSITION);
                body_a.transform.rotation.set_angle(angle_a);
            }
        }
        {
            let body_b = data.bodies.get_mut(self.body_b).unwrap();
            if is_dynamic_b {
                let angle_b = body_b.transform.rotation.angle() + angular_impulse * inv_inertia_b * (1.0 + BAUMGARTE_POSITION);
                body_b.transform.rotation.set_angle(angle_b);
            }
        }

        c.length() < 0.001 && angular_error.abs() < 0.001
    }
}

impl Joint for WeldJoint {}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_relative_eq;
    use physics_types::{Body, BodyType, Material, Shape, Circle};
    use physics_math::Rot2;
    use slotmap::SlotMap;

    fn create_test_bodies() -> (SlotMap<BodyHandle, Body>, BodyHandle, BodyHandle, Transform, Transform) {
        let mut bodies = SlotMap::with_key();
        let ta = Transform::new(Vec2::new(-1.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(1.0, 0.0), Rot2::new(0.0));
        let shape = Shape::Circle(Circle::new(0.5));
        
        let handle_a = bodies.insert_with_key(|handle| Body::new(
            handle,
            shape.clone(),
            ta.position,
            0.0,
            BodyType::Dynamic,
            Material::default(),
        ));
        let handle_b = bodies.insert_with_key(|handle| Body::new(
            handle,
            shape.clone(),
            tb.position,
            0.0,
            BodyType::Dynamic,
            Material::default(),
        ));
        
        (bodies, handle_a, handle_b, ta, tb)
    }

    #[test]
    fn test_revolute_joint_creation() {
        let (_, handle_a, handle_b, ta, tb) = create_test_bodies();
        let anchor = Vec2::new(0.0, 0.0);
        let joint = RevoluteJoint::new(handle_a, handle_b, anchor, &ta, &tb);
        
        assert_eq!(joint.body_a, handle_a);
        assert_eq!(joint.body_b, handle_b);
        assert_eq!(joint.impulse, Vec2::ZERO);
    }

    #[test]
    fn test_distance_joint_creation() {
        let (_, handle_a, handle_b, ta, tb) = create_test_bodies();
        let anchor_a = Vec2::new(-1.0, 0.0);
        let anchor_b = Vec2::new(1.0, 0.0);
        let joint = DistanceJoint::new(handle_a, handle_b, anchor_a, anchor_b, &ta, &tb);
        
        assert_eq!(joint.body_a, handle_a);
        assert_eq!(joint.body_b, handle_b);
        assert_relative_eq!(joint.length, 2.0, epsilon = 1e-6);
    }

    #[test]
    fn test_prismatic_joint_creation() {
        let (_, handle_a, handle_b, ta, tb) = create_test_bodies();
        let anchor = Vec2::new(0.0, 0.0);
        let axis = Vec2::new(1.0, 0.0);
        let joint = PrismaticJoint::new(handle_a, handle_b, anchor, axis, &ta, &tb);
        
        assert_eq!(joint.body_a, handle_a);
        assert_eq!(joint.body_b, handle_b);
        assert_eq!(joint.impulse, Vec2::ZERO);
    }

    #[test]
    fn test_weld_joint_creation() {
        let (_, handle_a, handle_b, ta, tb) = create_test_bodies();
        let anchor = Vec2::new(0.0, 0.0);
        let joint = WeldJoint::new(handle_a, handle_b, anchor, &ta, &tb);
        
        assert_eq!(joint.body_a, handle_a);
        assert_eq!(joint.body_b, handle_b);
        assert_eq!(joint.impulse, Vec2::ZERO);
        assert_eq!(joint.angular_impulse, 0.0);
    }

    #[test]
    fn test_revolute_joint_with_motor_and_limit() {
        let (_, handle_a, handle_b, ta, tb) = create_test_bodies();
        let anchor = Vec2::new(0.0, 0.0);
        let joint = RevoluteJoint::new(handle_a, handle_b, anchor, &ta, &tb)
            .with_angle_limit(-1.0, 1.0)
            .with_motor(2.0, 100.0);
        
        assert!(joint.enable_limit);
        assert!(joint.enable_motor);
        assert_relative_eq!(joint.lower_angle, -1.0, epsilon = 1e-6);
        assert_relative_eq!(joint.upper_angle, 1.0, epsilon = 1e-6);
        assert_relative_eq!(joint.motor_speed, 2.0, epsilon = 1e-6);
        assert_relative_eq!(joint.max_torque, 100.0, epsilon = 1e-6);
    }

    #[test]
    fn test_prismatic_joint_with_motor_and_limit() {
        let (_, handle_a, handle_b, ta, tb) = create_test_bodies();
        let anchor = Vec2::new(0.0, 0.0);
        let axis = Vec2::new(1.0, 0.0);
        let joint = PrismaticJoint::new(handle_a, handle_b, anchor, axis, &ta, &tb)
            .with_limit(-2.0, 2.0)
            .with_motor(1.0, 50.0);
        
        assert!(joint.enable_limit);
        assert!(joint.enable_motor);
        assert_relative_eq!(joint.lower_limit, -2.0, epsilon = 1e-6);
        assert_relative_eq!(joint.upper_limit, 2.0, epsilon = 1e-6);
        assert_relative_eq!(joint.motor_speed, 1.0, epsilon = 1e-6);
        assert_relative_eq!(joint.max_force, 50.0, epsilon = 1e-6);
    }
}
