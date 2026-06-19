/// 物理材质。

///

/// 定义物理体的表面物理属性，包括弹性、摩擦和密度。

///

/// # 示例

///

/// ```rust

/// use physics_types::Material;

///

/// // 使用默认材质

/// let mat = Material::DEFAULT;

/// assert_eq!(mat.restitution, 0.2);

///

/// // 创建自定义材质

/// let rubber = Material::new(0.8, 0.9, 0.7, 1.1);

/// assert_eq!(rubber.restitution, 0.8);

///

/// // 使用构建器模式

/// let ice = Material::DEFAULT

///     .with_restitution(0.05)

///     .with_friction(0.1, 0.05);

/// ```

#[derive(Copy, Clone, Debug, PartialEq)]

pub struct Material {

    /// 弹性系数（恢复系数），范围 [0, 1]。0 表示完全非弹性碰撞，1 表示完全弹性碰撞。

    pub restitution: f32,

    /// 静摩擦系数，通常范围 [0, 1]。

    pub static_friction: f32,

    /// 动摩擦系数，通常范围 [0, 1]，一般小于等于静摩擦系数。

    pub dynamic_friction: f32,

    /// 密度（kg/m²），用于计算物体质量。

    pub density: f32,

}

impl Material {

    /// 默认材质。

    ///

    /// 弹性 0.2、静摩擦 0.6、动摩擦 0.4、密度 1.0。

    ///

    /// # 示例

    ///

    /// ```rust

    /// use physics_types::Material;

    ///

    /// let mat = Material::DEFAULT;

    /// assert_eq!(mat.density, 1.0);

    /// ```

    pub const DEFAULT: Material = Material {

        restitution: 0.2,

        static_friction: 0.6,

        dynamic_friction: 0.4,

        density: 1.0,

    };

    /// 创建新的物理材质。

    ///

    /// # 参数

    ///

    /// * `restitution` - 弹性系数

    /// * `static_friction` - 静摩擦系数

    /// * `dynamic_friction` - 动摩擦系数

    /// * `density` - 密度

    ///

    /// # 示例

    ///

    /// ```rust

    /// use physics_types::Material;

    ///

    /// let mat = Material::new(0.5, 0.8, 0.6, 2.0);

    /// assert_eq!(mat.restitution, 0.5);

    /// assert_eq!(mat.static_friction, 0.8);

    /// assert_eq!(mat.dynamic_friction, 0.6);

    /// assert_eq!(mat.density, 2.0);

    /// ```

    #[inline]

    pub fn new(restitution: f32, static_friction: f32, dynamic_friction: f32, density: f32) -> Self {

        Material {

            restitution,

            static_friction,

            dynamic_friction,

            density,

        }

    }

    /// 设置弹性系数，返回修改后的材质（构建器模式）。

    ///

    /// # 示例

    ///

    /// ```rust

    /// use physics_types::Material;

    ///

    /// let bouncy = Material::DEFAULT.with_restitution(0.9);

    /// assert_eq!(bouncy.restitution, 0.9);

    /// ```

    #[inline]

    pub fn with_restitution(mut self, restitution: f32) -> Self {

        self.restitution = restitution;

        self

    }

    /// 设置摩擦系数（静摩擦和动摩擦），返回修改后的材质（构建器模式）。

    ///

    /// # 示例

    ///

    /// ```rust

    /// use physics_types::Material;

    ///

    /// let slippery = Material::DEFAULT.with_friction(0.1, 0.05);

    /// assert_eq!(slippery.static_friction, 0.1);

    /// assert_eq!(slippery.dynamic_friction, 0.05);

    /// ```

    #[inline]

    pub fn with_friction(mut self, static_friction: f32, dynamic_friction: f32) -> Self {

        self.static_friction = static_friction;

        self.dynamic_friction = dynamic_friction;

        self

    }

    /// 设置密度，返回修改后的材质（构建器模式）。

    ///

    /// # 示例

    ///

    /// ```rust

    /// use physics_types::Material;

    ///

    /// let heavy = Material::DEFAULT.with_density(7.8);

    /// assert_eq!(heavy.density, 7.8);

    /// ```

    #[inline]

    pub fn with_density(mut self, density: f32) -> Self {

        self.density = density;

        self

    }

    /// 组合两个材质的弹性系数（几何平均）。

    ///

    /// 用于碰撞时，使用两个接触材质弹性系数的几何平均值作为碰撞弹性。

    ///

    /// # 示例

    ///

    /// ```rust

    /// use physics_types::Material;

    ///

    /// let combined = Material::combine_restitution(0.25, 0.25);

    /// assert!((combined - 0.25).abs() < 1e-6);

    /// ```

    #[inline]

    pub fn combine_restitution(a: f32, b: f32) -> f32 {

        (a * b).sqrt()

    }

    /// 组合两个材质的摩擦系数（几何平均）。

    ///

    /// 用于碰撞时，使用两个接触材质摩擦系数的几何平均值作为碰撞摩擦。

    ///

    /// # 示例

    ///

    /// ```rust

    /// use physics_types::Material;

    ///

    /// let combined = Material::combine_friction(0.36, 0.36);

    /// assert!((combined - 0.36).abs() < 1e-6);

    /// ```

    #[inline]

    pub fn combine_friction(a: f32, b: f32) -> f32 {

        (a * b).sqrt()

    }

}

impl Default for Material {

    fn default() -> Self {

        Material::DEFAULT

    }

}

#[cfg(test)]

mod tests {

    use super::*;

    use approx::assert_abs_diff_eq;

    #[test]

    fn test_material_creation() {

        let m = Material::new(0.5, 0.8, 0.6, 2.0);

        assert_abs_diff_eq!(m.restitution, 0.5);

        assert_abs_diff_eq!(m.static_friction, 0.8);

    }

    #[test]

    fn test_combine() {

        assert_abs_diff_eq!(Material::combine_restitution(0.25, 0.25), 0.25);

        assert_abs_diff_eq!(Material::combine_friction(0.36, 0.36), 0.36);

    }

}

