use physics_collision::ContactManifold;
use physics_types::{Body, BodyHandle, Material};
use physics_math::Vec2;

use crate::constraint::{Constraint, ConstraintSolverData, Jacobian};

const PENETRATION_SLOP: f32 = 0.05;
const BAUMGARTE_COEFFICIENT: f32 = 0.2;

#[derive(Clone, Debug)]
pub struct ContactConstraintPoint {
    pub local_point_a: Vec2,
    pub local_point_b: Vec2,
    pub normal_impulse: f32,
    pub tangent_impulse: f32,
    pub normal_mass: f32,
    pub tangent_mass: f32,
    pub bias: f32,
}

#[derive(Clone, Debug)]
pub struct ContactConstraint {
    pub body_a: BodyHandle,
    pub body_b: BodyHandle,
    pub normal: Vec2,
    pub tangent: Vec2,
    pub points: [ContactConstraintPoint; 2],
    pub point_count: usize,
    pub restitution: f32,
    pub friction: f32,
}

impl ContactConstraint {
    pub fn new(manifold: &ContactManifold, body_a: &Body, body_b: &Body) -> Self {
        let mut points = [
            ContactConstraintPoint {
                local_point_a: Vec2::ZERO,
                local_point_b: Vec2::ZERO,
                normal_impulse: 0.0,
                tangent_impulse: 0.0,
                normal_mass: 0.0,
                tangent_mass: 0.0,
                bias: 0.0,
            },
            ContactConstraintPoint {
                local_point_a: Vec2::ZERO,
                local_point_b: Vec2::ZERO,
                normal_impulse: 0.0,
                tangent_impulse: 0.0,
                normal_mass: 0.0,
                tangent_mass: 0.0,
                bias: 0.0,
            },
        ];

        let normal = manifold.normal;
        let tangent = normal.perp();

        let restitution = Material::combine_restitution(
            body_a.material.restitution,
            body_b.material.restitution,
        );
        let friction = Material::combine_friction(
            body_a.material.dynamic_friction,
            body_b.material.dynamic_friction,
        );

        let point_count = manifold.point_count.min(2);

        for i in 0..point_count {
            let cp = &manifold.points[i];
            let point = cp.point;

            let local_a = body_a.transform.rotation.inv_mul_vec(point - body_a.transform.position);
            let local_b = body_b.transform.rotation.inv_mul_vec(point - body_b.transform.position);

            points[i].local_point_a = local_a;
            points[i].local_point_b = local_b;
        }

        ContactConstraint {
            body_a: manifold.body_a,
            body_b: manifold.body_b,
            normal,
            tangent,
            points,
            point_count,
            restitution,
            friction,
        }
    }

    fn get_point_velocity(body: &Body, local_point: Vec2) -> Vec2 {
        let world_point = body.transform.position + body.transform.rotation.mul_vec(local_point);
        let r = world_point - body.transform.position;
        body.linear_velocity + r.perp() * body.angular_velocity
    }
}

impl Constraint for ContactConstraint {
    fn body_a(&self) -> BodyHandle {
        self.body_a
    }

    fn body_b(&self) -> BodyHandle {
        self.body_b
    }

    fn prepare(&mut self, data: &ConstraintSolverData) {
        let body_a = data.bodies.get(self.body_a).unwrap();
        let body_b = data.bodies.get(self.body_b).unwrap();

        let normal = self.normal;
        let tangent = self.tangent;

        for i in 0..self.point_count {
            let cp = &mut self.points[i];

            let world_a = body_a.transform.position + body_a.transform.rotation.mul_vec(cp.local_point_a);
            let world_b = body_b.transform.position + body_b.transform.rotation.mul_vec(cp.local_point_b);

            let ra = world_a - body_a.transform.position;
            let rb = world_b - body_b.transform.position;

            let normal_jacobian = Jacobian::new(
                -normal,
                -ra.cross(normal),
                normal,
                rb.cross(normal),
            );

            let tangent_jacobian = Jacobian::new(
                -tangent,
                -ra.cross(tangent),
                tangent,
                rb.cross(tangent),
            );

            cp.normal_mass = normal_jacobian.compute_effective_mass(body_a, body_b);
            cp.tangent_mass = tangent_jacobian.compute_effective_mass(body_a, body_b);

            let va = Self::get_point_velocity(body_a, cp.local_point_a);
            let vb = Self::get_point_velocity(body_b, cp.local_point_b);
            let dv = vb - va;
            let normal_velocity = dv.dot(normal);

            let penetration = (world_b - world_a).dot(normal);

            cp.bias = if normal_velocity < -1.0 {
                BAUMGARTE_COEFFICIENT * data.inv_dt * (penetration + PENETRATION_SLOP).min(0.0)
                    - self.restitution * normal_velocity
            } else {
                BAUMGARTE_COEFFICIENT * data.inv_dt * (penetration + PENETRATION_SLOP).min(0.0)
            };

            if cp.normal_mass > f32::EPSILON {
                cp.normal_mass = 1.0 / cp.normal_mass;
            }
            if cp.tangent_mass > f32::EPSILON {
                cp.tangent_mass = 1.0 / cp.tangent_mass;
            }
        }
    }

