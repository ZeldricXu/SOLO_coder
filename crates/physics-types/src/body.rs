use slotmap::{new_key_type, Key};

use physics_math::{AABB, Rot2, Transform, Vec2};

use crate::{material::Material, shape::{CollisionFilter, Shape}};

new_key_type! {
    /// 物理体的句柄类型，用于在世界中唯一标识一个物理体。
    pub struct BodyHandle;
}

/// 物理体类型。
///
/// 决定了物理体在模拟中的行为方式。
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum BodyType {
    /// 静态物体，不受力影响，不移动。
    Static,
    /// 运动学物体，可以通过代码控制移动，但不受力影响。
    Kinematic,
    /// 动态物体，受力影响，参与完整的物理模拟。
    Dynamic,
}

impl Default for BodyType {
    fn default() -> Self {
        BodyType::Dynamic
    }
}

/// 物理体，是物理模拟的基本单位。
///
/// 包含了位置、速度、质量、形状、材质等所有物理属性。
///
/// # 示例
///
/// ```rust
/// use physics_types::{Body, BodyType, Material, Shape, BodyHandle};
/// use physics_types::shape::Circle;
/// use physics_math::Vec2;
/// use slotmap::KeyData;
///
/// let shape = Shape::Circle(Circle::new(1.0));
/// let material = Material::DEFAULT;
/// let handle: BodyHandle = KeyData::from_ffi(1).into();
///
/// let body = Body::new(
///     handle,
///     shape,
///     Vec2::new(0.0, 5.0),
///     0.0,
///     BodyType::Dynamic,
///     material,
/// );
///
/// assert!(body.is_dynamic());
/// assert_eq!(body.position().y, 5.0);
/// ```
#[derive(Clone, Debug)]
pub struct Body {
    /// 物理体的唯一标识句柄。
    pub handle: BodyHandle,
    /// 物理体类型。
    pub body_type: BodyType,
    /// 物理体的碰撞形状。
    pub shape: Shape,
    /// 物理体的材质。
    pub material: Material,

    /// 当前变换（位置和旋转）。
    pub transform: Transform,
    /// 上一帧的变换。
    pub prev_transform: Transform,

    /// 线速度。
    pub linear_velocity: Vec2,
    /// 角速度。
    pub angular_velocity: f32,

    /// 当前累积的力。
    pub force: Vec2,
    /// 当前累积的扭矩。
    pub torque: f32,

    /// 质量。
    pub mass: f32,
    /// 质量的倒数（0 表示无限质量）。
    pub inv_mass: f32,
    /// 转动惯量。
    pub inertia: f32,
    /// 转动惯量的倒数。
    pub inv_inertia: f32,

    /// 重力缩放系数。
    pub gravity_scale: f32,
    /// 线性阻尼系数。
    pub linear_damping: f32,
    /// 角速度阻尼系数。
    pub angular_damping: f32,

    /// 是否为传感器（不产生碰撞响应，只触发事件）。
    pub is_sensor: bool,
    /// 是否处于激活状态。
    pub is_active: bool,
    /// 是否标记为高速物体（启用CCD连续碰撞检测）。
    pub is_bullet: bool,

    /// 碰撞过滤设置。
    pub collision_filter: CollisionFilter,

    /// 用户自定义数据。
    pub user_data: u64,
}

impl Body {
    /// 创建一个新的物理体。
    ///
    /// # 参数
    ///
    /// * `handle` - 物理体句柄
    /// * `shape` - 碰撞形状
    /// * `position` - 初始位置
    /// * `angle` - 初始旋转角度（弧度）
    /// * `body_type` - 物理体类型
    /// * `material` - 材质
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::{Body, BodyType, Material, Shape, BodyHandle};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle: BodyHandle = KeyData::from_ffi(1).into();
    /// let body = Body::new(handle, shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT);
    /// ```
    pub fn new(
        handle: BodyHandle,
        shape: Shape,
        position: Vec2,
        angle: f32,
        body_type: BodyType,
        material: Material,
    ) -> Self {
        let transform = Transform::new(position, Rot2::new(angle));
        let mass = if body_type == BodyType::Dynamic {
            shape.compute_mass(material.density)
        } else {
            0.0
        };
        let inv_mass = if mass > f32::EPSILON { 1.0 / mass } else { 0.0 };
        let inertia = if body_type == BodyType::Dynamic {
            shape.compute_inertia(mass)
        } else {
            0.0
        };
        let inv_inertia = if inertia > f32::EPSILON {
            1.0 / inertia
        } else {
            0.0
        };

        Body {
            handle,
            body_type,
            shape,
            material,
            transform,
            prev_transform: transform,
            linear_velocity: Vec2::ZERO,
            angular_velocity: 0.0,
            force: Vec2::ZERO,
            torque: 0.0,
            mass,
            inv_mass,
            inertia,
            inv_inertia,
            gravity_scale: 1.0,
            linear_damping: 0.0,
            angular_damping: 0.0,
            is_sensor: false,
            is_active: true,
            is_bullet: false,
            collision_filter: CollisionFilter::default(),
            user_data: 0,
        }
    }

