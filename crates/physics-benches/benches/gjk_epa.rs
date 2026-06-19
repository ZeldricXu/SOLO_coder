use criterion::{black_box, criterion_group, criterion_main, Criterion};

use physics_collision::gjk_epa::{gjk, detect_collision};
use physics_math::{Rot2, Transform, Vec2};

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

fn polygon_support(vertices: Vec<Vec2>) -> impl Fn(Vec2) -> Vec2 {
    move |dir: Vec2| {
        let mut best = vertices[0];
        let mut best_dot = dir.dot(best);
        for &v in &vertices[1..] {
            let d = dir.dot(v);
            if d > best_dot {
                best = v;
                best_dot = d;
            }
        }
        best
    }
}

fn setup_circle_pairs(count: usize, overlapping: bool) -> Vec<(Transform, Transform, f32, f32)> {
    let mut pairs = Vec::with_capacity(count);
    let offset = if overlapping { 1.5 } else { 3.5 };
    for i in 0..count {
        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let angle = (i as f32 * 0.7) % 6.28;
        let tb = Transform::new(Vec2::new(offset, angle.sin() * 0.3), Rot2::new(0.0));
        pairs.push((ta, tb, 1.0, 1.0));
    }
    pairs
}

fn setup_circle_polygon_pairs(count: usize, overlapping: bool) -> Vec<(Transform, Transform, f32)> {
    let mut pairs = Vec::with_capacity(count);
    let offset = if overlapping { 1.2 } else { 4.0 };
    for i in 0..count {
        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
        let tb = Transform::new(Vec2::new(offset, (i as f32 * 0.5).sin() * 0.2), Rot2::new((i as f32 * 0.3) % 3.14));
        pairs.push((ta, tb, 1.0));
    }
    pairs
}

fn setup_polygon_polygon_pairs(count: usize, overlapping: bool) -> Vec<(Transform, Transform)> {
    let mut pairs = Vec::with_capacity(count);
    let offset = if overlapping { 1.0 } else { 5.0 };
    for i in 0..count {
        let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new((i as f32 * 0.2) % 3.14));
        let tb = Transform::new(Vec2::new(offset, 0.0), Rot2::new((i as f32 * 0.4) % 3.14));
        pairs.push((ta, tb));
    }
    pairs
}

fn benchmark_gjk_circle_circle(c: &mut Criterion) {
    let mut group = c.benchmark_group("GJK Circle-Circle");
    group.sample_size(50);

    for &overlapping in &[true, false] {
        let label = if overlapping { "overlapping" } else { "separated" };
        let pairs = setup_circle_pairs(100, overlapping);
        group.bench_function(format!("100 pairs - {}", label), |b| {
            b.iter(|| {
                for &(ref ta, ref tb, ra, rb) in &pairs {
                    let sa = circle_support(ra);
                    let sb = circle_support(rb);
                    black_box(gjk(&sa, &sb, ta, tb));
                }
            });
        });
    }

    group.finish();
}

fn benchmark_gjk_circle_polygon(c: &mut Criterion) {
    let mut group = c.benchmark_group("GJK Circle-Polygon");
    group.sample_size(50);

    let hex_vertices: Vec<Vec2> = (0..6)
        .map(|i| {
            let angle = i as f32 * std::f32::consts::PI / 3.0;
            Vec2::new(angle.cos() * 1.5, angle.sin() * 1.5)
        })
        .collect();

    for &overlapping in &[true, false] {
        let label = if overlapping { "overlapping" } else { "separated" };
        let pairs = setup_circle_polygon_pairs(100, overlapping);
        group.bench_function(format!("100 pairs - {}", label), |b| {
            b.iter(|| {
                for &(ref ta, ref tb, radius) in &pairs {
                    let sa = circle_support(radius);
                    let sb = polygon_support(hex_vertices.clone());
                    black_box(gjk(&sa, &sb, ta, tb));
                }
            });
        });
    }

    group.finish();
}

fn benchmark_gjk_polygon_polygon(c: &mut Criterion) {
    let mut group = c.benchmark_group("GJK Polygon-Polygon");
    group.sample_size(50);

    let hex_a: Vec<Vec2> = (0..6)
        .map(|i| {
            let angle = i as f32 * std::f32::consts::PI / 3.0;
            Vec2::new(angle.cos() * 1.5, angle.sin() * 1.5)
        })
        .collect();

    let hex_b: Vec<Vec2> = (0..5)
        .map(|i| {
            let angle = i as f32 * 2.0 * std::f32::consts::PI / 5.0;
            Vec2::new(angle.cos() * 1.2, angle.sin() * 1.2)
        })
        .collect();

    for &overlapping in &[true, false] {
        let label = if overlapping { "overlapping" } else { "separated" };
        let pairs = setup_polygon_polygon_pairs(100, overlapping);
        group.bench_function(format!("100 pairs - {}", label), |b| {
            b.iter(|| {
                for &(ref ta, ref tb) in &pairs {
                    let sa = polygon_support(hex_a.clone());
                    let sb = polygon_support(hex_b.clone());
                    black_box(gjk(&sa, &sb, ta, tb));
                }
            });
        });
    }

    group.finish();
}

fn benchmark_epa_circle_circle(c: &mut Criterion) {
    let mut group = c.benchmark_group("EPA Circle-Circle");
    group.sample_size(50);

    let pairs = setup_circle_pairs(50, true);
    group.bench_function("50 overlapping pairs", |b| {
        b.iter(|| {
            for &(ref ta, ref tb, ra, rb) in &pairs {
                let sa = circle_support(ra);
                let sb = circle_support(rb);
                black_box(detect_collision(sa, sb, ta, tb));
            }
        });
    });

    group.finish();
}

fn benchmark_detect_collision_full(c: &mut Criterion) {
    let mut group = c.benchmark_group("detect_collision Full Pipeline");
    group.sample_size(50);

    let pairs = setup_circle_pairs(100, true);
    group.bench_function("100 circle pairs (overlapping)", |b| {
        b.iter(|| {
            for &(ref ta, ref tb, ra, rb) in &pairs {
                let sa = circle_support(ra);
                let sb = circle_support(rb);
                black_box(detect_collision(sa, sb, ta, tb));
            }
        });
    });

    group.finish();
}

criterion_group!(
    benches,
    benchmark_gjk_circle_circle,
    benchmark_gjk_circle_polygon,
    benchmark_gjk_polygon_polygon,
    benchmark_epa_circle_circle,
    benchmark_detect_collision_full,
);
criterion_main!(benches);
