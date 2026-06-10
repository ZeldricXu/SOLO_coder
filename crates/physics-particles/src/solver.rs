use crate::fluid::FluidSystem;
use crate::particle::Particle;
use physics_math::Vec2;

pub struct ParticleSolver {
    pub gravity: Vec2,
    pub damping: f32,
    pub max_velocity: f32,
}

impl Default for ParticleSolver {
    fn default() -> Self {
        ParticleSolver {
            gravity: Vec2::new(0.0, -9.81),
            damping: 0.998,
            max_velocity: 100.0,
        }
    }
}

impl ParticleSolver {
    pub fn new(gravity: Vec2) -> Self {
        ParticleSolver {
            gravity,
            damping: 0.998,
            max_velocity: 100.0,
        }
    }

    pub fn apply_gravity(&self, particles: &mut [Particle]) {
        for p in particles {
            p.apply_force(self.gravity * p.mass);
        }
    }

    pub fn apply_damping(&self, particles: &mut [Particle]) {
        for p in particles {
            p.velocity *= self.damping;
        }
    }

    pub fn clamp_velocities(&self, particles: &mut [Particle]) {
        for p in particles {
            let speed = p.velocity.length();
            if speed > self.max_velocity {
                p.velocity = p.velocity.normalize() * self.max_velocity;
            }
        }
    }

    pub fn resolve_collisions_with_bounds(
        &self,
        particles: &mut [Particle],
        min: Vec2,
        max: Vec2,
        restitution: f32,
    ) {
        for p in particles {
            let r = p.radius;

            if p.position.x - r < min.x {
                p.position.x = min.x + r;
                if p.velocity.x < 0.0 {
                    p.velocity.x *= -restitution;
                }
            } else if p.position.x + r > max.x {
                p.position.x = max.x - r;
                if p.velocity.x > 0.0 {
                    p.velocity.x *= -restitution;
                }
            }

            if p.position.y - r < min.y {
                p.position.y = min.y + r;
                if p.velocity.y < 0.0 {
                    p.velocity.y *= -restitution;
                }
            } else if p.position.y + r > max.y {
                p.position.y = max.y - r;
                if p.velocity.y > 0.0 {
                    p.velocity.y *= -restitution;
                }
            }
        }
    }

    pub fn resolve_particle_collisions(&self, particles: &mut [Particle], restitution: f32) {
        for i in 0..particles.len() {
            for j in (i + 1)..particles.len() {
                let (left, right) = particles.split_at_mut(j);
                let pi = &mut left[i];
                let pj = &mut right[0];

                let r_total = pi.radius + pj.radius;
                let delta = pj.position - pi.position;
                let distance = delta.length();

                if distance < r_total && distance > f32::EPSILON {
                    let normal = delta / distance;
                    let overlap = r_total - distance;

                    let total_mass = pi.mass + pj.mass;
                    let ratio_i = pj.mass / total_mass;
                    let ratio_j = pi.mass / total_mass;

                    pi.position -= normal * overlap * ratio_i;
                    pj.position += normal * overlap * ratio_j;

                    let rel_vel = pj.velocity - pi.velocity;
                    let vel_along_normal = rel_vel.dot(normal);

                    if vel_along_normal > 0.0 {
                        let impulse = -(1.0 + restitution) * vel_along_normal / (1.0 / pi.mass + 1.0 / pj.mass);

                        pi.velocity -= normal * impulse / pi.mass;
                        pj.velocity += normal * impulse / pj.mass;
                    }
                }
            }
        }
    }

    pub fn step_simple(&self, particles: &mut [Particle], dt: f32) {
        self.apply_gravity(particles);
        self.apply_damping(particles);

        for p in particles.iter_mut() {
            p.integrate(dt);
        }

        self.clamp_velocities(particles);
    }

    pub fn step_fluid(&self, fluid: &mut FluidSystem, dt: f32) {
        fluid.step(dt);
        self.clamp_velocities(&mut fluid.particles);
    }
}
