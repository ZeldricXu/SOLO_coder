# CFD Solver Case Configuration Format (case.toml)

## 概述

CFD 求解器使用 TOML 格式的算例配置文件（`case.toml`）。所有参数都有默认值，用户只需指定需要修改的项。

## 快速开始

### 最小稳态算例

```toml
name = "lid_driven_cavity"
description = "2D lid-driven cavity flow at Re=100"

[mesh]
file = "cavity_128.msh"

[physics]
time_mode = "steady"
turbulence_model = "laminar"

[[boundary_conditions]]
name = "lid"
bc_type = "velocity_inlet"
velocity = [1.0, 0.0, 0.0]
physical_groups = ["top"]

[[boundary_conditions]]
name = "walls"
bc_type = "wall_no_slip"
physical_groups = ["bottom", "left", "right"]
```

### 瞬态算例

```toml
name = "cylinder_wake"
description = "Flow over a circular cylinder, transient SST simulation"

[mesh]
file = "cylinder.cgns"
format = "cgns"

[physics]
time_mode = "transient"
turbulence_model = "k_omega_sst"
rho = 1.0
nu = 1e-3

[transient]
time_discretization = "second_order_implicit"
end_time = 10.0
initial_dt = 0.005
cfl_target = 2.0

[solver]
max_iter = 1000
preconditioner = "block_ilu0"

[[boundary_conditions]]
name = "inlet"
bc_type = "velocity_inlet"
velocity = [10.0, 0.0, 0.0]
turbulence_intensity = 0.05
turbulence_viscosity_ratio = 10.0
physical_groups = ["inlet"]

[[boundary_conditions]]
name = "outlet"
bc_type = "pressure_outlet"
pressure = 0.0
physical_groups = ["outlet"]
```

---

## 配置项参考

### 顶层元数据

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `name` | string | `"untitled_case"` | 算例名称 |
| `description` | string | `""` | 算例描述 |

---

### `[mesh]` - 网格配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `file` | string | **必填** | 网格文件路径 |
| `format` | string | `"gmsh"` | 网格格式: `gmsh`, `cgns`, `polymesh` |
| `scale` | float | `1.0` | 网格坐标缩放因子 |
| `translate` | [float; 3] | `null` | 网格平移向量 `[dx, dy, dz]` |

**示例：**
```toml
[mesh]
file = "grid.cgns"
format = "cgns"
scale = 0.001          # 将 mm 转为 m
translate = [0.5, 0, 0]
```

---

### `[physics]` - 物理模型配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `time_mode` | string | `"steady"` | 时间模式: `steady`, `transient` |
| `turbulence_model` | string | `"k_epsilon"` | 湍流模型: `laminar`, `k_epsilon`, `k_omega_sst` |
| `rho` | float | `1.225` | 密度 (kg/m³) |
| `nu` | float | `1.5e-5` | 运动粘度 (m²/s) |
| `gravity` | [float; 3] | `[0, -9.81, 0]` | 重力加速度矢量 |

**湍流模型说明：**
- `laminar`: 层流模拟，无湍流模型
- `k_epsilon`: 标准 k-ε 模型，适合充分发展的湍流
- `k_omega_sst`: Menter k-ω SST 模型，适合壁面流动和分离流

---

### `[transient]` - 瞬态计算配置（仅 `time_mode = "transient"` 时使用）

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `time_discretization` | string | `"first_order_implicit"` | 时间离散: `first_order_implicit`, `second_order_implicit` |
| `end_time` | float | `1.0` | 总计算时间 (s) |
| `initial_dt` | float | `0.01` | 初始物理时间步长 (s) |
| `min_dt` | float | `1e-6` | 最小物理时间步长 |
| `max_dt` | float | `0.1` | 最大物理时间步长 |
| `cfl_target` | float | `1.0` | CFL 数目标值，用于自适应时间步 |
| `max_inner_iter` | int | `50` | 每个时间步内最大迭代次数 |
| `inner_tol` | float | `1e-4` | 内迭代收敛判据 |

**时间离散说明：**
- `first_order_implicit`: 一阶隐式欧拉，耗散较大，适合启动阶段
- `second_order_implicit`: 二阶隐式三点向后差分，精度更高

---

### `[solver]` - 求解器控制参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `max_iter` | int | `500` | 最大外迭代次数 |
| `tol` | float | `1e-6` | 外迭代收敛判据（相对残差） |
| `linear_solver` | string | `"bicgstab"` | 线性求解器: `bicgstab`, `gmres` |
| `preconditioner` | string | `"ilu0"` | 预处理器: `none`, `jacobi`, `ilu0`, `block_ilu0` |
| `max_linear_iter` | int | `200` | 线性求解最大迭代次数 |
| `linear_tol` | float | `1e-5` | 线性求解收敛判据 |
| `convection_scheme` | string | `"upwind"` | 对流离散: `upwind`, `central`, `quick` |
| `alpha_u` | float | `0.7` | 速度松弛因子 |
| `alpha_p` | float | `0.3` | 压力松弛因子 |
| `alpha_k` | float | `0.8` | k 方程松弛因子 |
| `alpha_epsilon` | float | `0.8` | ε 方程松弛因子 |
| `alpha_omega` | float | `0.8` | ω 方程松弛因子 |

