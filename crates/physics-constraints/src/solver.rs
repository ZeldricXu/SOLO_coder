use slotmap::SlotMap;

use physics_types::{Body, BodyHandle};

use crate::constraint::{Constraint, ConstraintSolveStep, ConstraintSolverData};

/// 约束求解器。
///
/// 负责按序执行约束的准备、速度求解和位置求解三个阶段。
///
/// # 示例
///
/// ```rust
/// use physics_constraints::ConstraintSolver;
///
/// // 创建一个默认配置的求解器（8次速度迭代，3次位置迭代）
/// let solver = ConstraintSolver::default();
/// assert_eq!(solver.velocity_iterations, 8);
/// assert_eq!(solver.position_iterations, 3);
///
/// // 自定义迭代次数
/// let solver = ConstraintSolver::new(16, 5);
/// assert_eq!(solver.velocity_iterations, 16);
/// ```
pub struct ConstraintSolver {
    /// 速度求解迭代次数。
    pub velocity_iterations: usize,
    /// 位置求解迭代次数。
    pub position_iterations: usize,
    /// 是否启用热启动（使用上一帧的冲量进行初始化）。
    pub use_warm_starting: bool,
}

impl Default for ConstraintSolver {
    fn default() -> Self {
        ConstraintSolver {
            velocity_iterations: 8,
            position_iterations: 3,
            use_warm_starting: true,
        }
    }
}

impl ConstraintSolver {
    /// 创建一个新的约束求解器。
    ///
    /// # 参数
    ///
    /// * `velocity_iterations` - 速度求解迭代次数
    /// * `position_iterations` - 位置求解迭代次数
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_constraints::ConstraintSolver;
    ///
    /// let solver = ConstraintSolver::new(10, 4);
    /// assert_eq!(solver.velocity_iterations, 10);
    /// assert_eq!(solver.position_iterations, 4);
    /// ```
    pub fn new(velocity_iterations: usize, position_iterations: usize) -> Self {
        ConstraintSolver {
            velocity_iterations,
            position_iterations,
            use_warm_starting: true,
        }
    }

    /// 使用指定的迭代次数创建约束求解器。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_constraints::ConstraintSolver;
    ///
    /// let solver = ConstraintSolver::with_iterations(10, 4);
    /// assert_eq!(solver.velocity_iterations, 10);
    /// ```
    pub fn with_iterations(velocity_iterations: usize, position_iterations: usize) -> Self {
        Self::new(velocity_iterations, position_iterations)
    }

