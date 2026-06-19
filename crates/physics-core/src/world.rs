use std::collections::HashMap;

use slotmap::SlotMap;

use physics_collision::{AABBTreeBroadPhase, BroadPhase, ContactManifold, NarrowPhase};
use physics_constraints::{ConstraintSolver, ContactConstraint, DistanceJoint, RevoluteJoint, PrismaticJoint, WeldJoint};
use physics_dynamics::integrator::{Integrator, IntegratorDefault};
use physics_types::{Body, BodyHandle, BodyType, Material, Shape};
use physics_events::{CollisionEvent, EventDispatcher, TriggerEvent};
use physics_math::{AABB, Vec2};
use physics_particles::{FluidParams, FluidSystem, Particle, ParticleSolver};


/// 物理求解器配置。
///
/// 控制物理模拟的各种参数，包括迭代次数、时间步长等。
///
/// # 示例
///
/// ```rust
/// use physics_core::SolverConfig;
///
/// // 使用默认配置
/// let config = SolverConfig::default();
/// assert_eq!(config.velocity_iterations, 8);
/// assert_eq!(config.position_iterations, 3);
///
/// // 自定义配置
/// let custom_config = SolverConfig {
///     velocity_iterations: 16,
///     position_iterations: 5,
///     time_step: 1.0 / 120.0,
///     max_sub_steps: 20,
/// };
/// assert_eq!(custom_config.velocity_iterations, 16);
/// ```
#[derive(Clone, Debug)]
pub struct SolverConfig {
    /// 速度求解迭代次数。
    ///
    /// 更多的迭代次数通常会产生更稳定的模拟，但计算成本更高。
    pub velocity_iterations: usize,
    /// 位置求解迭代次数。
    ///
    /// 用于修正物体穿透，更多迭代次数可减少穿透。
    pub position_iterations: usize,
    /// 固定时间步长（秒）。
    ///
    /// 物理模拟使用固定时间步长以保证稳定性。
    pub time_step: f32,
    /// 单帧最大子步数。
    ///
    /// 防止当帧率过低时进行过多的物理计算导致"死亡螺旋"。
    pub max_sub_steps: usize,
}

impl Default for SolverConfig {
    fn default() -> Self {
        SolverConfig {
            velocity_iterations: 8,
            position_iterations: 3,
            time_step: 1.0 / 60.0,
            max_sub_steps: 10,
        }
    }
}

/// 物理世界，是物理模拟的核心入口。
///
/// 管理所有物理体、碰撞检测、约束求解和积分。
///
/// # 类型参数
///
/// * `BP` - 宽相碰撞检测实现，默认为 `AABBTreeBroadPhase`
/// * `I` - 积分器实现，默认为 `IntegratorDefault`（SemiImplicitEuler）
///
/// # 示例
///
/// ```rust
/// use physics_core::PhysicsWorld;
/// use physics_types::{BodyType, Material, Shape};
/// use physics_types::shape::Circle;
/// use physics_math::Vec2;
///
/// // 创建物理世界
/// let mut world = PhysicsWorld::new();
///
/// // 添加一个静态地面和一个动态球体
/// let ground_shape = Shape::Circle(Circle::new(10.0));
/// let ball_shape = Shape::Circle(Circle::new(1.0));
///
/// let ground_handle = world.add_body(
///     ground_shape,
///     Vec2::new(0.0, -10.0),
///     0.0,
///     BodyType::Static,
///     Material::DEFAULT,
/// );
///
/// let ball_handle = world.add_body(
///     ball_shape,
///     Vec2::new(0.0, 5.0),
///     0.0,
///     BodyType::Dynamic,
///     Material::DEFAULT,
/// );
///
/// // 步进模拟
/// for _ in 0..60 {
///     world.step(1.0 / 60.0);
/// }
///
/// // 检查球体是否下落到地面附近
/// let ball = world.get_body(ball_handle).unwrap();
/// assert!(ball.position().y < 5.0);
/// ```
pub struct PhysicsWorld<BP: BroadPhase = AABBTreeBroadPhase, I: Integrator = IntegratorDefault> {
    /// 重力加速度向量。
    pub gravity: Vec2,
    /// 所有物理体的存储。
    pub bodies: SlotMap<BodyHandle, Body>,
    /// 每个物理体对应的形状列表。
    pub body_shapes: HashMap<BodyHandle, Vec<Shape>>,
    /// AABB扩展边距，用于宽相检测的容差。
    pub aabb_margin: f32,
    /// 允许的最小物理体尺寸。
    pub min_body_size: f32,
    /// 允许的最大物理体尺寸。
    pub max_body_size: f32,

    /// 宽相碰撞检测实现。
    pub broad_phase: BP,
    /// 窄相碰撞检测实现。
    pub narrow_phase: NarrowPhase,
    /// 约束求解器。
    pub constraint_solver: ConstraintSolver,
    /// 运动积分器。
    pub integrator: I,

