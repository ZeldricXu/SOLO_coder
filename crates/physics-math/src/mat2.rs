use serde::{Deserialize, Serialize};
use std::ops::{Add, AddAssign, Mul, MulAssign, Sub, SubAssign};

use crate::Vec2;

#[derive(Copy, Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Mat2 {
    pub cols: [Vec2; 2],
}

impl Mat2 {
    pub const ZERO: Mat2 = Mat2 {
        cols: [Vec2::ZERO, Vec2::ZERO],
    };
    pub const IDENTITY: Mat2 = Mat2 {
        cols: [Vec2::X, Vec2::Y],
    };

    #[inline]
    pub fn new(col1: Vec2, col2: Vec2) -> Self {
        Mat2 { cols: [col1, col2] }
    }

    #[inline]
    pub fn from_rows(row1: Vec2, row2: Vec2) -> Self {
        Mat2 {
            cols: [Vec2::new(row1.x, row2.x), Vec2::new(row1.y, row2.y)],
        }
    }

    #[inline]
    pub fn from_angle(angle: f32) -> Self {
        let c = angle.cos();
        let s = angle.sin();
        Mat2::new(Vec2::new(c, s), Vec2::new(-s, c))
    }

    #[inline]
    pub fn from_scale(scale: Vec2) -> Self {
        Mat2::new(Vec2::new(scale.x, 0.0), Vec2::new(0.0, scale.y))
    }

    #[inline]
    pub fn row(&self, index: usize) -> Vec2 {
        Vec2::new(self.cols[0][index], self.cols[1][index])
    }

    #[inline]
    pub fn col(&self, index: usize) -> Vec2 {
        self.cols[index]
    }

    #[inline]
    pub fn determinant(&self) -> f32 {
        self.cols[0].x * self.cols[1].y - self.cols[0].y * self.cols[1].x
    }

    #[inline]
    pub fn transpose(&self) -> Mat2 {
        Mat2::from_rows(self.col(0), self.col(1))
    }

    #[inline]
    pub fn inverse(&self) -> Mat2 {
        let det = self.determinant();
        if det.abs() > f32::EPSILON {
            let inv_det = 1.0 / det;
            Mat2::new(
                Vec2::new(self.cols[1].y * inv_det, -self.cols[0].y * inv_det),
                Vec2::new(-self.cols[1].x * inv_det, self.cols[0].x * inv_det),
            )
        } else {
            Mat2::ZERO
        }
    }

    #[inline]
    pub fn mul_vec(&self, v: Vec2) -> Vec2 {
        Vec2::new(
            self.cols[0].x * v.x + self.cols[1].x * v.y,
            self.cols[0].y * v.x + self.cols[1].y * v.y,
        )
    }

    #[inline]
    pub fn mul_mat(&self, other: &Mat2) -> Mat2 {
        Mat2::new(
            self.mul_vec(other.col(0)),
            self.mul_vec(other.col(1)),
        )
    }

    #[inline]
    pub fn transpose_mul_vec(&self, v: Vec2) -> Vec2 {
        Vec2::new(self.col(0).dot(v), self.col(1).dot(v))
    }

    #[inline]
    pub fn scale(&self, s: f32) -> Mat2 {
        Mat2::new(self.col(0) * s, self.col(1) * s)
    }
}

impl std::ops::Index<usize> for Mat2 {
    type Output = Vec2;

    fn index(&self, index: usize) -> &Vec2 {
        &self.cols[index]
    }
}

impl std::ops::IndexMut<usize> for Mat2 {
    fn index_mut(&mut self, index: usize) -> &mut Vec2 {
        &mut self.cols[index]
    }
}

impl Add for Mat2 {
    type Output = Mat2;

    #[inline]
    fn add(self, rhs: Mat2) -> Mat2 {
        Mat2::new(self.cols[0] + rhs.cols[0], self.cols[1] + rhs.cols[1])
    }
}

impl AddAssign for Mat2 {
    #[inline]
    fn add_assign(&mut self, rhs: Mat2) {
        self.cols[0] += rhs.cols[0];
        self.cols[1] += rhs.cols[1];
    }
}

impl Sub for Mat2 {
    type Output = Mat2;

    #[inline]
    fn sub(self, rhs: Mat2) -> Mat2 {
        Mat2::new(self.cols[0] - rhs.cols[0], self.cols[1] - rhs.cols[1])
    }
}

impl SubAssign for Mat2 {
    #[inline]
    fn sub_assign(&mut self, rhs: Mat2) {
        self.cols[0] -= rhs.cols[0];
        self.cols[1] -= rhs.cols[1];
    }
}

impl Mul<f32> for Mat2 {
    type Output = Mat2;

    #[inline]
    fn mul(self, rhs: f32) -> Mat2 {
        Mat2::new(self.cols[0] * rhs, self.cols[1] * rhs)
    }
}

impl Mul<Vec2> for Mat2 {
    type Output = Vec2;

    #[inline]
    fn mul(self, rhs: Vec2) -> Vec2 {
        self.mul_vec(rhs)
    }
}

impl Mul<Mat2> for Mat2 {
    type Output = Mat2;

    #[inline]
    fn mul(self, rhs: Mat2) -> Mat2 {
        self.mul_mat(&rhs)
    }
}

impl MulAssign<f32> for Mat2 {
    #[inline]
    fn mul_assign(&mut self, rhs: f32) {
        self.cols[0] *= rhs;
        self.cols[1] *= rhs;
    }
}

impl approx::AbsDiffEq for Mat2 {
    type Epsilon = f32;

    fn default_epsilon() -> Self::Epsilon {
        f32::EPSILON
    }

    fn abs_diff_eq(&self, other: &Self, epsilon: Self::Epsilon) -> bool {
        self.cols[0].abs_diff_eq(&other.cols[0], epsilon)
            && self.cols[1].abs_diff_eq(&other.cols[1], epsilon)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_identity() {
        let i = Mat2::IDENTITY;
        let v = Vec2::new(2.0, 3.0);
        assert_abs_diff_eq!(i * v, v);
    }

    #[test]
    fn test_rotation() {
        let m = Mat2::from_angle(std::f32::consts::FRAC_PI_2);
        let v = Vec2::new(1.0, 0.0);
        let result = m * v;
        assert_abs_diff_eq!(result, Vec2::new(0.0, 1.0), epsilon = 1e-6);
    }

    #[test]
    fn test_determinant() {
        let m = Mat2::new(Vec2::new(1.0, 2.0), Vec2::new(3.0, 4.0));
        assert_abs_diff_eq!(m.determinant(), -2.0);
    }

    #[test]
    fn test_inverse() {
        let m = Mat2::new(Vec2::new(4.0, 3.0), Vec2::new(3.0, 2.0));
        let inv = m.inverse();
        assert_abs_diff_eq!(m * inv, Mat2::IDENTITY, epsilon = 1e-6);
    }
}
