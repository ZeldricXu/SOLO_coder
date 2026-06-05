"""
Lid-driven cavity flow example using the pycfd framework.
This is a classic CFD verification case with well-known benchmark solutions.
"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np
import matplotlib.pyplot as plt
from pycfd.mesh import create_2d_structured_mesh
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import (
    BoundaryManager, VelocityInletBC, WallBC,
    PressureOutletBC
)
from pycfd.turbulence import KEpsilonModel
from pycfd.postproc import (
    plot_contour, plot_residuals, plot_velocity_profile,
    compute_vorticity, trace_streamlines
)
from pycfd.core.hdf5_io import HDF5Writer

def run_lid_driven_cavity(nx=32, ny=32, re=1000, n_iter=1000, use_kepsilon=False):
    print(f"Running lid-driven cavity: Re={re}, grid={nx}x{ny}")
    L = 1.0
    mesh = create_2d_structured_mesh(nx, ny, [0, L], [0, L], stretching='uniform')
    U_lid = 1.0
    nu = U_lid * L / re
    rho = 1.0
    flow = FlowField(mesh.n_cells, mesh.ndim)
    flow.u.fill(0.0)
    flow.p.fill(0.0)
    bc_manager = BoundaryManager()
    bc_manager.add_bc('top', WallBC(mesh.boundary_map.get('top', 0), velocity=[U_lid, 0.0]))
    bc_manager.add_bc('bottom', WallBC(mesh.boundary_map.get('bottom', 1), velocity=[0.0, 0.0]))
    bc_manager.add_bc('left', WallBC(mesh.boundary_map.get('left', 2), velocity=[0.0, 0.0]))
    bc_manager.add_bc('right', WallBC(mesh.boundary_map.get('right', 3), velocity=[0.0, 0.0]))
    turbulence_model = None
    if use_kepsilon:
        turbulence_model = KEpsilonModel(C_mu=0.09, C1=1.44, C2=1.92, 
                                         sigma_k=1.0, sigma_epsilon=1.3)
        flow.k = np.full(mesh.n_cells, 0.01 * U_lid ** 2)
        flow.epsilon = np.full(mesh.n_cells, 0.01 * U_lid ** 3 / L)
    solver = SimpleSolver(
        mesh=mesh,
        flow=flow,
        bc_manager=bc_manager,
        nu=nu,
        rho=rho,
        turbulence_model=turbulence_model,
        convection_scheme='tvd',
        limiter='vanleer'
    )
    for it in range(n_iter):
        residuals = solver.step()
        if (it + 1) % 100 == 0:
            max_vel = np.max(np.linalg.norm(solver.flow.u, axis=1))
            print(f"Iter {it+1}/{n_iter}: "
                  f"U_res={residuals['u_mom']:.4e}, "
                  f"p_res={residuals['p']:.4e}, "
                  f"max_vel={max_vel:.4f}")
        if all(v < 1e-6 for v in residuals.values()):
            print(f"Converged at iteration {it+1}")
            break
    center_y = L / 2
    u_along_y = []
    for j in range(ny):
        y = j * L / (ny - 1)
        point = np.array([center_y, y])
        distances = np.sum((mesh.cell_centers - point) ** 2, axis=1)
        nearest = np.argmin(distances)
        u_along_y.append(solver.flow.u[nearest, 0])
    vorticity = compute_vorticity(mesh, solver.flow.u)
    writer = HDF5Writer('cavity_flow_results.h5')
    writer.write_mesh(mesh)
    writer.write_field(solver.flow.u, 'velocity', 0)
    writer.write_field(solver.flow.p, 'pressure', 0)
    writer.write_field(vorticity, 'vorticity', 0)
    writer.close()
    fig, axes = plt.subplots(1, 3, figsize=(18, 5))
    plot_contour(mesh, solver.flow.p, ax=axes[0], cmap='jet', levels=20)
    axes[0].set_title('Pressure')
    plot_contour(mesh, np.linalg.norm(solver.flow.u, axis=1), ax=axes[1], cmap='hot', levels=20)
    axes[1].set_title('Velocity magnitude')
    plot_contour(mesh, vorticity, ax=axes[2], cmap='RdBu_r', levels=20)
    axes[2].set_title('Vorticity')
    plt.tight_layout()
    plt.savefig('cavity_flow_results.png', dpi=150)
    fig2, ax = plt.subplots(figsize=(8, 6))
    ax.plot(np.linspace(0, 1, len(u_along_y)), u_along_y, 'bo-', linewidth=2)
    ax.set_xlabel('y / L')
    ax.set_ylabel('u / U_lid')
    ax.set_title(f'U-velocity at centerline (Re={re})')
    ax.grid(True, alpha=0.3)
    plt.savefig('cavity_velocity_profile.png', dpi=150)
    start_points = [[0.1, y] for y in np.linspace(0.1, 0.9, 5)]
    streamlines = trace_streamlines(mesh, solver.flow.u, start_points, max_steps=500)
    fig3, ax = plt.subplots(figsize=(8, 8))
    for sl in streamlines:
        ax.plot(sl[:, 0], sl[:, 1], 'b-', linewidth=1)
    ax.set_xlim(0, L)
    ax.set_ylim(0, L)
    ax.set_xlabel('x')
    ax.set_ylabel('y')
    ax.set_title('Streamlines')
    ax.set_aspect('equal')
    plt.savefig('cavity_streamlines.png', dpi=150)
    return {
        'mesh': mesh,
        'flow': solver.flow,
        'residuals': solver.residuals,
        'vorticity': vorticity
    }

if __name__ == '__main__':
    results = run_lid_driven_cavity(nx=32, ny=32, re=1000, n_iter=500, use_kepsilon=False)
    print("Simulation complete! Results saved to cavity_flow_*.png and cavity_flow_results.h5")