    /// 当前帧的接触流形列表。
    pub contact_manifolds: Vec<ContactManifold>,
    /// 当前帧的接触约束列表。
    pub contact_constraints: Vec<ContactConstraint>,

    /// 旋转关节列表。
    pub revolute_joints: Vec<RevoluteJoint>,
    /// 距离关节列表。
    pub distance_joints: Vec<DistanceJoint>,
    /// 棱柱关节列表。
    pub prismatic_joints: Vec<PrismaticJoint>,
    /// 焊接关节列表。
    pub weld_joints: Vec<WeldJoint>,

    /// 事件分发器，用于碰撞和触发事件。
    pub event_dispatcher: EventDispatcher,

    /// 粒子列表。
    pub particles: Vec<Particle>,
    /// 粒子求解器。
    pub particle_solver: ParticleSolver,
    /// 流体系统（可选）。
    pub fluid_system: Option<FluidSystem>,

    /// 求解器配置。
    pub solver_config: SolverConfig,

    accumulator: f32,

    previous_contacts: HashMap<(BodyHandle, BodyHandle), ContactManifold>,
}

impl<BP: BroadPhase + Default, I: Integrator + Default> PhysicsWorld<BP, I> {
    /// 创建一个新的物理世界，使用默认参数。
    ///
    /// 默认重力为 `(0.0, -9.81)`，使用 AABB 树宽相检测和半隐式欧拉积分器。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    ///
    /// let world: PhysicsWorld = PhysicsWorld::new();
    /// assert_eq!(world.body_count(), 0);
    /// ```
    pub fn new() -> Self {
        PhysicsWorld {
            gravity: Vec2::new(0.0, -9.81),
            bodies: SlotMap::with_key(),
            body_shapes: HashMap::new(),
            aabb_margin: 0.1,
            min_body_size: 0.01,
            max_body_size: 100.0,

            broad_phase: BP::default(),
            narrow_phase: NarrowPhase::new(),
            constraint_solver: ConstraintSolver::new(8, 3),
            integrator: I::default(),

            contact_manifolds: Vec::new(),
            contact_constraints: Vec::new(),

            revolute_joints: Vec::new(),
            distance_joints: Vec::new(),
            prismatic_joints: Vec::new(),
            weld_joints: Vec::new(),

            event_dispatcher: EventDispatcher::new(),

            particles: Vec::new(),
            particle_solver: ParticleSolver::new(Vec2::new(0.0, -9.81)),
            fluid_system: None,

            solver_config: SolverConfig::default(),

            accumulator: 0.0,

            previous_contacts: HashMap::new(),
        }
    }

    /// 设置重力加速度（链式调用）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_math::Vec2;
    ///
    /// let world: PhysicsWorld = PhysicsWorld::new()
    ///     .with_gravity(Vec2::new(0.0, -20.0));
    ///
    /// assert_eq!(world.gravity.y, -20.0);
    /// ```
    #[inline]
    pub fn with_gravity(mut self, gravity: Vec2) -> Self {
        self.gravity = gravity;
        self
    }

    /// 设置求解器配置（链式调用）。
    ///
    /// 会同时更新约束求解器的迭代次数。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::{PhysicsWorld, SolverConfig};
    ///
    /// let config = SolverConfig {
    ///     velocity_iterations: 16,
    ///     position_iterations: 5,
    ///     ..Default::default()
    /// };
    ///
    /// let world: PhysicsWorld = PhysicsWorld::new()
    ///     .with_solver_config(config);
    ///
    /// assert_eq!(world.constraint_solver.velocity_iterations, 16);
    /// ```
    #[inline]
    pub fn with_solver_config(mut self, config: SolverConfig) -> Self {
        let vi = config.velocity_iterations;
        let pi = config.position_iterations;
        self.solver_config = config;
        self.constraint_solver = ConstraintSolver::with_iterations(vi, pi);
        self
    }

    /// 设置宽相碰撞检测实现（链式调用）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_collision::{BruteForceBroadPhase, AABBTreeBroadPhase};
    ///
    /// let world: PhysicsWorld<BruteForceBroadPhase> = PhysicsWorld::new()
    ///     .with_broad_phase(BruteForceBroadPhase::new());
    /// ```
    #[inline]
    pub fn with_broad_phase(mut self, broad_phase: BP) -> Self {
        self.broad_phase = broad_phase;
        self
    }

    /// 设置积分器实现（链式调用）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_dynamics::integrator::RK4;
    ///
    /// let world: PhysicsWorld<_, RK4> = PhysicsWorld::new()
    ///     .with_integrator(RK4::new());
    /// ```
    #[inline]
    pub fn with_integrator(mut self, integrator: I) -> Self {
        self.integrator = integrator;
        self
    }
}

