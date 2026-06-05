"""
Plane channel flow example demonstrating periodic BC and turbulence modeling.
"""
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import numpy as np
import matplotlib.pyplot as plt
from pycfd.mesh import create_2d_structured_mesh, generate_boundary_layer
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import (
    BoundaryManager, VelocityInletBC, WallBC,
    PressureOutletBC, PeriodicBC
)
from pycfd.turbulence import KOmegaSSTModel
from pycfd.postproc import (
    plot_velocity_profile, plot_contour, plot_residuals,
    compute_vorticity, compute_q_criterion
)
from pycfd.bc.udf import compile_udf

def run_channel_flow(nx=40, ny=30, re_tau=590, n_iter=1500):
    print(f"Running channel flow: Re_tau={re_tau}, grid={nx}x{ny}")
    Lx = 2 * np.pi
    Ly = 2.0
    Lz = np.pi
    nu = 1.0 / re_tau
    rho = 1.0
    mesh = create_2d_structured_mesh(nx, ny, [0, Lx], [-1, 1], stretching='tanh', tanh_strength=2.0)
    mesh = generate_boundary_layer(mesh, wall_boundary_ids=[1, 3], n_layers=5, stretching_ratio=1.2)
    U_bulk = 1.0
    flow = FlowField(mesh.n_cells, mesh.ndim)
    flow.u[:, 0] = U_bulk * (1 - np.abs(mesh.cell_centers[:, 1]) ** (1/7))
    flow.p.fill(0.0)
    bc_manager = BoundaryManager()
    bc_manager.add_bc('inlet', VelocityInletBC(mesh.boundary_map.get('left', 2), 
                                                velocity=compile_udf('1.0 * (1 - y**2)')))
    bc_manager.add_bc('outlet', PressureOutletBC(mesh.boundary_map.get('right', 3), pressure=0.0))
    bc_manager.add_bc('top_wall', WallBC(mesh.boundary_map.get('top', 0), no_slip=True))
    bc_manager.add_bc('bottom_wall', WallBC(mesh.boundary_map.get('bottom', 1), no_slip=True))
    sst_model = KOmegaSSTModel(
        beta1=0.075, beta2=0.0828, sigma_k1=0.85, sigma_k2=1.0,
        sigma_omega1=0.5, sigma_omega2=0.856, gamma1=0.5532, gamma2=0.4403,
        a1=0.31
    )
    k_init = 0.01 * U_bulk ** 2
    omega_init = k_init ** 0.5 / (0.09 * 0.1 * Ly)
    flow.k = np.full(mesh.n_cells, k_init)
    flow.omega = np.full(mesh.n_cells, omega_init)
    solver = SimpleSolver(
        mesh=mesh,
        flow=flow,
        bc_manager=bc_manager,
        nu=nu,
        rho=rho,
        turbulence_model=sst_model,
        convection_scheme='tvd',
        limiter='superbee',
        alpha_u=0.7,
        alpha_p=0.3
    )
    for it in range(n_iter):
        residuals = solver.step()
        if (it + 1) % 100 == 0:
            u_max = np.max(solver.flow.u[:, 0])
            dpdx = -(solver.flow.p[-1] - solver.flow.p[0]) / Lx
            print(f"Iter {it+1}/{n_iter}: "
                  f"U_res={residuals['u_mom']:.4e}, "
                  f"p_res={residuals['p']:.4e}, "
                  f"U_max={u_max:.4f}, dp/dx={dpdx:.4e}")
        if all(v < 1e-6 for v in residuals.values()):
            print(f"Converged at iteration {it+1}")
            break
    y_plus = np.abs(mesh.cell_centers[:, 1]) * re_tau
    u_plus = solver.flow.u[:, 0] * re_tau
    fig, axes = plt.subplots(1, 2, figsize=(16, 6))
    plot_contour(mesh, solver.flow.u[:, 0], ax=axes[0], cmap='jet', levels=30)
    axes[0].set_title('Streamwise velocity')
    plot_contour(mesh, np.log10(solver.flow.k + 1e-10), ax=axes[1], cmap='hot', levels=30)
    axes[1].set_title('Turbulent kinetic energy (log10)')
    plt.tight_layout()
    plt.savefig('channel_flow_contours.png', dpi=150)
    fig2, ax = plt.subplots(figsize=(8, 6))
    idx = np.argsort(y_plus)
    ax.semilogx(y_plus[idx], u_plus[idx], 'bo', markersize=4, label='Computed')
    y_viscous = np.linspace(1, 30, 100)
    ax.semilogx(y_viscous, y_viscous, 'k--', label='Linear (y+)')
    y_log = np.linspace(30, 500, 100)
    ax.semilogx(y_log, 2.5 * np.log(y_log) + 5.5, 'r--', label='Log law')
    ax.set_xlabel('y+')
    ax.set_ylabel('u+')
    ax.set_title(f'Law of the wall verification (Re_tau={re_tau})')
    ax.legend()
    ax.grid(True, alpha=0.3, which='both')
    plt.savefig('channel_law_of_wall.png', dpi=150)
    fig3, ax = plt.subplots(figsize=(8, 6))
    plot_residuals(solver.residuals, ax=ax)
    ax.set_title('Convergence history')
    plt.tight_layout()
    plt.savefig('channel_residuals.png', dpi=150)
    return {
        'mesh': mesh,
        'flow': solver.flow,
        'y_plus': y_plus,
        'u_plus': u_plus,
        'residuals': solver.residuals
    }

if __name__ == '__main__':
    results = run_channel_flow(nx=40, ny=30, re_tau=590, n_iter=1000)
    print("Channel flow simulation complete!")
