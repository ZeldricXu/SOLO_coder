use nalgebra::Vector3;
use std::collections::HashMap;
use crate::boundary::{BoundaryCondition, BoundaryType};

#[derive(Debug, Clone)]
pub struct VelocityInlet {
    pub velocity: Vector3<f64>,
    pub face_indices: Vec<usize>,
}

impl VelocityInlet {
    pub fn new(velocity: Vector3<f64>) -> Self {
        VelocityInlet {
            velocity,
            face_indices: Vec::new(),
        }
    }

    pub fn with_faces(velocity: Vector3<f64>, face_indices: Vec<usize>) -> Self {
        VelocityInlet {
            velocity,
            face_indices,
        }
    }
}

impl BoundaryCondition for VelocityInlet {
    fn as_any(&self) -> &dyn std::any::Any { self }
    fn boundary_type(&self) -> BoundaryType {
        BoundaryType::VelocityInlet
    }

    fn apply_velocity(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        _velocity: &Vector3<f64>,
    ) {
        let diag_value = 1e15;
        *a_p = diag_value;
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
        *b = diag_value * self.velocity.x;
    }

    fn apply_pressure(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        _a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        _pressure: f64,
    ) {
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
        *b = 0.0;
    }

    fn apply_scalar(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        _phi: f64,
        var_type: &str,
    ) {
        let diag_value = 1e15;
        *a_p = diag_value;
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
        let inlet_value = match var_type {
            "k" => 0.001,
            "epsilon" => 0.001,
            _ => 0.0,
        };
        *b = diag_value * inlet_value;
    }

    fn get_velocity_value(&self, _face_idx: usize) -> Vector3<f64> {
        self.velocity
    }

    fn get_pressure_value(&self, _face_idx: usize) -> f64 {
        0.0
    }

    fn get_scalar_value(&self, _face_idx: usize, var_type: &str) -> f64 {
        match var_type {
            "k" => 0.001,
            "epsilon" => 0.001,
            _ => 0.0,
        }
    }
}

#[derive(Debug, Clone)]
pub struct PressureOutlet {
    pub pressure: f64,
    pub face_indices: Vec<usize>,
}

impl PressureOutlet {
    pub fn new(pressure: f64) -> Self {
        PressureOutlet {
            pressure,
            face_indices: Vec::new(),
        }
    }

    pub fn with_faces(pressure: f64, face_indices: Vec<usize>) -> Self {
        PressureOutlet {
            pressure,
            face_indices,
        }
    }
}

impl BoundaryCondition for PressureOutlet {
    fn as_any(&self) -> &dyn std::any::Any { self }
    fn boundary_type(&self) -> BoundaryType {
        BoundaryType::PressureOutlet
    }

    fn apply_velocity(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        _a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        _b: &mut f64,
        _velocity: &Vector3<f64>,
    ) {
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
    }

    fn apply_pressure(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        _pressure: f64,
    ) {
        let diag_value = 1e15;
        *a_p = diag_value;
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
        *b = diag_value * self.pressure;
    }

    fn apply_scalar(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        phi: f64,
        _var_type: &str,
    ) {
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
        *b = *a_p * phi;
    }

    fn get_velocity_value(&self, _face_idx: usize) -> Vector3<f64> {
        Vector3::zeros()
    }

    fn get_pressure_value(&self, _face_idx: usize) -> f64 {
        self.pressure
    }

    fn get_scalar_value(&self, _face_idx: usize, _var_type: &str) -> f64 {
        0.0
    }
}

#[derive(Debug, Clone)]
pub struct WallNoSlip {
    pub face_indices: Vec<usize>,
}

impl WallNoSlip {
    pub fn new() -> Self {
        WallNoSlip {
            face_indices: Vec::new(),
        }
    }

    pub fn with_faces(face_indices: Vec<usize>) -> Self {
        WallNoSlip { face_indices }
    }
}

impl Default for WallNoSlip {
    fn default() -> Self {
        Self::new()
    }
}

impl BoundaryCondition for WallNoSlip {
    fn as_any(&self) -> &dyn std::any::Any { self }
    fn boundary_type(&self) -> BoundaryType {
        BoundaryType::WallNoSlip
    }

    fn apply_velocity(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        _velocity: &Vector3<f64>,
    ) {
        let diag_value = 1e15;
        *a_p = diag_value;
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
        *b = 0.0;
    }

    fn apply_pressure(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        _a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        _b: &mut f64,
        _pressure: f64,
    ) {
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
    }

    fn apply_scalar(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        b: &mut f64,
        _phi: f64,
        var_type: &str,
    ) {
        let diag_value = 1e15;
        *a_p = diag_value;
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
        let wall_value = match var_type {
            "k" => 0.0,
            "epsilon" => 0.0,
            _ => 0.0,
        };
        *b = diag_value * wall_value;
    }

    fn get_velocity_value(&self, _face_idx: usize) -> Vector3<f64> {
        Vector3::zeros()
    }

    fn get_pressure_value(&self, _face_idx: usize) -> f64 {
        0.0
    }

