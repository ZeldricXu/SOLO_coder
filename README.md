# Physics Engine

一个模块化的 2D 物理引擎，使用 Rust 编写。

## Crate 列表

| Crate | 描述 |
|-------|------|
| `physics-math` | 2D 物理数学基础库，包含向量、矩阵、变换等基础类型 |
| `physics-types` | 核心类型定义，包括刚体、形状、材质等 |
| `physics-spatial` | 空间索引数据结构（AABB 树、哈希网格） |
| `physics-collision` | 碰撞检测（宽相位、窄相位 GJK/EPA、接触流形） |
| `physics-dynamics` | 刚体动力学求解器，支持多种积分器 |
| `physics-constraints` | 约束求解器，支持接触约束和关节约束 |
| `physics-particles` | 粒子系统与流体模拟 |
| `physics-events` | 事件分发系统 |
| `physics-serialization` | 场景序列化与加载 |
| `physics-debug` | 调试渲染工具 |
| `physics-core` | 整合所有模块的核心入口 |
| `physics-benches` | 性能基准测试 |

## 快速开始

将以下内容添加到你的 `Cargo.toml`：

```toml
[dependencies]
physics-core = "0.1.0"
```

### 示例：创建一个简单的物理世界

```rust
use physics_core::{PhysicsWorld, SolverConfig};
use physics_core::core::{BodyType, Material, Shape};
use physics_core::core::shape::{Circle, Rectangle, HalfSpace};
use physics_core::math::Vec2;

fn main() {
    // 配置求解器
    let solver_config = SolverConfig {
        velocity_iterations: 8,
        position_iterations: 3,
        time_step: 1.0 / 60.0,
        max_sub_steps: 10,
    };

    // 创建物理世界，设置重力
    let mut world = PhysicsWorld::new()
        .with_gravity(Vec2::new(0.0, -9.81))
        .with_solver_config(solver_config);

    // 创建地面
    let ground_material = Material::new(0.1, 0.8, 0.5, 1.0);
    world.add_body(
        Shape::HalfSpace(HalfSpace::ground()),
        Vec2::new(0.0, -5.0),
        0.0,
        BodyType::Static,
        ground_material,
    );

    // 创建一个动态圆形物体
    let ball_material = Material::new(0.3, 0.5, 0.3, 1.0);
    let ball_handle = world.add_body(
        Shape::Circle(Circle::new(0.5)),
        Vec2::new(0.0, 5.0),
        0.0,
        BodyType::Dynamic,
        ball_material,
    );

    // 模拟 60 帧（1 秒）
    for _ in 0..60 {
        world.step(1.0 / 60.0);
    }

    // 获取物体位置
    if let Some(body) = world.get_body(ball_handle) {
        println!("球的位置: {:?}", body.position());
    }
}
```

运行示例：

```bash
cargo run --example pendulum
```

## 功能特性

- **刚体动力学**：支持静态、动态、运动学三种刚体类型
- **碰撞检测**：
  - 宽相位：AABB 树、空间哈希
  - 窄相位：GJK/EPA 算法
  - 支持圆形、矩形、多边形、半空间等多种形状
- **约束系统**：
  - 接触约束（带摩擦和弹性）
  - 旋转关节、距离关节等
  - 支持马达驱动
- **粒子系统**：基于位置的流体模拟
- **事件系统**：碰撞事件、接触事件分发
- **序列化**：场景保存与加载（JSON/YAML）
- **模块化设计**：各模块独立，可按需引入
- **高性能**：Release 模式启用 LTO 优化

## License

本项目采用双许可证：

- MIT License ([LICENSE-MIT](LICENSE-MIT)
- Apache License, Version 2.0 ([LICENSE-APACHE](LICENSE-APACHE))

你可以根据自己的选择使用任一许可证条款使用本项目。
