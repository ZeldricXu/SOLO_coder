use slotmap::new_key_type;

use physics_types::{Body, BodyHandle};
use physics_math::Vec2;

new_key_type! {
    /// 约束的句柄类型，用于唯一标识一个约束。
    pub struct ConstraintHandle;
}

/// 约束求解器的数据上下文。
///
/// 在约束求解过程中传递给各个约束，提供对物理体和时间步长信息的访问。
///
/// # 示例
///
/// ```rust
/// use physics_constraints::ConstraintSolverData;
/// use physics_types::{Body, BodyType, Material, Shape};
/// use physics_types::shape::Circle;
/// use physics_math::Vec2;
/// use slotmap::SlotMap;
///
/// let mut bodies = SlotMap::with_key();
/// let dt = 1.0 / 60.0;
/// let inv_dt = 60.0;
///
/// let data = ConstraintSolverData {
///     bodies: &mut bodies,
///     dt,
///     inv_dt,
/// };
/// ```
pub struct ConstraintSolverData<'a> {
    /// 物理体存储的可变引用。
    pub bodies: &'a mut slotmap::SlotMap<BodyHandle, Body>,
    /// 当前时间步长。
    pub dt: f32,
    /// 当前时间步长的倒数。
    pub inv_dt: f32,
}

/// 约束 trait。
///
/// 所有物理约束（接触约束、关节等）都需要实现此 trait。
/// 约束求解分为三个阶段：准备、速度求解、位置求解。
///
/// # 示例
///
/// 一个简单的距离约束示例：
///
/// ```rust
/// use physics_constraints::{Constraint, ConstraintSolverData, ConstraintSolveStep};
/// use physics_types::BodyHandle;
/// use physics_math::Vec2;
///
/// struct DistanceConstraint {
///     body_a: BodyHandle,
///     body_b: BodyHandle,
///     distance: f32,
/// }
///
/// impl Constraint for DistanceConstraint {
///     fn body_a(&self) -> BodyHandle {
///         self.body_a
///     }
///
///     fn body_b(&self) -> BodyHandle {
///         self.body_b
///     }
///
///     fn prepare(&mut self, _data: &ConstraintSolverData) {
///         // 准备约束数据（如计算雅可比矩阵、有效质量等）
///     }
///
///     fn solve_velocity(&mut self, _data: &mut ConstraintSolverData) {
///         // 求解速度约束
///     }
///
///     fn solve_position(&mut self, _data: &mut ConstraintSolverData) -> bool {
///         // 求解位置约束，返回是否完全满足
///         true
///     }
/// }
/// ```
pub trait Constraint {
    /// 获取约束关联的第一个物理体。
    fn body_a(&self) -> BodyHandle;
    /// 获取约束关联的第二个物理体。
    fn body_b(&self) -> BodyHandle;
    /// 约束准备阶段，计算雅可比矩阵、有效质量等。
    fn prepare(&mut self, data: &ConstraintSolverData);
    /// 速度求解阶段，通过冲量修正速度。
    fn solve_velocity(&mut self, data: &mut ConstraintSolverData);
    /// 位置求解阶段，修正位置穿透。
    ///
    /// # 返回
    ///
    /// 如果约束已完全满足返回 `true`，否则返回 `false`。
    fn solve_position(&mut self, data: &mut ConstraintSolverData) -> bool;

    /// 根据指定的求解阶段执行对应的求解步骤。
    fn apply(&mut self, data: &mut ConstraintSolverData, step: ConstraintSolveStep) -> bool {
        match step {
            ConstraintSolveStep::Prepare => {
                self.prepare(data);
                true
            }
            ConstraintSolveStep::Velocity => {
                self.solve_velocity(data);
                true
            }
            ConstraintSolveStep::Position => self.solve_position(data),
        }
    }
}

/// 约束求解阶段。
///
/// 约束求解分为三个阶段依次执行。
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum ConstraintSolveStep {
    /// 准备阶段，计算约束所需的数据。
    Prepare,
    /// 速度求解阶段。
    Velocity,
    /// 位置求解阶段。
    Position,
}

/// 雅可比矩阵，用于定义约束的方向。
///
/// 对于两个物体的约束，雅可比矩阵描述了约束对每个物体
/// 线速度和角速度的影响方向。
///
/// # 示例
///
/// ```rust
/// use physics_constraints::Jacobian;
/// use physics_math::Vec2;
///
/// let jacobian = Jacobian::new(
///     Vec2::new(1.0, 0.0),
///     0.0,
///     Vec2::new(-1.0, 0.0),
///     0.0,
/// );
/// ```
#[derive(Clone, Copy, Debug)]
pub struct Jacobian {
    /// 物体 A 的线速度雅可比分量。
    pub linear_a: Vec2,
    /// 物体 A 的角速度雅可比分量。
    pub angular_a: f32,
    /// 物体 B 的线速度雅可比分量。
    pub linear_b: Vec2,
    /// 物体 B 的角速度雅可比分量。
    pub angular_b: f32,
}

impl Jacobian {
    /// 创建一个新的雅可比矩阵。
    ///
    /// # 参数
    ///
    /// * `linear_a` - 物体 A 的线速度分量
    /// * `angular_a` - 物体 A 的角速度分量
    /// * `linear_b` - 物体 B 的线速度分量
    /// * `angular_b` - 物体 B 的角速度分量
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_constraints::Jacobian;
    /// use physics_math::Vec2;
    ///
    /// let j = Jacobian::new(
    ///     Vec2::new(0.0, 1.0),
    ///     0.5,
    ///     Vec2::new(0.0, -1.0),
    ///     -0.5,
    /// );
    /// ```
    pub fn new(linear_a: Vec2, angular_a: f32, linear_b: Vec2, angular_b: f32) -> Self {
        Jacobian {
            linear_a,
            angular_a,
            linear_b,
            angular_b,
        }
    }

