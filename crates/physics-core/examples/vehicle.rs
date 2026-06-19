use physics_core::{PhysicsWorld, SolverConfig};
use physics_core::collision::AABBTreeBroadPhase;
use physics_core::core::{BodyType, Material, Shape};
use physics_core::core::shape::{Circle, Rectangle, HalfSpace};
use physics_core::constraints::RevoluteJoint;
use physics_core::math::{Transform, Vec2, Rot2};

type World = PhysicsWorld<AABBTreeBroadPhase>;

fn main() {
    println!("=== 车辆物理模拟示例 ===");
    println!("本示例演示：车身矩形 + 4个圆形车轮 + 旋转关节 + 马达驱动");
    println!();

    // 物理参数配置
    // 车辆模拟需要更高的迭代次数来保证车轮和车身的连接稳定
    let solver_config = SolverConfig {
        velocity_iterations: 12,
        position_iterations: 6,
        time_step: 1.0 / 60.0,
        max_sub_steps: 10,
    };

    // 创建物理世界
    let mut world: World = PhysicsWorld::new()
        .with_gravity(Vec2::new(0.0, -9.81))
        .with_solver_config(solver_config);

    // ========== 材质定义 ==========

    // 地面材质：高摩擦，防止车轮打滑
    let ground_material = Material::new(
        0.1,    // restitution: 低弹性，减少颠簸
        0.9,    // static_friction: 高静摩擦，抓地力强
        0.7,    // dynamic_friction: 高动摩擦，制动效果好
        1.0,    // density
    );

    // 车身材质：中等密度，降低重心提高稳定性
    let chassis_material = Material::new(
        0.2,    // restitution
        0.3,    // static_friction
        0.2,    // dynamic_friction
        3.0,    // density: 较高密度，车身更稳
    );

    // 车轮材质：高摩擦，提供良好抓地力
    let wheel_material = Material::new(
        0.1,    // restitution: 低弹性，行驶平稳
        1.0,    // static_friction: 极高静摩擦，防止起步打滑
        0.8,    // dynamic_friction: 高动摩擦，转向和制动性能好
        2.0,    // density: 中等密度，车轮有一定重量
    );

    // ========== 创建场景 ==========

    // 地面（带坡度的地形）
    let ground_shape = Shape::HalfSpace(HalfSpace::ground());
    world.add_body(
        ground_shape,
        Vec2::new(0.0, 0.0),
        0.0,
        BodyType::Static,
        ground_material,
    );

    // 添加一个小斜坡（用静态矩形模拟）
    let ramp_shape = Shape::Rectangle(Rectangle::new(6.0, 0.5));
    world.add_body(
        ramp_shape,
        Vec2::new(8.0, 0.25),
        -0.15,  // 轻微倾斜角度（约8.6度）
        BodyType::Static,
        ground_material,
    );

    // 添加几个障碍物
    for i in 0..3 {
        let obstacle_shape = Shape::Rectangle(Rectangle::new(0.4, 0.3));
        world.add_body(
            obstacle_shape,
            Vec2::new(-5.0 + i as f32 * 2.5, 0.15),
            0.0,
            BodyType::Static,
            ground_material,
        );
    }

    println!("--- 车辆参数 ---");

    // ========== 车辆参数 ==========
    const CHASSIS_WIDTH: f32 = 2.4;    // 车身宽度
    const CHASSIS_HEIGHT: f32 = 0.6;   // 车身高度
    const WHEEL_RADIUS: f32 = 0.35;    // 车轮半径
    const WHEEL_BASE: f32 = 1.8;       // 轴距（前后轮距离）
    const TRACK_WIDTH: f32 = 1.6;      // 轮距（左右轮距离）

    const CHASSIS_MASS: f32 = 50.0;    // 车身质量
    const WHEEL_MASS: f32 = 10.0;      // 单个车轮质量

    // 马达参数
    const MOTOR_SPEED: f32 = 15.0;     // 马达角速度（弧度/秒）
    const MOTOR_MAX_TORQUE: f32 = 300.0; // 马达最大扭矩

    println!("车身尺寸: {:.1}m x {:.1}m", CHASSIS_WIDTH, CHASSIS_HEIGHT);
    println!("车轮半径: {:.2}m", WHEEL_RADIUS);
    println!("轴距: {:.1}m, 轮距: {:.1}m", WHEEL_BASE, TRACK_WIDTH);
    println!("总质量: {:.1}kg", CHASSIS_MASS + WHEEL_MASS * 4.0);
    println!("马达转速: {:.1} rad/s ({:.1} RPM)", MOTOR_SPEED, MOTOR_SPEED * 60.0 / (2.0 * std::f32::consts::PI));
    println!("最大扭矩: {:.1} N·m", MOTOR_MAX_TORQUE);
    println!("理论最高车速: {:.1} km/h", WHEEL_RADIUS * MOTOR_SPEED * 3.6);
    println!();

    // ========== 创建车辆 ==========

    // 车身位置（初始高度要让车轮刚好接触地面）
    let chassis_pos = Vec2::new(0.0, WHEEL_RADIUS + CHASSIS_HEIGHT * 0.5 + 0.5);

    // 创建车身
    let chassis_shape = Shape::Rectangle(Rectangle::new(CHASSIS_WIDTH, CHASSIS_HEIGHT));
    let chassis_handle = world.add_body(
        chassis_shape,
        chassis_pos,
        0.0,
        BodyType::Dynamic,
        chassis_material,
    );

    // 设置车身阻尼
    if let Some(body) = world.get_body_mut(chassis_handle) {
        body.linear_damping = 0.1;
        body.angular_damping = 0.1;
    }

    // 车轮位置（相对于车身中心）
    let wheel_offsets = [
        Vec2::new(-WHEEL_BASE * 0.5, -CHASSIS_HEIGHT * 0.5 - WHEEL_RADIUS * 0.1),  // 前轮左
        Vec2::new(WHEEL_BASE * 0.5, -CHASSIS_HEIGHT * 0.5 - WHEEL_RADIUS * 0.1),   // 后轮左
        Vec2::new(-WHEEL_BASE * 0.5, -CHASSIS_HEIGHT * 0.5 - WHEEL_RADIUS * 0.1),  // 前轮右
        Vec2::new(WHEEL_BASE * 0.5, -CHASSIS_HEIGHT * 0.5 - WHEEL_RADIUS * 0.1),   // 后轮右
    ];

    let mut wheel_handles = Vec::with_capacity(4);

    // 创建4个车轮
    for (i, &offset) in wheel_offsets.iter().enumerate() {
        let wheel_pos = chassis_pos + offset;
        let wheel_shape = Shape::Circle(Circle::new(WHEEL_RADIUS));
        let wheel_handle = world.add_body(
            wheel_shape,
            wheel_pos,
            0.0,
            BodyType::Dynamic,
            wheel_material,
        );

        // 设置车轮阻尼
        if let Some(body) = world.get_body_mut(wheel_handle) {
            body.linear_damping = 0.05;
            body.angular_damping = 0.05;
        }

        wheel_handles.push(wheel_handle);

        // 创建旋转关节连接车身和车轮
        let chassis_transform = Transform::new(chassis_pos, Rot2::new(0.0));
        let wheel_transform = Transform::new(wheel_pos, Rot2::new(0.0));

        // 关节锚点在车轮中心
        let anchor = wheel_pos;

        let mut joint = RevoluteJoint::new(chassis_handle, wheel_handle, anchor, &chassis_transform, &wheel_transform);

        // 后轮驱动（索引1和3是后轮），添加马达
        if i == 1 || i == 3 {
            joint = joint.with_motor(MOTOR_SPEED, MOTOR_MAX_TORQUE);
            println!("  车轮{} (后轮): 驱动轮，马达已启用", i + 1);
        } else {
            println!("  车轮{} (前轮): 从动轮", i + 1);
        }

        world.add_revolute_joint(joint);
    }

    println!();
    println!("--- 开始模拟 ---");
    println!();

    // ========== 模拟循环 ==========
    const SIMULATION_SECONDS: f32 = 10.0;
    const FPS: f32 = 60.0;
    const TOTAL_FRAMES: usize = (SIMULATION_SECONDS * FPS) as usize;

    let mut max_speed = 0.0f32;
    let mut min_height = f32::INFINITY;
    let mut max_height = f32::NEG_INFINITY;
    let mut distance_traveled = 0.0f32;
    let mut last_pos = chassis_pos.x;

    // 用于计算平均速度
    let mut speed_sum = 0.0f32;
    let mut speed_samples = 0;

    for frame in 0..TOTAL_FRAMES {
        world.step(1.0 / FPS);

        // 每20帧输出一次状态
        if frame % 20 == 0 {
            let time = frame as f32 / FPS;

            // 获取车身状态
            let (chassis_x, chassis_y, chassis_angle, chassis_speed) = if let Some(body) = world.get_body(chassis_handle) {
                (
                    body.position().x,
                    body.position().y,
                    body.angle().to_degrees(),
                    body.linear_velocity.x,
                )
            } else {
                (0.0, 0.0, 0.0, 0.0)
            };

            // 记录最高/最低高度
            if chassis_y > max_height {
                max_height = chassis_y;
            }
            if chassis_y < min_height {
                min_height = chassis_y;
            }

            // 记录最大速度
            if chassis_speed.abs() > max_speed {
                max_speed = chassis_speed.abs();
            }

            // 统计速度
            speed_sum += chassis_speed.abs();
            speed_samples += 1;

            // 计算行驶距离
            distance_traveled += (chassis_x - last_pos).abs();
            last_pos = chassis_x;

            // 获取车轮转速
            let mut wheel_speeds = Vec::with_capacity(4);
            for &handle in &wheel_handles {
                if let Some(body) = world.get_body(handle) {
                    wheel_speeds.push(body.angular_velocity);
                }
            }

            // 获取马达扭矩（后轮关节）
            let motor_torque = if let Some(joint) = world.revolute_joints().get(1) {
                joint.motor_impulse * FPS
            } else {
                0.0
            };

            println!(
                "t={:5.1}s | 位置: ({:5.1}, {:5.2})m | 角度: {:5.1}° | 速度: {:5.2}m/s ({:5.1}km/h) | 车轮转速: {:5.1}/{:5.1}/{:5.1}/{:5.1} rad/s | 马达扭矩: {:6.1}N·m",
                time,
                chassis_x,
                chassis_y,
                chassis_angle,
                chassis_speed,
                chassis_speed * 3.6,
                wheel_speeds.get(0).unwrap_or(&0.0),
                wheel_speeds.get(1).unwrap_or(&0.0),
                wheel_speeds.get(2).unwrap_or(&0.0),
                wheel_speeds.get(3).unwrap_or(&0.0),
                motor_torque
            );

            // 特殊事件提示
            if chassis_y < WHEEL_RADIUS + CHASSIS_HEIGHT * 0.5 - 0.1 {
                println!("  ⚠️  车身过低，可能底盘触地！");
            }
            if chassis_angle.abs() > 15.0 {
                println!("  ⚠️  车身倾斜角度过大！");
            }
        }
    }

    // ========== 模拟结果统计 ==========
    println!();
    println!("--- 模拟结束 ---");
    println!();

    let avg_speed = if speed_samples > 0 { speed_sum / speed_samples as f32 } else { 0.0 };

    println!("【车辆性能统计】");
    println!("  最高速度: {:.2} m/s ({:.1} km/h)", max_speed, max_speed * 3.6);
    println!("  平均速度: {:.2} m/s ({:.1} km/h)", avg_speed, avg_speed * 3.6);
    println!("  总行驶距离: {:.2} m", distance_traveled);
    println!("  车身最高位置: {:.2} m", max_height);
    println!("  车身最低位置: {:.2} m", min_height);
    println!("  垂直颠簸幅度: {:.2} m", max_height - min_height);
    println!();

    println!("【地形交互】");
    if distance_traveled > 15.0 {
        println!("  ✓ 车辆成功通过斜坡和障碍物");
    } else if distance_traveled > 5.0 {
        println!("  ⚠️  车辆行驶了一段距离，但可能被障碍物阻挡");
    } else {
        println!("  ✗ 车辆未能有效前进，可能打滑或马达动力不足");
    }
    println!();

    println!("【参数调整建议】");
    if max_speed < 3.0 {
        println!("  - 速度偏低，可增加马达转速或降低车身质量");
    }
    if max_height - min_height > 0.3 {
        println!("  - 颠簸较大，可增加阻尼或降低重心");
    }
    if avg_speed < max_speed * 0.3 {
        println!("  - 平均速度远低于最高速，可能频繁打滑，可增加地面摩擦");
    }
    println!();

    println!("✓ 车辆物理模拟完成！");
}
