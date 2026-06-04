use std::path::{Path, PathBuf};
use std::fs;
use serde::{Deserialize, Serialize};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum ConfigError {
    #[error("Failed to read config file: {0}")]
    IoError(#[from] std::io::Error),

    #[error("Failed to parse TOML: {0}")]
    TomlError(#[from] toml::de::Error),

    #[error("Invalid configuration: {0}")]
    InvalidConfig(String),

    #[error("Missing required field: {0}")]
    MissingField(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TimeMode {
    Steady,
    Transient,
}

impl Default for TimeMode {
    fn default() -> Self {
        TimeMode::Steady
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TimeDiscretization {
    FirstOrderImplicit,
    SecondOrderImplicit,
}

impl Default for TimeDiscretization {
    fn default() -> Self {
        TimeDiscretization::FirstOrderImplicit
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TurbulenceModel {
    Laminar,
    KEpsilon,
    #[serde(rename = "k_omega_sst")]
    KOmegaSST,
}

impl Default for TurbulenceModel {
    fn default() -> Self {
        TurbulenceModel::KEpsilon
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MeshFormat {
    Gmsh,
    Cgns,
    PolyMesh,
}

impl Default for MeshFormat {
    fn default() -> Self {
        MeshFormat::Gmsh
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConvectionSchemeType {
    Upwind,
    Central,
    Quick,
}

impl Default for ConvectionSchemeType {
    fn default() -> Self {
        ConvectionSchemeType::Upwind
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LinearSolverType {
    BiCGSTAB,
    GMRES,
}

impl Default for LinearSolverType {
    fn default() -> Self {
        LinearSolverType::BiCGSTAB
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PreconditionerType {
    None,
    Jacobi,
    Ilu0,
    #[serde(rename = "block_ilu0")]
    BlockIlu0,
}

impl Default for PreconditionerType {
    fn default() -> Self {
        PreconditionerType::Ilu0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BoundaryConditionType {
    VelocityInlet,
    PressureOutlet,
    WallNoSlip,
    WallSlip,
    Symmetry,
    Periodic,
    FarField,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BoundaryConditionConfig {
    pub name: String,
    pub bc_type: BoundaryConditionType,

    #[serde(default)]
    pub velocity: Option<[f64; 3]>,

    #[serde(default)]
    pub pressure: Option<f64>,

    #[serde(default)]
    pub temperature: Option<f64>,

    #[serde(default)]
    pub turbulence_intensity: Option<f64>,

    #[serde(default)]
    pub turbulence_viscosity_ratio: Option<f64>,

    #[serde(default)]
    pub physical_groups: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshConfig {
    pub file: PathBuf,

    #[serde(default)]
    pub format: MeshFormat,

    #[serde(default)]
    pub scale: f64,

    #[serde(default)]
    pub translate: Option<[f64; 3]>,
}

impl Default for MeshConfig {
    fn default() -> Self {
        MeshConfig {
            file: PathBuf::from("mesh.msh"),
            format: MeshFormat::default(),
            scale: 1.0,
            translate: None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PhysicsConfig {
    #[serde(default)]
    pub time_mode: TimeMode,

    #[serde(default)]
    pub turbulence_model: TurbulenceModel,

    #[serde(default = "default_rho")]
    pub rho: f64,

    #[serde(default = "default_nu")]
    pub nu: f64,

    #[serde(default = "default_gravity")]
    pub gravity: [f64; 3],
}

fn default_rho() -> f64 { 1.225 }
fn default_nu() -> f64 { 1.5e-5 }
fn default_gravity() -> [f64; 3] { [0.0, -9.81, 0.0] }

impl Default for PhysicsConfig {
    fn default() -> Self {
        PhysicsConfig {
            time_mode: TimeMode::Steady,
            turbulence_model: TurbulenceModel::KEpsilon,
            rho: default_rho(),
            nu: default_nu(),
            gravity: default_gravity(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransientConfig {
    #[serde(default)]
    pub time_discretization: TimeDiscretization,

    #[serde(default = "default_end_time")]
    pub end_time: f64,

    #[serde(default = "default_initial_dt")]
    pub initial_dt: f64,

    #[serde(default = "default_min_dt")]
    pub min_dt: f64,

    #[serde(default = "default_max_dt")]
    pub max_dt: f64,

    #[serde(default = "default_cfl_target")]
    pub cfl_target: f64,

    #[serde(default = "default_max_inner_iter")]
    pub max_inner_iter: usize,

    #[serde(default = "default_inner_tol")]
    pub inner_tol: f64,
}

fn default_end_time() -> f64 { 1.0 }
fn default_initial_dt() -> f64 { 0.01 }
fn default_min_dt() -> f64 { 1e-6 }
fn default_max_dt() -> f64 { 0.1 }
fn default_cfl_target() -> f64 { 1.0 }
fn default_max_inner_iter() -> usize { 50 }
fn default_inner_tol() -> f64 { 1e-4 }

impl Default for TransientConfig {
    fn default() -> Self {
        TransientConfig {
            time_discretization: TimeDiscretization::FirstOrderImplicit,
            end_time: default_end_time(),
            initial_dt: default_initial_dt(),
            min_dt: default_min_dt(),
            max_dt: default_max_dt(),
            cfl_target: default_cfl_target(),
            max_inner_iter: default_max_inner_iter(),
            inner_tol: default_inner_tol(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SolverConfig {
    #[serde(default = "default_max_iter")]
    pub max_iter: usize,

    #[serde(default = "default_tol")]
    pub tol: f64,

    #[serde(default)]
    pub linear_solver: LinearSolverType,

    #[serde(default)]
    pub preconditioner: PreconditionerType,

    #[serde(default = "default_max_linear_iter")]
    pub max_linear_iter: usize,

    #[serde(default = "default_linear_tol")]
    pub linear_tol: f64,

    #[serde(default)]
    pub convection_scheme: ConvectionSchemeType,

    #[serde(default = "default_alpha_u")]
    pub alpha_u: f64,

    #[serde(default = "default_alpha_p")]
    pub alpha_p: f64,

    #[serde(default = "default_alpha_k")]
    pub alpha_k: f64,

    #[serde(default = "default_alpha_epsilon")]
    pub alpha_epsilon: f64,

    #[serde(default = "default_alpha_omega")]
    pub alpha_omega: f64,
}

fn default_max_iter() -> usize { 500 }
fn default_tol() -> f64 { 1e-6 }
fn default_max_linear_iter() -> usize { 200 }
fn default_linear_tol() -> f64 { 1e-5 }
fn default_alpha_u() -> f64 { 0.7 }
fn default_alpha_p() -> f64 { 0.3 }
fn default_alpha_k() -> f64 { 0.8 }
fn default_alpha_epsilon() -> f64 { 0.8 }
fn default_alpha_omega() -> f64 { 0.8 }

impl Default for SolverConfig {
    fn default() -> Self {
        SolverConfig {
            max_iter: default_max_iter(),
            tol: default_tol(),
            linear_solver: LinearSolverType::default(),
            preconditioner: PreconditionerType::default(),
            max_linear_iter: default_max_linear_iter(),
            linear_tol: default_linear_tol(),
            convection_scheme: ConvectionSchemeType::default(),
            alpha_u: default_alpha_u(),
            alpha_p: default_alpha_p(),
            alpha_k: default_alpha_k(),
            alpha_epsilon: default_alpha_epsilon(),
            alpha_omega: default_alpha_omega(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutputConfig {
    #[serde(default = "default_output_dir")]
    pub output_dir: PathBuf,

    #[serde(default = "default_write_interval")]
    pub write_interval: usize,

    #[serde(default = "default_initial_condition_file")]
    pub initial_condition_file: Option<PathBuf>,

    #[serde(default)]
    pub write_nodal_fields: bool,

    #[serde(default)]
    pub output_fields: Vec<String>,

    #[serde(default = "default_format")]
    pub format: String,
}

fn default_output_dir() -> PathBuf { PathBuf::from("results") }
fn default_write_interval() -> usize { 10 }
fn default_initial_condition_file() -> Option<PathBuf> { None }
fn default_format() -> String { "vtk".to_string() }

impl Default for OutputConfig {
    fn default() -> Self {
        OutputConfig {
            output_dir: default_output_dir(),
            write_interval: default_write_interval(),
            initial_condition_file: None,
            write_nodal_fields: true,
            output_fields: vec![
                "u".to_string(),
                "v".to_string(),
                "w".to_string(),
                "p".to_string(),
                "k".to_string(),
                "epsilon".to_string(),
                "omega".to_string(),
                "nu_t".to_string(),
            ],
            format: default_format(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegionTurbulenceConfig {
    pub name: String,
    pub model: TurbulenceModel,
    pub cell_zones: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaseConfig {
    #[serde(default)]
    pub mesh: MeshConfig,

    #[serde(default)]
    pub physics: PhysicsConfig,

    #[serde(default)]
    pub transient: TransientConfig,

    #[serde(default)]
    pub solver: SolverConfig,

    #[serde(default)]
    pub output: OutputConfig,

    #[serde(default)]
    pub boundary_conditions: Vec<BoundaryConditionConfig>,

    #[serde(default)]
    pub turbulence_regions: Vec<RegionTurbulenceConfig>,

    #[serde(default)]
    pub name: String,

    #[serde(default)]
    pub description: String,
}

impl Default for CaseConfig {
    fn default() -> Self {
        CaseConfig {
            mesh: MeshConfig::default(),
            physics: PhysicsConfig::default(),
            transient: TransientConfig::default(),
            solver: SolverConfig::default(),
            output: OutputConfig::default(),
            boundary_conditions: Vec::new(),
            turbulence_regions: Vec::new(),
            name: "untitled_case".to_string(),
            description: String::new(),
        }
    }
}

impl CaseConfig {
    pub fn from_file<P: AsRef<Path>>(path: P) -> Result<Self, ConfigError> {
        let content = fs::read_to_string(path)?;
        Self::from_str(&content)
    }

    pub fn from_str(content: &str) -> Result<Self, ConfigError> {
        let parsed: CaseConfig = toml::from_str(content)?;
        parsed.validate()?;
        Ok(parsed)
    }

    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.mesh.file.as_os_str().is_empty() {
            return Err(ConfigError::MissingField("mesh.file".to_string()));
        }

        if self.solver.max_iter == 0 {
            return Err(ConfigError::InvalidConfig(
                "solver.max_iter must be greater than 0".to_string()
            ));
        }

        if self.solver.tol <= 0.0 {
            return Err(ConfigError::InvalidConfig(
                "solver.tol must be greater than 0".to_string()
            ));
        }

        if self.physics.rho <= 0.0 {
            return Err(ConfigError::InvalidConfig(
                "physics.rho must be greater than 0".to_string()
            ));
        }

        if self.physics.nu <= 0.0 {
            return Err(ConfigError::InvalidConfig(
                "physics.nu must be greater than 0".to_string()
            ));
        }

        if matches!(self.physics.time_mode, TimeMode::Transient) {
            if self.transient.end_time <= 0.0 {
                return Err(ConfigError::InvalidConfig(
                    "transient.end_time must be greater than 0".to_string()
                ));
            }
            if self.transient.initial_dt <= 0.0 {
                return Err(ConfigError::InvalidConfig(
                    "transient.initial_dt must be greater than 0".to_string()
                ));
            }
        }

        for bc in &self.boundary_conditions {
            match bc.bc_type {
                BoundaryConditionType::VelocityInlet => {
                    if bc.velocity.is_none() {
                        return Err(ConfigError::InvalidConfig(
                            format!("Boundary condition '{}' is VelocityInlet but velocity is not specified", bc.name)
                        ));
                    }
                }
                BoundaryConditionType::PressureOutlet => {
                    if bc.pressure.is_none() {
                        return Err(ConfigError::InvalidConfig(
                            format!("Boundary condition '{}' is PressureOutlet but pressure is not specified", bc.name)
                        ));
                    }
                }
                _ => {}
            }
        }

        Ok(())
    }

    pub fn to_file<P: AsRef<Path>>(&self, path: P) -> Result<(), ConfigError> {
        let toml_str = toml::to_string_pretty(self)
            .map_err(|e| ConfigError::InvalidConfig(format!("Failed to serialize to TOML: {}", e)))?;
        fs::write(path, toml_str)?;
        Ok(())
    }

    pub fn merge_defaults(&mut self) {
        let defaults = CaseConfig::default();

        if self.solver.alpha_u == 0.0 {
            self.solver.alpha_u = defaults.solver.alpha_u;
        }
        if self.solver.alpha_p == 0.0 {
            self.solver.alpha_p = defaults.solver.alpha_p;
        }
        if self.solver.alpha_k == 0.0 {
            self.solver.alpha_k = defaults.solver.alpha_k;
        }
        if self.solver.alpha_epsilon == 0.0 {
            self.solver.alpha_epsilon = defaults.solver.alpha_epsilon;
        }
        if self.solver.alpha_omega == 0.0 {
            self.solver.alpha_omega = defaults.solver.alpha_omega;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_config() {
        let config = CaseConfig::default();
        assert_eq!(config.physics.time_mode, TimeMode::Steady);
        assert_eq!(config.physics.turbulence_model, TurbulenceModel::KEpsilon);
        assert_eq!(config.physics.rho, 1.225);
        assert_eq!(config.solver.max_iter, 500);
        assert_eq!(config.solver.tol, 1e-6);
    }

    #[test]
    fn test_parse_minimal_config() {
        let toml_str = r#"
name = "lid_driven_cavity"

[mesh]
file = "cavity_128.msh"

[physics]
time_mode = "steady"
turbulence_model = "laminar"

[solver]
max_iter = 300
tol = 1e-7

[[boundary_conditions]]
name = "lid"
bc_type = "velocity_inlet"
velocity = [1.0, 0.0, 0.0]
physical_groups = ["top"]

[[boundary_conditions]]
name = "walls"
bc_type = "wall_no_slip"
physical_groups = ["bottom", "left", "right"]
"#;

        let config = CaseConfig::from_str(toml_str).unwrap();
        assert_eq!(config.name, "lid_driven_cavity");
        assert_eq!(config.mesh.file, PathBuf::from("cavity_128.msh"));
        assert_eq!(config.physics.time_mode, TimeMode::Steady);
        assert_eq!(config.physics.turbulence_model, TurbulenceModel::Laminar);
        assert_eq!(config.solver.max_iter, 300);
        assert_eq!(config.boundary_conditions.len(), 2);
        assert_eq!(config.boundary_conditions[0].velocity, Some([1.0, 0.0, 0.0]));
    }

    #[test]
    fn test_parse_transient_config() {
        let toml_str = r#"
name = "cylinder_wake"

[mesh]
file = "cylinder.cgns"
format = "cgns"

[physics]
time_mode = "transient"
turbulence_model = "k_omega_sst"

[transient]
time_discretization = "second_order_implicit"
end_time = 10.0
initial_dt = 0.005
cfl_target = 2.0

[solver]
max_iter = 1000
tol = 1e-6
preconditioner = "block_ilu0"

[[boundary_conditions]]
name = "inlet"
bc_type = "velocity_inlet"
velocity = [10.0, 0.0, 0.0]
physical_groups = ["inlet"]

[[boundary_conditions]]
name = "outlet"
bc_type = "pressure_outlet"
pressure = 0.0
physical_groups = ["outlet"]
"#;

        let config = CaseConfig::from_str(toml_str).unwrap();
        assert_eq!(config.physics.time_mode, TimeMode::Transient);
        assert_eq!(config.physics.turbulence_model, TurbulenceModel::KOmegaSST);
        assert_eq!(config.mesh.format, MeshFormat::Cgns);
        assert_eq!(config.transient.time_discretization, TimeDiscretization::SecondOrderImplicit);
        assert_eq!(config.transient.end_time, 10.0);
        assert_eq!(config.solver.preconditioner, PreconditionerType::BlockIlu0);
    }

    #[test]
    fn test_invalid_config_missing_velocity() {
        let toml_str = r#"
[mesh]
file = "test.msh"

[physics]
time_mode = "steady"
turbulence_model = "laminar"

[[boundary_conditions]]
name = "inlet"
bc_type = "velocity_inlet"
physical_groups = ["inlet"]
"#;

        let result = CaseConfig::from_str(toml_str);
        assert!(result.is_err());
        assert!(format!("{}", result.unwrap_err()).contains("velocity is not specified"));
    }

    #[test]
    fn test_invalid_config_zero_iter() {
        let toml_str = r#"
[mesh]
file = "test.msh"

[physics]
time_mode = "steady"
turbulence_model = "laminar"

[solver]
max_iter = 0
"#;

        let result = CaseConfig::from_str(toml_str);
        assert!(result.is_err());
        assert!(format!("{}", result.unwrap_err()).contains("must be greater than 0"));
    }
}
