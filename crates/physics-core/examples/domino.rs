use physics_core::{PhysicsWorld, SolverConfig};
use physics_core::collision::AABBTreeBroadPhase;
use physics_core::core::{BodyType, Material, Shape};
use physics_core::core::shape::{Circle, Rectangle, HalfSpace};
use physics_core::math::{Transform, Vec2, Rot2};

type World = PhysicsWorld<AABBTreeBroadPhase>;

fn main() {
    println!("=== 多米诺骨牌倒塌示例 ===");

    // 物理参数配置
    // 速度迭代次数：8次，确保碰撞和关节约束求解稳定
    // 位置迭代次数：3次，减少穿透
    // 时间步长：1/60秒，标准物理模拟步长
    // 最大子步数：10，防止大时间步导致的不稳定
    let solver_config = SolverConfig {
        velocity_iterations: 8,
        position_iterations: 3,
        time_step: 1.0 / 60.0,
        max_sub_steps: 10,
    };

    // 创建物理世界，重力设为标准地球重力
    let mut world: World = PhysicsWorld::new()
        .with_gravity(Vec2::new(0.0, -9.81))
        .with_solver_config(solver_config);

    // 地面材质：低弹性，高摩擦，防止骨牌滑动
    //  restitution: 0.1 - 几乎不反弹
    //  static_friction: 0.8 - 高静摩擦，骨牌站立时稳定
    //  dynamic_friction: 0.5 - 动摩擦适中
    //  density: 1.0 - 密度适中
    let ground_material = Material::new(0.1, 0.8, 0.5, 1.0);

    // 骨牌材质：中等弹性，低摩擦，便于倒塌连锁反应
    //  restitution: 0.0 - 完全非弹性碰撞，能量传递更真实
    //  static_friction: 0.4 - 较低静摩擦，容易被推倒
    //  dynamic_friction: 0.3 - 低动摩擦，倒塌流畅
    //  density: 2.0 - 较高密度，骨牌有足够重量
    let domino_material = Material::new(0.0, 0.4, 0.3, 2.0);

    // 创建地面（使用半空间，无限大平面）
    let ground_shape = Shape::HalfSpace(HalfSpace::ground());
    world.add_body(
        ground_shape,
        Vec2::new(0.0, 0.0),
        0.0,
        BodyType::Static,
        ground_material,
    );

    // 骨牌尺寸参数
    const DOMINO_WIDTH: f32 = 0.2;    // 骨牌宽度（厚度）
    const DOMINO_HEIGHT: f32 = 1.2;   // 骨牌高度
    const DOMINO_SPACING: f32 = 0.6;  // 骨牌间距（约为高度的一半）
    const DOMINO_COUNT: usize = 15;    // 骨牌数量

    // 创建一排骨牌
    let mut domino_handles = Vec::with_capacity(DOMINO_COUNT);
    for i in 0..DOMINO_COUNT {
        // 每个骨牌的X坐标，从左到右排列
        let x = -8.0 + i as f32 * DOMINO_SPACING;
        // Y坐标为高度的一半，确保骨牌底部在地面上
        let y = DOMINO_HEIGHT * 0.5;

        // 创建矩形骨牌
        let domino_shape = Shape::Rectangle(Rectangle::new(DOMINO_WIDTH, DOMINO_HEIGHT));
        let handle = world.add_body(
            domino_shape,
            Vec2::new(x, y),
            0.0,
            BodyType::Dynamic,
            domino_material,
        );

        // 设置线性阻尼和角阻尼，防止过度抖动
        if let Some(body) = world.get_body_mut(handle) {
            body.linear_damping = 0.05;  // 轻微线性阻尼
            body.angular_damping = 0.05; // 轻微角阻尼
        }

        domino_handles.push(handle);
    }

    // 给第一个骨牌施加一个向右的冲量，推倒它
    // 冲量作用点在骨牌顶部附近，产生较大的力矩
    if let Some(&first_handle) = domino_handles.first() {
        if let Some(first_body) = world.get_body(first_handle) {
            // 冲量大小：5.0单位，向右
            // 作用点：骨牌顶部下方约1/4处
            let impulse = Vec2::new(5.0, 0.0);
            let point = first_body.position() + Vec2::new(0.0, DOMINO_HEIGHT * 0.25);
            
            // 获取可变引用施加冲量
            if let Some(body) = world.get_body_mut(first_handle) {
                body.apply_impulse_at_point(impulse, point);
            }
        }
    }

    println!("骨牌数量: {}", DOMINO_COUNT);
    println!("骨牌尺寸: {}m x {}m", DOMINO_WIDTH, DOMINO_HEIGHT);
    println!("骨牌间距: {}m", DOMINO_SPACING);
    println!();

    // 模拟6秒（360帧）
    const SIMULATION_SECONDS: f32 = 6.0;
    const FPS: f32 = 60.0;
    const TOTAL_FRAMES: usize = (SIMULATION_SECONDS * FPS) as usize;

    let mut fallen_count = 0;
    let mut max_fallen = 0;

    for frame in 0..TOTAL_FRAMES {
        world.step(1.0 / FPS);

        // 每30帧输出一次状态
        if frame % 30 == 0 {
            let time = frame as f32 / FPS;
            let mut current_fallen = 0;

            // 统计已倒塌的骨牌（角度超过30度视为倒塌）
            for &handle in &domino_handles {
                if let Some(body) = world.get_body(handle) {
                    let angle = body.angle().abs();
                    if angle > std::f32::consts::PI / 6.0 {
                        current_fallen += 1;
                    }
                }
            }

            // 记录最大倒塌数量
            if current_fallen > max_fallen {
                max_fallen = current_fallen;
            }

            println!(
                "时间: {:5.1}s | 已倒塌: {:2}/{} | 动态物体: {} | 接触点: {}",
                time,
                current_fallen,
                DOMINO_COUNT,
                world.dynamic_body_count(),
                world.contact_manifolds().len()
            );

            // 输出第一个未倒塌骨牌的角度
            for (i, &handle) in domino_handles.iter().enumerate() {
                if let Some(body) = world.get_body(handle) {
                    let angle = body.angle().to_degrees().abs();
                    if angle < 30.0 {
                        println!("  第{}块骨牌角度: {:5.1}°", i + 1, angle);
                        break;
                    }
                }
            }
        }
    }

    // 最终统计
    for &handle in &domino_handles {
        if let Some(body) = world.get_body(handle) {
            let angle = body.angle().abs();
            if angle > std::f32::consts::PI / 6.0 {
                fallen_count += 1;
            }
        }
    }

    println!();
    println!("=== 模拟结束 ===");
    println!("最终倒塌数量: {}/{}", fallen_count, DOMINO_COUNT);
    println!("最大倒塌数量: {}", max_fallen);
    println!("连锁反应完成率: {:.1}%", fallen_count as f32 / DOMINO_COUNT as f32 * 100.0);

    if fallen_count == DOMINO_COUNT {
        println!("✓ 完美！所有骨牌都成功倒塌！");
    } else if fallen_count > DOMINO_COUNT / 2 {
        println!("✓ 不错！大部分骨牌倒塌了。");
    } else {
        println!("✗ 连锁反应中断，只有部分骨牌倒塌。");
    }
}
