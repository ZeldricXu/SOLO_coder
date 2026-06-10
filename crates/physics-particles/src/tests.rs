#[cfg(test)]
mod tests {
    use approx::assert_abs_diff_eq;

    use crate::fluid::{FluidParams, FluidSystem};
    use crate::particle::Particle;
    use crate::solver::ParticleSolver;
    use physics_math::Vec2;

    #[test]
    fn test_particle_integration() {
        let mut p = Particle::new(Vec2::new(0.0, 0.0), 1.0, 0.1);
        p.velocity = Vec2::new(1.0, 0.0);

        let dt = 1.0;
        p.integrate(dt);

        assert_abs_diff_eq!(p.position.x, 1.0);
        assert_abs_diff_eq!(p.velocity.x, 1.0);
    }

    #[test]
    fn test_particle_force() {
        let mut p = Particle::new(Vec2::new(0.0, 0.0), 2.0, 0.1);
        p.apply_force(Vec2::new(4.0, 0.0));

        let dt = 1.0;
        p.integrate(dt);

        assert_abs_diff_eq!(p.acceleration.x, 2.0);
        assert_abs_diff_eq!(p.velocity.x, 2.0);
        assert_abs_diff_eq!(p.position.x, 2.0);
    }

    #[test]
    fn test_particle_solver_gravity() {
        let solver = ParticleSolver::new(Vec2::new(0.0, -9.81));
        let mut particles = vec![Particle::new(Vec2::new(0.0, 0.0), 1.0, 0.1)];

        solver.apply_gravity(&mut particles);

        assert_abs_diff_eq!(particles[0].force.y, -9.81);
    }

    #[test]
    fn test_fluid_system_creation() {
        let params = FluidParams::default();
        let mut fluid = FluidSystem::new(params);

        for i in 0..10 {
            let x = (i % 5) as f32 * 0.1;
            let y = (i / 5) as f32 * 0.1;
            fluid.add_particle(Particle::new(Vec2::new(x, y), 0.1, 0.05));
        }

        assert_eq!(fluid.particle_count(), 10);
    }

    #[test]
    fn test_particle_solver_simple_step() {
        let solver = ParticleSolver::new(Vec2::new(0.0, -9.81));
        let mut particles = vec![Particle::new(Vec2::new(0.0, 10.0), 1.0, 0.1)];

        let dt = 1.0 / 60.0;
        solver.step_simple(&mut particles, dt);

        assert!(particles[0].position.y < 10.0);
        assert!(particles[0].velocity.y < 0.0);
    }

    #[test]
    fn test_boundary_collision() {
        let solver = ParticleSolver::default();
        let mut particles = vec![Particle::new(Vec2::new(0.0, -10.5), 1.0, 1.0)];
        particles[0].velocity = Vec2::new(0.0, -1.0);

        solver.resolve_collisions_with_bounds(
            &mut particles,
            Vec2::new(-10.0, -10.0),
            Vec2::new(10.0, 10.0),
            1.0,
        );

        assert_abs_diff_eq!(particles[0].position.y, -9.0);
        assert!(particles[0].velocity.y > 0.0);
    }
}
