pub mod boundary;
pub mod conditions;

pub use boundary::{BoundaryCondition, BoundaryType, BoundaryFace};
pub use conditions::{
    VelocityInlet, PressureOutlet, WallNoSlip, Symmetry, Periodic,
};



#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VariableType {
    U,
    V,
    W,
    P,
    K,
    Epsilon,
}
