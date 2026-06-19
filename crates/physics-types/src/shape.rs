use physics_math::{polygon_moment_of_inertia, AABB, Transform, Vec2};

/// 碰撞过滤器。
///
/// 通过位掩码和组索引控制哪些物理体之间可以发生碰撞。
///
/// # 示例
///
/// ```rust
/// use physics_types::shape::CollisionFilter;
///
/// // 两个相同过滤器的物体可以碰撞
/// let filter1 = CollisionFilter::new(0x0001, 0xFFFF);
/// let filter2 = CollisionFilter::new(0x0001, 0xFFFF);
/// assert!(filter1.should_collide(&filter2));
///
/// // 不同组的物体不能碰撞
/// let filter3 = CollisionFilter::new(0x0002, 0x0002);
/// assert!(!filter1.should_collide(&filter3));
/// ```
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct CollisionFilter {
    /// 类别位掩码，标识此物体属于哪些类别。
    pub category_bits: u16,
    /// 掩码位掩码，标识此物体可以与哪些类别碰撞。
    pub mask_bits: u16,
    /// 组索引。同组正数总是碰撞，同组负数总是不碰撞，不同组使用位掩码判断。
    pub group_index: i16,
}

impl Default for CollisionFilter {
    fn default() -> Self {
        CollisionFilter {
            category_bits: 0x0001,
            mask_bits: 0xFFFF,
            group_index: 0,
        }
    }
}

impl CollisionFilter {
    /// 创建一个新的碰撞过滤器。
    ///
    /// # 参数
    ///
    /// * `category_bits` - 类别位掩码
    /// * `mask_bits` - 掩码位掩码
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::shape::CollisionFilter;
    ///
    /// let filter = CollisionFilter::new(0x0001, 0xFFFF);
    /// assert_eq!(filter.category_bits, 0x0001);
    /// assert_eq!(filter.group_index, 0);
    /// ```
    #[inline]
    pub fn new(category_bits: u16, mask_bits: u16) -> Self {
        CollisionFilter {
            category_bits,
            mask_bits,
            group_index: 0,
        }
    }

    /// 设置组索引（链式调用）。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::shape::CollisionFilter;
    ///
    /// // 同组正数总是碰撞
    /// let filter1 = CollisionFilter::new(0x0001, 0xFFFF).with_group(1);
    /// let filter2 = CollisionFilter::new(0x0001, 0xFFFF).with_group(1);
    /// assert!(filter1.should_collide(&filter2));
    ///
    /// // 同组负数总是不碰撞
    /// let filter3 = CollisionFilter::new(0x0001, 0xFFFF).with_group(-1);
    /// let filter4 = CollisionFilter::new(0x0001, 0xFFFF).with_group(-1);
    /// assert!(!filter3.should_collide(&filter4));
    /// ```
    #[inline]
    pub fn with_group(mut self, group_index: i16) -> Self {
        self.group_index = group_index;
        self
    }

    /// 判断两个过滤器是否允许碰撞。
    #[inline]
    pub fn should_collide(&self, other: &CollisionFilter) -> bool {
        if self.group_index != 0 && other.group_index != 0 {
            if self.group_index == other.group_index {
                return self.group_index > 0;
            }
        }
        (self.category_bits & other.mask_bits) != 0 && (other.category_bits & self.mask_bits) != 0
    }
}

/// 碰撞形状枚举。
///
/// 支持多种几何形状用于碰撞检测。
///
/// # 示例
///
/// ```rust
/// use physics_types::shape::{Shape, Circle, Rectangle};
/// use physics_math::Vec2;
///
/// let circle = Shape::Circle(Circle::new(1.0));
/// let rect = Shape::Rectangle(Rectangle::new(2.0, 3.0));
///
/// assert!(circle.is_convex());
/// assert!(rect.is_convex());
/// ```
#[derive(Clone, Debug, PartialEq)]
pub enum Shape {
    /// 圆形。
    Circle(Circle),
    /// 矩形（轴对齐或旋转）。
    Rectangle(Rectangle),
    /// 凸多边形。
    Polygon(Polygon),
    /// 线段。
    Segment(Segment),
    /// 半空间（无限大平面）。
    HalfSpace(HalfSpace),
}

