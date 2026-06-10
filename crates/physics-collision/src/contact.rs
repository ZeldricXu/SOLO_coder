use physics_math::{Transform, Vec2};

use physics_core::{BodyHandle, Shape};

use slotmap::Key;

#[derive(Clone, Copy, Debug)]
pub struct ContactPoint {
    pub point: Vec2,
    pub normal: Vec2,
    pub penetration: f32,
}

#[derive(Clone, Debug)]
pub struct ContactManifold {
    pub body_a: BodyHandle,
    pub body_b: BodyHandle,
    pub points: [ContactPoint; 2],
    pub point_count: usize,
    pub normal: Vec2,
}

impl ContactManifold {
    pub fn new(body_a: BodyHandle, body_b: BodyHandle) -> Self {
        ContactManifold {
            body_a,
            body_b,
            points: [ContactPoint::zero(); 2],
            point_count: 0,
            normal: Vec2::ZERO,
        }
    }

    pub fn add_point(&mut self, point: ContactPoint) {
        if self.point_count < 2 {
            self.points[self.point_count] = point;
            self.point_count += 1;
        }
    }

    pub fn clear(&mut self) {
        self.point_count = 0;
        self.normal = Vec2::ZERO;
    }
}

impl ContactPoint {
    pub fn zero() -> Self {
        ContactPoint {
            point: Vec2::ZERO,
            normal: Vec2::ZERO,
            penetration: 0.0,
        }
    }

    pub fn new(point: Vec2, normal: Vec2, penetration: f32) -> Self {
        ContactPoint {
            point,
            normal,
            penetration,
        }
    }
}

pub trait Collide {
    fn collide(
        &self,
        transform_a: &Transform,
        other: &Self,
        transform_b: &Transform,
    ) -> Option<ContactManifold>;
}

impl Collide for Shape {
    fn collide(
        &self,
        transform_a: &Transform,
        other: &Shape,
        transform_b: &Transform,
    ) -> Option<ContactManifold> {
        match (self, other) {
            (Shape::Circle(a), Shape::Circle(b)) => {
                circle_vs_circle(a, transform_a, b, transform_b)
            }
            (Shape::Circle(c), Shape::Rectangle(r)) => circle_vs_polygon(
                c,
                transform_a,
                &r.vertices(),
                transform_b,
            )
            .map(|mut m| {
                m.normal = -m.normal;
                m
            }),
            (Shape::Rectangle(r), Shape::Circle(c)) => {
                circle_vs_polygon(c, transform_b, &r.vertices(), transform_a)
            }
            (Shape::Circle(c), Shape::Polygon(p)) => circle_vs_polygon(
                c,
                transform_a,
                p.vertices(),
                transform_b,
            )
            .map(|mut m| {
                m.normal = -m.normal;
                m
            }),
            (Shape::Polygon(p), Shape::Circle(c)) => {
                circle_vs_polygon(c, transform_b, p.vertices(), transform_a)
            }
            (Shape::Rectangle(a), Shape::Rectangle(b)) => {
                polygon_vs_polygon(&a.vertices(), transform_a, &b.vertices(), transform_b)
            }
            (Shape::Rectangle(r), Shape::Polygon(p)) => {
                polygon_vs_polygon(&r.vertices(), transform_a, p.vertices(), transform_b)
            }
            (Shape::Polygon(p), Shape::Rectangle(r)) => {
                polygon_vs_polygon(p.vertices(), transform_a, &r.vertices(), transform_b)
            }
            (Shape::Polygon(a), Shape::Polygon(b)) => {
                polygon_vs_polygon(a.vertices(), transform_a, b.vertices(), transform_b)
            }
            (Shape::HalfSpace(h), Shape::Circle(c)) => {
                half_space_vs_circle(h, transform_a, c, transform_b)
            }
            (Shape::Circle(c), Shape::HalfSpace(h)) => {
                half_space_vs_circle(h, transform_b, c, transform_a)
                    .map(|mut m| {
                        m.normal = -m.normal;
                        m
                    })
            }
            (Shape::HalfSpace(h), Shape::Rectangle(r)) => {
                half_space_vs_polygon(h, transform_a, &r.vertices(), transform_b)
            }
            (Shape::Rectangle(r), Shape::HalfSpace(h)) => {
                half_space_vs_polygon(h, transform_b, &r.vertices(), transform_a)
                    .map(|mut m| {
                        m.normal = -m.normal;
                        m
                    })
            }
            (Shape::HalfSpace(h), Shape::Polygon(p)) => {
                half_space_vs_polygon(h, transform_a, p.vertices(), transform_b)
            }
            (Shape::Polygon(p), Shape::HalfSpace(h)) => {
                half_space_vs_polygon(h, transform_b, p.vertices(), transform_a)
                    .map(|mut m| {
                        m.normal = -m.normal;
                        m
                    })
            }
            (Shape::HalfSpace(_), Shape::HalfSpace(_)) => None,
            (Shape::Segment(_), _) | (_, Shape::Segment(_)) => None,
        }
    }
}

