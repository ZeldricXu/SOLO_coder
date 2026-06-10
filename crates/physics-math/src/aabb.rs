use serde::{Deserialize, Serialize};

use crate::Vec2;

#[derive(Copy, Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct AABB {
    pub min: Vec2,
    pub max: Vec2,
}

impl AABB {
    #[inline]
    pub fn new(min: Vec2, max: Vec2) -> Self {
        AABB { min, max }
    }

    #[inline]
    pub fn from_center_extents(center: Vec2, extents: Vec2) -> Self {
        AABB {
            min: center - extents,
            max: center + extents,
        }
    }

    #[inline]
    pub fn from_points(points: &[Vec2]) -> Self {
        if points.is_empty() {
            return AABB {
                min: Vec2::ZERO,
                max: Vec2::ZERO,
            };
        }

        let mut min = points[0];
        let mut max = points[0];

        for &p in points.iter().skip(1) {
            min = min.min(p);
            max = max.max(p);
        }

        AABB { min, max }
    }

    #[inline]
    pub fn center(&self) -> Vec2 {
        (self.min + self.max) * 0.5
    }

    #[inline]
    pub fn extents(&self) -> Vec2 {
        (self.max - self.min) * 0.5
    }

    #[inline]
    pub fn size(&self) -> Vec2 {
        self.max - self.min
    }

    #[inline]
    pub fn area(&self) -> f32 {
        let s = self.size();
        s.x * s.y
    }

    #[inline]
    pub fn perimeter(&self) -> f32 {
        let s = self.size();
        2.0 * (s.x + s.y)
    }

    #[inline]
    pub fn contains(&self, point: Vec2) -> bool {
        point.x >= self.min.x
            && point.x <= self.max.x
            && point.y >= self.min.y
            && point.y <= self.max.y
    }

    #[inline]
    pub fn contains_aabb(&self, other: &AABB) -> bool {
        self.min.x <= other.min.x
            && self.max.x >= other.max.x
            && self.min.y <= other.min.y
            && self.max.y >= other.max.y
    }

    #[inline]
    pub fn intersects(&self, other: &AABB) -> bool {
        self.min.x <= other.max.x
            && self.max.x >= other.min.x
            && self.min.y <= other.max.y
            && self.max.y >= other.min.y
    }

    #[inline]
    pub fn merged(&self, other: &AABB) -> AABB {
        AABB {
            min: self.min.min(other.min),
            max: self.max.max(other.max),
        }
    }

    #[inline]
    pub fn expand(&self, margin: f32) -> AABB {
        AABB {
            min: self.min - Vec2::splat(margin),
            max: self.max + Vec2::splat(margin),
        }
    }

    #[inline]
    pub fn expand_vec(&self, margin: Vec2) -> AABB {
        AABB {
            min: self.min - margin,
            max: self.max + margin,
        }
    }

    #[inline]
    pub fn translated(&self, offset: Vec2) -> AABB {
        AABB {
            min: self.min + offset,
            max: self.max + offset,
        }
    }

    #[inline]
    pub fn clip_line(&self, p1: Vec2, p2: Vec2) -> Option<(Vec2, Vec2)> {
        let mut t_min = 0.0f32;
        let mut t_max = 1.0f32;
        let d = p2 - p1;

        for i in 0..2 {
            if d[i].abs() < f32::EPSILON {
                if p1[i] < self.min[i] || p1[i] > self.max[i] {
                    return None;
                }
            } else {
                let inv_d = 1.0 / d[i];
                let mut t1 = (self.min[i] - p1[i]) * inv_d;
                let mut t2 = (self.max[i] - p1[i]) * inv_d;

                if t1 > t2 {
                    std::mem::swap(&mut t1, &mut t2);
                }

                t_min = t_min.max(t1);
                t_max = t_max.min(t2);

                if t_min > t_max {
                    return None;
                }
            }
        }

        Some((p1 + d * t_min, p1 + d * t_max))
    }

    #[inline]
    pub fn distance(&self, point: Vec2) -> f32 {
        let closest = point.clamp(self.min, self.max);
        point.distance(closest)
    }

    #[inline]
    pub fn distance_squared(&self, point: Vec2) -> f32 {
        let closest = point.clamp(self.min, self.max);
        point.distance_squared(closest)
    }
}

impl approx::AbsDiffEq for AABB {
    type Epsilon = f32;

    fn default_epsilon() -> Self::Epsilon {
        f32::EPSILON
    }

    fn abs_diff_eq(&self, other: &Self, epsilon: Self::Epsilon) -> bool {
        self.min.abs_diff_eq(&other.min, epsilon) && self.max.abs_diff_eq(&other.max, epsilon)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_center_extents() {
        let aabb = AABB::from_center_extents(Vec2::new(2.0, 3.0), Vec2::new(1.0, 1.5));
        assert_abs_diff_eq!(aabb.center(), Vec2::new(2.0, 3.0));
        assert_abs_diff_eq!(aabb.extents(), Vec2::new(1.0, 1.5));
    }

    #[test]
    fn test_contains() {
        let aabb = AABB::new(Vec2::new(0.0, 0.0), Vec2::new(2.0, 2.0));
        assert!(aabb.contains(Vec2::new(1.0, 1.0)));
        assert!(!aabb.contains(Vec2::new(3.0, 1.0)));
    }

    #[test]
    fn test_intersects() {
        let a = AABB::new(Vec2::new(0.0, 0.0), Vec2::new(2.0, 2.0));
        let b = AABB::new(Vec2::new(1.0, 1.0), Vec2::new(3.0, 3.0));
        let c = AABB::new(Vec2::new(3.0, 3.0), Vec2::new(4.0, 4.0));
        assert!(a.intersects(&b));
        assert!(!a.intersects(&c));
    }

    #[test]
    fn test_merge() {
        let a = AABB::new(Vec2::new(0.0, 0.0), Vec2::new(1.0, 1.0));
        let b = AABB::new(Vec2::new(2.0, 2.0), Vec2::new(3.0, 3.0));
        let merged = a.merged(&b);
        assert_abs_diff_eq!(merged.min, Vec2::new(0.0, 0.0));
        assert_abs_diff_eq!(merged.max, Vec2::new(3.0, 3.0));
    }
}