impl<BP: BroadPhase, I: Integrator> PhysicsWorld<BP, I> {

    /// 添加一个具有单个形状的物理体到世界中。
    ///
    /// # 参数
    ///
    /// * `shape` - 物理体的碰撞形状
    /// * `position` - 初始位置
    /// * `angle` - 初始旋转角度（弧度）
    /// * `body_type` - 物理体类型（静态/运动学/动态）
    /// * `material` - 物理材质
    ///
    /// # 返回
    ///
    /// 返回物理体的句柄，可用于后续操作。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    ///
    /// let handle = world.add_body(
    ///     shape,
    ///     Vec2::new(0.0, 5.0),
    ///     0.0,
    ///     BodyType::Dynamic,
    ///     Material::DEFAULT,
    /// );
    ///
    /// assert_eq!(world.body_count(), 1);
    /// ```
    #[inline]
    pub fn add_body(
        &mut self,
        shape: Shape,
        position: Vec2,
        angle: f32,
        body_type: BodyType,
        material: Material,
    ) -> BodyHandle {
        let shapes = vec![shape];
        self.add_body_with_shapes(shapes, position, angle, body_type, material)
    }

    /// 添加一个具有多个形状的物理体到世界中（复合形状）。
    ///
    /// # 参数
    ///
    /// * `shapes` - 物理体的碰撞形状列表
    /// * `position` - 初始位置
    /// * `angle` - 初始旋转角度（弧度）
    /// * `body_type` - 物理体类型（静态/运动学/动态）
    /// * `material` - 物理材质
    ///
    /// # 返回
    ///
    /// 返回物理体的句柄。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::{Circle, Rectangle};
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    ///
    /// // 创建一个由球体和盒子组成的复合形状
    /// let shapes = vec![
    ///     Shape::Circle(Circle::new(1.0)),
    ///     Shape::Rectangle(Rectangle::new(2.0, 1.0)),
    /// ];
    ///
    /// let handle = world.add_body_with_shapes(
    ///     shapes,
    ///     Vec2::new(0.0, 5.0),
    ///     0.0,
    ///     BodyType::Dynamic,
    ///     Material::DEFAULT,
    /// );
    ///
    /// assert_eq!(world.get_body_shapes(handle).unwrap().len(), 2);
    /// ```
    #[inline]
    pub fn add_body_with_shapes(
        &mut self,
        shapes: Vec<Shape>,
        position: Vec2,
        angle: f32,
        body_type: BodyType,
        material: Material,
    ) -> BodyHandle {
        let first_shape = shapes
            .first()
            .cloned()
            .unwrap_or(Shape::Circle(physics_types::shape::Circle::new(1.0)));

        let handle = self.bodies.insert_with_key(|handle| {
            Body::new(handle, first_shape, position, angle, body_type, material)
        });

        self.body_shapes.insert(handle, shapes.clone());

        if body_type != BodyType::Static {
            if let Some(body) = self.bodies.get(handle) {
                self.broad_phase.add_body(handle, body);
            }
        }

        handle
    }

    fn compute_combined_aabb(&self, body: &Body, shapes: &[Shape]) -> Option<AABB> {
        let mut combined_aabb: Option<AABB> = None;
        for shape in shapes {
            let aabb = shape.compute_aabb(&body.transform).expand(self.aabb_margin);
            combined_aabb = match combined_aabb {
                Some(existing) => Some(existing.merged(&aabb)),
                None => Some(aabb),
            };
        }
        combined_aabb
    }

    fn update_broad_phase(&mut self, handle: BodyHandle) {
        let body = match self.bodies.get(handle) {
            Some(b) => b,
            None => return,
        };

        self.broad_phase.update_body(handle, body);
    }

    /// 从世界中移除物理体。
    ///
    /// # 参数
    ///
    /// * `handle` - 要移除的物理体句柄
    ///
    /// # 返回
    ///
    /// 如果句柄有效，返回被移除的物理体。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle = world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// assert_eq!(world.body_count(), 1);
    /// let removed = world.remove_body(handle);
    /// assert!(removed.is_some());
    /// assert_eq!(world.body_count(), 0);
    /// ```
    #[inline]
    pub fn remove_body(&mut self, handle: BodyHandle) -> Option<Body> {
        self.body_shapes.remove(&handle);
        self.broad_phase.remove_body(handle);
        self.bodies.remove(handle)
    }

    /// 获取物理体的不可变引用。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle = world.add_body(shape, Vec2::new(2.0, 3.0), 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// let body = world.get_body(handle).unwrap();
    /// assert_eq!(body.position().x, 2.0);
    /// ```
    #[inline]
    pub fn get_body(&self, handle: BodyHandle) -> Option<&Body> {
        self.bodies.get(handle)
    }

