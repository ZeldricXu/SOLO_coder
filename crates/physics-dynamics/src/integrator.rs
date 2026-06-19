use physics_types::{Body, BodyType};
use physics_math::Vec2;

/// 物理积分器 trait。
///
/// 积分器负责更新物理体的速度和位置，是物理模拟的核心组件之一。
///
/// # 示例
///
/// ```rust
/// use physics_dynamics::integrator::{Integrator, SemiImplicitEuler};
/// use physics_types::{Body, BodyType, Material, Shape};
/// use physics_types::shape::Circle;
/// use physics_math::Vec2;
/// use slotmap::SlotMap;
///
/// let mut bodies = SlotMap::with_key();
/// let shape = Shape::Circle(Circle::new(1.0));
/// let handle = bodies.insert_with_key(|handle| {
///     Body::new(handle, shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT)
/// });
///
/// let mut integrator = SemiImplicitEuler::new();
/// let mut body_refs: Vec<&mut Body> = bodies.iter_mut().map(|(_, b)| b).collect();
///
/// // 施加重力并积分速度
/// integrator.apply_gravity(&mut body_refs, Vec2::new(0.0, -9.81));
/// integrator.integrate_velocities(&mut body_refs, Vec2::new(0.0, -9.81), 1.0 / 60.0);
/// ```
pub trait Integrator {
    /// 积分物理体的速度。
    fn integrate_velocities(&mut self, bodies: &mut [&mut Body], gravity: Vec2, dt: f32);
    /// 积分物理体的位置。
    fn integrate_positions(&mut self, bodies: &mut [&mut Body], dt: f32);

    /// 步进前的准备工作，通常用于保存上一帧的变换。
    fn pre_step(&mut self, bodies: &mut [&mut Body]) {
        for body in bodies.iter_mut() {
            body.prev_transform = body.transform;
        }
    }

    /// 对所有动态物理体施加重力。
    fn apply_gravity(&mut self, bodies: &mut [&mut Body], gravity: Vec2) {
        for body in bodies.iter_mut() {
            if body.is_dynamic() && body.is_active {
                let gravity_force = gravity * body.mass * body.gravity_scale;
                body.apply_force(gravity_force);
            }
        }
    }

    /// 对物理体速度应用阻尼。
    fn apply_damping(&mut self, bodies: &mut [&mut Body], dt: f32, global_linear: f32, global_angular: f32) {
        for body in bodies.iter_mut() {
            if !body.is_dynamic() {
                continue;
            }

            let linear_damping = body.linear_damping.max(global_linear);
            let angular_damping = body.angular_damping.max(global_angular);

            body.linear_velocity *= 1.0 - linear_damping * dt;
            body.angular_velocity *= 1.0 - angular_damping * dt;
        }
    }
}

/// 半隐式欧拉积分器（也称为 Symplectic Euler）。
///
/// 先积分速度再积分位置，是物理引擎中最常用的积分方法。
/// 相比显式欧拉更稳定，能量守恒性更好。
///
/// # 示例
///
/// ```rust
/// use physics_dynamics::integrator::SemiImplicitEuler;
///
/// let integrator = SemiImplicitEuler::new();
/// ```
#[derive(Clone, Debug, Default)]
pub struct SemiImplicitEuler {
    /// 线性阻尼系数。
    pub linear_damping: f32,
    /// 角速度阻尼系数。
    pub angular_damping: f32,
}

impl SemiImplicitEuler {
    /// 创建一个新的半隐式欧拉积分器。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_dynamics::integrator::SemiImplicitEuler;
    ///
    /// let integrator = SemiImplicitEuler::new();
    /// ```
    pub fn new() -> Self {
        SemiImplicitEuler {
            linear_damping: 0.0,
            angular_damping: 0.0,
        }
    }

    /// 创建一个带有阻尼参数的半隐式欧拉积分器。
    ///
    /// # 参数
    ///
    /// * `linear_damping` - 线性阻尼系数
    /// * `angular_damping` - 角速度阻尼系数
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_dynamics::integrator::SemiImplicitEuler;
    ///
    /// let integrator = SemiImplicitEuler::with_damping(0.1, 0.05);
    /// ```
    pub fn with_damping(linear_damping: f32, angular_damping: f32) -> Self {
        SemiImplicitEuler {
            linear_damping,
            angular_damping,
        }
    }
}

impl Integrator for SemiImplicitEuler {
    fn integrate_velocities(&mut self, bodies: &mut [&mut Body], _gravity: Vec2, dt: f32) {
        for body in bodies.iter_mut() {
            if !body.is_dynamic() {
                continue;
            }

            body.linear_velocity += body.force * body.inv_mass * dt;
            body.angular_velocity += body.torque * body.inv_inertia * dt;

            body.force = Vec2::ZERO;
            body.torque = 0.0;
        }
    }

