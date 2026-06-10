use physics_math::Vec2;
use physics_spatial::HashGrid;

use crate::particle::Particle;

pub struct FluidParams {
    pub rest_density: f32,
    pub pressure_stiffness: f32,
    pub viscosity: f32,
    pub smoothing_radius: f32,
    pub gravity: Vec2,
}

impl Default for FluidParams {
    fn default() -> Self {
        FluidParams {
            rest_density: 1000.0,
            pressure_stiffness: 200.0,
            viscosity: 0.1,
            smoothing_radius: 0.1,
            gravity: Vec2::new(0.0, -9.81),
        }
    }
}

pub struct FluidSystem {
    pub particles: Vec<Particle>,
    pub params: FluidParams,
    hash_grid: HashGrid,
}

impl FluidSystem {
    pub fn new(params: FluidParams) -> Self {
        let cell_size = params.smoothing_radius * 2.0;
        FluidSystem {
            particles: Vec::new(),
            params,
            hash_grid: HashGrid::new(cell_size),
        }
    }

    pub fn add_particle(&mut self, particle: Particle) {
        self.particles.push(particle);
    }

    pub fn add_particles(&mut self, particles: Vec<Particle>) {
        self.particles.extend(particles);
    }

    pub fn particle_count(&self) -> usize {
        self.particles.len()
    }

    fn poly6_kernel(r: f32, h: f32) -> f32 {
        if r > h {
            return 0.0;
        }
        let diff = h * h - r * r;
        (315.0 / (64.0 * std::f32::consts::PI * h.powi(9))) * diff.powi(3)
    }

    fn spiky_kernel_gradient(r: Vec2, h: f32) -> Vec2 {
        let r_len = r.length();
        if r_len > h || r_len < f32::EPSILON {
            return Vec2::ZERO;
        }
        let diff = h - r_len;
        let factor = -45.0 / (std::f32::consts::PI * h.powi(6)) * diff * diff;
        r.normalize() * factor
    }

    fn viscosity_kernel_laplacian(r: f32, h: f32) -> f32 {
        if r > h {
            return 0.0;
        }
        45.0 / (std::f32::consts::PI * h.powi(6)) * (h - r)
    }

    pub fn update_hash_grid(&mut self) {
        self.hash_grid.clear();
        for (i, p) in self.particles.iter().enumerate() {
            self.hash_grid.insert(p.position, i);
        }
    }

    pub fn compute_densities(&mut self) {
        let h = self.params.smoothing_radius;

        for i in 0..self.particles.len() {
            let pi = &self.particles[i];
            let mut density: f32 = 0.0;

            let neighbors = self.hash_grid.query(pi.position, h);

            for &j in &neighbors {
                let pj = &self.particles[j];
                let r = (pi.position - pj.position).length();
                density += pj.mass * Self::poly6_kernel(r, h);
            }

            self.particles[i].density = density.max(self.params.rest_density * 0.1);
        }
    }

    pub fn compute_pressures(&mut self) {
        for p in &mut self.particles {
            p.pressure = self.params.pressure_stiffness
                * (p.density - self.params.rest_density).max(0.0);
        }
    }

    pub fn compute_forces(&mut self) {
        let h = self.params.smoothing_radius;
        let viscosity = self.params.viscosity;

        for i in 0..self.particles.len() {
            let pi_pos = self.particles[i].position;
            let pi_vel = self.particles[i].velocity;
            let pi_pressure = self.particles[i].pressure;

            let mut pressure_force = Vec2::ZERO;
            let mut viscosity_force = Vec2::ZERO;

            let neighbors = self.hash_grid.query(pi_pos, h);

            for &j in &neighbors {
                if i == j {
                    continue;
                }

                let pj = &self.particles[j];
                let r_vec = pi_pos - pj.position;
                let r = r_vec.length();

                if r < f32::EPSILON {
                    continue;
                }

                let pressure_term = (pi_pressure + pj.pressure) / (2.0 * pj.density.max(f32::EPSILON));
                pressure_force -= pj.mass * pressure_term * Self::spiky_kernel_gradient(r_vec, h);

                let vel_diff = pj.velocity - pi_vel;
                viscosity_force += viscosity * pj.mass * vel_diff
                    / pj.density.max(f32::EPSILON)
                    * Self::viscosity_kernel_laplacian(r, h);

                let overlap = h - r;
                if overlap > 0.0 {
                    let repulsion = r_vec.normalize() * overlap * 0.5;
                    pressure_force += repulsion * pj.mass;
                }
            }

            self.particles[i].force =
                pressure_force + viscosity_force + self.params.gravity * self.particles[i].mass;
        }
    }

    pub fn integrate(&mut self, dt: f32) {
        for p in &mut self.particles {
            p.integrate(dt);
        }
    }

    pub fn step(&mut self, dt: f32) {
        self.update_hash_grid();
        self.compute_densities();
        self.compute_pressures();
        self.compute_forces();
        self.integrate(dt);
    }
}
