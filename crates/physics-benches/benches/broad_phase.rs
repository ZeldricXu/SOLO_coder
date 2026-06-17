use criterion::{black_box, criterion_group, criterion_main, Criterion};
use rand::Rng;

use physics_collision::{AABBTreeBroadPhase, BroadPhase, BruteForceBroadPhase};
use physics_core::{Body, BodyHandle, BodyType, Material, Shape, Circle};
use physics_math::Vec2;
use slotmap::SlotMap;

fn create_random_bodies(count: usize, world_size: f32) -> (SlotMap<BodyHandle, Body>, Vec<BodyHandle>) {
    let mut rng = rand::thread_rng();
    let mut bodies = SlotMap::with_key();
    let mut handles = Vec::with_capacity(count);

    for _ in 0..count {
        let x = rng.gen_range(-world_size..world_size);
        let y = rng.gen_range(-world_size..world_size);
        let radius = rng.gen_range(0.5..2.0);
        let shape = Shape::Circle(Circle::new(radius));
        let position = Vec2::new(x, y);

        let handle = bodies.insert_with_key(|handle| {
            Body::new(handle, shape, position, 0.0, BodyType::Dynamic, Material::DEFAULT)
        });
        handles.push(handle);
    }

    (bodies, handles)
}

fn populate_broad_phase<BP: BroadPhase>(bp: &mut BP, bodies: &SlotMap<BodyHandle, Body>) {
    for (handle, body) in bodies {
        bp.add_body(handle, body);
    }
}

fn benchmark_500_bodies(c: &mut Criterion) {
    let mut group = c.benchmark_group("Broad Phase 500 Bodies (dense)");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(5));

    let (bodies, _handles) = create_random_bodies(500, 20.0);

    group.bench_function("BruteForce - get_potential_pairs", |b| {
        let mut bp = BruteForceBroadPhase::default();
        populate_broad_phase(&mut bp, &bodies);
        b.iter(|| {
            black_box(bp.get_potential_pairs());
        });
    });

    group.bench_function("AABBTree - get_potential_pairs", |b| {
        let mut bp = AABBTreeBroadPhase::default();
        populate_broad_phase(&mut bp, &bodies);
        b.iter(|| {
            black_box(bp.get_potential_pairs());
        });
    });

    group.finish();
}

fn benchmark_200_bodies(c: &mut Criterion) {
    let mut group = c.benchmark_group("Broad Phase 200 Bodies");
    group.sample_size(20);
    group.measurement_time(std::time::Duration::from_secs(5));

    let (bodies, _handles) = create_random_bodies(200, 30.0);

    group.bench_function("BruteForce - get_potential_pairs", |b| {
        let mut bp = BruteForceBroadPhase::default();
        populate_broad_phase(&mut bp, &bodies);
        b.iter(|| {
            black_box(bp.get_potential_pairs());
        });
    });

    group.bench_function("AABBTree - get_potential_pairs", |b| {
        let mut bp = AABBTreeBroadPhase::default();
        populate_broad_phase(&mut bp, &bodies);
        b.iter(|| {
            black_box(bp.get_potential_pairs());
        });
    });

    group.finish();
}

fn benchmark_add_remove(c: &mut Criterion) {
    let mut group = c.benchmark_group("Broad Phase Add/Remove 500");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(5));

    let (bodies, handles) = create_random_bodies(500, 50.0);

    group.bench_function("BruteForce - add 500 bodies", |b| {
        b.iter(|| {
            let mut bp = BruteForceBroadPhase::default();
            for &handle in &handles {
                let body = bodies.get(handle).unwrap();
                bp.add_body(handle, body);
            }
            black_box(bp);
        });
    });

    group.bench_function("AABBTree - add 500 bodies", |b| {
        b.iter(|| {
            let mut bp = AABBTreeBroadPhase::default();
            for &handle in &handles {
                let body = bodies.get(handle).unwrap();
                bp.add_body(handle, body);
            }
            black_box(bp);
        });
    });

    group.finish();
}