/// 半空间（无限大平面）。
///
/// 由法线和到原点的有符号距离定义，平面方程为 `normal · x = distance`。
///
/// # 示例
///
/// ```rust
/// use physics_types::shape::HalfSpace;
/// use physics_math::Vec2;
///
/// // 创建一个标准地面（向上的法线，距离原点0）
/// let ground = HalfSpace::ground();
/// assert_eq!(ground.normal, Vec2::new(0.0, 1.0));
/// assert_eq!(ground.signed_distance(Vec2::new(0.0, 1.0)), 1.0);
/// ```
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct HalfSpace {
    /// 平面法线（单位向量）。
    pub normal: Vec2,
    /// 从原点到平面的有符号距离。
    pub distance: f32,
}

impl HalfSpace {
    /// 创建一个新的半空间。
    ///
    /// 法线会被自动归一化。
    #[inline]
    pub fn new(normal: Vec2, distance: f32) -> Self {
        HalfSpace {
            normal: normal.normalize(),
            distance,
        }
    }

    /// 通过平面上一点和法线创建半空间。
    ///
    /// # 示例
    ///
    /// ```rust
    /// use physics_types::shape::HalfSpace;
    /// use physics_math::Vec2;
    ///
    /// let point = Vec2::new(0.0, 5.0);
    /// let normal = Vec2::new(0.0, 1.0);
    /// let half_space = HalfSpace::from_point_normal(point, normal);
    ///
    /// assert_eq!(half_space.signed_distance(Vec2::new(0.0, 6.0)), 1.0);
    /// assert_eq!(half_space.signed_distance(point), 0.0);
    /// ```
    #[inline]
    pub fn from_point_normal(point: Vec2, normal: Vec2) -> Self {
        let n = normal.normalize();
        HalfSpace {
            normal: n,
            distance: point.dot(n),
        }
    }

    /// 创建一个标准地面半空间（y=0 平面，法线朝上）。
    #[inline]
    pub fn ground() -> Self {
        HalfSpace {
            normal: Vec2::new(0.0, 1.0),
            distance: 0.0,
        }
    }

    /// 计算半空间的 AABB（无限大）。
    #[inline]
    pub fn compute_aabb(&self, _transform: &Transform) -> AABB {
        AABB {
            min: Vec2::new(f32::NEG_INFINITY, f32::NEG_INFINITY),
            max: Vec2::new(f32::INFINITY, f32::INFINITY),
        }
    }

    /// 计算质量（半空间质量为 0）。
    #[inline]
    pub fn compute_mass(&self, _density: f32) -> f32 {
        0.0
    }

    /// 计算转动惯量（半空间惯量为 0）。
    #[inline]
    pub fn compute_inertia(&self, _mass: f32) -> f32 {
        0.0
    }

    /// 计算点到半空间的有符号距离。
    ///
    /// 正数表示在平面正面（法线一侧），负数表示在背面。
    #[inline]
    pub fn signed_distance(&self, point: Vec2) -> f32 {
        point.dot(self.normal) - self.distance
    }

    /// 获取支持点（用于 GJK 算法）。
    #[inline]
    pub fn support_point(&self, direction: Vec2, _transform: &Transform) -> Vec2 {
        if direction.dot(self.normal) < 0.0 {
            self.normal * (f32::NEG_INFINITY)
        } else {
            self.normal * f32::INFINITY
        }
    }
}

/// 圆形。
///
/// # 示例
///
/// ```rust
/// use physics_types::shape::Circle;
/// use approx::assert_abs_diff_eq;
///
/// let c = Circle::new(2.0);
/// assert_abs_diff_eq!(c.radius(), 2.0);
/// ```
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Circle {
    /// 半径。
    pub radius: f32,
}

impl Circle {
    /// 创建一个新的圆形。
    #[inline]
    pub fn new(radius: f32) -> Self {
        Circle { radius }
    }

    /// 获取半径。
    #[inline]
    pub fn radius(&self) -> f32 {
        self.radius
    }

    /// 计算圆形的 AABB。
    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        let r = Vec2::splat(self.radius);
        AABB::from_center_extents(transform.position, r)
    }

    /// 计算圆形的质量。
    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        std::f32::consts::PI * self.radius * self.radius * density
    }

    /// 计算圆形的转动惯量。
    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        0.5 * mass * self.radius * self.radius
    }

    /// 获取支持点（用于 GJK 算法）。
    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        let dir = transform.rotation.inv_mul_vec(direction).normalize();
        transform.position + transform.rotation.mul_vec(dir * self.radius)
    }
}