    /// 求解一组约束引用。
    ///
    /// # 参数
    ///
    /// * `constraints` - 约束的可变引用切片
    /// * `bodies` - 物理体存储
    /// * `dt` - 时间步长
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_constraints::{ConstraintSolver, Constraint, ConstraintSolverData};
    /// use physics_types::{Body, BodyType, Material, Shape, BodyHandle};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::SlotMap;
    ///
    /// #[derive(Clone)]
    /// struct TestConstraint {
    ///     body_a: BodyHandle,
    ///     body_b: BodyHandle,
    /// }
    ///
    /// impl Constraint for TestConstraint {
    ///     fn body_a(&self) -> BodyHandle { self.body_a }
    ///     fn body_b(&self) -> BodyHandle { self.body_b }
    ///     fn prepare(&mut self, _data: &ConstraintSolverData) {}
    ///     fn solve_velocity(&mut self, _data: &mut ConstraintSolverData) {}
    ///     fn solve_position(&mut self, _data: &mut ConstraintSolverData) -> bool { true }
    /// }
    ///
    /// let solver = ConstraintSolver::new(2, 2);
    /// let mut bodies = SlotMap::with_key();
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let h1 = bodies.insert_with_key(|handle| {
    ///     Body::new(handle, shape.clone(), Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT)
    /// });
    /// let h2 = bodies.insert_with_key(|handle| {
    ///     Body::new(handle, shape, Vec2::new(2.0, 0.0), 0.0, BodyType::Dynamic, Material::DEFAULT)
    /// });
    ///
    /// let mut c = TestConstraint { body_a: h1, body_b: h2 };
    /// let mut constraint_refs: Vec<&mut dyn Constraint> = vec![&mut c];
    ///
    /// solver.solve(&mut constraint_refs, &mut bodies, 1.0 / 60.0);
    /// ```
    pub fn solve(
        &self,
        constraints: &mut [&mut dyn Constraint],
        bodies: &mut SlotMap<BodyHandle, Body>,
        dt: f32,
    ) {
        if constraints.is_empty() {
            return;
        }

        let inv_dt = if dt > f32::EPSILON { 1.0 / dt } else { 0.0 };

        let mut data = ConstraintSolverData { bodies, dt, inv_dt };

        for constraint in constraints.iter_mut() {
            constraint.apply(&mut data, ConstraintSolveStep::Prepare);
        }

        for _ in 0..self.velocity_iterations {
            for constraint in constraints.iter_mut() {
                constraint.apply(&mut data, ConstraintSolveStep::Velocity);
            }
        }

        for _ in 0..self.position_iterations {
            let mut all_solved = true;
            for constraint in constraints.iter_mut() {
                if !constraint.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            if all_solved {
                break;
            }
        }
    }

    /// 求解一组装箱的约束（trait 对象）。
    ///
    /// # 参数
    ///
    /// * `constraints` - 装箱约束的切片
    /// * `bodies` - 物理体存储
    /// * `dt` - 时间步长
    pub fn solve_boxed(
        &self,
        constraints: &mut [Box<dyn Constraint>],
        bodies: &mut SlotMap<BodyHandle, Body>,
        dt: f32,
    ) {
        if constraints.is_empty() {
            return;
        }

        let inv_dt = if dt > f32::EPSILON { 1.0 / dt } else { 0.0 };

        let mut data = ConstraintSolverData { bodies, dt, inv_dt };

        for constraint in constraints.iter_mut() {
            constraint.apply(&mut data, ConstraintSolveStep::Prepare);
        }

        for _ in 0..self.velocity_iterations {
            for constraint in constraints.iter_mut() {
                constraint.apply(&mut data, ConstraintSolveStep::Velocity);
            }
        }

        for _ in 0..self.position_iterations {
            let mut all_solved = true;
            for constraint in constraints.iter_mut() {
                if !constraint.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            if all_solved {
                break;
            }
        }
    }

    /// 求解多种类型的约束数组（接触约束和各类关节）。
    ///
    /// 这是一个高性能版本，避免了 trait 对象的动态分发开销。
    ///
    /// # 参数
    ///
    /// * `contacts` - 接触约束数组
    /// * `revolute` - 旋转关节数组
    /// * `distance` - 距离关节数组
    /// * `prismatic` - 棱柱关节数组
    /// * `weld` - 焊接关节数组
    /// * `bodies` - 物理体存储
    /// * `dt` - 时间步长
    pub fn solve_all<'a, C1, C2, C3, C4, C5>(
        &self,
        contacts: &'a mut [C1],
        revolute: &'a mut [C2],
        distance: &'a mut [C3],
        prismatic: &'a mut [C4],
        weld: &'a mut [C5],
        bodies: &mut SlotMap<BodyHandle, Body>,
        dt: f32,
    ) where
        C1: Constraint,
        C2: Constraint,
        C3: Constraint,
        C4: Constraint,
        C5: Constraint,
    {
        let inv_dt = if dt > f32::EPSILON { 1.0 / dt } else { 0.0 };
        let mut data = ConstraintSolverData { bodies, dt, inv_dt };

        for c in contacts.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }
        for c in revolute.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }
        for c in distance.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }
        for c in prismatic.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }
        for c in weld.iter_mut() {
            c.apply(&mut data, ConstraintSolveStep::Prepare);
        }