fn benchmark_update(c: &mut Criterion) {
    let mut group = c.benchmark_group("Broad Phase Update 500");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(5));

    let (bodies, handles) = create_random_bodies(500, 50.0);

    let mut rng = rand::thread_rng();
    let mut updated_bodies = bodies.clone();
    for &handle in &handles {
        let body = updated_bodies.get_mut(handle).unwrap();
        let dx = rng.gen_range(-2.0..2.0);
        let dy = rng.gen_range(-2.0..2.0);
        body.transform.position += Vec2::new(dx, dy);
    }

    group.bench_function("BruteForce - update 500 bodies", |b| {
        let mut bp = BruteForceBroadPhase::default();
        for &handle in &handles {
            let body = bodies.get(handle).unwrap();
            bp.add_body(handle, body);
        }
        b.iter(|| {
            for &handle in &handles {
                let body = updated_bodies.get(handle).unwrap();
                bp.update_body(handle, body);
            }
            black_box(bp.get_potential_pairs());
        });
    });

    group.bench_function("AABBTree - update 500 bodies", |b| {
        let mut bp = AABBTreeBroadPhase::default();
        for &handle in &handles {
            let body = bodies.get(handle).unwrap();
            bp.add_body(handle, body);
        }
        b.iter(|| {
            for &handle in &handles {
                let body = updated_bodies.get(handle).unwrap();
                bp.update_body(handle, body);
            }
            black_box(bp.get_potential_pairs());
        });
    });

    group.finish();
}

fn benchmark_1000_bodies(c: &mut Criterion) {
    let mut group = c.benchmark_group("Broad Phase 1000 Bodies (dense)");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(5));

    let (bodies, _handles) = create_random_bodies(1000, 30.0);

    let mut bp_brute = BruteForceBroadPhase::default();
    populate_broad_phase(&mut bp_brute, &bodies);
    let pairs_brute = bp_brute.get_potential_pairs().len();

    let mut bp_tree = AABBTreeBroadPhase::default();
    populate_broad_phase(&mut bp_tree, &bodies);
    let pairs_tree = bp_tree.get_potential_pairs().len();

    println!("\n=== 1000 Bodies Statistics ===");
    println!("BruteForce potential pairs: {}", pairs_brute);
    println!("AABBTree potential pairs:   {}", pairs_tree);

    group.bench_function("BruteForce - get_potential_pairs", |b| {
        let mut bp = BruteForceBroadPhase::default();
        populate_broad_phase(&mut bp, &bodies);
        b.iter(|| {
            black_box(bp.get_potential_pairs());
        });
    });

    group.bench_function("AABBTree - get_potential_pairs", |b| {
        let mut bp = AABBTreeBroadPhase::default();
        populate_broad_phase(&mut bp, &bodies);
        b.iter(|| {
            black_box(bp.get_potential_pairs());
        });
    });

    group.finish();
}

fn benchmark_full_step_500(c: &mut Criterion) {
    let mut group = c.benchmark_group("Full Physics Step 500 Bodies");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(5));

    group.bench_function("BruteForce - full step", |b| {
        let mut world: physics_engine::PhysicsWorld<BruteForceBroadPhase> = physics_engine::PhysicsWorld::new();
        let mut rng = rand::thread_rng();
        for _ in 0..500 {
            let x = rng.gen_range(-20.0..20.0);
            let y = rng.gen_range(-20.0..20.0);
            let radius = rng.gen_range(0.5..1.5);
            let shape = Shape::Circle(Circle::new(radius));
            world.add_body(shape, Vec2::new(x, y), 0.0, BodyType::Dynamic, Material::DEFAULT);
        }
        let dt = 1.0 / 60.0;
        b.iter(|| {
            black_box(world.step_single(dt));
        });
    });

    group.bench_function("AABBTree - full step", |b| {
        let mut world: physics_engine::PhysicsWorld<AABBTreeBroadPhase> = physics_engine::PhysicsWorld::new();
        let mut rng = rand::thread_rng();
        for _ in 0..500 {
            let x = rng.gen_range(-20.0..20.0);
            let y = rng.gen_range(-20.0..20.0);
            let radius = rng.gen_range(0.5..1.5);
            let shape = Shape::Circle(Circle::new(radius));
            world.add_body(shape, Vec2::new(x, y), 0.0, BodyType::Dynamic, Material::DEFAULT);
        }
        let dt = 1.0 / 60.0;
        b.iter(|| {
            black_box(world.step_single(dt));
        });
    });

    group.finish();
}

