use serde::{Deserialize, Serialize};

use crate::{Mat2, Vec2};

#[derive(Copy, Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Rot2 {
    pub sin: f32,
    pub cos: f32,
}

impl Rot2 {
    pub const IDENTITY: Rot2 = Rot2 { sin: 0.0, cos: 1.0 };

    #[inline]
    pub fn new(angle: f32) -> Self {
        Rot2 {
            sin: angle.sin(),
            cos: angle.cos(),
        }
    }

    #[inline]
    pub fn from_sin_cos(sin: f32, cos: f32) -> Self {
        Rot2 { sin, cos }
    }

    #[inline]
    pub fn angle(&self) -> f32 {
        self.sin.atan2(self.cos)
    }

    #[inline]
    pub fn set_angle(&mut self, angle: f32) {
        self.sin = angle.sin();
        self.cos = angle.cos();
    }

    #[inline]
    pub fn inverse(&self) -> Rot2 {
        Rot2 {
            sin: -self.sin,
            cos: self.cos,
        }
    }

    #[inline]
    pub fn mul(&self, other: Rot2) -> Rot2 {
        Rot2 {
            sin: self.cos * other.sin + self.sin * other.cos,
            cos: self.cos * other.cos - self.sin * other.sin,
        }
    }

    #[inline]
    pub fn mul_vec(&self, v: Vec2) -> Vec2 {
        Vec2::new(
            self.cos * v.x - self.sin * v.y,
            self.sin * v.x + self.cos * v.y,
        )
    }

    #[inline]
    pub fn inv_mul_vec(&self, v: Vec2) -> Vec2 {
        Vec2::new(
            self.cos * v.x + self.sin * v.y,
            -self.sin * v.x + self.cos * v.y,
        )
    }

    #[inline]
    pub fn to_mat2(&self) -> Mat2 {
        Mat2::new(
            Vec2::new(self.cos, self.sin),
            Vec2::new(-self.sin, self.cos),
        )
    }

    #[inline]
    pub fn normalize(&mut self) {
        let len = (self.sin * self.sin + self.cos * self.cos).sqrt();
        if len > f32::EPSILON {
            self.sin /= len;
            self.cos /= len;
        }
    }

    #[inline]
    pub fn lerp(&self, other: Rot2, t: f32) -> Rot2 {
        let angle = self.angle();
        let other_angle = other.angle();
        let mut diff = other_angle - angle;
        if diff > std::f32::consts::PI {
            diff -= 2.0 * std::f32::consts::PI;
        } else if diff < -std::f32::consts::PI {
            diff += 2.0 * std::f32::consts::PI;
        }
        Rot2::new(angle + diff * t)
    }
}

impl std::ops::Mul<Rot2> for Rot2 {
    type Output = Rot2;

    #[inline]
    fn mul(self, rhs: Rot2) -> Rot2 {
        Rot2 {
            sin: self.cos * rhs.sin + self.sin * rhs.cos,
            cos: self.cos * rhs.cos - self.sin * rhs.sin,
        }
    }
}

impl std::ops::Mul<Vec2> for Rot2 {
    type Output = Vec2;

    #[inline]
    fn mul(self, rhs: Vec2) -> Vec2 {
        self.mul_vec(rhs)
    }
}

impl Default for Rot2 {
    fn default() -> Self {
        Rot2::IDENTITY
    }
}

impl approx::AbsDiffEq for Rot2 {
    type Epsilon = f32;

    fn default_epsilon() -> Self::Epsilon {
        f32::EPSILON
    }

    fn abs_diff_eq(&self, other: &Self, epsilon: Self::Epsilon) -> bool {
        self.sin.abs_diff_eq(&other.sin, epsilon) && self.cos.abs_diff_eq(&other.cos, epsilon)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_rotation() {
        let r = Rot2::new(std::f32::consts::FRAC_PI_2);
        let v = Vec2::new(1.0, 0.0);
        let result = r * v;
        assert_abs_diff_eq!(result, Vec2::new(0.0, 1.0), epsilon = 1e-6);
    }

    #[test]
    fn test_inverse() {
        let r = Rot2::new(std::f32::consts::FRAC_PI_4);
        let inv = r.inverse();
        let result = r * inv;
        assert_abs_diff_eq!(result, Rot2::IDENTITY, epsilon = 1e-6);
    }

    #[test]
    fn test_inv_mul_vec() {
        let r = Rot2::new(std::f32::consts::FRAC_PI_3);
        let v = Vec2::new(2.0, 3.0);
        let rotated = r * v;
        let result = r.inv_mul_vec(rotated);
        assert_abs_diff_eq!(result, v, epsilon = 1e-6);
    }
}