        for _ in 0..self.velocity_iterations {
            for c in contacts.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
            for c in revolute.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
            for c in distance.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
            for c in prismatic.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
            for c in weld.iter_mut() {
                c.apply(&mut data, ConstraintSolveStep::Velocity);
            }
        }

        for _ in 0..self.position_iterations {
            let mut all_solved = true;
            for c in contacts.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            for c in revolute.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            for c in distance.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            for c in prismatic.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            for c in weld.iter_mut() {
                if !c.apply(&mut data, ConstraintSolveStep::Position) {
                    all_solved = false;
                }
            }
            if all_solved {
                break;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::constraint::Jacobian;
    use physics_types::{Body, BodyType, Material, Shape, Circle};
    use physics_math::Vec2;
    use slotmap::SlotMap;

    #[derive(Clone)]
    struct TestConstraint {
        body_a: BodyHandle,
        body_b: BodyHandle,
        prepared: bool,
        velocity_solved: bool,
        position_solved: bool,
    }

    impl Constraint for TestConstraint {
        fn body_a(&self) -> BodyHandle {
            self.body_a
        }

        fn body_b(&self) -> BodyHandle {
            self.body_b
        }

        fn prepare(&mut self, _data: &ConstraintSolverData) {
            self.prepared = true;
        }

        fn solve_velocity(&mut self, _data: &mut ConstraintSolverData) {
            self.velocity_solved = true;
        }

        fn solve_position(&mut self, _data: &mut ConstraintSolverData) -> bool {
            self.position_solved = true;
            true
        }
    }

    fn create_test_body(
        bodies: &mut SlotMap<BodyHandle, Body>,
        position: Vec2,
    ) -> BodyHandle {
        let shape = Shape::Circle(Circle::new(1.0));
        bodies.insert_with_key(|handle| {
            Body::new(
                handle,
                shape,
                position,
                0.0,
                BodyType::Dynamic,
                Material::DEFAULT,
            )
        })
    }

    #[test]
    fn test_constraint_solver_solve() {
        let mut bodies = SlotMap::with_key();
        let h1 = create_test_body(&mut bodies, Vec2::ZERO);
        let h2 = create_test_body(&mut bodies, Vec2::new(2.0, 0.0));

        let solver = ConstraintSolver::new(2, 2);

        let mut c1 = TestConstraint {
            body_a: h1,
            body_b: h2,
            prepared: false,
            velocity_solved: false,
            position_solved: false,
        };
        let mut c2 = TestConstraint {
            body_a: h1,
            body_b: h2,
            prepared: false,
            velocity_solved: false,
            position_solved: false,
        };

        let mut constraint_refs: Vec<&mut dyn Constraint> = vec![&mut c1, &mut c2];
        solver.solve(&mut constraint_refs, &mut bodies, 1.0 / 60.0);

        assert!(c1.prepared);
        assert!(c1.velocity_solved);
        assert!(c1.position_solved);
        assert!(c2.prepared);
        assert!(c2.velocity_solved);
        assert!(c2.position_solved);
    }

    #[test]
    fn test_constraint_solver_boxed() {
        let mut bodies = SlotMap::with_key();
        let h1 = create_test_body(&mut bodies, Vec2::ZERO);
        let h2 = create_test_body(&mut bodies, Vec2::new(2.0, 0.0));

        let solver = ConstraintSolver::new(2, 2);

        let c1: Box<dyn Constraint> = Box::new(TestConstraint {
            body_a: h1,
            body_b: h2,
            prepared: false,
            velocity_solved: false,
            position_solved: false,
        });
        let c2: Box<dyn Constraint> = Box::new(TestConstraint {
            body_a: h1,
            body_b: h2,
            prepared: false,
            velocity_solved: false,
            position_solved: false,
        });

        let mut constraints = vec![c1, c2];
        solver.solve_boxed(&mut constraints, &mut bodies, 1.0 / 60.0);

        assert!(constraints[0].body_a() == h1);
        assert!(constraints[0].body_b() == h2);
    }
}
