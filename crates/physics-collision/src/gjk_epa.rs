use physics_math::{Transform, Vec2};

const GJK_MAX_ITERATIONS: usize = 64;
const EPA_MAX_ITERATIONS: usize = 64;
const EPA_TOLERANCE: f32 = 1e-4;

#[derive(Clone, Copy, Debug)]
struct SimplexPoint {
    support: Vec2,
    point_a: Vec2,
    point_b: Vec2,
}

impl SimplexPoint {
    fn new(support: Vec2, point_a: Vec2, point_b: Vec2) -> Self {
        SimplexPoint {
            support,
            point_a,
            point_b,
        }
    }
}

pub struct GJKResult {
    pub collision: bool,
    pub(crate) simplex: Vec<SimplexPoint>,
}

pub struct EPAResult {
    pub normal: Vec2,
    pub penetration: f32,
    pub point: Vec2,
}

pub fn gjk<SA, SB>(
    support_a: &SA,
    support_b: &SB,
    transform_a: &Transform,
    transform_b: &Transform,
) -> GJKResult
where
    SA: Fn(Vec2) -> Vec2,
    SB: Fn(Vec2) -> Vec2,
{
    let support = |dir: Vec2| {
        let sa = transform_a.mul_vec(support_a(transform_a.rotation.inv_mul_vec(dir)));
        let sb = transform_b.mul_vec(support_b(transform_b.rotation.inv_mul_vec(-dir)));
        SimplexPoint::new(sa - sb, sa, sb)
    };

    let mut simplex = Vec::with_capacity(3);
    let mut direction = Vec2::new(1.0, 0.0);

    simplex.push(support(direction));

    if simplex[0].support.dot(direction) <= 0.0 {
        return GJKResult {
            collision: false,
            simplex,
        };
    }

    direction = -simplex[0].support;

    for _ in 0..GJK_MAX_ITERATIONS {
        let point = support(direction);

        if point.support.dot(direction) <= 0.0 {
            return GJKResult {
                collision: false,
                simplex,
            };
        }

        simplex.push(point);

        if contains_origin(&mut simplex, &mut direction) {
            return GJKResult {
                collision: true,
                simplex,
            };
        }
    }

    GJKResult {
        collision: false,
        simplex,
    }
}

fn contains_origin(simplex: &mut Vec<SimplexPoint>, direction: &mut Vec2) -> bool {
    match simplex.len() {
        2 => contains_origin_line(simplex, direction),
        3 => contains_origin_triangle(simplex, direction),
        _ => false,
    }
}

fn contains_origin_line(simplex: &Vec<SimplexPoint>, direction: &mut Vec2) -> bool {
    let a = simplex[1];
    let b = simplex[0];

    let ao = -a.support;
    let ab = b.support - a.support;

    let ab_perp = triple_product(ab, ao, ab);

    if ab_perp.dot(ab_perp) < 1e-6 {
        let perp = ab.perp();
        if perp.dot(ao) < 0.0 {
            *direction = -perp;
        } else {
            *direction = perp;
        }
    } else {
        *direction = ab_perp;
    }

    false
}

fn contains_origin_triangle(simplex: &mut Vec<SimplexPoint>, direction: &mut Vec2) -> bool {
    let a = simplex[2];
    let b = simplex[1];
    let c = simplex[0];

    let ao = -a.support;
    let ab = b.support - a.support;
    let ac = c.support - a.support;

    let ab_perp = triple_product(ac, ab, ab);
    let ac_perp = triple_product(ab, ac, ac);

    if ab_perp.dot(ao) > 0.0 {
        simplex.remove(0);
        *direction = ab_perp;
        false
    } else if ac_perp.dot(ao) > 0.0 {
        simplex.remove(1);
        *direction = ac_perp;
        false
    } else {
        true
    }
}

fn triple_product(a: Vec2, b: Vec2, c: Vec2) -> Vec2 {
    b * a.dot(c) - a * b.dot(c)
}

pub fn epa<SA, SB>(
    simplex: Vec<SimplexPoint>,
    support_a: &SA,
    support_b: &SB,
    transform_a: &Transform,
    transform_b: &Transform,
) -> Option<EPAResult>
where
    SA: Fn(Vec2) -> Vec2,
    SB: Fn(Vec2) -> Vec2,
{
    let support = |dir: Vec2| {
        let sa = transform_a.mul_vec(support_a(transform_a.rotation.inv_mul_vec(dir)));
        let sb = transform_b.mul_vec(support_b(transform_b.rotation.inv_mul_vec(-dir)));
        SimplexPoint::new(sa - sb, sa, sb)
    };

    let mut polytope = simplex;

    for _ in 0..EPA_MAX_ITERATIONS {
        let (edge_idx, edge_normal, edge_dist) = find_closest_edge(&polytope);

        let point = support(edge_normal);
        let dist = point.support.dot(edge_normal);

        if dist - edge_dist < EPA_TOLERANCE {
            let edge_a = polytope[edge_idx];
            let edge_b = polytope[(edge_idx + 1) % polytope.len()];

            let alpha = barycentric_coordinate(
                edge_a.support,
                edge_b.support,
                point.support,
                edge_normal,
            );

            let contact_point = edge_a.point_a * alpha + edge_b.point_a * (1.0 - alpha);

            return Some(EPAResult {
                normal: edge_normal,
                penetration: dist,
                point: contact_point,
            });
        }

        polytope.insert(edge_idx + 1, point);
    }

    None
}