fn benchmark_2000_bodies(c: &mut Criterion) {
    let mut group = c.benchmark_group("Broad Phase 2000 Bodies (dense)");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(8));

    let (bodies, _handles) = create_random_bodies(2000, 40.0);

    let mut bp_brute = BruteForceBroadPhase::default();
    populate_broad_phase(&mut bp_brute, &bodies);
    let pairs_brute = bp_brute.get_potential_pairs().len();

    let mut bp_tree = AABBTreeBroadPhase::default();
    populate_broad_phase(&mut bp_tree, &bodies);
    let pairs_tree = bp_tree.get_potential_pairs().len();

    println!("\n=== 2000 Bodies Statistics ===");
    println!("BruteForce potential pairs: {}", pairs_brute);
    println!("AABBTree potential pairs:   {}", pairs_tree);

    group.bench_function("BruteForce - get_potential_pairs", |b| {
        let mut bp = BruteForceBroadPhase::default();
        populate_broad_phase(&mut bp, &bodies);
        b.iter(|| {
            black_box(bp.get_potential_pairs());
        });
    });

    group.bench_function("AABBTree - get_potential_pairs", |b| {
        let mut bp = AABBTreeBroadPhase::default();
        populate_broad_phase(&mut bp, &bodies);
        b.iter(|| {
            black_box(bp.get_potential_pairs());
        });
    });

    group.finish();
}

fn benchmark_full_step_1000(c: &mut Criterion) {
    let mut group = c.benchmark_group("Full Physics Step 1000 Bodies");
    group.sample_size(10);
    group.measurement_time(std::time::Duration::from_secs(8));

    group.bench_function("BruteForce - full step", |b| {
        let mut world: physics_engine::PhysicsWorld<BruteForceBroadPhase> = physics_engine::PhysicsWorld::new();
        let mut rng = rand::thread_rng();
        for _ in 0..1000 {
            let x = rng.gen_range(-30.0..30.0);
            let y = rng.gen_range(-30.0..30.0);
            let radius = rng.gen_range(0.5..1.5);
            let shape = Shape::Circle(Circle::new(radius));
            world.add_body(shape, Vec2::new(x, y), 0.0, BodyType::Dynamic, Material::DEFAULT);
        }
        let dt = 1.0 / 60.0;
        b.iter(|| {
            black_box(world.step_single(dt));
        });
    });

    group.bench_function("AABBTree - full step", |b| {
        let mut world: physics_engine::PhysicsWorld<AABBTreeBroadPhase> = physics_engine::PhysicsWorld::new();
        let mut rng = rand::thread_rng();
        for _ in 0..1000 {
            let x = rng.gen_range(-30.0..30.0);
            let y = rng.gen_range(-30.0..30.0);
            let radius = rng.gen_range(0.5..1.5);
            let shape = Shape::Circle(Circle::new(radius));
            world.add_body(shape, Vec2::new(x, y), 0.0, BodyType::Dynamic, Material::DEFAULT);
        }
        let dt = 1.0 / 60.0;
        b.iter(|| {
            black_box(world.step_single(dt));
        });
    });

    group.finish();
}

criterion_group!(
    benches,
    benchmark_200_bodies,
    benchmark_500_bodies,
    benchmark_1000_bodies,
    benchmark_2000_bodies,
    benchmark_add_remove,
    benchmark_update,
    benchmark_full_step_500,
    benchmark_full_step_1000
);
criterion_main!(benches);