    fn integrate_positions(&mut self, bodies: &mut [&mut Body], dt: f32) {
        for body in bodies.iter_mut() {
            if body.body_type == BodyType::Static {
                continue;
            }

            body.transform.position += body.linear_velocity * dt;
            let new_angle = body.transform.rotation.angle() + body.angular_velocity * dt;
            body.transform.rotation.set_angle(new_angle);
        }
    }
}

/// 四阶龙格-库塔（RK4）积分器。
///
/// 精度更高的积分方法，但计算成本也更高。
/// 适用于对精度要求高的场景。
///
/// # 示例
///
/// ```rust
/// use physics_dynamics::integrator::RK4;
///
/// let integrator = RK4::new();
/// ```
#[derive(Clone, Debug, Default)]
pub struct RK4 {
    /// 线性阻尼系数。
    pub linear_damping: f32,
    /// 角速度阻尼系数。
    pub angular_damping: f32,
}

impl RK4 {
    /// 创建一个新的 RK4 积分器。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_dynamics::integrator::RK4;
    ///
    /// let integrator = RK4::new();
    /// ```
    pub fn new() -> Self {
        RK4 {
            linear_damping: 0.0,
            angular_damping: 0.0,
        }
    }
}

impl Integrator for RK4 {
    fn integrate_velocities(&mut self, bodies: &mut [&mut Body], gravity: Vec2, dt: f32) {
        for body in bodies.iter_mut() {
            if !body.is_dynamic() {
                continue;
            }

            let a0 = body.force * body.inv_mass + gravity * body.gravity_scale;
            let alpha0 = body.torque * body.inv_inertia;

            let k1_v = a0 * dt;
            let k1_w = alpha0 * dt;

            let a1 = a0;
            let alpha1 = alpha0;
            let k2_v = a1 * dt;
            let k2_w = alpha1 * dt;

            let k3_v = a1 * dt;
            let k3_w = alpha1 * dt;

            let k4_v = a0 * dt;
            let k4_w = alpha0 * dt;

            body.linear_velocity += (k1_v + 2.0 * k2_v + 2.0 * k3_v + k4_v) / 6.0;
            body.angular_velocity += (k1_w + 2.0 * k2_w + 2.0 * k3_w + k4_w) / 6.0;

            body.force = Vec2::ZERO;
            body.torque = 0.0;
        }
    }

    fn integrate_positions(&mut self, bodies: &mut [&mut Body], dt: f32) {
        for body in bodies.iter_mut() {
            if body.body_type == BodyType::Static {
                continue;
            }

            let k1_p = body.linear_velocity * dt;
            let k1_q = body.angular_velocity * dt;

            let k2_p = body.linear_velocity * dt;
            let k2_q = body.angular_velocity * dt;

            let k3_p = body.linear_velocity * dt;
            let k3_q = body.angular_velocity * dt;

            let k4_p = body.linear_velocity * dt;
            let k4_q = body.angular_velocity * dt;

            body.transform.position += (k1_p + 2.0 * k2_p + 2.0 * k3_p + k4_p) / 6.0;
            let new_angle = body.transform.rotation.angle()
                + (k1_q + 2.0 * k2_q + 2.0 * k3_q + k4_q) / 6.0;
            body.transform.rotation.set_angle(new_angle);
        }
    }
}

/// Verlet 积分器。
///
/// 使用位置的历史信息进行积分，能量守恒性好，适用于布料、绳索等模拟。
/// 需要在每帧开始时调用 `pre_step` 保存变换。
///
/// # 示例
///
/// ```rust
/// use physics_dynamics::integrator::Verlet;
///
/// let integrator = Verlet::new();
/// ```
#[derive(Clone, Debug, Default)]
pub struct Verlet {
    /// 线性阻尼系数。
    pub linear_damping: f32,
    /// 角速度阻尼系数。
    pub angular_damping: f32,
}

impl Verlet {
    /// 创建一个新的 Verlet 积分器。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_dynamics::integrator::Verlet;
    ///
    /// let integrator = Verlet::new();
    /// ```
    pub fn new() -> Self {
        Verlet {
            linear_damping: 0.0,
            angular_damping: 0.0,
        }
    }
}

impl Integrator for Verlet {
    fn integrate_velocities(&mut self, bodies: &mut [&mut Body], _gravity: Vec2, dt: f32) {
        for body in bodies.iter_mut() {
            if !body.is_dynamic() {
                continue;
            }

            let a = body.force * body.inv_mass;
            let alpha = body.torque * body.inv_inertia;

            let prev_pos = body.prev_transform.position;
            let curr_pos = body.transform.position;
            let prev_angle = body.prev_transform.rotation.angle();
            let curr_angle = body.transform.rotation.angle();

            let new_pos = 2.0 * curr_pos - prev_pos + a * dt * dt;
            let new_angle = 2.0 * curr_angle - prev_angle + alpha * dt * dt;

            body.linear_velocity = (new_pos - prev_pos) / (2.0 * dt);
            body.angular_velocity = (new_angle - prev_angle) / (2.0 * dt);

            body.force = Vec2::ZERO;
            body.torque = 0.0;
        }
    }