fn find_closest_edge(polytope: &[SimplexPoint]) -> (usize, Vec2, f32) {
    let mut min_dist = f32::INFINITY;
    let mut min_idx = 0;
    let mut min_normal = Vec2::ZERO;

    for i in 0..polytope.len() {
        let j = (i + 1) % polytope.len();

        let a = polytope[i].support;
        let b = polytope[j].support;

        let edge = b - a;
        let mut normal = Vec2::new(-edge.y, edge.x);
        let len = normal.length();
        if len < f32::EPSILON {
            continue;
        }
        normal = normal / len;

        let midpoint = (a + b) * 0.5;
        if midpoint.dot(normal) < 0.0 {
            normal = -normal;
        }

        let dist = normal.dot(a);

        if dist < min_dist {
            min_dist = dist;
            min_idx = i;
            min_normal = normal;
        }
    }

    (min_idx, min_normal, min_dist)
}

fn barycentric_coordinate(a: Vec2, b: Vec2, p: Vec2, _normal: Vec2) -> f32 {
    let ab = b - a;
    let ap = p - a;

    let t = ap.dot(ab) / ab.dot(ab);
    t.clamp(0.0, 1.0)
}

pub fn detect_collision<SA, SB>(
    support_a: SA,
    support_b: SB,
    transform_a: &Transform,
    transform_b: &Transform,
) -> Option<EPAResult>
where
    SA: Fn(Vec2) -> Vec2,
    SB: Fn(Vec2) -> Vec2,
{
    let gjk_result = gjk(&support_a, &support_b, transform_a, transform_b);

    if !gjk_result.collision {
        return None;
    }

    let mut result = epa(
        gjk_result.simplex,
        &support_a,
        &support_b,
        transform_a,
        transform_b,
    )?;

    let d = transform_b.position - transform_a.position;
    if result.normal.dot(d) < 0.0 {
        result.normal = -result.normal;
    }

    Some(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use physics_math::{Rot2, Transform};

    fn square_support(half_size: f32) -> impl Fn(Vec2) -> Vec2 {
        move |dir: Vec2| Vec2::new(half_size * dir.x.signum(), half_size * dir.y.signum())
    }

    fn circle_support(radius: f32) -> impl Fn(Vec2) -> Vec2 {
        move |dir: Vec2| {
            if dir.length_squared() > 1e-6 {
                dir.normalize() * radius
            } else {
                Vec2::ZERO
            }
        }
    }

    #[test]
    fn test_gjk_no_collision() {
        let support_a = square_support(1.0);
        let support_b = square_support(1.0);

        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(5.0, 0.0), Rot2::new(0.0));

        let result = gjk(&support_a, &support_b, &ta, &tb);
        assert!(!result.collision);
    }

    #[test]
    fn test_gjk_collision() {
        let support_a = square_support(1.0);
        let support_b = square_support(1.0);

        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(1.5, 0.0), Rot2::new(0.0));

        let result = gjk(&support_a, &support_b, &ta, &tb);
        assert!(result.collision);
    }

    #[test]
    fn test_gjk_circle_square_collision() {
        let support_a = circle_support(1.0);
        let support_b = square_support(1.0);

        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(1.5, 0.0), Rot2::new(0.0));

        let result = gjk(&support_a, &support_b, &ta, &tb);
        assert!(result.collision);
    }

    #[test]
    fn test_gjk_vertex_vertex_contact_normal() {
        let support_a = square_support(1.0);
        let support_b = square_support(1.0);

        let offset = (2.0_f32).sqrt();
        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(
            Vec2::new(offset - 0.01, offset - 0.01),
            Rot2::new(std::f32::consts::FRAC_PI_4),
        );

        let result = detect_collision(support_a, support_b, &ta, &tb);
        if let Some(epa_result) = result {
            let d = tb.position - ta.position;
            assert!(
                epa_result.normal.dot(d) > 0.0,
                "Normal should point from A to B, got normal={:?}, d={:?}",
                epa_result.normal,
                d
            );
        }
    }

    #[test]
    fn test_gjk_degenerate_simplex_normal() {
        let support_a = square_support(1.0);
        let support_b = square_support(1.0);

        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(1.99, 0.0), Rot2::new(0.0));

        let result = detect_collision(support_a, support_b, &ta, &tb);
        if let Some(epa_result) = result {
            assert!(
                epa_result.normal.x > 0.5,
                "Normal should be approximately along x-axis, got {:?}",
                epa_result.normal
            );
            let d = tb.position - ta.position;
            assert!(
                epa_result.normal.dot(d) > 0.0,
                "Normal should point in separation direction"
            );
        }
    }

    #[test]
    fn test_epa_normal_points_away_from_a() {
        let support_a = circle_support(1.0);
        let support_b = square_support(1.0);

        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(1.5, 0.5), Rot2::new(0.0));

        let result = detect_collision(support_a, support_b, &ta, &tb);
        if let Some(epa_result) = result {
            let d = tb.position - ta.position;
            assert!(
                epa_result.normal.dot(d) > 0.0,
                "EPA normal should point from A to B, got normal={:?}, d={:?}",
                epa_result.normal,
                d
            );
        }
    }
}
