use crate::Vec2;

#[inline]
pub fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

#[inline]
pub fn clamp(value: f32, min: f32, max: f32) -> f32 {
    value.max(min).min(max)
}

#[inline]
pub fn sign(value: f32) -> f32 {
    if value >= 0.0 {
        1.0
    } else {
        -1.0
    }
}

#[inline]
pub fn min_max(a: f32, b: f32) -> (f32, f32) {
    if a < b {
        (a, b)
    } else {
        (b, a)
    }
}

#[inline]
pub fn lerp_angle(a: f32, b: f32, t: f32) -> f32 {
    let mut diff = b - a;
    if diff > std::f32::consts::PI {
        diff -= 2.0 * std::f32::consts::PI;
    } else if diff < -std::f32::consts::PI {
        diff += 2.0 * std::f32::consts::PI;
    }
    a + diff * t
}

#[inline]
pub fn nearest_point_on_segment(point: Vec2, a: Vec2, b: Vec2) -> (Vec2, f32) {
    let ab = b - a;
    let ap = point - a;
    let proj = ap.dot(ab);
    let len_sq = ab.length_squared();

    if len_sq < f32::EPSILON {
        return (a, 0.0);
    }

    let t = clamp(proj / len_sq, 0.0, 1.0);
    (a + ab * t, t)
}

#[inline]
pub fn distance_to_segment(point: Vec2, a: Vec2, b: Vec2) -> f32 {
    let (closest, _) = nearest_point_on_segment(point, a, b);
    point.distance(closest)
}

#[inline]
pub fn distance_to_segment_squared(point: Vec2, a: Vec2, b: Vec2) -> f32 {
    let (closest, _) = nearest_point_on_segment(point, a, b);
    point.distance_squared(closest)
}

#[inline]
pub fn segment_intersect(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2) -> Option<Vec2> {
    let d1 = p2 - p1;
    let d2 = p4 - p3;
    let cross = d1.cross(d2);

    if cross.abs() < f32::EPSILON {
        return None;
    }

    let t = (p3 - p1).cross(d2) / cross;
    let s = (p3 - p1).cross(d1) / cross;

    if t >= 0.0 && t <= 1.0 && s >= 0.0 && s <= 1.0 {
        Some(p1 + d1 * t)
    } else {
        None
    }
}

#[inline]
pub fn barycentric(point: Vec2, a: Vec2, b: Vec2, c: Vec2) -> (f32, f32, f32) {
    let v0 = b - a;
    let v1 = c - a;
    let v2 = point - a;

    let d00 = v0.dot(v0);
    let d01 = v0.dot(v1);
    let d11 = v1.dot(v1);
    let d20 = v2.dot(v0);
    let d21 = v2.dot(v1);

    let denom = d00 * d11 - d01 * d01;

    if denom.abs() < f32::EPSILON {
        return (1.0, 0.0, 0.0);
    }

    let v = (d11 * d20 - d01 * d21) / denom;
    let w = (d00 * d21 - d01 * d20) / denom;
    let u = 1.0 - v - w;

    (u, v, w)
}

#[inline]
pub fn point_in_triangle(point: Vec2, a: Vec2, b: Vec2, c: Vec2) -> bool {
    let (u, v, w) = barycentric(point, a, b, c);
    u >= 0.0 && v >= 0.0 && w >= 0.0
}

#[inline]
pub fn polygon_area(vertices: &[Vec2]) -> f32 {
    if vertices.len() < 3 {
        return 0.0;
    }

    let mut area = 0.0;
    let n = vertices.len();

    for i in 0..n {
        let j = (i + 1) % n;
        area += vertices[i].cross(vertices[j]);
    }

    area.abs() * 0.5
}

#[inline]
pub fn polygon_centroid(vertices: &[Vec2]) -> Vec2 {
    if vertices.is_empty() {
        return Vec2::ZERO;
    }

    let n = vertices.len();
    if n == 1 {
        return vertices[0];
    }
    if n == 2 {
        return (vertices[0] + vertices[1]) * 0.5;
    }

    let mut centroid = Vec2::ZERO;
    let mut signed_area = 0.0;

    for i in 0..n {
        let j = (i + 1) % n;
        let cross = vertices[i].cross(vertices[j]);
        signed_area += cross;
        centroid += (vertices[i] + vertices[j]) * cross;
    }

    if signed_area.abs() > f32::EPSILON {
        centroid /= 3.0 * signed_area;
    } else {
        centroid = vertices.iter().fold(Vec2::ZERO, |acc, v| acc + *v) / n as f32;
    }

    centroid
}

#[inline]
pub fn polygon_moment_of_inertia(vertices: &[Vec2], mass: f32) -> f32 {
    if vertices.len() < 3 {
        return 0.0;
    }

    let n = vertices.len();
    let mut numerator = 0.0;
    let mut denominator = 0.0;

    for i in 0..n {
        let j = (i + 1) % n;
        let cross = vertices[j].cross(vertices[i]);
        numerator += cross * (vertices[i].dot(vertices[i]) + vertices[i].dot(vertices[j]) + vertices[j].dot(vertices[j]));
        denominator += cross;
    }

    if denominator.abs() < f32::EPSILON {
        return 0.0;
    }

    (mass / 6.0) * (numerator / denominator)
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_nearest_point_on_segment() {
        let a = Vec2::new(0.0, 0.0);
        let b = Vec2::new(2.0, 0.0);

        let (p1, t1) = nearest_point_on_segment(Vec2::new(1.0, 1.0), a, b);
        assert_abs_diff_eq!(p1, Vec2::new(1.0, 0.0));
        assert_abs_diff_eq!(t1, 0.5);

        let (p2, t2) = nearest_point_on_segment(Vec2::new(-1.0, 0.0), a, b);
        assert_abs_diff_eq!(p2, a);
        assert_abs_diff_eq!(t2, 0.0);
    }

    #[test]
    fn test_segment_intersect() {
        let p1 = Vec2::new(0.0, 0.0);
        let p2 = Vec2::new(2.0, 2.0);
        let p3 = Vec2::new(0.0, 2.0);
        let p4 = Vec2::new(2.0, 0.0);

        let intersection = segment_intersect(p1, p2, p3, p4);
        assert!(intersection.is_some());
        assert_abs_diff_eq!(intersection.unwrap(), Vec2::new(1.0, 1.0));
    }

    #[test]
    fn test_polygon_area() {
        let square = vec![
            Vec2::new(0.0, 0.0),
            Vec2::new(2.0, 0.0),
            Vec2::new(2.0, 2.0),
            Vec2::new(0.0, 2.0),
        ];
        assert_abs_diff_eq!(polygon_area(&square), 4.0);
    }

    #[test]
    fn test_polygon_centroid() {
        let square = vec![
            Vec2::new(0.0, 0.0),
            Vec2::new(2.0, 0.0),
            Vec2::new(2.0, 2.0),
            Vec2::new(0.0, 2.0),
        ];
        assert_abs_diff_eq!(polygon_centroid(&square), Vec2::new(1.0, 1.0));
    }
}