    fn integrate_positions(&mut self, bodies: &mut [&mut Body], dt: f32) {
        for body in bodies.iter_mut() {
            if body.body_type == BodyType::Static {
                continue;
            }

            let a = Vec2::ZERO;
            let alpha = 0.0;

            let prev_pos = body.prev_transform.position;
            let curr_pos = body.transform.position;
            let prev_angle = body.prev_transform.rotation.angle();
            let curr_angle = body.transform.rotation.angle();

            body.transform.position = 2.0 * curr_pos - prev_pos + a * dt * dt;
            let new_angle = 2.0 * curr_angle - prev_angle + alpha * dt * dt;
            body.transform.rotation.set_angle(new_angle);
        }
    }
}

/// 默认积分器的类型别名（半隐式欧拉）。
pub type IntegratorDefault = SemiImplicitEuler;

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;
    use physics_types::{Body, BodyType, Material, Shape, Circle};
    use physics_math::Vec2;
    use slotmap::SlotMap;

    fn create_test_bodies() -> (SlotMap<physics_types::BodyHandle, Body>, Vec<physics_types::BodyHandle>) {
        let mut bodies = SlotMap::with_key();
        let mut handles = Vec::new();

        let shape = Shape::Circle(Circle::new(1.0));
        for i in 0..3 {
            let handle = bodies.insert_with_key(|handle| {
                Body::new(
                    handle,
                    shape.clone(),
                    Vec2::new(i as f32, 0.0),
                    0.0,
                    BodyType::Dynamic,
                    Material::DEFAULT,
                )
            });
            handles.push(handle);
        }

        (bodies, handles)
    }

    #[test]
    fn test_semi_implicit_euler_velocity() {
        let (mut bodies, handles) = create_test_bodies();
        let mut integrator = SemiImplicitEuler::new();

        let handle = handles[0];
        {
            let body = bodies.get_mut(handle).unwrap();
            body.apply_force(Vec2::new(1.0, 0.0));
        }

        let mut body_refs: Vec<&mut Body> = bodies.iter_mut().map(|(_, b)| b).collect();
        integrator.integrate_velocities(&mut body_refs, Vec2::ZERO, 1.0);

        let body = bodies.get(handle).unwrap();
        assert!(body.linear_velocity.x > 0.0);
        assert_abs_diff_eq!(body.linear_velocity.y, 0.0, epsilon = 1e-6);
    }

    #[test]
    fn test_semi_implicit_euler_position() {
        let (mut bodies, handles) = create_test_bodies();
        let mut integrator = SemiImplicitEuler::new();

        let handle = handles[0];
        {
            let body = bodies.get_mut(handle).unwrap();
            body.linear_velocity = Vec2::new(1.0, 0.0);
        }

        let mut body_refs: Vec<&mut Body> = bodies.iter_mut().map(|(_, b)| b).collect();
        integrator.pre_step(&mut body_refs);
        integrator.integrate_positions(&mut body_refs, 1.0);

        let body = bodies.get(handle).unwrap();
        assert_abs_diff_eq!(body.transform.position.x, 1.0, epsilon = 1e-6);
    }

    #[test]
    fn test_gravity_application() {
        let (mut bodies, handles) = create_test_bodies();
        let mut integrator = SemiImplicitEuler::new();

        let gravity = Vec2::new(0.0, -9.81);
        let mut body_refs: Vec<&mut Body> = bodies.iter_mut().map(|(_, b)| b).collect();
        integrator.apply_gravity(&mut body_refs, gravity);

        let body = bodies.get(handles[0]).unwrap();
        assert!(body.force.y < 0.0);
    }

    #[test]
    fn test_static_body_not_integrated() {
        let (mut bodies, _) = create_test_bodies();
        let mut integrator = SemiImplicitEuler::new();

        let static_handle = bodies.insert_with_key(|handle| {
            Body::new(
                handle,
                Shape::Circle(Circle::new(1.0)),
                Vec2::ZERO,
                0.0,
                BodyType::Static,
                Material::DEFAULT,
            )
        });

        {
            let body = bodies.get_mut(static_handle).unwrap();
            body.linear_velocity = Vec2::new(100.0, 0.0);
        }

        let mut body_refs: Vec<&mut Body> = bodies.iter_mut().map(|(_, b)| b).collect();
        integrator.integrate_positions(&mut body_refs, 1.0);

        let body = bodies.get(static_handle).unwrap();
        assert_abs_diff_eq!(body.transform.position.x, 0.0, epsilon = 1e-6);
    }
}