    /// 获取物理体的可变引用。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle = world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// if let Some(body) = world.get_body_mut(handle) {
    ///     body.set_position(Vec2::new(5.0, 5.0));
    /// }
    ///
    /// assert_eq!(world.get_body(handle).unwrap().position().x, 5.0);
    /// ```
    #[inline]
    pub fn get_body_mut(&mut self, handle: BodyHandle) -> Option<&mut Body> {
        self.bodies.get_mut(handle)
    }

    /// 获取物理体的形状列表。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle = world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// let shapes = world.get_body_shapes(handle).unwrap();
    /// assert_eq!(shapes.len(), 1);
    /// ```
    #[inline]
    pub fn get_body_shapes(&self, handle: BodyHandle) -> Option<&[Shape]> {
        self.body_shapes.get(&handle).map(|v| v.as_slice())
    }

    /// 遍历所有物理体。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// world.add_body(shape.clone(), Vec2::new(0.0, 0.0), 0.0, BodyType::Dynamic, Material::DEFAULT);
    /// world.add_body(shape, Vec2::new(1.0, 0.0), 0.0, BodyType::Static, Material::DEFAULT);
    ///
    /// for body in world.bodies() {
    ///     println!("Position: {:?}", body.position());
    /// }
    ///
    /// assert_eq!(world.bodies().count(), 2);
    /// ```
    #[inline]
    pub fn bodies(&self) -> impl Iterator<Item = &Body> {
        self.bodies.values()
    }

    /// 可变遍历所有物理体。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// world.add_body(shape.clone(), Vec2::new(0.0, 0.0), 0.0, BodyType::Dynamic, Material::DEFAULT);
    /// world.add_body(shape, Vec2::new(1.0, 0.0), 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// for body in world.bodies_mut() {
    ///     body.apply_force(Vec2::new(10.0, 0.0));
    /// }
    /// ```
    #[inline]
    pub fn bodies_mut(&mut self) -> impl Iterator<Item = &mut Body> {
        self.bodies.values_mut()
    }

    /// 遍历所有动态物理体。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// world.add_body(shape.clone(), Vec2::new(0.0, 0.0), 0.0, BodyType::Dynamic, Material::DEFAULT);
    /// world.add_body(shape, Vec2::new(1.0, 0.0), 0.0, BodyType::Static, Material::DEFAULT);
    ///
    /// assert_eq!(world.dynamic_bodies().count(), 1);
    /// ```
    #[inline]
    pub fn dynamic_bodies(&self) -> impl Iterator<Item = &Body> {
        self.bodies.values().filter(|b| b.is_dynamic())
    }

