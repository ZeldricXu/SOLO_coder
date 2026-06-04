use nalgebra::Vector3;

pub trait ConvectionScheme {
    fn interpolate_face(
        &self,
        phi_owner: f64,
        phi_neighbor: f64,
        grad_phi_owner: Vector3<f64>,
        grad_phi_neighbor: Vector3<f64>,
        d_owner: Vector3<f64>,
        d_neighbor: Vector3<f64>,
        mass_flux: f64,
    ) -> f64;
}

pub struct UpwindScheme;

impl UpwindScheme {
    pub fn new() -> Self {
        UpwindScheme
    }
}

impl Default for UpwindScheme {
    fn default() -> Self {
        Self::new()
    }
}

impl ConvectionScheme for UpwindScheme {
    fn interpolate_face(
        &self,
        phi_owner: f64,
        phi_neighbor: f64,
        _grad_phi_owner: Vector3<f64>,
        _grad_phi_neighbor: Vector3<f64>,
        _d_owner: Vector3<f64>,
        _d_neighbor: Vector3<f64>,
        mass_flux: f64,
    ) -> f64 {
        if mass_flux > 0.0 {
            phi_owner
        } else {
            phi_neighbor
        }
    }
}

pub struct CentralScheme;

impl CentralScheme {
    pub fn new() -> Self {
        CentralScheme
    }
}

impl Default for CentralScheme {
    fn default() -> Self {
        Self::new()
    }
}

impl ConvectionScheme for CentralScheme {
    fn interpolate_face(
        &self,
        phi_owner: f64,
        phi_neighbor: f64,
        _grad_phi_owner: Vector3<f64>,
        _grad_phi_neighbor: Vector3<f64>,
        _d_owner: Vector3<f64>,
        _d_neighbor: Vector3<f64>,
        _mass_flux: f64,
    ) -> f64 {
        0.5 * (phi_owner + phi_neighbor)
    }
}

pub struct SecondOrderUpwindScheme;

impl SecondOrderUpwindScheme {
    pub fn new() -> Self {
        SecondOrderUpwindScheme
    }
}

impl Default for SecondOrderUpwindScheme {
    fn default() -> Self {
        Self::new()
    }
}

impl ConvectionScheme for SecondOrderUpwindScheme {
    fn interpolate_face(
        &self,
        phi_owner: f64,
        phi_neighbor: f64,
        grad_phi_owner: Vector3<f64>,
        grad_phi_neighbor: Vector3<f64>,
        d_owner: Vector3<f64>,
        d_neighbor: Vector3<f64>,
        mass_flux: f64,
    ) -> f64 {
        if mass_flux > 0.0 {
            phi_owner + grad_phi_owner.dot(&d_owner)
        } else {
            phi_neighbor + grad_phi_neighbor.dot(&d_neighbor)
        }
    }
}