    /// 创建一个临时物理体，用于碰撞检测等临时操作。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::{Body, Shape};
    /// use physics_types::shape::Circle;
    /// use physics_math::{Transform, Rot2, Vec2};
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let transform = Transform::new(Vec2::ZERO, Rot2::new(0.0));
    /// let temp_body = Body::new_temp(shape, transform);
    /// ```
    pub fn new_temp(shape: Shape, transform: Transform) -> Self {
        Body {
            handle: BodyHandle::null(),
            body_type: BodyType::Static,
            shape,
            material: Material::DEFAULT,
            transform,
            prev_transform: transform,
            linear_velocity: Vec2::ZERO,
            angular_velocity: 0.0,
            force: Vec2::ZERO,
            torque: 0.0,
            mass: 0.0,
            inv_mass: 0.0,
            inertia: 0.0,
            inv_inertia: 0.0,
            gravity_scale: 1.0,
            linear_damping: 0.0,
            angular_damping: 0.0,
            is_sensor: false,
            is_active: true,
            is_bullet: false,
            collision_filter: CollisionFilter::default(),
            user_data: 0,
        }
    }

    /// 设置碰撞过滤器（链式调用）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::{Body, BodyType, Material, Shape, BodyHandle, shape::CollisionFilter};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle: BodyHandle = KeyData::from_ffi(1).into();
    /// let filter = CollisionFilter::new(0x0001, 0xFFFF);
    ///
    /// let body = Body::new(handle, shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT)
    ///     .with_collision_filter(filter);
    /// ```
    #[inline]
    pub fn with_collision_filter(mut self, filter: CollisionFilter) -> Self {
        self.collision_filter = filter;
        self
    }

    /// 设置碰撞过滤器。
    #[inline]
    pub fn set_collision_filter(&mut self, filter: CollisionFilter) {
        self.collision_filter = filter;
    }

    /// 检查是否应该与另一个物理体发生碰撞。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::{Body, BodyType, Material, Shape, BodyHandle, shape::CollisionFilter};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let filter1 = CollisionFilter::new(0x0001, 0x0001);
    /// let filter2 = CollisionFilter::new(0x0002, 0x0002);
    ///
    /// let handle1: BodyHandle = KeyData::from_ffi(1).into();
    /// let handle2: BodyHandle = KeyData::from_ffi(2).into();
    ///
    /// let body1 = Body::new(handle1, shape.clone(), Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT)
    ///     .with_collision_filter(filter1);
    /// let body2 = Body::new(handle2, shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT)
    ///     .with_collision_filter(filter2);
    ///
    /// assert!(!body1.should_collide_with(&body2));
    /// ```
    #[inline]
    pub fn should_collide_with(&self, other: &Body) -> bool {
        self.collision_filter.should_collide(&other.collision_filter)
    }

    /// 获取物理体句柄。
    #[inline]
    pub fn handle(&self) -> BodyHandle {
        self.handle
    }

    /// 获取物理体类型。
    #[inline]
    pub fn body_type(&self) -> BodyType {
        self.body_type
    }

    /// 检查是否为动态物理体。
    #[inline]
    pub fn is_dynamic(&self) -> bool {
        self.body_type == BodyType::Dynamic
    }

    /// 检查是否为运动学物理体。
    #[inline]
    pub fn is_kinematic(&self) -> bool {
        self.body_type == BodyType::Kinematic
    }

