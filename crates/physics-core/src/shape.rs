use physics_math::{polygon_moment_of_inertia, AABB, Transform, Vec2};

#[derive(Clone, Debug, PartialEq)]
pub enum Shape {
    Circle(Circle),
    Rectangle(Rectangle),
    Polygon(Polygon),
    Segment(Segment),
}

#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Circle {
    pub radius: f32,
}

impl Circle {
    #[inline]
    pub fn new(radius: f32) -> Self {
        Circle { radius }
    }

    #[inline]
    pub fn radius(&self) -> f32 {
        self.radius
    }

    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        let r = Vec2::splat(self.radius);
        AABB::from_center_extents(transform.position, r)
    }

    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        std::f32::consts::PI * self.radius * self.radius * density
    }

    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        0.5 * mass * self.radius * self.radius
    }

    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        let dir = transform.rotation.inv_mul_vec(direction).normalize();
        transform.position + transform.rotation.mul_vec(dir * self.radius)
    }
}

#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Rectangle {
    pub half_extents: Vec2,
}

impl Rectangle {
    #[inline]
    pub fn new(width: f32, height: f32) -> Self {
        Rectangle {
            half_extents: Vec2::new(width * 0.5, height * 0.5),
        }
    }

    #[inline]
    pub fn from_half_extents(half_extents: Vec2) -> Self {
        Rectangle { half_extents }
    }

    #[inline]
    pub fn half_extents(&self) -> Vec2 {
        self.half_extents
    }

    #[inline]
    pub fn width(&self) -> f32 {
        self.half_extents.x * 2.0
    }

    #[inline]
    pub fn height(&self) -> f32 {
        self.half_extents.y * 2.0
    }

    #[inline]
    pub fn vertices(&self) -> [Vec2; 4] {
        let h = self.half_extents;
        [
            Vec2::new(-h.x, -h.y),
            Vec2::new(h.x, -h.y),
            Vec2::new(h.x, h.y),
            Vec2::new(-h.x, h.y),
        ]
    }

    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        let vertices = self.vertices();
        let rotated: Vec<Vec2> = vertices.iter().map(|v| transform.mul_vec(*v)).collect();
        AABB::from_points(&rotated)
    }

    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        self.half_extents.x * self.half_extents.y * 4.0 * density
    }

    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        let w = self.half_extents.x * 2.0;
        let h = self.half_extents.y * 2.0;
        (mass / 12.0) * (w * w + h * h)
    }

    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        let local_dir = transform.rotation.inv_mul_vec(direction);
        let local_point = Vec2::new(
            if local_dir.x >= 0.0 {
                self.half_extents.x
            } else {
                -self.half_extents.x
            },
            if local_dir.y >= 0.0 {
                self.half_extents.y
            } else {
                -self.half_extents.y
            },
        );
        transform.mul_vec(local_point)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Polygon {
    vertices: Vec<Vec2>,
    normals: Vec<Vec2>,
}

impl Polygon {
    pub fn new(vertices: Vec<Vec2>) -> Self {
        let vertices = Self::compute_hull(vertices);
        let normals = Self::compute_normals(&vertices);
        Polygon { vertices, normals }
    }

    fn compute_hull(mut points: Vec<Vec2>) -> Vec<Vec2>
    {
        use physics_math::polygon_centroid;

        if points.len() < 3 {
            return points;
        }

        points.sort_by(|a, b| {
            if a.x != b.x {
                a.x.partial_cmp(&b.x).unwrap()
            } else {
                a.y.partial_cmp(&b.y).unwrap()
            }
        });

        let mut lower: Vec<Vec2> = Vec::new();
        for p in &points {
            while lower.len() >= 2 {
                let a: Vec2 = lower[lower.len() - 2];
                let b: Vec2 = lower[lower.len() - 1];
                let cross: f32 = (b - a).cross(*p - a);
                if cross <= 0.0 {
                    lower.pop();
                } else {
                    break;
                }
            }
            lower.push(*p);
        }

        let mut upper: Vec<Vec2> = Vec::new();
        for p in points.iter().rev() {
            while upper.len() >= 2 {
                let a: Vec2 = upper[upper.len() - 2];
                let b: Vec2 = upper[upper.len() - 1];
                let cross: f32 = (b - a).cross(*p - a);
                if cross <= 0.0 {
                    upper.pop();
                } else {
                    break;
                }
            }
            upper.push(*p);
        }

        lower.pop();
        upper.pop();
        lower.extend(upper);

        let centroid = polygon_centroid(&lower);
        lower.sort_by(|a, b| {
            let angle_a = (*a - centroid).angle();
            let angle_b = (*b - centroid).angle();
            angle_a.partial_cmp(&angle_b).unwrap()
        });

        lower
    }

