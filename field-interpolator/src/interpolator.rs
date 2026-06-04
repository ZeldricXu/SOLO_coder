use nalgebra::Point3;
use mesh_generator::Mesh;
use rayon::prelude::*;
use crate::shape_functions::{get_shape_function, ShapeFunction};

struct JacobianCache {
    cell_idx: usize,
    inv_jacobian: Vec<Vec<f64>>,
    det_j: f64,
    dim: usize,
}

pub struct FieldInterpolator {
    node_weights: Vec<Vec<(usize, f64)>>,
    jacobian_cache: Option<JacobianCache>,
    cache_hits: usize,
    cache_misses: usize,
}

impl FieldInterpolator {
    pub fn new(mesh: &Mesh) -> Self {
        let node_weights = Self::compute_interpolation_weights(mesh);
        FieldInterpolator {
            node_weights,
            jacobian_cache: None,
            cache_hits: 0,
            cache_misses: 0,
        }
    }

    fn compute_interpolation_weights(mesh: &Mesh) -> Vec<Vec<(usize, f64)>> {
        let num_nodes = mesh.num_nodes();
        let mut weights: Vec<Vec<(usize, f64)>> = vec![Vec::new(); num_nodes];

        for (cell_idx, element) in mesh.elements.iter().enumerate() {
            let shape_func = get_shape_function(element.element_type);
            let center_coords = Self::element_center_local_coords(element.element_type);
            let n_vals = shape_func.evaluate(&center_coords);

            for (i, &node_idx) in element.node_indices.iter().enumerate() {
                weights[node_idx].push((cell_idx, n_vals[i]));
            }
        }

        weights.par_iter_mut().for_each(|node_weights| {
            let sum: f64 = node_weights.iter().map(|(_, w)| *w).sum();
            if sum > 1e-15 {
                for (_, w) in node_weights.iter_mut() {
                    *w /= sum;
                }
            }
        });

        weights
    }

    fn element_center_local_coords(element_type: mesh_generator::ElementType) -> Vec<f64> {
        match element_type {
            mesh_generator::ElementType::Triangle => vec![1.0 / 3.0, 1.0 / 3.0],
            mesh_generator::ElementType::Quadrilateral => vec![0.0, 0.0],
            mesh_generator::ElementType::Tetrahedron => vec![0.25, 0.25, 0.25],
            mesh_generator::ElementType::Hexahedron => vec![0.0, 0.0, 0.0],
        }
    }

    pub fn cell_to_node(&self, cell_field: &[f64]) -> Vec<f64> {
        let num_nodes = self.node_weights.len();
        let mut node_field = vec![0.0; num_nodes];

        node_field.par_iter_mut().enumerate().for_each(|(node_idx, val)| {
            let mut sum = 0.0;
            for &(cell_idx, weight) in &self.node_weights[node_idx] {
                sum += cell_field[cell_idx] * weight;
            }
            *val = sum;
        });

        node_field
    }

    pub fn cell_to_node_vector(
        &self,
        u: &[f64],
        v: &[f64],
        w: &[f64],
    ) -> (Vec<f64>, Vec<f64>, Vec<f64>) {
        let u_node = self.cell_to_node(u);
        let v_node = self.cell_to_node(v);
        let w_node = self.cell_to_node(w);
        (u_node, v_node, w_node)
    }

    pub fn interpolate_to_point(
        &mut self,
        mesh: &Mesh,
        cell_idx: usize,
        point: &Point3<f64>,
        cell_field: &[f64],
    ) -> Result<f64, &'static str> {
        let element = &mesh.elements[cell_idx];
        let shape_func = get_shape_function(element.element_type);

        let local_coords = self.inverse_isoparametric_cached(
            mesh,
            cell_idx,
            point,
            &*shape_func,
        )?;

        let n_vals = shape_func.evaluate(&local_coords);
        let mut result = 0.0;
        for (i, &node_idx) in element.node_indices.iter().enumerate() {
            result += cell_field[node_idx] * n_vals[i];
        }