fn half_space_vs_circle(
    half_space: &physics_core::HalfSpace,
    th: &Transform,
    circle: &physics_core::Circle,
    tc: &Transform,
) -> Option<ContactManifold> {
    let center = tc.position;
    let normal = th.rotation.mul_vec(half_space.normal);
    let plane_point = normal * half_space.distance;
    
    let distance = (center - plane_point).dot(normal);
    
    if distance > circle.radius {
        return None;
    }
    
    let penetration = circle.radius - distance;
    let point = center - normal * circle.radius;
    
    let mut manifold = ContactManifold::new(Key::null(), Key::null());
    manifold.normal = normal;
    manifold.add_point(ContactPoint::new(point, normal, penetration));
    
    Some(manifold)
}

fn half_space_vs_polygon(
    half_space: &physics_core::HalfSpace,
    th: &Transform,
    vertices: &[Vec2],
    tp: &Transform,
) -> Option<ContactManifold> {
    let normal = th.rotation.mul_vec(half_space.normal);
    let plane_point = normal * half_space.distance;
    
    let mut min_distance = f32::INFINITY;
    let mut deepest_point = Vec2::ZERO;
    
    for v in vertices {
        let world_point = tp.mul_vec(*v);
        let distance = (world_point - plane_point).dot(normal);
        
        if distance < min_distance {
            min_distance = distance;
            deepest_point = world_point;
        }
    }
    
    if min_distance > 0.0 {
        return None;
    }
    
    let penetration = -min_distance;
    
    let mut manifold = ContactManifold::new(Key::null(), Key::null());
    manifold.normal = normal;
    
    let contact_point = ContactPoint::new(deepest_point, normal, penetration);
    manifold.add_point(contact_point);
    
    for v in vertices {
        let world_point = tp.mul_vec(*v);
        let distance = (world_point - plane_point).dot(normal);
        if distance <= 0.0 && world_point != deepest_point {
            let cp = ContactPoint::new(world_point, normal, -distance);
            manifold.add_point(cp);
            if manifold.point_count >= 2 {
                break;
            }
        }
    }
    
    Some(manifold)
}

fn circle_vs_circle(
    a: &physics_core::Circle,
    ta: &Transform,
    b: &physics_core::Circle,
    tb: &Transform,
) -> Option<ContactManifold> {
    let pa = ta.position;
    let pb = tb.position;
    let d = pb - pa;
    let dist_sq = d.dot(d);
    let r = a.radius + b.radius;

    if dist_sq > r * r {
        return None;
    }

    let dist = dist_sq.sqrt();
    let normal = if dist > 0.0 { d / dist } else { Vec2::new(1.0, 0.0) };
    let point = pa + normal * a.radius;
    let penetration = r - dist;

    let mut manifold = ContactManifold::new(Key::null(), Key::null());
    manifold.normal = normal;
    manifold.add_point(ContactPoint::new(point, normal, penetration));

    Some(manifold)
}

fn circle_vs_polygon(
    circle: &physics_core::Circle,
    tc: &Transform,
    vertices: &[Vec2],
    tp: &Transform,
) -> Option<ContactManifold> {
    let center = tc.position;

    let mut min_dist_sq = f32::INFINITY;
    let mut closest_edge = 0;

    for i in 0..vertices.len() {
        let p1 = tp.mul_vec(vertices[i]);
        let p2 = tp.mul_vec(vertices[(i + 1) % vertices.len()]);

        let (cp, _) = physics_math::utils::nearest_point_on_segment(center, p1, p2);
        let dist_sq = (center - cp).dot(center - cp);

        if dist_sq < min_dist_sq {
            min_dist_sq = dist_sq;
            closest_edge = i;
        }
    }

    let radius = circle.radius;
    let dist = min_dist_sq.sqrt();

    if dist > radius {
        return None;
    }

    let v1 = tp.mul_vec(vertices[closest_edge]);
    let v2 = tp.mul_vec(vertices[(closest_edge + 1) % vertices.len()]);
    let edge = v2 - v1;
    let mut normal = Vec2::new(-edge.y, edge.x).normalize();

    if (center - v1).dot(normal) < 0.0 {
        normal = -normal;
    }

    let penetration = radius - dist;
    let point = center - normal * radius;

    let mut manifold = ContactManifold::new(Key::null(), Key::null());
    manifold.normal = normal;
    manifold.add_point(ContactPoint::new(point, normal, penetration));

    Some(manifold)
}