    /// 获取物理体总数。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// assert_eq!(world.body_count(), 0);
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT);
    /// assert_eq!(world.body_count(), 1);
    /// ```
    #[inline]
    pub fn body_count(&self) -> usize {
        self.bodies.len()
    }

    /// 获取动态物理体数量。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// world.add_body(shape.clone(), Vec2::new(0.0, 0.0), 0.0, BodyType::Dynamic, Material::DEFAULT);
    /// world.add_body(shape, Vec2::new(1.0, 0.0), 0.0, BodyType::Static, Material::DEFAULT);
    ///
    /// assert_eq!(world.dynamic_body_count(), 1);
    /// ```
    #[inline]
    pub fn dynamic_body_count(&self) -> usize {
        self.bodies.values().filter(|b| b.is_dynamic()).count()
    }

    /// 添加一个旋转关节。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_constraints::RevoluteJoint;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::{Vec2, Transform, Rot2};
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new().with_gravity(Vec2::ZERO);
    /// let shape = Shape::Circle(Circle::new(0.5));
    /// let material = Material::DEFAULT.with_density(1.0);
    /// let ta = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
    /// let tb = Transform::new(Vec2::new(0.0, 0.0), Rot2::new(0.0));
    ///
    /// let handle_a = world.add_body(shape.clone(), Vec2::new(0.0, 0.0), 0.0, BodyType::Static, material);
    /// let handle_b = world.add_body(shape, Vec2::new(0.0, 0.0), 0.0, BodyType::Dynamic, material);
    ///
    /// let anchor = Vec2::new(0.0, 0.0);
    /// let joint = RevoluteJoint::new(handle_a, handle_b, anchor, &ta, &tb);
    /// world.add_revolute_joint(joint);
    ///
    /// assert_eq!(world.revolute_joints().len(), 1);
    /// ```
    #[inline]
    pub fn add_revolute_joint(&mut self, joint: RevoluteJoint) {
        self.revolute_joints.push(joint);
    }

    /// 添加一个距离关节。
    #[inline]
    pub fn add_distance_joint(&mut self, joint: DistanceJoint) {
        self.distance_joints.push(joint);
    }

    /// 添加一个棱柱关节。
    #[inline]
    pub fn add_prismatic_joint(&mut self, joint: PrismaticJoint) {
        self.prismatic_joints.push(joint);
    }

    /// 添加一个焊接关节。
    #[inline]
    pub fn add_weld_joint(&mut self, joint: WeldJoint) {
        self.weld_joints.push(joint);
    }

    /// 添加一个粒子。
    ///
    /// 如果启用了流体系统，粒子将被添加到流体系统中。
    #[inline]
    pub fn add_particle(&mut self, particle: Particle) {
        if let Some(fluid) = &mut self.fluid_system {
            fluid.add_particle(particle);
        } else {
            self.particles.push(particle);
        }
    }

    /// 启用流体模拟。
    ///
    /// # 参数
    ///
    /// * `smoothing_radius` - SPH 平滑半径
    /// * `rest_density` - 静止密度
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// world.enable_fluid(0.5, 1000.0);
    /// assert!(world.fluid_particles().is_some());
    /// ```
    #[inline]
    pub fn enable_fluid(&mut self, smoothing_radius: f32, rest_density: f32) {
        let params = FluidParams {
            smoothing_radius,
            rest_density,
            pressure_stiffness: 200.0,
            viscosity: 0.1,
            gravity: self.gravity,
        };
        self.fluid_system = Some(FluidSystem::new(params));
    }

    /// 清空物理世界中的所有内容。
    ///
    /// 移除所有物理体、关节、粒子和事件回调。
    #[inline]
    pub fn clear(&mut self) {
        self.bodies.clear();
        self.body_shapes.clear();
        self.broad_phase.clear();
        self.contact_manifolds.clear();
        self.contact_constraints.clear();
        self.revolute_joints.clear();
        self.distance_joints.clear();
        self.prismatic_joints.clear();
        self.weld_joints.clear();
        self.particles.clear();
        self.fluid_system = None;
        self.accumulator = 0.0;
        self.previous_contacts.clear();
        self.event_dispatcher.clear();
    }

    /// 对所有动态物体施加重力。
    ///
    /// 通常在 `step` 内部自动调用。
    #[inline]
    pub fn apply_gravity(&mut self) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.apply_gravity(&mut body_refs, self.gravity);
    }

    /// 保存当前变换，用于 Verlet 积分等需要上一帧状态的积分器。
    #[inline]
    pub fn save_transforms(&mut self) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.pre_step(&mut body_refs);
    }

    fn integrate_velocities(&mut self, dt: f32) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.integrate_velocities(&mut body_refs, self.gravity, dt);
    }

    fn integrate_positions(&mut self, dt: f32) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.integrate_positions(&mut body_refs, dt);
    }

    fn apply_damping(&mut self, dt: f32) {
        let mut body_refs: Vec<&mut Body> = self.bodies.iter_mut().map(|(_, b)| b).collect();
        self.integrator.apply_damping(&mut body_refs, dt, 0.0, 0.0);
    }

    /// 推进物理模拟，使用可变时间步长。
    ///
    /// 内部会将可变时间步长累积并分割为多个固定时间步长执行，以保证模拟稳定性。
    ///
    /// # 参数
    ///
    /// * `delta_time` - 经过的时间（秒）
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_types::{BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// world.add_body(shape, Vec2::new(0.0, 5.0), 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// // 模拟 1 秒
    /// for _ in 0..60 {
    ///     world.step(1.0 / 60.0);
    /// }
    ///
    /// let body = world.bodies().next().unwrap();
    /// assert!(body.position().y < 5.0);
    /// ```
    pub fn step(&mut self, delta_time: f32) {
        let fixed_dt = self.solver_config.time_step;
        let max_sub_steps = self.solver_config.max_sub_steps;
        
        self.accumulator += delta_time;
        
        let mut sub_steps = 0;
        while self.accumulator >= fixed_dt && sub_steps < max_sub_steps {
            self.step_single(fixed_dt);
            self.accumulator -= fixed_dt;
            sub_steps += 1;
        }
        
        if sub_steps >= max_sub_steps {
            self.accumulator = 0.0;
        }
    }

    /// 执行单个固定时间步长的物理模拟。
    ///
    /// 包含：保存变换、施加重力、积分速度、阻尼、宽相检测、窄相检测、约束求解、积分位置、碰撞事件检测。
    ///
    /// # 参数
    ///
    /// * `dt` - 时间步长（秒）
    pub fn step_single(&mut self, dt: f32) {
        if dt <= 0.0 {
            return;
        }

        self.save_transforms();
        self.apply_gravity();

        self.integrate_velocities(dt);
        self.apply_damping(dt);

        let handles: Vec<BodyHandle> = self.bodies.keys().collect();
        for handle in handles {
            if let Some(body) = self.bodies.get(handle) {
                if body.is_dynamic() {
                    self.update_broad_phase(handle);
                }
            }
        }

        self.contact_manifolds.clear();
        self.contact_constraints.clear();

        let broad_pairs = self.broad_phase.get_potential_pairs();

        for (handle_a, handle_b) in &broad_pairs {
            let body_a = match self.bodies.get(*handle_a) {
                Some(b) => b,
                None => continue,
            };
            let body_b = match self.bodies.get(*handle_b) {
                Some(b) => b,
                None => continue,
            };

            if body_a.is_static() && body_b.is_static() {
                continue;
            }

            let shapes_a = match self.body_shapes.get(handle_a) {
                Some(s) => s,
                None => continue,
            };
            let shapes_b = match self.body_shapes.get(handle_b) {
                Some(s) => s,
                None => continue,
            };

            for shape_a in shapes_a {
                for shape_b in shapes_b {
                    let temp_body_a = Body::new_temp(shape_a.clone(), body_a.transform);
                    let temp_body_b = Body::new_temp(shape_b.clone(), body_b.transform);

                    if let Some(mut manifold) =
                        self.narrow_phase
                            .collide(&temp_body_a, *handle_a, &temp_body_b, *handle_b)
                    {
                        manifold.body_a = *handle_a;
                        manifold.body_b = *handle_b;
                        self.contact_manifolds.push(manifold);
                    }
                }
            }
        }

        {
            for manifold in &self.contact_manifolds {
                if manifold.point_count == 0 {
                    continue;
                }

                let body_a = self.bodies.get(manifold.body_a).unwrap();
                let body_b = self.bodies.get(manifold.body_b).unwrap();

                let constraint = ContactConstraint::new(manifold, body_a, body_b);
                self.contact_constraints.push(constraint);
            }

            self.constraint_solver.solve_all(
                &mut self.contact_constraints,
                &mut self.revolute_joints,
                &mut self.distance_joints,
                &mut self.prismatic_joints,
                &mut self.weld_joints,
                &mut self.bodies,
                dt,
            );
        }

        self.integrate_positions(dt);

        for body in self.bodies.values_mut() {
            body.clear_forces();
        }

        self.detect_collision_events();

        if !self.particles.is_empty() {
            self.step_particles(dt);
        }
    }

    fn detect_collision_events(&mut self) {
        let mut current_contacts: HashMap<(BodyHandle, BodyHandle), ContactManifold> = HashMap::new();

        for manifold in &self.contact_manifolds {
            if manifold.point_count == 0 {
                continue;
            }

            let key = (manifold.body_a, manifold.body_b);
            let ordered_key = if key.0 < key.1 { key } else { (key.1, key.0) };
            current_contacts.insert(ordered_key, manifold.clone());
        }

        self.event_dispatcher.begin_frame();
        self.event_dispatcher.dispatch_collisions(&self.contact_manifolds);

        self.previous_contacts = current_contacts;
    }

    fn step_particles(&mut self, dt: f32) {
        if let Some(fluid) = &mut self.fluid_system {
            fluid.step(dt);
            self.particle_solver.step_fluid(fluid, dt);
        } else if !self.particles.is_empty() {
            self.particle_solver.step_simple(&mut self.particles, dt);
        }
    }

    /// 注册碰撞事件回调。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_core::PhysicsWorld;
    /// use physics_events::CollisionEvent;
    /// use std::sync::atomic::{AtomicUsize, Ordering};
    /// use std::sync::Arc;
    ///
    /// let mut world: PhysicsWorld = PhysicsWorld::new();
    /// let collision_count = Arc::new(AtomicUsize::new(0));
    /// let counter_clone = collision_count.clone();
    ///
    /// world.register_collision_callback(move |_event: &CollisionEvent| {
    ///     counter_clone.fetch_add(1, Ordering::SeqCst);
    /// });
    /// ```
    #[inline]
    pub fn register_collision_callback<F>(&mut self, callback: F)
    where
        F: FnMut(&CollisionEvent) + Send + Sync + 'static,
    {
        self.event_dispatcher.register_collision_callback(callback);
    }

    /// 注册触发事件回调。
    #[inline]
    pub fn register_trigger_callback<F>(&mut self, callback: F)
    where
        F: FnMut(&TriggerEvent) + Send + Sync + 'static,
    {
        self.event_dispatcher.register_trigger_callback(callback);
    }

    /// 获取当前帧的接触流形。
    #[inline]
    pub fn contact_manifolds(&self) -> &[ContactManifold] {
        &self.contact_manifolds
    }

    /// 获取当前帧的接触约束。
    #[inline]
    pub fn contact_constraints(&self) -> &[ContactConstraint] {
        &self.contact_constraints
    }

    /// 获取所有旋转关节。
    #[inline]
    pub fn revolute_joints(&self) -> &[RevoluteJoint] {
        &self.revolute_joints
    }

    /// 获取所有距离关节。
    #[inline]
    pub fn distance_joints(&self) -> &[DistanceJoint] {
        &self.distance_joints
    }

    /// 获取所有棱柱关节。
    #[inline]
    pub fn prismatic_joints(&self) -> &[PrismaticJoint] {
        &self.prismatic_joints
    }

    /// 获取所有焊接关节。
    #[inline]
    pub fn weld_joints(&self) -> &[WeldJoint] {
        &self.weld_joints
    }

    /// 获取所有粒子。
    #[inline]
    pub fn particles(&self) -> &[Particle] {
        &self.particles
    }

    /// 如果启用了流体系统，获取流体粒子。
    #[inline]
    pub fn fluid_particles(&self) -> Option<&[Particle]> {
        self.fluid_system.as_ref().map(|_| &self.particles[..])
    }
}

