use log::info;
use mesh_generator::Mesh;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimestepMode {
    PseudoTime,
    PhysicalTime,
    DualTime,
}

pub struct TimestepController {
    pub mode: TimestepMode,
    pub cfl_target: f64,
    pub physical_cfl_target: f64,
    pub dt_min: f64,
    pub dt_max: f64,
    pub physical_dt_min: f64,
    pub physical_dt_max: f64,
    pub growth_factor: f64,
    pub reduction_factor: f64,
    pub current_dt: f64,
    pub current_physical_dt: f64,
    pub initial_dt: f64,
    pub initial_physical_dt: f64,
    pub inner_iter_target: usize,
    residual_history: Vec<f64>,
    inner_iter_history: Vec<usize>,
}

impl TimestepController {
    pub fn new(initial_dt: f64, cfl_target: f64) -> Self {
        TimestepController {
            mode: TimestepMode::PseudoTime,
            cfl_target,
            physical_cfl_target: 1.0,
            dt_min: 1e-6,
            dt_max: 1.0,
            physical_dt_min: 1e-6,
            physical_dt_max: 0.1,
            growth_factor: 1.2,
            reduction_factor: 0.5,
            current_dt: initial_dt,
            current_physical_dt: 0.01,
            initial_dt,
            initial_physical_dt: 0.01,
            inner_iter_target: 20,
            residual_history: Vec::new(),
            inner_iter_history: Vec::new(),
        }
    }

    pub fn with_mode(mut self, mode: TimestepMode) -> Self {
        self.mode = mode;
        self
    }

    pub fn with_limits(mut self, dt_min: f64, dt_max: f64) -> Self {
        self.dt_min = dt_min;
        self.dt_max = dt_max;
        self
    }

    pub fn with_physical_limits(mut self, dt_min: f64, dt_max: f64) -> Self {
        self.physical_dt_min = dt_min;
        self.physical_dt_max = dt_max;
        self
    }

    pub fn with_inner_iter_target(mut self, target: usize) -> Self {
        self.inner_iter_target = target;
        self
    }

    pub fn with_growth_factors(mut self, growth: f64, reduction: f64) -> Self {
        self.growth_factor = growth;
        self.reduction_factor = reduction;
        self
    }

    pub fn compute_cfl_number(
        &self,
        mesh: &Mesh,
        u: &[f64],
        v: &[f64],
        w: &[f64],
    ) -> f64 {
        let mut cfl_max: f64 = 0.0;

        for (cell_idx, cell) in mesh.elements.iter().enumerate() {
            let vel_mag = (u[cell_idx].powi(2) + v[cell_idx].powi(2) + w[cell_idx].powi(2)).sqrt();
            let volume = cell.volume;
            let char_length = if mesh.is_2d {
                volume.sqrt()
            } else {
                volume.cbrt()
            };

            if char_length > 1e-15 {
                let cfl = vel_mag * self.current_dt / char_length;
                cfl_max = cfl_max.max(cfl);
            }
        }

        cfl_max
    }

    pub fn compute_local_timestep(
        &self,
        mesh: &Mesh,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        local_dt: &mut [f64],
    ) {
        for (cell_idx, cell) in mesh.elements.iter().enumerate() {
            let vel_mag = (u[cell_idx].powi(2) + v[cell_idx].powi(2) + w[cell_idx].powi(2)).sqrt();
            let volume = cell.volume;
            let char_length = if mesh.is_2d {
                volume.sqrt()
            } else {
                volume.cbrt()
            };

            if vel_mag > 1e-15 {
                local_dt[cell_idx] = self.cfl_target * char_length / vel_mag;
            } else {
                local_dt[cell_idx] = self.dt_max;
            }

            local_dt[cell_idx] = local_dt[cell_idx].clamp(self.dt_min, self.dt_max);
        }
    }

