use physics_core::{PhysicsWorld, SolverConfig};
use physics_core::collision::AABBTreeBroadPhase;
use physics_core::core::{BodyType, Material, Shape};
use physics_core::core::shape::{Rectangle, HalfSpace};
use physics_core::particles::Particle;
use physics_core::math::Vec2;
use rand::Rng;

type World = PhysicsWorld<AABBTreeBroadPhase>;

fn main() {
    println!("=== SPH粒子流体模拟示例 ===");
    println!("本示例演示：200个粒子的光滑粒子流体动力学(SPH)模拟");
    println!();

    // 物理参数配置
    // 流体模拟对时间步长敏感，需要更小的时间步和更多迭代
    let solver_config = SolverConfig {
        velocity_iterations: 6,
        position_iterations: 2,
        time_step: 1.0 / 120.0,  // 更小的时间步长，保证流体稳定
        max_sub_steps: 20,        // 更多子步数
    };

    // 创建物理世界
    let mut world: World = PhysicsWorld::new()
        .with_gravity(Vec2::new(0.0, -9.81))
        .with_solver_config(solver_config);

    // ========== 流体参数 ==========
    const PARTICLE_COUNT: usize = 200;     // 粒子数量
    const PARTICLE_MASS: f32 = 0.1;        // 单个粒子质量
    const PARTICLE_RADIUS: f32 = 0.08;     // 粒子半径
    const SMOOTHING_RADIUS: f32 = 0.25;    // SPH光滑核半径（约为粒子间距的2-3倍）
    const REST_DENSITY: f32 = 800.0;       // 静止密度（水为1000，这里稍低便于观察）

    // 容器参数
    const CONTAINER_WIDTH: f32 = 6.0;      // 容器宽度
    const CONTAINER_HEIGHT: f32 = 4.0;     // 容器高度
    const WALL_THICKNESS: f32 = 0.3;       // 墙壁厚度

    println!("--- 流体参数 ---");
    println!("粒子数量: {}", PARTICLE_COUNT);
    println!("粒子质量: {:.3}kg", PARTICLE_MASS);
    println!("粒子半径: {:.3}m", PARTICLE_RADIUS);
    println!("光滑核半径: {:.3}m", SMOOTHING_RADIUS);
    println!("静止密度: {:.1} kg/m³", REST_DENSITY);
    println!("总流体质量: {:.2}kg", PARTICLE_COUNT as f32 * PARTICLE_MASS);
    println!();

    // ========== 创建容器 ==========
    // 容器材质（静态，高摩擦）
    let wall_material = Material::new(
        0.05,   // restitution: 极低弹性，粒子碰到墙壁几乎不反弹
        0.3,    // static_friction: 中等静摩擦
        0.2,    // dynamic_friction: 低动摩擦，流体流动更自然
        0.0,    // density: 静态物体密度为0
    );

    // 底面
    let bottom_shape = Shape::Rectangle(Rectangle::new(CONTAINER_WIDTH + WALL_THICKNESS * 2.0, WALL_THICKNESS));
    world.add_body(
        bottom_shape,
        Vec2::new(0.0, -CONTAINER_HEIGHT * 0.5 - WALL_THICKNESS * 0.5),
        0.0,
        BodyType::Static,
        wall_material,
    );

    // 左壁
    let left_shape = Shape::Rectangle(Rectangle::new(WALL_THICKNESS, CONTAINER_HEIGHT));
    world.add_body(
        left_shape,
        Vec2::new(-CONTAINER_WIDTH * 0.5 - WALL_THICKNESS * 0.5, -WALL_THICKNESS * 0.5),
        0.0,
        BodyType::Static,
        wall_material,
    );

    // 右壁
    let right_shape = Shape::Rectangle(Rectangle::new(WALL_THICKNESS, CONTAINER_HEIGHT));
    world.add_body(
        right_shape,
        Vec2::new(CONTAINER_WIDTH * 0.5 + WALL_THICKNESS * 0.5, -WALL_THICKNESS * 0.5),
        0.0,
        BodyType::Static,
        wall_material,
    );

    // 障碍物（容器内部的一个小方块，让流体绕流）
    let obstacle_shape = Shape::Rectangle(Rectangle::new(1.2, 0.8));
    world.add_body(
        obstacle_shape,
        Vec2::new(0.5, -1.5),
        0.0,
        BodyType::Static,
        wall_material,
    );

    println!("--- 容器配置 ---");
    println!("容器尺寸: {:.1}m x {:.1}m", CONTAINER_WIDTH, CONTAINER_HEIGHT);
    println!("墙壁厚度: {:.2}m", WALL_THICKNESS);
    println!("内部障碍物: 1.2m x 0.8m 矩形");
    println!();

    // ========== 启用流体系统 ==========
    world.enable_fluid(SMOOTHING_RADIUS, REST_DENSITY);

    // ========== 生成粒子 ==========
    // 在容器左上角区域规则排列粒子
    const PARTICLES_PER_ROW: usize = 20;  // 每行粒子数
    const ROW_SPACING: f32 = 0.15;        // 行间距
    const COL_SPACING: f32 = 0.15;        // 列间距

    let start_x = -CONTAINER_WIDTH * 0.5 + 0.5;  // 起始X位置（容器内靠左）
    let start_y = CONTAINER_HEIGHT * 0.5 - 0.5;  // 起始Y位置（容器内靠上）

    println!("--- 生成粒子 ---");

    for i in 0..PARTICLE_COUNT {
        let row = i / PARTICLES_PER_ROW;
        let col = i % PARTICLES_PER_ROW;

        // 计算粒子位置（规则网格排列）
        let x = start_x + col as f32 * COL_SPACING;
        let y = start_y - row as f32 * ROW_SPACING;

        // 添加微小随机扰动，避免完美网格导致的数值不稳定
        let jitter_x = (rand::thread_rng().gen::<f32>() - 0.5) * 0.02;
        let jitter_y = (rand::thread_rng().gen::<f32>() - 0.5) * 0.02;

        let position = Vec2::new(x + jitter_x, y + jitter_y);

        // 创建粒子（蓝色，半透明）
        let particle = Particle::new(position, PARTICLE_MASS, PARTICLE_RADIUS)
            .with_color(0.2, 0.5, 0.9, 0.8)
            .with_velocity(Vec2::new(
                (rand::thread_rng().gen::<f32>() - 0.5) * 0.5,  // 微小水平初速度
                0.0,
            ));

        world.add_particle(particle);
    }

    println!("已生成 {} 个粒子", PARTICLE_COUNT);
    println!("排列: {} 行 x {} 列", PARTICLE_COUNT / PARTICLES_PER_ROW, PARTICLES_PER_ROW);
    println!("初始区域: x∈[{:.1}, {:.1}], y∈[{:.1}, {:.1}]",
        start_x,
        start_x + (PARTICLES_PER_ROW - 1) as f32 * COL_SPACING,
        start_y - (PARTICLE_COUNT / PARTICLES_PER_ROW - 1) as f32 * ROW_SPACING,
        start_y
    );
    println!();

    println!("--- 开始模拟 ---");
    println!();

    // ========== 模拟循环 ==========
    const SIMULATION_SECONDS: f32 = 6.0;
    const FPS: f32 = 60.0;
    const TOTAL_FRAMES: usize = (SIMULATION_SECONDS * FPS) as usize;

    let mut max_particle_speed = 0.0f32;
    let mut min_fluid_height = f32::INFINITY;
    let mut max_fluid_height = f32::NEG_INFINITY;
    let mut settled_particles = 0;

    for frame in 0..TOTAL_FRAMES {
        world.step(1.0 / FPS);

        // 每30帧输出一次状态
        if frame % 30 == 0 {
            let time = frame as f32 / FPS;

            // 统计粒子状态
            let mut avg_density = 0.0f32;
            let mut avg_speed = 0.0f32;
            let mut min_y = f32::INFINITY;
            let mut max_y = f32::NEG_INFINITY;
            let mut low_speed_count = 0;

            if let Some(fluid) = &world.fluid_system {
                for particle in &fluid.particles {
                    avg_density += particle.density;
                    let speed = particle.velocity.length();
                    avg_speed += speed;

                    if speed > max_particle_speed {
                        max_particle_speed = speed;
                    }
                    if speed < 0.1 {
                        low_speed_count += 1;
                    }

                    if particle.position.y < min_y {
                        min_y = particle.position.y;
                    }
                    if particle.position.y > max_y {
                        max_y = particle.position.y;
                    }
                }

                let count = fluid.particles.len().max(1);
                avg_density /= count as f32;
                avg_speed /= count as f32;

                if min_y < min_fluid_height {
                    min_fluid_height = min_y;
                }
                if max_y > max_fluid_height {
                    max_fluid_height = max_y;
                }

                settled_particles = low_speed_count;

                println!(
                    "t={:5.1}s | 平均密度: {:6.1} kg/m³ | 平均速度: {:5.2}m/s | 最高速度: {:5.2}m/s | 流体高度: [{:.2}, {:.2}]m | 慢速粒子: {}/{}",
                    time,
                    avg_density,
                    avg_speed,
                    max_particle_speed,
                    min_y,
                    max_y,
                    low_speed_count,
                    fluid.particles.len()
                );

                // 特殊事件提示
                if time < 0.5 && avg_speed > 1.0 {
                    println!("  🌊 流体正在下落，初始加速阶段");
                }
                if time > 1.0 && time < 2.0 && low_speed_count > 50 {
                    println!("  💧 流体开始沉降");
                }
                if low_speed_count > PARTICLE_COUNT * 8 / 10 {
                    println!("  🌊 流体基本稳定，大部分粒子已沉降");
                }
            }
        }
    }

    // ========== 模拟结果统计 ==========
    println!();
    println!("--- 模拟结束 ---");
    println!();

    println!("【流体统计】");
    println!("  粒子最高速度: {:.2} m/s", max_particle_speed);
    println!("  流体最低高度: {:.2} m", min_fluid_height);
    println!("  流体最高高度: {:.2} m", max_fluid_height);
    println!("  最终沉降高度: {:.2} m", max_fluid_height - min_fluid_height);
    println!("  最终稳定粒子: {}/{}", settled_particles, PARTICLE_COUNT);
    println!();

    // 计算理论体积（假设不可压缩流体）
    let total_mass = PARTICLE_COUNT as f32 * PARTICLE_MASS;
    let theoretical_volume = total_mass / REST_DENSITY;
    let base_area = CONTAINER_WIDTH * 1.0;  // 假设单位深度
    let theoretical_height = theoretical_volume / base_area;

    println!("【SPH算法验证】");
    println!("  总流体质量: {:.2} kg", total_mass);
    println!("  理论体积: {:.4} m³", theoretical_volume);
    println!("  理论静止高度: {:.3} m", theoretical_height);
    println!("  实际测量高度: {:.3} m", max_fluid_height - min_fluid_height);
    println!("  相对误差: {:.1}%",
        ((max_fluid_height - min_fluid_height) - theoretical_height).abs() / theoretical_height * 100.0
    );
    println!();

    println!("【参数说明】");
    println!("  - 光滑核半径(h): {:.2}m，影响粒子间相互作用范围", SMOOTHING_RADIUS);
    println!("  - 静止密度(ρ₀): {:.1}kg/m³，控制流体的不可压缩性", REST_DENSITY);
    println!("  - 压力刚度: 200.0，控制密度误差的反馈强度");
    println!("  - 粘度系数: 0.1，控制流体的粘稠度");
    println!();

    println!("✓ SPH流体模拟完成！");
}
