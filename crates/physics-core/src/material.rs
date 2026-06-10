#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Material {
    pub restitution: f32,
    pub static_friction: f32,
    pub dynamic_friction: f32,
    pub density: f32,
}

impl Material {
    pub const DEFAULT: Material = Material {
        restitution: 0.2,
        static_friction: 0.6,
        dynamic_friction: 0.4,
        density: 1.0,
    };

    #[inline]
    pub fn new(restitution: f32, static_friction: f32, dynamic_friction: f32, density: f32) -> Self {
        Material {
            restitution,
            static_friction,
            dynamic_friction,
            density,
        }
    }

    #[inline]
    pub fn with_restitution(mut self, restitution: f32) -> Self {
        self.restitution = restitution;
        self
    }

    #[inline]
    pub fn with_friction(mut self, static_friction: f32, dynamic_friction: f32) -> Self {
        self.static_friction = static_friction;
        self.dynamic_friction = dynamic_friction;
        self
    }

    #[inline]
    pub fn with_density(mut self, density: f32) -> Self {
        self.density = density;
        self
    }

    #[inline]
    pub fn combine_restitution(a: f32, b: f32) -> f32 {
        (a * b).sqrt()
    }

    #[inline]
    pub fn combine_friction(a: f32, b: f32) -> f32 {
        (a * b).sqrt()
    }
}

impl Default for Material {
    fn default() -> Self {
        Material::DEFAULT
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_material_creation() {
        let m = Material::new(0.5, 0.8, 0.6, 2.0);
        assert_abs_diff_eq!(m.restitution, 0.5);
        assert_abs_diff_eq!(m.static_friction, 0.8);
    }

    #[test]
    fn test_combine() {
        assert_abs_diff_eq!(Material::combine_restitution(0.25, 0.25), 0.25);
        assert_abs_diff_eq!(Material::combine_friction(0.36, 0.36), 0.36);
    }
}