    /// 检查是否为静态物理体。
    #[inline]
    pub fn is_static(&self) -> bool {
        self.body_type == BodyType::Static
    }

    /// 获取物理体位置。
    #[inline]
    pub fn position(&self) -> Vec2 {
        self.transform.position
    }

    /// 获取物理体旋转角度（弧度）。
    #[inline]
    pub fn angle(&self) -> f32 {
        self.transform.rotation.angle()
    }

    /// 设置物理体位置。
    #[inline]
    pub fn set_position(&mut self, position: Vec2) {
        self.transform.position = position;
    }

    /// 设置物理体旋转角度。
    #[inline]
    pub fn set_angle(&mut self, angle: f32) {
        self.transform.rotation.set_angle(angle);
    }

    /// 同时设置位置和旋转角度。
    #[inline]
    pub fn set_transform(&mut self, position: Vec2, angle: f32) {
        self.transform.position = position;
        self.transform.rotation.set_angle(angle);
    }

    /// 对物理体质心施加一个力。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::{Body, BodyType, Material, Shape, BodyHandle};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle: BodyHandle = KeyData::from_ffi(1).into();
    /// let mut body = Body::new(handle, shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// body.apply_force(Vec2::new(10.0, 0.0));
    /// assert_eq!(body.force.x, 10.0);
    /// ```
    #[inline]
    pub fn apply_force(&mut self, force: Vec2) {
        if self.is_dynamic() {
            self.force += force;
        }
    }

    /// 在指定点施加一个力（会产生扭矩）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::{Body, BodyType, Material, Shape, BodyHandle};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle: BodyHandle = KeyData::from_ffi(1).into();
    /// let mut body = Body::new(handle, shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// // 在边缘施加向上的力，会产生旋转
    /// body.apply_force_at_point(Vec2::new(0.0, 10.0), Vec2::new(1.0, 0.0));
    /// assert!(body.torque != 0.0);
    /// ```
    #[inline]
    pub fn apply_force_at_point(&mut self, force: Vec2, point: Vec2) {
        if self.is_dynamic() {
            self.force += force;
            self.torque += (point - self.transform.position).cross(force);
        }
    }

    /// 施加一个扭矩。
    #[inline]
    pub fn apply_torque(&mut self, torque: f32) {
        if self.is_dynamic() {
            self.torque += torque;
        }
    }

    /// 对质心施加一个冲量（直接改变速度）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::{Body, BodyType, Material, Shape, BodyHandle};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    /// use approx::assert_abs_diff_eq;
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle: BodyHandle = KeyData::from_ffi(1).into();
    /// let mut body = Body::new(handle, shape, Vec2::ZERO, 0.0, BodyType::Dynamic,
    ///     Material::DEFAULT.with_density(1.0));
    ///
    /// let mass = body.mass;
    /// body.apply_impulse(Vec2::new(mass, 0.0));
    /// assert_abs_diff_eq!(body.linear_velocity.x, 1.0);
    /// ```
    #[inline]
    pub fn apply_impulse(&mut self, impulse: Vec2) {
        if self.is_dynamic() {
            self.linear_velocity += impulse * self.inv_mass;
        }
    }

    /// 在指定点施加一个冲量（会改变角速度）。
    #[inline]
    pub fn apply_impulse_at_point(&mut self, impulse: Vec2, point: Vec2) {
        if self.is_dynamic() {
            self.linear_velocity += impulse * self.inv_mass;
            self.angular_velocity += (point - self.transform.position).cross(impulse) * self.inv_inertia;
        }
    }

    /// 施加一个角冲量（直接改变角速度）。
    #[inline]
    pub fn apply_angular_impulse(&mut self, angular_impulse: f32) {
        if self.is_dynamic() {
            self.angular_velocity += angular_impulse * self.inv_inertia;
        }
    }