impl Default for PhysicsWorld {
    fn default() -> Self {
        PhysicsWorld::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use physics_types::shape::{Circle, Rectangle, HalfSpace, CollisionFilter};
    use approx::assert_abs_diff_eq;

    type TestWorld = PhysicsWorld;

    #[test]
    fn test_world_creation() {
        let world: TestWorld = PhysicsWorld::new();
        assert_eq!(world.body_count(), 0);
        assert_abs_diff_eq!(world.gravity.y, -9.81);
    }

    #[test]
    fn test_add_body() {
        let mut world: TestWorld = PhysicsWorld::new();
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        let handle = world.add_body(
            shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        assert_eq!(world.body_count(), 1);
        let body = world.get_body(handle).unwrap();
        assert_abs_diff_eq!(body.position().y, 5.0);
    }

    #[test]
    fn test_remove_body() {
        let mut world: TestWorld = PhysicsWorld::new();
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        let handle = world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, material);

        assert_eq!(world.body_count(), 1);
        let removed = world.remove_body(handle);
        assert!(removed.is_some());
        assert_eq!(world.body_count(), 0);
    }

    #[test]
    fn test_apply_gravity() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0);
        world.add_body(shape, Vec2::ZERO, 0.0, BodyType::Dynamic, material);

        world.apply_gravity();

