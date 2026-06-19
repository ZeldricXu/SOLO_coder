use ecolor::Color32;

pub const STATIC_BODY: Color32 = Color32::from_rgb(128, 128, 128);
pub const KINEMATIC_BODY: Color32 = Color32::from_rgb(255, 200, 0);
pub const DYNAMIC_BODY: Color32 = Color32::from_rgb(100, 150, 255);

pub fn trigger_body() -> Color32 { Color32::from_rgba_unmultiplied(255, 100, 100, 128) }
pub fn aabb_color() -> Color32 { Color32::from_rgba_unmultiplied(100, 255, 100, 128) }
pub const CONTACT_POINT: Color32 = Color32::from_rgb(255, 255, 0);
pub const CONTACT_NORMAL: Color32 = Color32::from_rgb(255, 0, 0);
pub const JOINT_COLOR: Color32 = Color32::from_rgb(255, 100, 255);

pub const PARTICLE_COLOR: Color32 = Color32::from_rgb(100, 200, 255);
pub fn fluid_particle() -> Color32 { Color32::from_rgba_unmultiplied(50, 150, 255, 200) }