/// 矩形。
///
/// # 示例
///
/// ```rust
/// use physics_types::shape::Rectangle;
/// use approx::assert_abs_diff_eq;
///
/// let r = Rectangle::new(4.0, 2.0);
/// assert_abs_diff_eq!(r.width(), 4.0);
/// assert_abs_diff_eq!(r.height(), 2.0);
/// ```
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Rectangle {
    /// 半长/半宽（从中心到边的距离）。
    pub half_extents: Vec2,
}

impl Rectangle {
    /// 通过宽度和高度创建矩形。
    #[inline]
    pub fn new(width: f32, height: f32) -> Self {
        Rectangle {
            half_extents: Vec2::new(width * 0.5, height * 0.5),
        }
    }

    /// 通过半尺寸创建矩形。
    #[inline]
    pub fn from_half_extents(half_extents: Vec2) -> Self {
        Rectangle { half_extents }
    }

    /// 获取半尺寸。
    #[inline]
    pub fn half_extents(&self) -> Vec2 {
        self.half_extents
    }

    /// 获取宽度。
    #[inline]
    pub fn width(&self) -> f32 {
        self.half_extents.x * 2.0
    }

    /// 获取高度。
    #[inline]
    pub fn height(&self) -> f32 {
        self.half_extents.y * 2.0
    }

    /// 获取四个顶点（局部坐标）。
    #[inline]
    pub fn vertices(&self) -> [Vec2; 4] {
        let h = self.half_extents;
        [
            Vec2::new(-h.x, -h.y),
            Vec2::new(h.x, -h.y),
            Vec2::new(h.x, h.y),
            Vec2::new(-h.x, h.y),
        ]
    }

    /// 计算矩形的 AABB。
    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        let vertices = self.vertices();
        let rotated: Vec<Vec2> = vertices.iter().map(|v| transform.mul_vec(*v)).collect();
        AABB::from_points(&rotated)
    }

    /// 计算矩形的质量。
    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        self.half_extents.x * self.half_extents.y * 4.0 * density
    }

    /// 计算矩形的转动惯量。
    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        let w = self.half_extents.x * 2.0;
        let h = self.half_extents.y * 2.0;
        (mass / 12.0) * (w * w + h * h)
    }

    /// 获取支持点（用于 GJK 算法）。
    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        let local_dir = transform.rotation.inv_mul_vec(direction);
        let local_point = Vec2::new(
            if local_dir.x >= 0.0 {
                self.half_extents.x
            } else {
                -self.half_extents.x
            },
            if local_dir.y >= 0.0 {
                self.half_extents.y
            } else {
                -self.half_extents.y
            },
        );
        transform.mul_vec(local_point)
    }
}

/// 凸多边形。
///
/// 顶点按逆时针顺序排列。
#[derive(Clone, Debug, PartialEq)]
pub struct Polygon {
    vertices: Vec<Vec2>,
    normals: Vec<Vec2>,
}

impl Polygon {
    /// 通过顶点集合创建凸多边形。
    ///
    /// 会自动计算凸包。
    pub fn new(vertices: Vec<Vec2>) -> Self {
        let vertices = Self::compute_hull(vertices);
        let normals = Self::compute_normals(&vertices);
        Polygon { vertices, normals }
    }

    fn compute_hull(mut points: Vec<Vec2>) -> Vec<Vec2>
    {
        use physics_math::polygon_centroid;

        if points.len() < 3 {
            return points;
        }

        points.sort_by(|a, b| {
            if a.x != b.x {
                a.x.partial_cmp(&b.x).unwrap()
            } else {
                a.y.partial_cmp(&b.y).unwrap()
            }
        });

        let mut lower: Vec<Vec2> = Vec::new();
        for p in &points {
            while lower.len() >= 2 {
                let a: Vec2 = lower[lower.len() - 2];
                let b: Vec2 = lower[lower.len() - 1];
                let cross: f32 = (b - a).cross(*p - a);
                if cross <= 0.0 {
                    lower.pop();
                } else {
                    break;
                }
            }
            lower.push(*p);
        }

        let mut upper: Vec<Vec2> = Vec::new();
        for p in points.iter().rev() {
            while upper.len() >= 2 {
                let a: Vec2 = upper[upper.len() - 2];
                let b: Vec2 = upper[upper.len() - 1];
                let cross: f32 = (b - a).cross(*p - a);
                if cross <= 0.0 {
                    upper.pop();
                } else {
                    break;
                }
            }
            upper.push(*p);
        }

        lower.pop();
        upper.pop();
        lower.extend(upper);

        let centroid = polygon_centroid(&lower);
        lower.sort_by(|a, b| {
            let angle_a = (*a - centroid).angle();
            let angle_b = (*b - centroid).angle();
            angle_a.partial_cmp(&angle_b).unwrap()
        });

        lower
    }