    /// 计算雅可比矩阵与速度向量的点积（约束速度）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_constraints::Jacobian;
    /// use physics_types::{Body, BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    ///
    /// let j = Jacobian::new(
    ///     Vec2::new(1.0, 0.0), 0.0,
    ///     Vec2::new(-1.0, 0.0), 0.0,
    /// );
    ///
    /// let mut body_a = Body::new(
    ///     KeyData::from_ffi(1).into(),
    ///     Shape::Circle(Circle::new(1.0)),
    ///     Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT,
    /// );
    /// let mut body_b = Body::new(
    ///     KeyData::from_ffi(2).into(),
    ///     Shape::Circle(Circle::new(1.0)),
    ///     Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT,
    /// );
    ///
    /// body_a.linear_velocity = Vec2::new(5.0, 0.0);
    /// body_b.linear_velocity = Vec2::new(2.0, 0.0);
    ///
    /// let velocity = j.compute(&body_a, &body_b);
    /// assert_eq!(velocity, 3.0);
    /// ```
    pub fn compute(&self, body_a: &Body, body_b: &Body) -> f32 {
        self.linear_a.dot(body_a.linear_velocity)
            + self.angular_a * body_a.angular_velocity
            + self.linear_b.dot(body_b.linear_velocity)
            + self.angular_b * body_b.angular_velocity
    }

    /// 计算约束的有效质量。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_constraints::Jacobian;
    /// use physics_types::{Body, BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    ///
    /// let j = Jacobian::new(
    ///     Vec2::new(1.0, 0.0), 0.0,
    ///     Vec2::new(-1.0, 0.0), 0.0,
    /// );
    ///
    /// let body_a = Body::new(
    ///     KeyData::from_ffi(1).into(),
    ///     Shape::Circle(Circle::new(1.0)),
    ///     Vec2::ZERO, 0.0, BodyType::Dynamic,
    ///     Material::DEFAULT.with_density(1.0),
    /// );
    /// let body_b = Body::new(
    ///     KeyData::from_ffi(2).into(),
    ///     Shape::Circle(Circle::new(1.0)),
    ///     Vec2::ZERO, 0.0, BodyType::Dynamic,
    ///     Material::DEFAULT.with_density(1.0),
    /// );
    ///
    /// let effective_mass = j.compute_effective_mass(&body_a, &body_b);
    /// assert!(effective_mass > 0.0);
    /// ```
    pub fn compute_effective_mass(&self, body_a: &Body, body_b: &Body) -> f32 {
        let mut mass = 0.0;

        if body_a.is_dynamic() {
            mass += self.linear_a.dot(self.linear_a) * body_a.inv_mass;
            mass += self.angular_a * self.angular_a * body_a.inv_inertia;
        }

        if body_b.is_dynamic() {
            mass += self.linear_b.dot(self.linear_b) * body_b.inv_mass;
            mass += self.angular_b * self.angular_b * body_b.inv_inertia;
        }

        mass
    }

    /// 对两个物理体施加冲量。
    ///
    /// # 参数
    ///
    /// * `body_a` - 第一个物理体
    /// * `body_b` - 第二个物理体
    /// * `impulse` - 冲量大小
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_constraints::Jacobian;
    /// use physics_types::{Body, BodyType, Material, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    /// use approx::assert_abs_diff_eq;
    ///
    /// let j = Jacobian::new(
    ///     Vec2::new(1.0, 0.0), 0.0,
    ///     Vec2::new(-1.0, 0.0), 0.0,
    /// );
    ///
    /// let mut body_a = Body::new(
    ///     KeyData::from_ffi(1).into(),
    ///     Shape::Circle(Circle::new(1.0)),
    ///     Vec2::ZERO, 0.0, BodyType::Dynamic,
    ///     Material::DEFAULT.with_density(1.0),
    /// );
    /// let mut body_b = Body::new(
    ///     KeyData::from_ffi(2).into(),
    ///     Shape::Circle(Circle::new(1.0)),
    ///     Vec2::ZERO, 0.0, BodyType::Dynamic,
    ///     Material::DEFAULT.with_density(1.0),
    /// );
    ///
    /// let inv_mass = body_a.inv_mass;
    /// j.apply_impulse(&mut body_a, &mut body_b, 10.0);
    ///
    /// assert_abs_diff_eq!(body_a.linear_velocity.x, 10.0 * inv_mass);
    /// assert_abs_diff_eq!(body_b.linear_velocity.x, -10.0 * inv_mass);
    /// ```
    pub fn apply_impulse(&self, body_a: &mut Body, body_b: &mut Body, impulse: f32) {
        if body_a.is_dynamic() {
            body_a.linear_velocity += self.linear_a * impulse * body_a.inv_mass;
            body_a.angular_velocity += self.angular_a * impulse * body_a.inv_inertia;
        }

        if body_b.is_dynamic() {
            body_b.linear_velocity += self.linear_b * impulse * body_b.inv_mass;
            body_b.angular_velocity += self.angular_b * impulse * body_b.inv_inertia;
        }
    }
}