**预处理器性能说明：**
- `block_ilu0`: 块ILU(0)，4×4块分解，NS方程最优，**推荐**
- `ilu0`: 标量ILU(0)，通用性好
- `jacobi`: 雅可比迭代，最稳定但收敛慢
- `none`: 无预处理器，仅用于测试

---

### `[output]` - 输出控制

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `output_dir` | string | `"results"` | 结果输出目录 |
| `write_interval` | int | `10` | 每隔多少步写入结果 |
| `initial_condition_file` | string | `null` | 初始条件VTK文件路径 |
| `write_nodal_fields` | bool | `true` | 是否输出节点插值场 |
| `output_fields` | [string] | `["u", "v", "w", "p", "k", "epsilon", "omega", "nu_t"]` | 要输出的场列表 |
| `format` | string | `"vtk"` | 输出格式: `vtk` |

**可用场变量：**
- `u`, `v`, `w`: 速度分量 (m/s)
- `p`: 压力 (Pa)
- `k`: 湍动能 (m²/s²)
- `epsilon`: 湍流耗散率 (m²/s³)
- `omega`: 湍流比耗散率 (1/s)
- `nu_t`: 湍流涡粘度 (m²/s)

---

### `[[boundary_conditions]]` - 边界条件

每个边界条件是一个独立的 TOML 表数组项。

**通用字段：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | string | 是 | 边界条件名称（唯一标识） |
| `bc_type` | string | 是 | 边界类型，见下方列表 |
| `physical_groups` | [string] | 是 | 应用的物理组名称列表 |

#### `velocity_inlet` - 速度入口

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `velocity` | [float; 3] | 是 | 入口速度矢量 `[u, v, w]` |
| `temperature` | float | 否 | 入口温度 (K) |
| `turbulence_intensity` | float | 否 | 湍流强度（0-1），默认 0.05 |
| `turbulence_viscosity_ratio` | float | 否 | 湍流粘度比，默认 10 |

**示例：**
```toml
[[boundary_conditions]]
name = "inlet"
bc_type = "velocity_inlet"
velocity = [10.0, 0, 0]
turbulence_intensity = 0.05
physical_groups = ["inlet"]
```

#### `pressure_outlet` - 压力出口

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `pressure` | float | 是 | 出口表压 (Pa) |
| `temperature` | float | 否 | 出口温度 (K) |

**示例：**
```toml
[[boundary_conditions]]
name = "outlet"
bc_type = "pressure_outlet"
pressure = 0.0
physical_groups = ["outlet"]
```

#### `wall_no_slip` - 无滑移壁面

**示例：**
```toml
[[boundary_conditions]]
name = "walls"
bc_type = "wall_no_slip"
physical_groups = ["wall1", "wall2"]
```

#### `wall_slip` - 自由滑移壁面

#### `symmetry` - 对称边界

#### `periodic` - 周期边界

#### `far_field` - 远场边界

---

### `[[turbulence_regions]]` - 分区湍流模型

可对不同区域指定不同的湍流模型。

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 区域名称 |
| `model` | string | 湍流模型: `laminar`, `k_epsilon`, `k_omega_sst` |
| `cell_zones` | [string] | 单元格区名称列表 |

**示例：**
```toml
[[turbulence_regions]]
name = "near_wall_region"
model = "k_omega_sst"
cell_zones = ["boundary_layer"]

[[turbulence_regions]]
name = "far_field"
model = "k_epsilon"
cell_zones = ["main_flow"]
```

---

## 完整示例

### 方腔流（层流稳态）

```toml
name = "lid_driven_cavity_128"
description = "2D lid-driven cavity, Re=1000, 128x128 quad mesh"

[mesh]
file = "cavity_128.msh"
format = "gmsh"

[physics]
time_mode = "steady"
turbulence_model = "laminar"
rho = 1.0
nu = 0.001
gravity = [0, 0, 0]

[solver]
max_iter = 1000
tol = 1e-7
alpha_u = 0.5
alpha_p = 0.3
preconditioner = "block_ilu0"

[output]
output_dir = "cavity_results"
write_interval = 50
write_nodal_fields = true

[[boundary_conditions]]
name = "lid"
bc_type = "velocity_inlet"
velocity = [1.0, 0.0, 0.0]
physical_groups = ["top"]

[[boundary_conditions]]
name = "bottom_wall"
bc_type = "wall_no_slip"
physical_groups = ["bottom"]

[[boundary_conditions]]
name = "left_wall"
bc_type = "wall_no_slip"
physical_groups = ["left"]

[[boundary_conditions]]
name = "right_wall"
bc_type = "wall_no_slip"
physical_groups = ["right"]
```