        let body = world.bodies().next().unwrap();
        let expected_force = body.mass * -10.0;
        assert_abs_diff_eq!(body.force.y, expected_force);
    }

    #[test]
    fn test_simple_fall() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0).with_restitution(0.0);
        world.add_body(
            shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        for _ in 0..30 {
            world.step(1.0 / 60.0);
        }

        let body = world.bodies().next().unwrap();
        assert!(body.position().y < 5.0);
    }

    #[test]
    fn test_collision_detection() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));

        let ground_shape = Shape::Rectangle(Rectangle::new(10.0, 1.0));
        let ground_material = Material::DEFAULT.with_restitution(0.5);
        world.add_body(
            ground_shape,
            Vec2::new(0.0, -5.0),
            0.0,
            BodyType::Static,
            ground_material,
        );

        let ball_shape = Shape::Circle(Circle::new(1.0));
        let ball_material = Material::DEFAULT.with_restitution(0.5);
        world.add_body(
            ball_shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            ball_material,
        );

        for _ in 0..60 {
            world.step(1.0 / 60.0);
        }

        let ball = world.bodies().nth(1).unwrap();
        assert!(ball.position().y > -4.5);
    }

    #[test]
    fn test_half_space_collision() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));

        let ground_shape = Shape::HalfSpace(HalfSpace::ground());
        let ground_material = Material::DEFAULT.with_restitution(0.0);
        world.add_body(
            ground_shape,
            Vec2::new(0.0, 0.0),
            0.0,
            BodyType::Static,
            ground_material,
        );

        let ball_shape = Shape::Circle(Circle::new(1.0));
        let ball_material = Material::DEFAULT.with_restitution(0.0);
        world.add_body(
            ball_shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            ball_material,
        );

        for _ in 0..60 {
            world.step(1.0 / 60.0);
        }

        let ball = world.bodies().nth(1).unwrap();
        assert!(ball.position().y < 1.0);
        assert!(ball.position().y > -0.5);
    }

    #[test]
    fn test_collision_filter() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::ZERO);

        let filter1 = CollisionFilter::new(0x0001, 0x0001);
        let filter2 = CollisionFilter::new(0x0002, 0x0002);

        let shape1 = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        
        let handle1 = world.add_body(
            shape1.clone(),
            Vec2::new(-0.5, 0.0),
            0.0,
            BodyType::Dynamic,
            material,
        );
        let handle2 = world.add_body(
            shape1,
            Vec2::new(0.5, 0.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        world.get_body_mut(handle1).unwrap().set_collision_filter(filter1);
        world.get_body_mut(handle2).unwrap().set_collision_filter(filter2);

        world.step(1.0 / 60.0);

        assert_eq!(world.contact_manifolds().len(), 0);
    }

    #[test]
    fn test_adaptive_time_step() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));

        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0);
        world.add_body(
            shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let dt = 1.0 / 60.0;
        world.step(dt * 2.5);

        let body = world.bodies().next().unwrap();
        assert!(body.position().y < 5.0);
    }

    #[test]
    fn test_max_sub_steps() {
        let mut config = SolverConfig::default();
        config.max_sub_steps = 3;
        let mut world: TestWorld = PhysicsWorld::new()
            .with_gravity(Vec2::new(0.0, -10.0))
            .with_solver_config(config);

        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT.with_density(1.0);
        world.add_body(
            shape,
            Vec2::new(0.0, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let dt = 1.0 / 60.0 * 10.0;
        world.step(dt);

        let body = world.bodies().next().unwrap();
        assert!(body.position().y < 5.0);
    }

    #[test]
    fn test_revolute_joint_with_motor() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::ZERO);

        let shape = Shape::Circle(Circle::new(0.5));
        let material = Material::DEFAULT.with_density(1.0);
        let ta = physics_math::Transform::new(Vec2::new(0.0, 0.0), physics_math::Rot2::new(0.0));
        let tb = physics_math::Transform::new(Vec2::new(0.0, 0.0), physics_math::Rot2::new(0.0));
        
        let handle_a = world.add_body(
            shape.clone(),
            Vec2::new(0.0, 0.0),
            0.0,
            BodyType::Static,
            material,
        );
        let handle_b = world.add_body(
            shape,
            Vec2::new(0.0, 0.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let anchor = Vec2::new(0.0, 0.0);
        let joint = RevoluteJoint::new(handle_a, handle_b, anchor, &ta, &tb)
            .with_motor(2.0, 100.0);
        world.add_revolute_joint(joint);

        for _ in 0..60 {
            world.step(1.0 / 60.0);
        }

        let body_b = world.get_body(handle_b).unwrap();
        assert!(body_b.angular_velocity.abs() > 0.5);
    }

    #[test]
    fn test_distance_joint() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::ZERO);

        let shape = Shape::Circle(Circle::new(0.5));
        let material = Material::DEFAULT.with_density(1.0);
        let ta = physics_math::Transform::new(Vec2::new(-1.0, 0.0), physics_math::Rot2::new(0.0));
        let tb = physics_math::Transform::new(Vec2::new(1.0, 0.0), physics_math::Rot2::new(0.0));
        
        let handle_a = world.add_body(
            shape.clone(),
            Vec2::new(-1.0, 0.0),
            0.0,
            BodyType::Static,
            material,
        );
        let handle_b = world.add_body(
            shape,
            Vec2::new(1.0, 0.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let joint = DistanceJoint::new(handle_a, handle_b, Vec2::new(-1.0, 0.0), Vec2::new(1.0, 0.0), &ta, &tb);
        world.add_distance_joint(joint);

        world.get_body_mut(handle_b).unwrap().linear_velocity = Vec2::new(0.0, 5.0);

        for _ in 0..10 {
            world.step(1.0 / 60.0);
        }

        let body_a = world.get_body(handle_a).unwrap();
        let body_b = world.get_body(handle_b).unwrap();
        let distance = (body_b.position() - body_a.position()).length();
        assert_abs_diff_eq!(distance, 2.0, epsilon = 0.1);
    }

    #[test]
    fn test_weld_joint() {
        let mut world: TestWorld = PhysicsWorld::new().with_gravity(Vec2::new(0.0, -10.0));

        let shape = Shape::Circle(Circle::new(0.5));
        let material = Material::DEFAULT.with_density(1.0);
        let ta = physics_math::Transform::new(Vec2::new(-0.5, 0.0), physics_math::Rot2::new(0.0));
        let tb = physics_math::Transform::new(Vec2::new(0.5, 0.0), physics_math::Rot2::new(0.0));
        
        let handle_a = world.add_body(
            shape.clone(),
            Vec2::new(-0.5, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );
        let handle_b = world.add_body(
            shape,
            Vec2::new(0.5, 5.0),
            0.0,
            BodyType::Dynamic,
            material,
        );

        let anchor = Vec2::new(0.0, 5.0);
        let joint = WeldJoint::new(handle_a, handle_b, anchor, &ta, &tb);
        world.add_weld_joint(joint);

        for _ in 0..30 {
            world.step(1.0 / 60.0);
        }

        let body_a = world.get_body(handle_a).unwrap();
        let body_b = world.get_body(handle_b).unwrap();
        let angle_diff = (body_b.angle() - body_a.angle()).abs();
        assert!(angle_diff < 0.1);
    }
}
