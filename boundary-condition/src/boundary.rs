use nalgebra::Vector3;
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum BoundaryType {
    VelocityInlet,
    PressureOutlet,
    WallNoSlip,
    Symmetry,
    Periodic,
}

#[derive(Debug, Clone)]
pub struct BoundaryFace {
    pub face_idx: usize,
    pub boundary_type: BoundaryType,
    pub tag: i32,
}

impl BoundaryFace {
    pub fn new(face_idx: usize, boundary_type: BoundaryType, tag: i32) -> Self {
        BoundaryFace {
            face_idx,
            boundary_type,
            tag,
        }
    }
}

pub trait BoundaryCondition {
    fn as_any(&self) -> &dyn std::any::Any;
    fn boundary_type(&self) -> BoundaryType;
    fn apply_velocity(
        &self,
        face_idx: usize,
        owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        velocity: &Vector3<f64>,
    );
    fn apply_pressure(
        &self,
        face_idx: usize,
        owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        pressure: f64,
    );
    fn apply_scalar(
        &self,
        face_idx: usize,
        owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        phi: f64,
        var_type: &str,
    );
    fn get_velocity_value(&self, face_idx: usize) -> Vector3<f64>;
    fn get_pressure_value(&self, face_idx: usize) -> f64;
    fn get_scalar_value(&self, face_idx: usize, var_type: &str) -> f64;
}

impl<T: BoundaryCondition + ?Sized> BoundaryCondition for Box<T> {
    fn as_any(&self) -> &dyn std::any::Any {
        (**self).as_any()
    }
    fn boundary_type(&self) -> BoundaryType {
        (**self).boundary_type()
    }

    fn apply_velocity(
        &self,
        face_idx: usize,
        owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        velocity: &Vector3<f64>,
    ) {
        (**self).apply_velocity(face_idx, owner_cell, a_p, a_neighbors, b, velocity)
    }

    fn apply_pressure(
        &self,
        face_idx: usize,
        owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        pressure: f64,
    ) {
        (**self).apply_pressure(face_idx, owner_cell, a_p, a_neighbors, b, pressure)
    }

    fn apply_scalar(
        &self,
        face_idx: usize,
        owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        phi: f64,
        var_type: &str,
    ) {
        (**self).apply_scalar(face_idx, owner_cell, a_p, a_neighbors, b, phi, var_type)
    }

    fn get_velocity_value(&self, face_idx: usize) -> Vector3<f64> {
        (**self).get_velocity_value(face_idx)
    }

    fn get_pressure_value(&self, face_idx: usize) -> f64 {
        (**self).get_pressure_value(face_idx)
    }

    fn get_scalar_value(&self, face_idx: usize, var_type: &str) -> f64 {
        (**self).get_scalar_value(face_idx, var_type)
    }
}
