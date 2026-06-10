use physics_math::Vec2;

#[derive(Clone, Debug)]
pub struct Particle {
    pub position: Vec2,
    pub velocity: Vec2,
    pub acceleration: Vec2,
    pub force: Vec2,
    pub density: f32,
    pub pressure: f32,
    pub mass: f32,
    pub radius: f32,
    pub color: (f32, f32, f32, f32),
}

impl Particle {
    pub fn new(position: Vec2, mass: f32, radius: f32) -> Self {
        Particle {
            position,
            velocity: Vec2::ZERO,
            acceleration: Vec2::ZERO,
            force: Vec2::ZERO,
            density: 0.0,
            pressure: 0.0,
            mass,
            radius,
            color: (0.2, 0.5, 0.8, 1.0),
        }
    }

    pub fn with_velocity(mut self, velocity: Vec2) -> Self {
        self.velocity = velocity;
        self
    }

    pub fn with_color(mut self, r: f32, g: f32, b: f32, a: f32) -> Self {
        self.color = (r, g, b, a);
        self
    }

    pub fn apply_force(&mut self, force: Vec2) {
        self.force += force;
    }

    pub fn integrate(&mut self, dt: f32) {
        self.acceleration = self.force / self.mass.max(f32::EPSILON);
        self.velocity += self.acceleration * dt;
        self.position += self.velocity * dt;
        self.force = Vec2::ZERO;
    }
}