    fn compute_normals(vertices: &[Vec2]) -> Vec<Vec2>
    {
        let mut normals = Vec::with_capacity(vertices.len());
        for i in 0..vertices.len() {
            let j = (i + 1) % vertices.len();
            let edge = vertices[j] - vertices[i];
            let normal = edge.perp().normalize();
            normals.push(normal);
        }
        normals
    }

    #[inline]
    pub fn vertices(&self) -> &[Vec2] {
        &self.vertices
    }

    #[inline]
    pub fn normals(&self) -> &[Vec2] {
        &self.normals
    }

    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        let transformed: Vec<Vec2> = self
            .vertices
            .iter()
            .map(|v| transform.mul_vec(*v))
            .collect();
        AABB::from_points(&transformed)
    }

    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        physics_math::polygon_area(&self.vertices) * density
    }

    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        polygon_moment_of_inertia(&self.vertices, mass)
    }

    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        let local_dir = transform.rotation.inv_mul_vec(direction);
        let mut best_dot = f32::NEG_INFINITY;
        let mut best_idx = 0;

        for (i, &v) in self.vertices.iter().enumerate() {
            let dot = v.dot(local_dir);
            if dot > best_dot {
                best_dot = dot;
                best_idx = i;
            }
        }

        transform.mul_vec(self.vertices[best_idx])
    }
}

#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Segment {
    pub a: Vec2,
    pub b: Vec2,
}

impl Segment {
    #[inline]
    pub fn new(a: Vec2, b: Vec2) -> Self {
        Segment { a, b }
    }

    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        let ta = transform.mul_vec(self.a);
        let tb = transform.mul_vec(self.b);
        AABB::from_points(&[ta, tb])
    }

    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        let length = (self.b - self.a).length();
        length * 0.1 * density
    }

    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        let length = (self.b - self.a).length();
        mass * length * length / 12.0
    }

    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        let ta = transform.mul_vec(self.a);
        let tb = transform.mul_vec(self.b);
        if direction.dot(ta) > direction.dot(tb) {
            ta
        } else {
            tb
        }
    }
}

impl Shape {
    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        match self {
            Shape::Circle(c) => c.compute_aabb(transform),
            Shape::Rectangle(r) => r.compute_aabb(transform),
            Shape::Polygon(p) => p.compute_aabb(transform),
            Shape::Segment(s) => s.compute_aabb(transform),
        }
    }

    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        match self {
            Shape::Circle(c) => c.compute_mass(density),
            Shape::Rectangle(r) => r.compute_mass(density),
            Shape::Polygon(p) => p.compute_mass(density),
            Shape::Segment(s) => s.compute_mass(density),
        }
    }

    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        match self {
            Shape::Circle(c) => c.compute_inertia(mass),
            Shape::Rectangle(r) => r.compute_inertia(mass),
            Shape::Polygon(p) => p.compute_inertia(mass),
            Shape::Segment(s) => s.compute_inertia(mass),
        }
    }

    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        match self {
            Shape::Circle(c) => c.support_point(direction, transform),
            Shape::Rectangle(r) => r.support_point(direction, transform),
            Shape::Polygon(p) => p.support_point(direction, transform),
            Shape::Segment(s) => s.support_point(direction, transform),
        }
    }

    #[inline]
    pub fn is_convex(&self) -> bool {
        match self {
            Shape::Circle(_) => true,
            Shape::Rectangle(_) => true,
            Shape::Polygon(_) => true,
            Shape::Segment(_) => true,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_circle() {
        let c = Circle::new(2.0);
        assert_abs_diff_eq!(c.radius(), 2.0);
        assert_abs_diff_eq!(c.compute_mass(1.0), std::f32::consts::PI * 4.0);
    }

    #[test]
    fn test_rectangle() {
        let r = Rectangle::new(4.0, 2.0);
        assert_abs_diff_eq!(r.width(), 4.0);
        assert_abs_diff_eq!(r.compute_mass(1.0), 8.0);
    }

    #[test]
    fn test_rectangle_aabb() {
        let r = Rectangle::new(2.0, 2.0);
        let t = Transform::IDENTITY;
        let aabb = r.compute_aabb(&t);
        assert_abs_diff_eq!(aabb.min, Vec2::new(-1.0, -1.0));
        assert_abs_diff_eq!(aabb.max, Vec2::new(1.0, 1.0));
    }
}
