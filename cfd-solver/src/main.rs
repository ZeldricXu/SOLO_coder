use std::env;
use std::path::Path;
use log::info;
use nalgebra::Vector3;

use mesh_generator::Mesh;
use navier_stokes_solver::{NavierStokesSolver, SolverParameters, FlowFields, GradientFields, UpwindScheme};
use boundary_condition::{VelocityInlet, PressureOutlet, WallNoSlip, Symmetry};
use turbulence_model::{KEpsilonModel, TurbulenceConstants};
use result_vtk_export::VtkExporter;
use timestep_controller::TimestepController;
use field_interpolator::FieldInterpolator;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::init();
    
    let args: Vec<String> = env::args().collect();
    let mesh_path = if args.len() > 1 {
        Path::new(&args[1])
    } else {
        Path::new("mesh.msh")
    };

    info!("Loading mesh from: {:?}", mesh_path);
    let mesh = Mesh::from_gmsh(mesh_path)?;
    
    info!(
        "Mesh loaded: {} cells, {} nodes, {} faces",
        mesh.num_cells(),
        mesh.num_nodes(),
        mesh.num_faces()
    );

    let num_cells = mesh.num_cells();
    let num_faces = mesh.num_faces();
    
    let mut flow_fields = FlowFields::new(num_cells, num_faces);
    let mut grad_fields = GradientFields::new(num_cells);
    
    let mut params = SolverParameters::default();
    params.max_iter = 500;
    params.tol = 1e-6;
    
    let mut ns_solver = NavierStokesSolver::new(num_cells, params.clone());
    let convection_scheme = UpwindScheme::new();

    let turbulence_constants = TurbulenceConstants::default();
    let mut k_epsilon = KEpsilonModel::new(num_cells, turbulence_constants);

    let inlet_velocity = Vector3::new(10.0, 0.0, 0.0);
    let inlet_bc = VelocityInlet::new(inlet_velocity);
    let outlet_bc = PressureOutlet::new(0.0);
    let wall_bc = WallNoSlip::new();
    let symmetry_bc = Symmetry::new();

    let boundary_conditions: Vec<Box<dyn boundary_condition::BoundaryCondition>> = vec![
        Box::new(inlet_bc),
        Box::new(outlet_bc),
        Box::new(wall_bc),
        Box::new(symmetry_bc),
    ];

    let mut ts_controller = TimestepController::new(0.1, 0.5);
    ts_controller = ts_controller.with_limits(1e-4, 1.0);

    info!("Starting SIMPLE algorithm...");
    
    let volume = mesh.cell_volumes();
    let mut local_dt = vec![0.0; num_cells];
    
    for outer_iter in 0..params.max_iter {
        ts_controller.compute_local_timestep(&mesh, &flow_fields.u, &flow_fields.v, &flow_fields.w, &mut local_dt);
        
        let converged = ns_solver.solve(
            &mesh,
            &mut flow_fields,
            &mut grad_fields,
            &convection_scheme,
            &boundary_conditions,
        );

        k_epsilon.compute_turbulent_viscosity(
            &flow_fields.k,
            &flow_fields.epsilon,
            &mut flow_fields.nu_t,
        );

        k_epsilon.solve(
            &mesh,
            &flow_fields.u,
            &flow_fields.v,
            &flow_fields.w,
            &grad_fields.grad_u,
            &grad_fields.grad_v,
            &grad_fields.grad_w,
            &mut flow_fields.k,
            &mut flow_fields.epsilon,
            params.nu,
            &flow_fields.nu_t,
            &flow_fields.mass_flux,
            &volume,
            params.rho,
            ts_controller.get_dt(),
            &boundary_conditions,
        );

        if outer_iter % 10 == 0 {
            info!("Outer iteration {}", outer_iter);
            
            let output_path = format!("results_{:04}.vtk", outer_iter);
            VtkExporter::export(
                &mesh,
                &output_path,
                &flow_fields.u,
                &flow_fields.v,
                &flow_fields.w,
                &flow_fields.p,
                Some(&flow_fields.k),
                Some(&flow_fields.epsilon),
                Some(&flow_fields.omega),
            )?;
            info!("Written results to {}", output_path);
        }

        if converged {
            info!("Solution converged in {} iterations", outer_iter);
            break;
        }
    }

    let interpolator = FieldInterpolator::new(&mesh);
    let (u_node, v_node, w_node) = interpolator.cell_to_node_vector(
        &flow_fields.u,
        &flow_fields.v,
        &flow_fields.w,
    );
    let p_node = interpolator.cell_to_node(&flow_fields.p);
    let k_node = interpolator.cell_to_node(&flow_fields.k);
    let eps_node = interpolator.cell_to_node(&flow_fields.epsilon);

    VtkExporter::export_with_node_data(
        &mesh,
        "final_results_node.vtk",
        &u_node,
        &v_node,
        &w_node,
        &p_node,
        Some(&k_node),
        Some(&eps_node),
    )?;
    info!("Written nodal results to final_results_node.vtk");

    info!("CFD simulation completed successfully!");
    Ok(())
}
