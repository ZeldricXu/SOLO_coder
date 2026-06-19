use criterion::{black_box, criterion_group, criterion_main, Criterion};

use slotmap::SlotMap;

use physics_collision::{AABBTreeBroadPhase, NarrowPhase, ContactManifold};
use physics_constraints::{ConstraintSolver, ContactConstraint, RevoluteJoint, DistanceJoint, PrismaticJoint, WeldJoint};
use physics_types::{Body, BodyHandle, BodyType, Material, Shape, Circle, Rectangle};
use physics_math::{Rot2, Transform, Vec2};

struct ConstraintScene {
    bodies: SlotMap<BodyHandle, Body>,
    contacts: Vec<ContactConstraint>,
    revolute_joints: Vec<RevoluteJoint>,
    solver: ConstraintSolver,
}

fn setup_constraint_scene(contact_count: usize, joint_count: usize) -> ConstraintScene {
    let mut bodies: SlotMap<BodyHandle, Body> = SlotMap::with_key();
    let mut contacts = Vec::new();
    let mut revolute_joints = Vec::new();

    let ground_shape = Shape::Rectangle(Rectangle::new(200.0, 1.0));
    let ground_handle = bodies.insert_with_key(|handle| {
        Body::new(handle, ground_shape, Vec2::new(0.0, -0.5), 0.0, BodyType::Static, Material::DEFAULT)
    });

    let box_size = 1.0;
    let spacing = 0.05;
    let cols = (contact_count as f32).sqrt().floor() as usize;
    let mut dynamic_handles = Vec::new();

    for i in 0..contact_count {
        let row = i / cols;
        let col = i % cols;
        let x = (col as f32 - cols as f32 / 2.0) * (box_size + spacing);
        let y = 0.5 + box_size / 2.0 + row as f32 * (box_size + spacing);
        let shape = Shape::Rectangle(Rectangle::new(box_size, box_size));
        let handle = bodies.insert_with_key(|h| {
            Body::new(h, shape, Vec2::new(x, y), 0.0, BodyType::Dynamic, Material::DEFAULT)
        });
        dynamic_handles.push(handle);
    }

    let narrow = NarrowPhase::new();
    for &dh in &dynamic_handles {
        if let (Some(ground), Some(dynamic)) = (bodies.get(ground_handle), bodies.get(dh)) {
            if let Some(manifold) = narrow.collide(ground, ground_handle, dynamic, dh) {
                if let (Some(ga), Some(db)) = (bodies.get(ground_handle), bodies.get(dh)) {
                    contacts.push(ContactConstraint::new(&manifold, ga, db));
                }
            }
        }
    }

    let joint_pairs: usize = joint_count.min(dynamic_handles.len() / 2);
    for i in 0..joint_pairs {
        let idx_a = i * 2;
        let idx_b = i * 2 + 1;
        if idx_b < dynamic_handles.len() {
            let ha = dynamic_handles[idx_a];
            let hb = dynamic_handles[idx_b];
            if let (Some(ba), Some(bb)) = (bodies.get(ha), bodies.get(hb)) {
                let anchor = (ba.position() + bb.position()) * 0.5;
                let ta = Transform::new(ba.position(), Rot2::new(ba.angle()));
                let tb = Transform::new(bb.position(), Rot2::new(bb.angle()));
                let joint = RevoluteJoint::new(ha, hb, anchor, &ta, &tb);
                revolute_joints.push(joint);
            }
        }
    }

    let solver = ConstraintSolver::new(8, 3);

    ConstraintScene {
        bodies,
        contacts,
        revolute_joints,
        solver,
    }
}

fn benchmark_constraint_solve(c: &mut Criterion) {
    let mut group = c.benchmark_group("Constraint Solver");
    group.sample_size(20);
    group.measurement_time(std::time::Duration::from_secs(5));

    for &(contacts, joints) in &[(100usize, 50usize), (200, 100), (500, 200)] {
        group.bench_function(
            format!("{} contacts + {} joints", contacts, joints),
            |b| {
                b.iter_batched(
                    || setup_constraint_scene(contacts, joints),
                    |mut scene| {
                        scene.solver.solve_all::<ContactConstraint, RevoluteJoint, DistanceJoint, PrismaticJoint, WeldJoint>(
                            &mut scene.contacts,
                            &mut scene.revolute_joints,
                            &mut [] as &mut [DistanceJoint],
                            &mut [] as &mut [PrismaticJoint],
                            &mut [] as &mut [WeldJoint],
                            &mut scene.bodies,
                            1.0 / 60.0,
                        );
                        black_box(&scene);
                    },
                    criterion::BatchSize::SmallInput,
                );
            },
        );
    }

    group.finish();
}

fn benchmark_constraint_iterations(c: &mut Criterion) {
    let mut group = c.benchmark_group("Constraint Solver Iterations");
    group.sample_size(20);
    group.measurement_time(std::time::Duration::from_secs(5));

    for &(vel_iter, pos_iter) in &[(4, 2), (8, 3), (16, 6)] {
        group.bench_function(
            format!("100c+50j vel={} pos={}", vel_iter, pos_iter),
            |b| {
                b.iter_batched(
                    || {
                        let mut scene = setup_constraint_scene(100, 50);
                        scene.solver = ConstraintSolver::new(vel_iter, pos_iter);
                        scene
                    },
                    |mut scene| {
                        scene.solver.solve_all::<ContactConstraint, RevoluteJoint, DistanceJoint, PrismaticJoint, WeldJoint>(
                            &mut scene.contacts,
                            &mut scene.revolute_joints,
                            &mut [] as &mut [DistanceJoint],
                            &mut [] as &mut [PrismaticJoint],
                            &mut [] as &mut [WeldJoint],
                            &mut scene.bodies,
                            1.0 / 60.0,
                        );
                        black_box(&scene);
                    },
                    criterion::BatchSize::SmallInput,
                );
            },
        );
    }

    group.finish();
}

criterion_group!(benches, benchmark_constraint_solve, benchmark_constraint_iterations);
criterion_main!(benches);