### 后向台阶流（k-ω SST 稳态）

```toml
name = "backward_facing_step"
description = "Turbulent flow over a backward-facing step, Re=5000"

[mesh]
file = "step_3d.cgns"
format = "cgns"

[physics]
time_mode = "steady"
turbulence_model = "k_omega_sst"
rho = 1.0
nu = 2e-4

[solver]
max_iter = 2000
tol = 1e-6
preconditioner = "block_ilu0"
convection_scheme = "quick"

[output]
output_fields = ["u", "v", "w", "p", "k", "omega", "nu_t"]

[[boundary_conditions]]
name = "inlet"
bc_type = "velocity_inlet"
velocity = [1.0, 0, 0]
turbulence_intensity = 0.05
turbulence_viscosity_ratio = 10.0
physical_groups = ["inlet"]

[[boundary_conditions]]
name = "outlet"
bc_type = "pressure_outlet"
pressure = 0.0
physical_groups = ["outlet"]

[[boundary_conditions]]
name = "walls"
bc_type = "wall_no_slip"
physical_groups = ["top", "bottom", "step"]

[[boundary_conditions]]
name = "sides"
bc_type = "symmetry"
physical_groups = ["left_side", "right_side"]
```

### 圆柱绕流（瞬态 SST）

```toml
name = "cylinder_vortex_shedding"
description = "Vortex shedding behind circular cylinder, Re=100"

[mesh]
file = "cylinder_2d.polymesh"
format = "polymesh"

[physics]
time_mode = "transient"
turbulence_model = "laminar"
rho = 1.0
nu = 0.01

[transient]
time_discretization = "second_order_implicit"
end_time = 8.0
initial_dt = 0.005
min_dt = 1e-4
max_dt = 0.05
cfl_target = 1.5
max_inner_iter = 30
inner_tol = 1e-3

[solver]
max_linear_iter = 100
preconditioner = "block_ilu0"

[output]
write_interval = 20

[[boundary_conditions]]
name = "inlet"
bc_type = "velocity_inlet"
velocity = [1.0, 0, 0]
physical_groups = ["inlet"]

[[boundary_conditions]]
name = "outlet"
bc_type = "pressure_outlet"
pressure = 0.0
physical_groups = ["outlet"]

[[boundary_conditions]]
name = "cylinder"
bc_type = "wall_no_slip"
physical_groups = ["cylinder"]

[[boundary_conditions]]
name = "far"
bc_type = "symmetry"
physical_groups = ["top", "bottom"]
```

---

## 参数验证规则

求解器在加载配置时会进行以下验证：

1. **必填字段检查**：`mesh.file` 必须指定
2. **正性检查**：
   - `solver.max_iter > 0`
   - `solver.tol > 0`
   - `physics.rho > 0`
   - `physics.nu > 0`
3. **瞬态参数检查**（仅 `time_mode = "transient"`）：
   - `transient.end_time > 0`
   - `transient.initial_dt > 0`
4. **边界条件完整性**：
   - `velocity_inlet` 必须指定 `velocity`
   - `pressure_outlet` 必须指定 `pressure`
5. **松弛因子**：建议范围 `(0, 1]`，过大会导致发散

---

## 默认值速查表

所有未显式指定的参数将使用以下默认值：

```toml
name = "untitled_case"
description = ""

[mesh]
file = "mesh.msh"
format = "gmsh"
scale = 1.0
translate = null

[physics]
time_mode = "steady"
turbulence_model = "k_epsilon"
rho = 1.225
nu = 1.5e-5
gravity = [0.0, -9.81, 0.0]

[transient]
time_discretization = "first_order_implicit"
end_time = 1.0
initial_dt = 0.01
min_dt = 1e-6
max_dt = 0.1
cfl_target = 1.0
max_inner_iter = 50
inner_tol = 1e-4

[solver]
max_iter = 500
tol = 1e-6
linear_solver = "bicgstab"
preconditioner = "ilu0"
max_linear_iter = 200
linear_tol = 1e-5
convection_scheme = "upwind"
alpha_u = 0.7
alpha_p = 0.3
alpha_k = 0.8
alpha_epsilon = 0.8
alpha_omega = 0.8

[output]
output_dir = "results"
write_interval = 10
initial_condition_file = null
write_nodal_fields = true
output_fields = ["u", "v", "w", "p", "k", "epsilon", "omega", "nu_t"]
format = "vtk"
```