    pub fn adaptive_step(
        &mut self,
        mesh: &Mesh,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        current_residual: f64,
    ) -> f64 {
        let cfl = self.compute_cfl_number(mesh, u, v, w);
        
        self.residual_history.push(current_residual);
        if self.residual_history.len() > 5 {
            self.residual_history.remove(0);
        }

        let residual_trend = if self.residual_history.len() >= 2 {
            let recent = &self.residual_history[self.residual_history.len().saturating_sub(3)..];
            if recent.windows(2).all(|w| w[0] >= w[1] * 0.99) {
                ResidualTrend::Converging
            } else if recent.windows(2).any(|w| w[0] < w[1]) {
                ResidualTrend::Diverging
            } else {
                ResidualTrend::Stagnant
            }
        } else {
            ResidualTrend::Stagnant
        };

        if cfl > self.cfl_target * 1.2 || residual_trend == ResidualTrend::Diverging {
            self.current_dt *= self.reduction_factor;
            info!("CFL={:.2e}, reducing timestep to {:.2e}", cfl, self.current_dt);
        } else if cfl < self.cfl_target * 0.5 && residual_trend == ResidualTrend::Converging {
            self.current_dt *= self.growth_factor;
            info!("CFL={:.2e}, increasing timestep to {:.2e}", cfl, self.current_dt);
        }

        self.current_dt = self.current_dt.clamp(self.dt_min, self.dt_max);
        self.current_dt
    }

    pub fn adaptive_physical_step(
        &mut self,
        mesh: &Mesh,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        inner_iterations: usize,
    ) -> f64 {
        self.inner_iter_history.push(inner_iterations);
        if self.inner_iter_history.len() > 3 {
            self.inner_iter_history.remove(0);
        }

        let avg_inner_iter = if self.inner_iter_history.is_empty() {
            inner_iterations as f64
        } else {
            self.inner_iter_history.iter().sum::<usize>() as f64 / self.inner_iter_history.len() as f64
        };

        let cfl_based_dt = self.estimate_dt_from_cfl(mesh, u, v, w, self.physical_cfl_target);

        let iter_based_factor = if avg_inner_iter < self.inner_iter_target as f64 * 0.7 {
            self.growth_factor
        } else if avg_inner_iter > self.inner_iter_target as f64 * 1.3 {
            self.reduction_factor
        } else {
            1.0
        };

        self.current_physical_dt = (self.current_physical_dt * iter_based_factor)
            .clamp(self.physical_dt_min, self.physical_dt_max)
            .min(cfl_based_dt);

        info!(
            "Physical timestep adjusted: avg_inner_iter={:.1}, new_dt={:.2e}",
            avg_inner_iter, self.current_physical_dt
        );

        self.current_physical_dt
    }

    fn estimate_dt_from_cfl(
        &self,
        mesh: &Mesh,
        u: &[f64],
        v: &[f64],
        w: &[f64],
        target_cfl: f64,
    ) -> f64 {
        let mut min_dt = f64::INFINITY;

        for (cell_idx, cell) in mesh.elements.iter().enumerate() {
            let vel_mag = (u[cell_idx].powi(2) + v[cell_idx].powi(2) + w[cell_idx].powi(2)).sqrt();
            let volume = cell.volume;
            let char_length = if mesh.is_2d {
                volume.sqrt()
            } else {
                volume.cbrt()
            };

            if vel_mag > 1e-15 {
                let dt = target_cfl * char_length / vel_mag;
                min_dt = min_dt.min(dt);
            }
        }

        min_dt.clamp(self.physical_dt_min, self.physical_dt_max)
    }

    pub fn get_dt(&self) -> f64 {
        self.current_dt
    }

    pub fn get_physical_dt(&self) -> f64 {
        self.current_physical_dt
    }

    pub fn set_physical_dt(&mut self, dt: f64) {
        self.current_physical_dt = dt.clamp(self.physical_dt_min, self.physical_dt_max);
    }

    pub fn reset(&mut self) {
        self.current_dt = self.initial_dt;
        self.current_physical_dt = self.initial_physical_dt;
        self.residual_history.clear();
        self.inner_iter_history.clear();
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ResidualTrend {
    Converging,
    Diverging,
    Stagnant,
}
