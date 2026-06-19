use criterion::{black_box, criterion_group, criterion_main, Criterion};

use physics_collision::AABBTreeBroadPhase;
use physics_core::PhysicsWorld;
use physics_types::{BodyType, Material, Shape, Rectangle};
use physics_math::Vec2;

fn setup_stacked_bodies(count: usize) -> PhysicsWorld<AABBTreeBroadPhase> {
    let mut world: PhysicsWorld<AABBTreeBroadPhase> = PhysicsWorld::new();

    let ground_shape = Shape::Rectangle(Rectangle::new(100.0, 1.0));
    world.add_body(
        ground_shape,
        Vec2::new(0.0, -0.5),
        0.0,
        BodyType::Static,
        Material::DEFAULT,
    );

    let cols = (count as f32).sqrt().floor() as usize;
    let rows = (count + cols - 1) / cols;
    let box_size = 1.0;
    let spacing = 0.05;

    for row in 0..rows {
        for col in 0..cols {
            let idx = row * cols + col;
            if idx >= count {
                break;
            }
            let x = (col as f32 - cols as f32 / 2.0) * (box_size + spacing);
            let y = 0.5 + box_size / 2.0 + row as f32 * (box_size + spacing);
            let shape = Shape::Rectangle(Rectangle::new(box_size, box_size));
            world.add_body(
                shape,
                Vec2::new(x, y),
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            );
        }
    }

    world
}

fn benchmark_step_single(c: &mut Criterion) {
    let mut group = c.benchmark_group("Physics Step Single");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(5));

    for &count in &[100usize, 500, 2000] {
        group.bench_function(format!("{} stacked bodies - step_single", count), |b| {
            let mut world = setup_stacked_bodies(count);
            let dt = 1.0 / 60.0;
            b.iter(|| {
                black_box(world.step_single(dt));
            });
        });
    }

    group.finish();
}

fn benchmark_step(c: &mut Criterion) {
    let mut group = c.benchmark_group("Physics Step (Adaptive Substeps)");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(5));

    for &count in &[100usize, 500, 2000] {
        group.bench_function(format!("{} stacked bodies - step (adaptive)", count), |b| {
            let mut world = setup_stacked_bodies(count);
            let dt = 1.0 / 60.0 * 2.5;
            b.iter(|| {
                black_box(world.step(dt));
            });
        });
    }

    group.finish();
}

criterion_group!(benches, benchmark_step_single, benchmark_step);
criterion_main!(benches);