fn polygon_vs_polygon(
    vertices_a: &[Vec2],
    ta: &Transform,
    vertices_b: &[Vec2],
    tb: &Transform,
) -> Option<ContactManifold> {
    let sat_result = sat(vertices_a, ta, vertices_b, tb);
    sat_result
}

fn sat(
    vertices_a: &[Vec2],
    ta: &Transform,
    vertices_b: &[Vec2],
    tb: &Transform,
) -> Option<ContactManifold> {
    let mut min_penetration = f32::INFINITY;
    let mut min_normal = Vec2::ZERO;

    for i in 0..vertices_a.len() {
        let v1 = ta.mul_vec(vertices_a[i]);
        let v2 = ta.mul_vec(vertices_a[(i + 1) % vertices_a.len()]);
        let edge = v2 - v1;
        let normal = Vec2::new(-edge.y, edge.x).normalize();

        let (min_a, max_a) = project(vertices_a, ta, normal);
        let (min_b, max_b) = project(vertices_b, tb, normal);

        let overlap = (max_a.min(max_b)) - (min_a.max(min_b));
        if overlap < 0.0 {
            return None;
        }

        if overlap < min_penetration {
            min_penetration = overlap;
            min_normal = normal;
        }
    }

    for i in 0..vertices_b.len() {
        let v1 = tb.mul_vec(vertices_b[i]);
        let v2 = tb.mul_vec(vertices_b[(i + 1) % vertices_b.len()]);
        let edge = v2 - v1;
        let normal = Vec2::new(-edge.y, edge.x).normalize();

        let (min_a, max_a) = project(vertices_a, ta, normal);
        let (min_b, max_b) = project(vertices_b, tb, normal);

        let overlap = (max_a.min(max_b)) - (min_a.max(min_b));
        if overlap < 0.0 {
            return None;
        }

        if overlap < min_penetration {
            min_penetration = overlap;
            min_normal = normal;
        }
    }

    let center_a: Vec2 = vertices_a.iter().fold(Vec2::ZERO, |acc, v| acc + *v) / vertices_a.len() as f32;
    let center_b: Vec2 = vertices_b.iter().fold(Vec2::ZERO, |acc, v| acc + *v) / vertices_b.len() as f32;
    let center_a_world = ta.mul_vec(center_a);
    let center_b_world = tb.mul_vec(center_b);

    if (center_b_world - center_a_world).dot(min_normal) < 0.0 {
        min_normal = -min_normal;
    }

    let ref_edge = find_reference_edge(vertices_a, ta, min_normal);
    let inc_edge = find_incident_edge(vertices_b, tb, -min_normal);

    let ref_v1 = ta.mul_vec(vertices_a[ref_edge]);
    let ref_v2 = ta.mul_vec(vertices_a[(ref_edge + 1) % vertices_a.len()]);
    let inc_v1 = tb.mul_vec(vertices_b[inc_edge]);
    let inc_v2 = tb.mul_vec(vertices_b[(inc_edge + 1) % vertices_b.len()]);

    let mut manifold = ContactManifold::new(Key::null(), Key::null());
    manifold.normal = min_normal;

    let ref_tangent = (ref_v2 - ref_v1).normalize();
    let ref_v1_dot = ref_tangent.dot(ref_v1);
    let ref_v2_dot = ref_tangent.dot(ref_v2);

    let cp1 = clip(inc_v1, inc_v2, ref_tangent, ref_v1_dot);
    let cp2 = clip(inc_v2, inc_v1, -ref_tangent, -ref_v2_dot);

    let ref_normal = min_normal;
    let ref_face_dot = ref_normal.dot(ref_v1);

    for p in [cp1, cp2] {
        let sep = ref_normal.dot(p) - ref_face_dot;
        if sep <= 0.0 {
            let contact = ContactPoint::new(p, ref_normal, -sep);
            manifold.add_point(contact);
        }
    }

    Some(manifold)
}

fn project(vertices: &[Vec2], transform: &Transform, axis: Vec2) -> (f32, f32) {
    let mut min = f32::INFINITY;
    let mut max = f32::NEG_INFINITY;

    for v in vertices {
        let p = transform.mul_vec(*v);
        let proj = p.dot(axis);
        if proj < min {
            min = proj;
        }
        if proj > max {
            max = proj;
        }
    }

    (min, max)
}

fn find_reference_edge(vertices: &[Vec2], transform: &Transform, normal: Vec2) -> usize {
    let mut max_dot = f32::NEG_INFINITY;
    let mut index = 0;

    for i in 0..vertices.len() {
        let v = transform.mul_vec(vertices[i]);
        let dot = normal.dot(v);
        if dot > max_dot {
            max_dot = dot;
            index = i;
        }
    }

    let v_prev = transform.mul_vec(vertices[(index + vertices.len() - 1) % vertices.len()]);
    let v_curr = transform.mul_vec(vertices[index]);
    let v_next = transform.mul_vec(vertices[(index + 1) % vertices.len()]);

    let left = (v_curr - v_prev).normalize();
    let right = (v_curr - v_next).normalize();

    if right.dot(normal) <= left.dot(normal) {
        (index + vertices.len() - 1) % vertices.len()
    } else {
        index
    }
}

