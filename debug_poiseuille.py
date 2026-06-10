import numpy as np
import sys
sys.path.insert(0, '/Users/huangzitong/Desktop/SoloCoder6月/Code/61-65/DF1-63')

from pycfd.mesh.structured import create_2d_structured_mesh
from pycfd.mesh.quality import check_mesh_quality
from pycfd.bc.boundary import (
    BoundaryManager, VelocityInletBC, PressureOutletBC, WallBC
)
from pycfd.solver.simple import SimpleSolver
from pycfd.solver.base import FlowField

L = 4.0
h = 1.0
nx, ny = 12, 10
reynolds = 50

mesh = create_2d_structured_mesh(nx, ny, [0, L], [0, h])

quality = check_mesh_quality(mesh)
print(f"Mesh quality: valid={quality.is_valid}")

u_max = 1.0
nu = u_max * h / reynolds

flow = FlowField(mesh.n_cells, mesh.ndim, n_faces=mesh.n_faces)
flow.u[:, 0] = u_max

bc_manager = BoundaryManager()
bc_manager.add_bc('inlet', VelocityInletBC('left', velocity=[u_max, 0.0]))
bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
bc_manager.add_bc('bottom', WallBC('bottom', no_slip=True))
bc_manager.add_bc('top', WallBC('top', no_slip=True))
bc_manager.initialize(mesh)

solver = SimpleSolver(
    mesh=mesh, flow=flow, bc_manager=bc_manager,
    nu=nu, rho=1.0, convection_scheme='upwind'
)
solver.underrelaxation = {'u': 0.7, 'p': 0.3, 'v': 0.7}

print(f"Initial max u: {np.max(flow.u[:, 0]):.6f}")
print(f"Initial max p: {np.max(flow.p):.6f}")

for i in range(200):
    res = solver.step()
    if i % 20 == 0:
        print(f"Step {i}: u_res={res['u']:.6f}, v_res={res['v']:.6f}, p_res={res['p']:.6f}, cont_res={res['continuity']:.6f}")

print(f"\nFinal max u: {np.max(flow.u[:, 0]):.6f}")
print(f"Final max |v|: {np.max(np.abs(flow.u[:, 1])):.6f}")

# Check pressure profile
cell_centers = mesh.cell_centers
inlet_mask = cell_centers[:, 0] < 0.5
outlet_mask = cell_centers[:, 0] > 3.5
mid_mask = (cell_centers[:, 0] > 1.75) & (cell_centers[:, 0] < 2.25)

p_inlet = np.mean(flow.p[inlet_mask])
p_outlet = np.mean(flow.p[outlet_mask])
p_mid = np.mean(flow.p[mid_mask])
print(f"\nPressure: inlet={p_inlet:.6f}, mid={p_mid:.6f}, outlet={p_outlet:.6f}")

# Check velocity profile at midpoint
mid_y = cell_centers[mid_mask, 1]
mid_u = flow.u[mid_mask, 0]

print(f"\nVelocity profile at x~2.0:")
sort_idx = np.argsort(mid_y)
for yi, ui in zip(mid_y[sort_idx], mid_u[sort_idx]):
    print(f"  y={yi:.3f}: u={ui:.6f}")

# Find max velocity location
max_u_idx = np.argmax(flow.u[:, 0])
print(f"\nMax u location: y={cell_centers[max_u_idx, 1]:.3f}, u={flow.u[max_u_idx, 0]:.6f}")