    fn get_scalar_value(&self, _face_idx: usize, _var_type: &str) -> f64 {
        0.0
    }
}

#[derive(Debug, Clone)]
pub struct Symmetry {
    pub face_indices: Vec<usize>,
}

impl Symmetry {
    pub fn new() -> Self {
        Symmetry {
            face_indices: Vec::new(),
        }
    }

    pub fn with_faces(face_indices: Vec<usize>) -> Self {
        Symmetry { face_indices }
    }
}

impl Default for Symmetry {
    fn default() -> Self {
        Self::new()
    }
}

impl BoundaryCondition for Symmetry {
    fn as_any(&self) -> &dyn std::any::Any { self }
    fn boundary_type(&self) -> BoundaryType {
        BoundaryType::Symmetry
    }

    fn apply_velocity(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        _a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        _b: &mut f64,
        _velocity: &Vector3<f64>,
    ) {
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
    }

    fn apply_pressure(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        _a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        _b: &mut f64,
        _pressure: f64,
    ) {
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
    }

    fn apply_scalar(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        _a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        _b: &mut f64,
        _phi: f64,
        _var_type: &str,
    ) {
        for val in a_neighbors.values_mut() {
            *val = 0.0;
        }
    }

    fn get_velocity_value(&self, _face_idx: usize) -> Vector3<f64> {
        Vector3::zeros()
    }

    fn get_pressure_value(&self, _face_idx: usize) -> f64 {
        0.0
    }

    fn get_scalar_value(&self, _face_idx: usize, _var_type: &str) -> f64 {
        0.0
    }
}

#[derive(Debug, Clone)]
pub struct Periodic {
    pub face_pairs: Vec<(usize, usize)>,
    pub translation_vector: Vector3<f64>,
    pub periodic_cell_map: HashMap<usize, usize>,
    pub processed_cells: std::collections::HashSet<usize>,
}

impl Periodic {
    pub fn new(translation_vector: Vector3<f64>) -> Self {
        Periodic {
            face_pairs: Vec::new(),
            translation_vector,
            periodic_cell_map: HashMap::new(),
            processed_cells: std::collections::HashSet::new(),
        }
    }

    pub fn with_pairs(translation_vector: Vector3<f64>, face_pairs: Vec<(usize, usize)>) -> Self {
        Periodic {
            face_pairs,
            translation_vector,
            periodic_cell_map: HashMap::new(),
            processed_cells: std::collections::HashSet::new(),
        }
    }

    pub fn register_periodic_cell_pair(&mut self, cell_master: usize, cell_slave: usize) {
        self.periodic_cell_map.insert(cell_slave, cell_master);
    }

    pub fn is_periodic_master(&self, cell_idx: usize) -> bool {
        !self.periodic_cell_map.contains_key(&cell_idx)
    }

    pub fn get_periodic_master(&self, cell_idx: usize) -> Option<usize> {
        self.periodic_cell_map.get(&cell_idx).copied()
    }

    pub fn mark_processed(&mut self, cell_idx: usize) {
        self.processed_cells.insert(cell_idx);
    }

    pub fn is_processed(&self, cell_idx: usize) -> bool {
        self.processed_cells.contains(&cell_idx)
    }

    pub fn reset_processed(&mut self) {
        self.processed_cells.clear();
    }
}

impl BoundaryCondition for Periodic {
    fn as_any(&self) -> &dyn std::any::Any { self }
    fn boundary_type(&self) -> BoundaryType {
        BoundaryType::Periodic
    }

    fn apply_velocity(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        _a_p: &mut f64,
        _a_neighbors: &mut HashMap<usize, f64>,
        _b: &mut f64,
        _velocity: &Vector3<f64>,
    ) {
    }

    fn apply_pressure(
        &self,
        _face_idx: usize,
        owner_cell: usize,
        a_p: &mut f64,
        a_neighbors: &mut HashMap<usize, f64>,
        _b: &mut f64,
        _pressure: f64,
    ) {
        if let Some(master_cell) = self.get_periodic_master(owner_cell) {
            if !self.is_processed(master_cell) {
                return;
            }
            
            for (neigh_idx, coeff) in a_neighbors.iter() {
                if *neigh_idx == master_cell {
                    let current_master_coeff = a_neighbors.get(&master_cell).copied().unwrap_or(0.0);
                    a_neighbors.insert(master_cell, current_master_coeff + coeff);
                    return;
                }
            }
        }
    }

    fn apply_scalar(
        &self,
        _face_idx: usize,
        _owner_cell: usize,
        _a_p: &mut f64,
        _a_neighbors: &mut HashMap<usize, f64>,
        _b: &mut f64,
        _phi: f64,
        _var_type: &str,
    ) {
    }

    fn get_velocity_value(&self, _face_idx: usize) -> Vector3<f64> {
        Vector3::zeros()
    }

    fn get_pressure_value(&self, _face_idx: usize) -> f64 {
        0.0
    }

    fn get_scalar_value(&self, _face_idx: usize, _var_type: &str) -> f64 {
        0.0
    }
}
