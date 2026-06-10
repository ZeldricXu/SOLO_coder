use serde::{Deserialize, Serialize};

use crate::{Rot2, Vec2};

#[derive(Copy, Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Transform {
    pub position: Vec2,
    pub rotation: Rot2,
}

impl Transform {
    pub const IDENTITY: Transform = Transform {
        position: Vec2::ZERO,
        rotation: Rot2::IDENTITY,
    };

    #[inline]
    pub fn new(position: Vec2, rotation: Rot2) -> Self {
        Transform { position, rotation }
    }

    #[inline]
    pub fn from_position(position: Vec2) -> Self {
        Transform {
            position,
            rotation: Rot2::IDENTITY,
        }
    }

    #[inline]
    pub fn from_angle(angle: f32) -> Self {
        Transform {
            position: Vec2::ZERO,
            rotation: Rot2::new(angle),
        }
    }

    #[inline]
    pub fn mul_transform(&self, other: Transform) -> Transform {
        Transform {
            position: self.mul_vec(other.position),
            rotation: self.rotation * other.rotation,
        }
    }

    #[inline]
    pub fn mul_vec(&self, v: Vec2) -> Vec2 {
        self.rotation.mul_vec(v) + self.position
    }

    #[inline]
    pub fn inv_mul_transform(&self, other: Transform) -> Transform {
        let inv_rot = self.rotation.inverse();
        Transform {
            position: inv_rot.mul_vec(other.position - self.position),
            rotation: inv_rot * other.rotation,
        }
    }

    #[inline]
    pub fn inv_mul_vec(&self, v: Vec2) -> Vec2 {
        self.rotation.inv_mul_vec(v - self.position)
    }

    #[inline]
    pub fn inverse(&self) -> Transform {
        let inv_rot = self.rotation.inverse();
        Transform {
            position: inv_rot.mul_vec(-self.position),
            rotation: inv_rot,
        }
    }

    #[inline]
    pub fn set_identity(&mut self) {
        self.position = Vec2::ZERO;
        self.rotation = Rot2::IDENTITY;
    }

    #[inline]
    pub fn lerp(&self, other: Transform, t: f32) -> Transform {
        Transform {
            position: self.position.lerp(other.position, t),
            rotation: self.rotation.lerp(other.rotation, t),
        }
    }
}

impl std::ops::Mul<Transform> for Transform {
    type Output = Transform;

    #[inline]
    fn mul(self, rhs: Transform) -> Transform {
        self.mul_transform(rhs)
    }
}

impl std::ops::Mul<Vec2> for Transform {
    type Output = Vec2;

    #[inline]
    fn mul(self, rhs: Vec2) -> Vec2 {
        self.mul_vec(rhs)
    }
}

impl Default for Transform {
    fn default() -> Self {
        Transform::IDENTITY
    }
}

impl approx::AbsDiffEq for Transform {
    type Epsilon = f32;

    fn default_epsilon() -> Self::Epsilon {
        f32::EPSILON
    }

    fn abs_diff_eq(&self, other: &Self, epsilon: Self::Epsilon) -> bool {
        self.position.abs_diff_eq(&other.position, epsilon)
            && self.rotation.abs_diff_eq(&other.rotation, epsilon)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_mul_vec() {
        let t = Transform::new(Vec2::new(1.0, 2.0), Rot2::new(std::f32::consts::FRAC_PI_2));
        let v = Vec2::new(1.0, 0.0);
        let result = t * v;
        assert_abs_diff_eq!(result, Vec2::new(1.0, 3.0), epsilon = 1e-6);
    }

    #[test]
    fn test_inverse() {
        let t = Transform::new(Vec2::new(3.0, 4.0), Rot2::new(std::f32::consts::FRAC_PI_4));
        let inv = t.inverse();
        let result = t * inv;
        assert_abs_diff_eq!(result, Transform::IDENTITY, epsilon = 1e-5);
    }

    #[test]
    fn test_inv_mul_vec() {
        let t = Transform::new(Vec2::new(2.0, 3.0), Rot2::new(std::f32::consts::FRAC_PI_6));
        let v = Vec2::new(5.0, 7.0);
        let transformed = t * v;
        let result = t.inv_mul_vec(transformed);
        assert_abs_diff_eq!(result, v, epsilon = 1e-6);
    }
}