        Ok(result)
    }

    fn inverse_isoparametric_cached(
        &mut self,
        mesh: &Mesh,
        cell_idx: usize,
        point: &Point3<f64>,
        shape_func: &dyn ShapeFunction,
    ) -> Result<Vec<f64>, &'static str> {
        let element = &mesh.elements[cell_idx];
        let dim = if element.element_type.is_2d() { 2 } else { 3 };

        let mut local = vec![0.0; dim];
        let max_iter = 50;
        let tol = 1e-10;

        let jacobian = Self::compute_jacobian(mesh, cell_idx, &local, shape_func, dim);
        let det_j = Self::determinant(&jacobian);
        if det_j.abs() < 1e-15 {
            return Err("Singular Jacobian");
        }
        let inv_jac = Self::inverse(&jacobian);

        if let Some(ref cache) = self.jacobian_cache {
            if cache.cell_idx == cell_idx && cache.dim == dim {
                self.cache_hits += 1;
            } else {
                self.cache_misses += 1;
                self.jacobian_cache = Some(JacobianCache {
                    cell_idx,
                    inv_jacobian: inv_jac,
                    det_j,
                    dim,
                });
            }
        } else {
            self.cache_misses += 1;
            self.jacobian_cache = Some(JacobianCache {
                cell_idx,
                inv_jacobian: inv_jac,
                det_j,
                dim,
            });
        }

        for _ in 0..max_iter {
            let n_vals = shape_func.evaluate(&local);

            let mut x_global = Point3::origin();
            for (i, &node_idx) in element.node_indices.iter().enumerate() {
                x_global.coords += mesh.nodes[node_idx].coords * n_vals[i];
            }

            let residual = point - x_global;

            let current_inv_jac = if let Some(ref cache) = self.jacobian_cache {
                if cache.cell_idx == cell_idx {
                    &cache.inv_jacobian
                } else {
                    let jac = Self::compute_jacobian(mesh, cell_idx, &local, shape_func, dim);
                    let inv = Self::inverse(&jac);
                    self.jacobian_cache = Some(JacobianCache {
                        cell_idx,
                        inv_jacobian: inv,
                        det_j: Self::determinant(&jac),
                        dim,
                    });
                    &self.jacobian_cache.as_ref().unwrap().inv_jacobian
                }
            } else {
                unreachable!()
            };

            let mut delta = vec![0.0; dim];
            for d in 0..dim {
                for j in 0..dim {
                    delta[d] += current_inv_jac[d][j] * residual[j];
                }
            }

            for d in 0..dim {
                local[d] += delta[d];
            }

            let norm: f64 = delta.iter().map(|x| x * x).sum();
            if norm.sqrt() < tol {
                return Ok(local);
            }

            let new_jacobian = Self::compute_jacobian(mesh, cell_idx, &local, shape_func, dim);
            let new_det_j = Self::determinant(&new_jacobian);
            if new_det_j.abs() > 1e-15 {
                self.jacobian_cache = Some(JacobianCache {
                    cell_idx,
                    inv_jacobian: Self::inverse(&new_jacobian),
                    det_j: new_det_j,
                    dim,
                });
            }
        }

        Err("Failed to converge in inverse isoparametric mapping")
    }

    fn compute_jacobian(
        mesh: &Mesh,
        cell_idx: usize,
        local: &[f64],
        shape_func: &dyn ShapeFunction,
        dim: usize,
    ) -> Vec<Vec<f64>> {
        let element = &mesh.elements[cell_idx];
        let dn = shape_func.derivatives(local);

        let mut jacobian = vec![vec![0.0; dim]; dim];
        for d in 0..dim {
            for j in 0..dim {
                for (i, &node_idx) in element.node_indices.iter().enumerate() {
                    let node_coord = if j == 0 {
                        mesh.nodes[node_idx].x
                    } else if j == 1 {
                        mesh.nodes[node_idx].y
                    } else {
                        mesh.nodes[node_idx].z
                    };
                    jacobian[d][j] += dn[i][d] * node_coord;
                }
            }
        }
        jacobian
    }

    pub fn inverse_isoparametric(
        mesh: &Mesh,
        cell_idx: usize,
        point: &Point3<f64>,
        shape_func: &dyn ShapeFunction,
    ) -> Result<Vec<f64>, &'static str> {
        let element = &mesh.elements[cell_idx];
        let dim = if element.element_type.is_2d() { 2 } else { 3 };

        let mut local = vec![0.0; dim];
        let max_iter = 50;
        let tol = 1e-10;

        for _ in 0..max_iter {
            let n_vals = shape_func.evaluate(&local);
            let dn = shape_func.derivatives(&local);

            let mut x_global = Point3::origin();
            for (i, &node_idx) in element.node_indices.iter().enumerate() {
                x_global.coords += mesh.nodes[node_idx].coords * n_vals[i];
            }

            let residual = point - x_global;

            let mut jacobian = vec![vec![0.0; dim]; dim];
            for d in 0..dim {
                for j in 0..dim {
                    for (i, &node_idx) in element.node_indices.iter().enumerate() {
                        let node_coord = if j == 0 {
                            mesh.nodes[node_idx].x
                        } else if j == 1 {
                            mesh.nodes[node_idx].y
                        } else {
                            mesh.nodes[node_idx].z
                        };
                        jacobian[d][j] += dn[i][d] * node_coord;
                    }
                }
            }

            let det_j = Self::determinant(&jacobian);
            if det_j.abs() < 1e-15 {
                return Err("Singular Jacobian");
            }

            let inv_jac = Self::inverse(&jacobian);
            let mut delta = vec![0.0; dim];
            for d in 0..dim {
                for j in 0..dim {
                    delta[d] += inv_jac[d][j] * residual[j];
                }
            }

            for d in 0..dim {
                local[d] += delta[d];
            }

            let norm: f64 = delta.iter().map(|x| x * x).sum();
            if norm.sqrt() < tol {
                return Ok(local);
            }
        }

        Err("Failed to converge in inverse isoparametric mapping")
    }

    fn determinant(mat: &[Vec<f64>]) -> f64 {
        let n = mat.len();
        if n == 2 {
            mat[0][0] * mat[1][1] - mat[0][1] * mat[1][0]
        } else {
            mat[0][0] * (mat[1][1] * mat[2][2] - mat[1][2] * mat[2][1])
                - mat[0][1] * (mat[1][0] * mat[2][2] - mat[1][2] * mat[2][0])
                + mat[0][2] * (mat[1][0] * mat[2][1] - mat[1][1] * mat[2][0])
        }
    }

    fn inverse(mat: &[Vec<f64>]) -> Vec<Vec<f64>> {
        let n = mat.len();
        let det = Self::determinant(mat);

        if n == 2 {
            vec![
                vec![mat[1][1] / det, -mat[0][1] / det],
                vec![-mat[1][0] / det, mat[0][0] / det],
            ]
        } else {
            let mut inv = vec![vec![0.0; 3]; 3];
            inv[0][0] = (mat[1][1] * mat[2][2] - mat[1][2] * mat[2][1]) / det;
            inv[0][1] = (mat[0][2] * mat[2][1] - mat[0][1] * mat[2][2]) / det;
            inv[0][2] = (mat[0][1] * mat[1][2] - mat[0][2] * mat[1][1]) / det;
            inv[1][0] = (mat[1][2] * mat[2][0] - mat[1][0] * mat[2][2]) / det;
            inv[1][1] = (mat[0][0] * mat[2][2] - mat[0][2] * mat[2][0]) / det;
            inv[1][2] = (mat[0][2] * mat[1][0] - mat[0][0] * mat[1][2]) / det;
            inv[2][0] = (mat[1][0] * mat[2][1] - mat[1][1] * mat[2][0]) / det;
            inv[2][1] = (mat[0][1] * mat[2][0] - mat[0][0] * mat[2][1]) / det;
            inv[2][2] = (mat[0][0] * mat[1][1] - mat[0][1] * mat[1][0]) / det;
            inv
        }
    }

    pub fn find_containing_cell(mesh: &Mesh, point: &Point3<f64>) -> Option<usize> {
        for (cell_idx, element) in mesh.elements.iter().enumerate() {
            let centroid = element.centroid;
            let dist = (point - centroid).norm();

            let char_len = if element.element_type.is_2d() {
                element.volume.sqrt()
            } else {
                element.volume.cbrt()
            };

            if dist < char_len * 2.0 {
                let shape_func = get_shape_function(element.element_type);
                if let Ok(local) = Self::inverse_isoparametric(mesh, cell_idx, point, &*shape_func) {
                    let in_cell = match element.element_type {
                        mesh_generator::ElementType::Triangle => {
                            local[0] >= -1e-6 && local[1] >= -1e-6 && local[0] + local[1] <= 1.0 + 1e-6
                        }
                        mesh_generator::ElementType::Quadrilateral => {
                            local[0] >= -1.0 - 1e-6 && local[0] <= 1.0 + 1e-6 &&
                            local[1] >= -1.0 - 1e-6 && local[1] <= 1.0 + 1e-6
                        }
                        mesh_generator::ElementType::Tetrahedron => {
                            local[0] >= -1e-6 && local[1] >= -1e-6 && local[2] >= -1e-6 &&
                            local[0] + local[1] + local[2] <= 1.0 + 1e-6
                        }
                        mesh_generator::ElementType::Hexahedron => {
                            local[0] >= -1.0 - 1e-6 && local[0] <= 1.0 + 1e-6 &&
                            local[1] >= -1.0 - 1e-6 && local[1] <= 1.0 + 1e-6 &&
                            local[2] >= -1.0 - 1e-6 && local[2] <= 1.0 + 1e-6
                        }
                    };
                    if in_cell {
                        return Some(cell_idx);
                    }
                }
            }
        }
        None
    }

    pub fn cache_hit_rate(&self) -> f64 {
        let total = self.cache_hits + self.cache_misses;
        if total == 0 {
            0.0
        } else {
            self.cache_hits as f64 / total as f64
        }
    }

    pub fn reset_cache_stats(&mut self) {
        self.cache_hits = 0;
        self.cache_misses = 0;
    }

    pub fn invalidate_cache(&mut self) {
        self.jacobian_cache = None;
    }
}