    /// 获取指定点的速度（考虑旋转）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::{Body, BodyType, Material, Shape, BodyHandle};
    /// use physics_types::shape::Circle;
    /// use physics_math::Vec2;
    /// use slotmap::KeyData;
    ///
    /// let shape = Shape::Circle(Circle::new(1.0));
    /// let handle: BodyHandle = KeyData::from_ffi(1).into();
    /// let mut body = Body::new(handle, shape, Vec2::ZERO, 0.0, BodyType::Dynamic, Material::DEFAULT);
    ///
    /// body.linear_velocity = Vec2::new(1.0, 0.0);
    /// body.angular_velocity = 1.0;
    ///
    /// // 物体边缘点的速度 = 线速度 + 旋转引起的速度
    /// let point_vel = body.get_point_velocity(Vec2::new(0.0, 1.0));
    /// ```
    #[inline]
    pub fn get_point_velocity(&self, point: Vec2) -> Vec2 {
        let r = point - self.transform.position;
        self.linear_velocity + Vec2::new(-self.angular_velocity * r.y, self.angular_velocity * r.x)
    }

    /// 清除累积的力和扭矩。
    #[inline]
    pub fn clear_forces(&mut self) {
        self.force = Vec2::ZERO;
        self.torque = 0.0;
    }

    /// 计算物理体的轴对齐包围盒（AABB）。
    #[inline]
    pub fn compute_aabb(&self) -> AABB {
        self.shape.compute_aabb(&self.transform)
    }

    /// 检查此物体是否为CCD（连续碰撞检测）候选。
    ///
    /// 当物体是动态的、标记为高速物体（is_bullet），且其速度在给定时间步长内
    /// 超过自身尺寸时，需要启用CCD来防止穿透。
    #[inline]
    pub fn is_ccd_candidate(&self, dt: f32) -> bool {
        if !self.is_dynamic() || !self.is_bullet {
            return false;
        }
        let velocity = self.linear_velocity.length();
        let aabb = self.compute_aabb();
        let extent = (aabb.max - aabb.min).length() * 0.5;
        velocity * dt > extent
    }

    /// 根据形状和材质重新计算质量属性。
    ///
    /// 在修改形状或材质后需要调用此方法。
    #[inline]
    pub fn update_mass_properties(&mut self) {
        if self.body_type == BodyType::Dynamic {
            self.mass = self.shape.compute_mass(self.material.density);
            self.inv_mass = if self.mass > f32::EPSILON {
                1.0 / self.mass
            } else {
                0.0
            };
            self.inertia = self.shape.compute_inertia(self.mass);
            self.inv_inertia = if self.inertia > f32::EPSILON {
                1.0 / self.inertia
            } else {
                0.0
            };
        } else {
            self.mass = 0.0;
            self.inv_mass = 0.0;
            self.inertia = 0.0;
            self.inv_inertia = 0.0;
        }
    }

    /// 设置物理体类型并更新质量属性。
    #[inline]
    pub fn set_body_type(&mut self, body_type: BodyType) {
        self.body_type = body_type;
        self.update_mass_properties();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shape::Circle;
    use approx::assert_abs_diff_eq;

    fn create_test_body() -> Body {
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        Body::new(
            BodyHandle::from(slotmap::KeyData::from_ffi(1)),
            shape,
            Vec2::ZERO,
            0.0,
            BodyType::Dynamic,
            material,
        )
    }

    #[test]
    fn test_body_creation() {
        let body = create_test_body();
        assert!(body.is_dynamic());
        assert_abs_diff_eq!(body.position(), Vec2::ZERO);
        assert!(body.mass > 0.0);
        assert!(body.inv_mass > 0.0);
    }

    #[test]
    fn test_apply_force() {
        let mut body = create_test_body();
        body.apply_force(Vec2::new(10.0, 0.0));
        assert_abs_diff_eq!(body.force.x, 10.0);
    }

    #[test]
    fn test_apply_impulse() {
        let mut body = create_test_body();
        let mass = body.mass;
        body.apply_impulse(Vec2::new(mass, 0.0));
        assert_abs_diff_eq!(body.linear_velocity.x, 1.0);
    }

    #[test]
    fn test_static_body() {
        let shape = Shape::Circle(Circle::new(1.0));
        let material = Material::DEFAULT;
        let body = Body::new(
            BodyHandle::from(slotmap::KeyData::from_ffi(2)),
            shape,
            Vec2::ZERO,
            0.0,
            BodyType::Static,
            material,
        );
        assert!(body.is_static());
        assert_abs_diff_eq!(body.inv_mass, 0.0);
        assert_abs_diff_eq!(body.inv_inertia, 0.0);
    }
}
