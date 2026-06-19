use physics_core::{PhysicsWorld, SolverConfig};
use physics_core::collision::AABBTreeBroadPhase;
use physics_core::core::{BodyType, Material, Shape};
use physics_core::core::shape::{Circle, Rectangle, HalfSpace};
use physics_core::constraints::RevoluteJoint;
use physics_core::math::{Transform, Vec2, Rot2};

type World = PhysicsWorld<AABBTreeBroadPhase>;

fn main() {
    println!("=== 摆锤物理模拟示例 ===");
    println!("本示例包含：单摆（无马达）、单摆（有马达）、双摆");
    println!();

    // 物理参数配置
    // 速度迭代次数：10次，关节约束需要更多迭代来保证稳定性
    // 位置迭代次数：5次，确保关节位置约束准确
    // 时间步长：1/60秒
    // 最大子步数：10
    let solver_config = SolverConfig {
        velocity_iterations: 10,
        position_iterations: 5,
        time_step: 1.0 / 60.0,
        max_sub_steps: 10,
    };

    // 创建物理世界，重力设为标准地球重力
    let mut world: World = PhysicsWorld::new()
        .with_gravity(Vec2::new(0.0, -9.81))
        .with_solver_config(solver_config);

    // 地面材质
    let ground_material = Material::new(0.1, 0.8, 0.5, 1.0);

    // 摆锤材质：高密度，低阻尼，摆动更持久
    //  restitution: 0.1 - 低弹性
    //  friction: 0.2 - 低摩擦，减少能量损失
    //  density: 5.0 - 高密度，摆动惯量更大
    let pendulum_material = Material::new(0.1, 0.2, 0.2, 5.0);

    // 支点材质（静态）
    let pivot_material = Material::new(0.0, 0.0, 0.0, 0.0);

    // 创建地面
    let ground_shape = Shape::HalfSpace(HalfSpace::ground());
    world.add_body(
        ground_shape,
        Vec2::new(0.0, -3.0),
        0.0,
        BodyType::Static,
        ground_material,
    );

    println!("--- 创建三个摆锤系统 ---");
    println!();

    // ========== 1. 单摆（无马达） ==========
    println!("【单摆1 - 无马达】");
    const PENDULUM1_LENGTH: f32 = 2.5;   // 摆长
    const PENDULUM1_ANGLE: f32 = -0.6;   // 初始角度（弧度，约-34度）

    // 支点（静态物体）
    let pivot1_pos = Vec2::new(-6.0, 3.0);
    let pivot1_shape = Shape::Circle(Circle::new(0.15));
    let pivot1_handle = world.add_body(
        pivot1_shape,
        pivot1_pos,
        0.0,
        BodyType::Static,
        pivot_material,
    );

    // 摆锤（圆形重物）
    let bob1_shape = Shape::Circle(Circle::new(0.3));
    let bob1_initial_pos = pivot1_pos + Vec2::new(
        PENDULUM1_LENGTH * PENDULUM1_ANGLE.sin(),
        -PENDULUM1_LENGTH * PENDULUM1_ANGLE.cos(),
    );
    let bob1_handle = world.add_body(
        bob1_shape,
        bob1_initial_pos,
        0.0,
        BodyType::Dynamic,
        pendulum_material,
    );

    // 设置摆锤阻尼，模拟空气阻力
    if let Some(body) = world.get_body_mut(bob1_handle) {
        body.linear_damping = 0.05;
        body.angular_damping = 0.05;
    }

    // 创建旋转关节连接支点和摆锤
    let ta1 = Transform::new(pivot1_pos, Rot2::new(0.0));
    let tb1 = Transform::new(bob1_initial_pos, Rot2::new(0.0));
    let joint1 = RevoluteJoint::new(pivot1_handle, bob1_handle, pivot1_pos, &ta1, &tb1);
    world.add_revolute_joint(joint1);

    println!("  摆长: {:.1}m", PENDULUM1_LENGTH);
    println!("  初始角度: {:.1}°", PENDULUM1_ANGLE.to_degrees());
    println!("  理论周期: {:.3}s", 2.0 * std::f32::consts::PI * (PENDULUM1_LENGTH / 9.81).sqrt());
    println!();

    // ========== 2. 单摆（有马达） ==========
    println!("【单摆2 - 带马达】");
    const PENDULUM2_LENGTH: f32 = 2.0;    // 摆长
    const MOTOR_SPEED: f32 = 2.0;         // 马达角速度（弧度/秒）
    const MOTOR_MAX_TORQUE: f32 = 100.0;  // 马达最大扭矩

    // 支点（静态物体）
    let pivot2_pos = Vec2::new(0.0, 3.0);
    let pivot2_shape = Shape::Circle(Circle::new(0.15));
    let pivot2_handle = world.add_body(
        pivot2_shape,
        pivot2_pos,
        0.0,
        BodyType::Static,
        pivot_material,
    );

    // 摆锤杆（矩形）
    let rod2_shape = Shape::Rectangle(Rectangle::new(0.1, PENDULUM2_LENGTH));
    let rod2_pos = pivot2_pos + Vec2::new(0.0, -PENDULUM2_LENGTH * 0.5);
    let rod2_handle = world.add_body(
        rod2_shape,
        rod2_pos,
        0.0,
        BodyType::Dynamic,
        pendulum_material,
    );

    // 摆锤末端重物
    let bob2_shape = Shape::Circle(Circle::new(0.35));
    let bob2_pos = pivot2_pos + Vec2::new(0.0, -PENDULUM2_LENGTH);
    let bob2_handle = world.add_body(
        bob2_shape,
        bob2_pos,
        0.0,
        BodyType::Dynamic,
        pendulum_material,
    );

    // 设置阻尼
    if let Some(body) = world.get_body_mut(rod2_handle) {
        body.linear_damping = 0.02;
        body.angular_damping = 0.02;
    }
    if let Some(body) = world.get_body_mut(bob2_handle) {
        body.linear_damping = 0.02;
        body.angular_damping = 0.02;
    }

    // 关节1：连接支点和摆杆（带马达）
    let ta2 = Transform::new(pivot2_pos, Rot2::new(0.0));
    let tb2_rod = Transform::new(rod2_pos, Rot2::new(0.0));
    let joint2_pivot = RevoluteJoint::new(pivot2_handle, rod2_handle, pivot2_pos, &ta2, &tb2_rod)
        .with_motor(MOTOR_SPEED, MOTOR_MAX_TORQUE);  // 添加马达
    world.add_revolute_joint(joint2_pivot);

    // 关节2：连接摆杆和末端重物（焊接效果，用旋转关节但无马达）
    let rod_end_pos = pivot2_pos + Vec2::new(0.0, -PENDULUM2_LENGTH);
    let ta2_rod = Transform::new(rod2_pos, Rot2::new(0.0));
    let tb2_bob = Transform::new(bob2_pos, Rot2::new(0.0));
    let joint2_bob = RevoluteJoint::new(rod2_handle, bob2_handle, rod_end_pos, &ta2_rod, &tb2_bob);
    world.add_revolute_joint(joint2_bob);

    println!("  摆长: {:.1}m", PENDULUM2_LENGTH);
    println!("  马达速度: {:.1} rad/s ({:.1} °/s)", MOTOR_SPEED, MOTOR_SPEED.to_degrees());
    println!("  最大扭矩: {:.1} N·m", MOTOR_MAX_TORQUE);
    println!();

    // ========== 3. 双摆 ==========
    println!("【双摆 - 混沌系统】");
    const DOUBLE_LENGTH1: f32 = 1.8;  // 第一段摆长
    const DOUBLE_LENGTH2: f32 = 1.5;  // 第二段摆长
    const DOUBLE_ANGLE1: f32 = 2.0;   // 第一段初始角度（约115度）
    const DOUBLE_ANGLE2: f32 = 1.5;   // 第二段初始角度（约86度）

    // 支点（静态物体）
    let pivot3_pos = Vec2::new(6.0, 3.5);
    let pivot3_shape = Shape::Circle(Circle::new(0.15));
    let pivot3_handle = world.add_body(
        pivot3_shape,
        pivot3_pos,
        0.0,
        BodyType::Static,
        pivot_material,
    );

    // 第一段摆锤
    let bob3a_shape = Shape::Circle(Circle::new(0.25));
    let bob3a_pos = pivot3_pos + Vec2::new(
        DOUBLE_LENGTH1 * DOUBLE_ANGLE1.sin(),
        -DOUBLE_LENGTH1 * DOUBLE_ANGLE1.cos(),
    );
    let bob3a_handle = world.add_body(
        bob3a_shape,
        bob3a_pos,
        0.0,
        BodyType::Dynamic,
        pendulum_material,
    );

    // 第二段摆锤
    let bob3b_shape = Shape::Circle(Circle::new(0.2));
    let bob3b_pos = bob3a_pos + Vec2::new(
        DOUBLE_LENGTH2 * DOUBLE_ANGLE2.sin(),
        -DOUBLE_LENGTH2 * DOUBLE_ANGLE2.cos(),
    );
    let bob3b_handle = world.add_body(
        bob3b_shape,
        bob3b_pos,
        0.0,
        BodyType::Dynamic,
        pendulum_material,
    );

    // 设置低阻尼，双摆对初始条件敏感
    if let Some(body) = world.get_body_mut(bob3a_handle) {
        body.linear_damping = 0.01;
        body.angular_damping = 0.01;
    }
    if let Some(body) = world.get_body_mut(bob3b_handle) {
        body.linear_damping = 0.01;
        body.angular_damping = 0.01;
    }

    // 关节1：连接支点和第一段摆锤
    let ta3 = Transform::new(pivot3_pos, Rot2::new(0.0));
    let tb3a = Transform::new(bob3a_pos, Rot2::new(0.0));
    let joint3a = RevoluteJoint::new(pivot3_handle, bob3a_handle, pivot3_pos, &ta3, &tb3a);
    world.add_revolute_joint(joint3a);

    // 关节2：连接第一段和第二段摆锤
    let ta3a = Transform::new(bob3a_pos, Rot2::new(0.0));
    let tb3b = Transform::new(bob3b_pos, Rot2::new(0.0));
    let joint3b = RevoluteJoint::new(bob3a_handle, bob3b_handle, bob3a_pos, &ta3a, &tb3b);
    world.add_revolute_joint(joint3b);

    println!("  第一段摆长: {:.1}m", DOUBLE_LENGTH1);
    println!("  第二段摆长: {:.1}m", DOUBLE_LENGTH2);
    println!("  第一段初始角度: {:.1}°", DOUBLE_ANGLE1.to_degrees());
    println!("  第二段初始角度: {:.1}°", DOUBLE_ANGLE2.to_degrees());
    println!("  特性: 混沌系统，对初始条件极其敏感");
    println!();

    println!("--- 开始模拟 ---");
    println!();

    // 模拟8秒
    const SIMULATION_SECONDS: f32 = 8.0;
    const FPS: f32 = 60.0;
    const TOTAL_FRAMES: usize = (SIMULATION_SECONDS * FPS) as usize;

    let mut max_bob1_height = f32::NEG_INFINITY;
    let mut min_bob1_height = f32::INFINITY;
    let mut max_bob3b_speed = 0.0f32;

    for frame in 0..TOTAL_FRAMES {
        world.step(1.0 / FPS);

        // 每15帧输出一次状态
        if frame % 15 == 0 {
            let time = frame as f32 / FPS;

            // 获取单摆1的状态
            let (bob1_angle, bob1_speed) = if let Some(body) = world.get_body(bob1_handle) {
                let rel_pos = body.position() - pivot1_pos;
                let angle = rel_pos.x.atan2(-rel_pos.y).to_degrees();
                let speed = body.linear_velocity.length();
                (angle, speed)
            } else {
                (0.0, 0.0)
            };

            // 记录单摆1的最高/最低点
            if let Some(body) = world.get_body(bob1_handle) {
                let h = body.position().y;
                if h > max_bob1_height {
                    max_bob1_height = h;
                }
                if h < min_bob1_height {
                    min_bob1_height = h;
                }
            }

            // 获取单摆2的状态（马达驱动）
            let (rod2_angle, motor_torque) = if let Some(body) = world.get_body(rod2_handle) {
                let angle = body.angle().to_degrees();
                let torque = if let Some(joint) = world.revolute_joints().get(1) {
                    joint.motor_impulse * FPS
                } else {
                    0.0
                };
                (angle, torque)
            } else {
                (0.0, 0.0)
            };

            // 获取双摆末端速度
            if let Some(body) = world.get_body(bob3b_handle) {
                let speed = body.linear_velocity.length();
                if speed > max_bob3b_speed {
                    max_bob3b_speed = speed;
                }
            }

            println!(
                "t={:5.2}s | 单摆1: 角度={:6.1}° 速度={:5.2}m/s | 单摆2: 角度={:6.1}° 力矩={:6.1}N·m | 双摆末端: 最高速度={:5.2}m/s",
                time,
                bob1_angle,
                bob1_speed,
                rod2_angle,
                motor_torque,
                max_bob3b_speed
            );
        }
    }

    println!();
    println!("--- 模拟结束 ---");
    println!();

    // 统计结果
    let bob1_amplitude = (max_bob1_height - min_bob1_height) * 0.5;
    println!("【单摆1统计】");
    println!("  最高位置: {:.2}m", max_bob1_height);
    println!("  最低位置: {:.2}m", min_bob1_height);
    println!("  摆动幅度: {:.2}m", bob1_amplitude);
    println!("  能量衰减: 由于阻尼，振幅会逐渐减小");
    println!();

    println!("【单摆2统计】");
    println!("  马达持续驱动，摆锤以恒定角速度旋转");
    println!("  理论转速: {:.1} RPM", MOTOR_SPEED * 60.0 / (2.0 * std::f32::consts::PI));
    println!();

    println!("【双摆统计】");
    println!("  末端最大速度: {:.2}m/s", max_bob3b_speed);
    println!("  混沌特性: 微小的初始角度差异会导致完全不同的运动轨迹");
    println!();

    println!("✓ 所有摆锤系统模拟完成！");
}