    fn compute_normals(vertices: &[Vec2]) -> Vec<Vec2>
    {
        let mut normals = Vec::with_capacity(vertices.len());
        for i in 0..vertices.len() {
            let j = (i + 1) % vertices.len();
            let edge = vertices[j] - vertices[i];
            let normal = edge.perp().normalize();
            normals.push(normal);
        }
        normals
    }

    /// 获取多边形顶点。
    #[inline]
    pub fn vertices(&self) -> &[Vec2] {
        &self.vertices
    }

    /// 获取多边形各边的法线。
    #[inline]
    pub fn normals(&self) -> &[Vec2] {
        &self.normals
    }

    /// 计算多边形的 AABB。
    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        let transformed: Vec<Vec2> = self
            .vertices
            .iter()
            .map(|v| transform.mul_vec(*v))
            .collect();
        AABB::from_points(&transformed)
    }

    /// 计算多边形的质量。
    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        physics_math::polygon_area(&self.vertices) * density
    }

    /// 计算多边形的转动惯量。
    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        polygon_moment_of_inertia(&self.vertices, mass)
    }

    /// 获取支持点（用于 GJK 算法）。
    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        let local_dir = transform.rotation.inv_mul_vec(direction);
        let mut best_dot = f32::NEG_INFINITY;
        let mut best_idx = 0;

        for (i, &v) in self.vertices.iter().enumerate() {
            let dot = v.dot(local_dir);
            if dot > best_dot {
                best_dot = dot;
                best_idx = i;
            }
        }

        transform.mul_vec(self.vertices[best_idx])
    }
}

/// 线段。
///
/// # 示例
///
/// ```rust
/// use physics_types::shape::Segment;
/// use physics_math::Vec2;
///
/// let seg = Segment::new(Vec2::new(0.0, 0.0), Vec2::new(1.0, 1.0));
/// ```
#[derive(Copy, Clone, Debug, PartialEq)]
pub struct Segment {
    /// 线段起点。
    pub a: Vec2,
    /// 线段终点。
    pub b: Vec2,
}

impl Segment {
    /// 创建一个新的线段。
    #[inline]
    pub fn new(a: Vec2, b: Vec2) -> Self {
        Segment { a, b }
    }

    /// 计算线段的 AABB。
    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        let ta = transform.mul_vec(self.a);
        let tb = transform.mul_vec(self.b);
        AABB::from_points(&[ta, tb])
    }

    /// 计算线段的质量（基于长度和假设的厚度）。
    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        let length = (self.b - self.a).length();
        length * 0.1 * density
    }

    /// 计算线段的转动惯量。
    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        let length = (self.b - self.a).length();
        mass * length * length / 12.0
    }

    /// 获取支持点（用于 GJK 算法）。
    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        let ta = transform.mul_vec(self.a);
        let tb = transform.mul_vec(self.b);
        if direction.dot(ta) > direction.dot(tb) {
            ta
        } else {
            tb
        }
    }
}

impl Shape {
    /// 计算形状的 AABB。
    #[inline]
    pub fn compute_aabb(&self, transform: &Transform) -> AABB {
        match self {
            Shape::Circle(c) => c.compute_aabb(transform),
            Shape::Rectangle(r) => r.compute_aabb(transform),
            Shape::Polygon(p) => p.compute_aabb(transform),
            Shape::Segment(s) => s.compute_aabb(transform),
            Shape::HalfSpace(h) => h.compute_aabb(transform),
        }
    }

    /// 计算形状的质量。
    #[inline]
    pub fn compute_mass(&self, density: f32) -> f32 {
        match self {
            Shape::Circle(c) => c.compute_mass(density),
            Shape::Rectangle(r) => r.compute_mass(density),
            Shape::Polygon(p) => p.compute_mass(density),
            Shape::Segment(s) => s.compute_mass(density),
            Shape::HalfSpace(h) => h.compute_mass(density),
        }
    }