    fn solve_velocity(&mut self, data: &mut ConstraintSolverData) {
        let normal = self.normal;
        let tangent = self.tangent;
        let friction = self.friction;

        for i in 0..self.point_count {
            let cp = &mut self.points[i];

            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();

            let ra_world = body_a.transform.rotation.mul_vec(cp.local_point_a);
            let rb_world = body_b.transform.rotation.mul_vec(cp.local_point_b);

            let va = body_a.linear_velocity + ra_world.perp() * body_a.angular_velocity;
            let vb = body_b.linear_velocity + rb_world.perp() * body_b.angular_velocity;
            let dv = vb - va;

            let normal_jacobian = Jacobian::new(
                -normal,
                -ra_world.cross(normal),
                normal,
                rb_world.cross(normal),
            );

            let lambda = -(dv.dot(normal) + cp.bias) * cp.normal_mass;
            let new_impulse = (cp.normal_impulse + lambda).max(0.0);
            let delta_impulse = new_impulse - cp.normal_impulse;
            cp.normal_impulse = new_impulse;

            {
                let body_a = data.bodies.get_mut(self.body_a).unwrap();
                if body_a.is_dynamic() {
                    body_a.linear_velocity += normal_jacobian.linear_a * delta_impulse * body_a.inv_mass;
                    body_a.angular_velocity += normal_jacobian.angular_a * delta_impulse * body_a.inv_inertia;
                }
            }
            {
                let body_b = data.bodies.get_mut(self.body_b).unwrap();
                if body_b.is_dynamic() {
                    body_b.linear_velocity += normal_jacobian.linear_b * delta_impulse * body_b.inv_mass;
                    body_b.angular_velocity += normal_jacobian.angular_b * delta_impulse * body_b.inv_inertia;
                }
            }

            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();

            let ra_world = body_a.transform.rotation.mul_vec(cp.local_point_a);
            let rb_world = body_b.transform.rotation.mul_vec(cp.local_point_b);

            let va = body_a.linear_velocity + ra_world.perp() * body_a.angular_velocity;
            let vb = body_b.linear_velocity + rb_world.perp() * body_b.angular_velocity;
            let dv = vb - va;

            let tangent_jacobian = Jacobian::new(
                -tangent,
                -ra_world.cross(tangent),
                tangent,
                rb_world.cross(tangent),
            );

            let max_friction_impulse = friction * cp.normal_impulse;
            let lambda_t = -dv.dot(tangent) * cp.tangent_mass;
            let new_tangent_impulse = (cp.tangent_impulse + lambda_t)
                .clamp(-max_friction_impulse, max_friction_impulse);
            let delta_tangent_impulse = new_tangent_impulse - cp.tangent_impulse;
            cp.tangent_impulse = new_tangent_impulse;

            {
                let body_a = data.bodies.get_mut(self.body_a).unwrap();
                if body_a.is_dynamic() {
                    body_a.linear_velocity += tangent_jacobian.linear_a * delta_tangent_impulse * body_a.inv_mass;
                    body_a.angular_velocity += tangent_jacobian.angular_a * delta_tangent_impulse * body_a.inv_inertia;
                }
            }
            {
                let body_b = data.bodies.get_mut(self.body_b).unwrap();
                if body_b.is_dynamic() {
                    body_b.linear_velocity += tangent_jacobian.linear_b * delta_tangent_impulse * body_b.inv_mass;
                    body_b.angular_velocity += tangent_jacobian.angular_b * delta_tangent_impulse * body_b.inv_inertia;
                }
            }
        }
    }

    fn solve_position(&mut self, data: &mut ConstraintSolverData) -> bool {
        let mut min_separation: f32 = 0.0;

        for i in 0..self.point_count {
            let cp = &mut self.points[i];

            let body_a = data.bodies.get(self.body_a).unwrap();
            let body_b = data.bodies.get(self.body_b).unwrap();

            let world_a = body_a.transform.position + body_a.transform.rotation.mul_vec(cp.local_point_a);
            let world_b = body_b.transform.position + body_b.transform.rotation.mul_vec(cp.local_point_b);

            let separation = (world_b - world_a).dot(self.normal);
            let penetration = separation + PENETRATION_SLOP;

            min_separation = min_separation.min(separation);

            if penetration < 0.0 {
                let normal = self.normal;
                let ra = world_a - body_a.transform.position;
                let rb = world_b - body_b.transform.position;

                let normal_jacobian = Jacobian::new(
                    -normal,
                    -ra.cross(normal),
                    normal,
                    rb.cross(normal),
                );

                let mass = normal_jacobian.compute_effective_mass(body_a, body_b);
                let impulse = if mass > f32::EPSILON {
                    -penetration / mass * (1.0 + BAUMGARTE_COEFFICIENT)
                } else {
                    0.0
                };

                {
                    let body_a = data.bodies.get_mut(self.body_a).unwrap();
                    if body_a.is_dynamic() {
                        body_a.transform.position += normal_jacobian.linear_a * impulse * body_a.inv_mass;
                        let new_angle = body_a.transform.rotation.angle()
                            + normal_jacobian.angular_a * impulse * body_a.inv_inertia;
                        body_a.transform.rotation.set_angle(new_angle);
                    }
                }
                {
                    let body_b = data.bodies.get_mut(self.body_b).unwrap();
                    if body_b.is_dynamic() {
                        body_b.transform.position += normal_jacobian.linear_b * impulse * body_b.inv_mass;
                        let new_angle = body_b.transform.rotation.angle()
                            + normal_jacobian.angular_b * impulse * body_b.inv_inertia;
                        body_b.transform.rotation.set_angle(new_angle);
                    }
                }
            }
        }

        min_separation > -PENETRATION_SLOP
    }
}