fn find_incident_edge(vertices: &[Vec2], transform: &Transform, normal: Vec2) -> usize {
    let mut min_dot = f32::INFINITY;
    let mut index = 0;

    for i in 0..vertices.len() {
        let v1 = transform.mul_vec(vertices[i]);
        let v2 = transform.mul_vec(vertices[(i + 1) % vertices.len()]);
        let edge = v2 - v1;
        let edge_normal = Vec2::new(-edge.y, edge.x).normalize();

        let dot = edge_normal.dot(normal);
        if dot < min_dot {
            min_dot = dot;
            index = i;
        }
    }

    index
}

fn clip(v1: Vec2, v2: Vec2, tangent: Vec2, offset: f32) -> Vec2 {
    let t = (offset - tangent.dot(v1)) / tangent.dot(v2 - v1);
    v1 + t * (v2 - v1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;
    use physics_core::Rectangle;
    use physics_math::Rot2;

    #[test]
    fn test_circle_vs_circle_overlap() {
        let circle = physics_core::Circle { radius: 1.0 };
        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(1.5, 0.0), Rot2::new(0.0));

        let manifold = circle_vs_circle(&circle, &ta, &circle, &tb);
        assert!(manifold.is_some());

        let manifold = manifold.unwrap();
        assert_eq!(manifold.point_count, 1);
        assert!(manifold.points[0].penetration > 0.0);
        assert_abs_diff_eq!(manifold.normal.x, 1.0, epsilon = 1e-6);
        assert_abs_diff_eq!(manifold.normal.y, 0.0, epsilon = 1e-6);
    }

    #[test]
    fn test_circle_vs_circle_no_overlap() {
        let circle = physics_core::Circle { radius: 1.0 };
        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(3.0, 0.0), Rot2::new(0.0));

        let manifold = circle_vs_circle(&circle, &ta, &circle, &tb);
        assert!(manifold.is_none());
    }

    #[test]
    fn test_polygon_vs_polygon_overlap() {
        let square = Rectangle::new(2.0, 2.0);
        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(1.5, 0.0), Rot2::new(0.0));

        let manifold = polygon_vs_polygon(&square.vertices(), &ta, &square.vertices(), &tb);
        assert!(manifold.is_some());

        let manifold = manifold.unwrap();
        assert!(manifold.points[0].penetration >= 0.0);
        assert!(manifold.normal.length_squared() > 0.0);
    }

    #[test]
    fn test_half_space_vs_circle_overlap() {
        use physics_core::HalfSpace;
        let half_space = HalfSpace::ground();
        let circle = physics_core::Circle { radius: 1.0 };
        let th = Transform::IDENTITY;
        let tc = Transform::new(Vec2::new(0.0, -0.5), Rot2::new(0.0));

        let manifold = half_space_vs_circle(&half_space, &th, &circle, &tc);
        assert!(manifold.is_some());

        let manifold = manifold.unwrap();
        assert_eq!(manifold.point_count, 1);
        assert_abs_diff_eq!(manifold.normal.x, 0.0, epsilon = 1e-6);
        assert_abs_diff_eq!(manifold.normal.y, 1.0, epsilon = 1e-6);
        assert_abs_diff_eq!(manifold.points[0].penetration, 1.5, epsilon = 1e-6);
    }

    #[test]
    fn test_half_space_vs_circle_no_overlap() {
        use physics_core::HalfSpace;
        let half_space = HalfSpace::ground();
        let circle = physics_core::Circle { radius: 1.0 };
        let th = Transform::IDENTITY;
        let tc = Transform::new(Vec2::new(0.0, 2.0), Rot2::new(0.0));

        let manifold = half_space_vs_circle(&half_space, &th, &circle, &tc);
        assert!(manifold.is_none());
    }

    #[test]
    fn test_half_space_vs_polygon_overlap() {
        use physics_core::HalfSpace;
        let half_space = HalfSpace::ground();
        let square = Rectangle::new(2.0, 2.0);
        let th = Transform::IDENTITY;
        let tp = Transform::new(Vec2::new(0.0, -0.5), Rot2::new(0.0));

        let manifold = half_space_vs_polygon(&half_space, &th, &square.vertices(), &tp);
        assert!(manifold.is_some());

        let manifold = manifold.unwrap();
        assert!(manifold.point_count >= 1);
        assert!(manifold.points[0].penetration > 0.0);
    }
}