    /// 计算形状的转动惯量。
    #[inline]
    pub fn compute_inertia(&self, mass: f32) -> f32 {
        match self {
            Shape::Circle(c) => c.compute_inertia(mass),
            Shape::Rectangle(r) => r.compute_inertia(mass),
            Shape::Polygon(p) => p.compute_inertia(mass),
            Shape::Segment(s) => s.compute_inertia(mass),
            Shape::HalfSpace(h) => h.compute_inertia(mass),
        }
    }

    /// 获取支持点（用于 GJK 算法）。
    #[inline]
    pub fn support_point(&self, direction: Vec2, transform: &Transform) -> Vec2 {
        match self {
            Shape::Circle(c) => c.support_point(direction, transform),
            Shape::Rectangle(r) => r.support_point(direction, transform),
            Shape::Polygon(p) => p.support_point(direction, transform),
            Shape::Segment(s) => s.support_point(direction, transform),
            Shape::HalfSpace(h) => h.support_point(direction, transform),
        }
    }

    /// 判断形状是否为凸形。
    #[inline]
    pub fn is_convex(&self) -> bool {
        match self {
            Shape::Circle(_) => true,
            Shape::Rectangle(_) => true,
            Shape::Polygon(_) => true,
            Shape::Segment(_) => true,
            Shape::HalfSpace(_) => true,
        }
    }

    /// 判断是否为半空间形状。
    #[inline]
    pub fn is_half_space(&self) -> bool {
        matches!(self, Shape::HalfSpace(_))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use approx::assert_abs_diff_eq;

    #[test]
    fn test_circle() {
        let c = Circle::new(2.0);
        assert_abs_diff_eq!(c.radius(), 2.0);
        assert_abs_diff_eq!(c.compute_mass(1.0), std::f32::consts::PI * 4.0);
    }

    #[test]
    fn test_rectangle() {
        let r = Rectangle::new(4.0, 2.0);
        assert_abs_diff_eq!(r.width(), 4.0);
        assert_abs_diff_eq!(r.compute_mass(1.0), 8.0);
    }

    #[test]
    fn test_rectangle_aabb() {
        let r = Rectangle::new(2.0, 2.0);
        let t = Transform::IDENTITY;
        let aabb = r.compute_aabb(&t);
        assert_abs_diff_eq!(aabb.min, Vec2::new(-1.0, -1.0));
        assert_abs_diff_eq!(aabb.max, Vec2::new(1.0, 1.0));
    }

    #[test]
    fn test_collision_filter_should_collide() {
        let filter1 = CollisionFilter::new(0x0001, 0xFFFF);
        let filter2 = CollisionFilter::new(0x0001, 0xFFFF);
        assert!(filter1.should_collide(&filter2));

        let filter3 = CollisionFilter::new(0x0002, 0x0002);
        assert!(!filter1.should_collide(&filter3));
        assert!(!filter3.should_collide(&filter1));
    }

    #[test]
    fn test_collision_filter_group() {
        let filter1 = CollisionFilter::new(0x0001, 0xFFFF).with_group(1);
        let filter2 = CollisionFilter::new(0x0001, 0xFFFF).with_group(1);
        assert!(filter1.should_collide(&filter2));

        let filter3 = CollisionFilter::new(0x0001, 0xFFFF).with_group(-1);
        let filter4 = CollisionFilter::new(0x0001, 0xFFFF).with_group(-1);
        assert!(!filter3.should_collide(&filter4));
    }

    #[test]
    fn test_half_space_signed_distance() {
        let half_space = HalfSpace::ground();
        assert_abs_diff_eq!(half_space.signed_distance(Vec2::new(0.0, 1.0)), 1.0);
        assert_abs_diff_eq!(half_space.signed_distance(Vec2::new(0.0, -1.0)), -1.0);
        assert_abs_diff_eq!(half_space.signed_distance(Vec2::new(5.0, 0.0)), 0.0);
    }

    #[test]
    fn test_half_space_from_point_normal() {
        let point = Vec2::new(0.0, 5.0);
        let normal = Vec2::new(0.0, 1.0);
        let half_space = HalfSpace::from_point_normal(point, normal);
        assert_abs_diff_eq!(half_space.signed_distance(Vec2::new(0.0, 6.0)), 1.0);
        assert_abs_diff_eq!(half_space.signed_distance(Vec2::new(0.0, 5.0)), 0.0);
        assert_abs_diff_eq!(half_space.signed_distance(Vec2::new(0.0, 4.0)), -1.0);
    }
}
