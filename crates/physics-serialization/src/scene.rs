use serde::{Deserialize, Serialize};

use physics_core::{BodyType, Material};
use physics_math::Vec2;

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SceneConfig {
    pub gravity: Vec2,
    pub time_step: f32,
    pub velocity_iterations: usize,
    pub position_iterations: usize,
    pub bodies: Vec<BodyConfig>,
    pub joints: Vec<JointConfig>,
}

impl Default for SceneConfig {
    fn default() -> Self {
        SceneConfig {
            gravity: Vec2::new(0.0, -9.81),
            time_step: 1.0 / 60.0,
            velocity_iterations: 8,
            position_iterations: 3,
            bodies: Vec::new(),
            joints: Vec::new(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct BodyConfig {
    pub shape: ShapeConfig,
    pub position: Vec2,
    pub angle: f32,
    #[serde(default)]
    pub velocity: Option<Vec2>,
    #[serde(default)]
    pub angular_velocity: Option<f32>,
    pub body_type: BodyTypeConfig,
    #[serde(default)]
    pub material: MaterialConfig,
    #[serde(default)]
    pub is_trigger: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum ShapeConfig {
    Circle { radius: f32 },
    Rectangle { width: f32, height: f32 },
    Polygon { vertices: Vec<Vec2> },
    Segment { start: Vec2, end: Vec2 },
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
pub enum BodyTypeConfig {
    Static,
    Kinematic,
    Dynamic,
}

impl From<BodyTypeConfig> for BodyType {
    fn from(config: BodyTypeConfig) -> Self {
        match config {
            BodyTypeConfig::Static => BodyType::Static,
            BodyTypeConfig::Kinematic => BodyType::Kinematic,
            BodyTypeConfig::Dynamic => BodyType::Dynamic,
        }
    }
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
pub struct MaterialConfig {
    #[serde(default = "default_restitution")]
    pub restitution: f32,
    #[serde(default = "default_static_friction")]
    pub static_friction: f32,
    #[serde(default = "default_dynamic_friction")]
    pub dynamic_friction: f32,
    #[serde(default = "default_density")]
    pub density: f32,
}

fn default_restitution() -> f32 {
    0.2
}

fn default_static_friction() -> f32 {
    0.6
}

fn default_dynamic_friction() -> f32 {
    0.4
}

fn default_density() -> f32 {
    1.0
}

impl Default for MaterialConfig {
    fn default() -> Self {
        MaterialConfig {
            restitution: default_restitution(),
            static_friction: default_static_friction(),
            dynamic_friction: default_dynamic_friction(),
            density: default_density(),
        }
    }
}

impl From<MaterialConfig> for Material {
    fn from(config: MaterialConfig) -> Self {
        Material::new(
            config.restitution,
            config.static_friction,
            config.dynamic_friction,
            config.density,
        )
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub enum JointConfig {
    Revolute {
        body_a: usize,
        body_b: usize,
        anchor: Vec2,
    },
    Distance {
        body_a: usize,
        body_b: usize,
        anchor_a: Vec2,
        anchor_b: Vec2,
        #[serde(default)]
        length: Option<f32>,
    },
}
