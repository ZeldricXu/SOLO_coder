#!/usr/bin/env python3
"""Debug script for symmetry BC test case."""

import numpy as np
from pycfd.mesh import create_2d_structured_mesh, check_mesh_quality
from pycfd.solver import SimpleSolver, FlowField
from pycfd.bc import BoundaryManager, VelocityInletBC, PressureOutletBC, SymmetryBC

mesh = create_2d_structured_mesh(16, 12, [0, 2], [0, 1])
quality = check_mesh_quality(mesh)
print(f"Mesh quality: valid={quality.is_valid}")

flow = FlowField(mesh.n_cells, mesh.ndim, n_faces=mesh.n_faces)
flow.u[:, 0] = 1.0

bc_manager = BoundaryManager()
bc_manager.add_bc('inlet', VelocityInletBC('left', velocity=[1.0, 0.0]))
bc_manager.add_bc('outlet', PressureOutletBC('right', static_pressure=0.0))
bc_manager.add_bc('bottom', SymmetryBC('bottom'))
bc_manager.add_bc('top', SymmetryBC('top'))
bc_manager.initialize(mesh)

solver = SimpleSolver(
    mesh=mesh, flow=flow, bc_manager=bc_manager,
    nu=0.01, rho=1.0, convection_scheme='upwind'
)

print(f"Initial max u: {np.max(flow.u[:, 0]):.6f}")
print(f"Initial max |v|: {np.max(np.abs(flow.u[:, 1])):.6f}")

for i in range(50):
    res = solver.step()
    if i % 10 == 0:
        print(f"Step {i}: u_res={res.get('u', 0):.6f}, v_res={res.get('v', 0):.6f}, p_res={res.get('p', 0):.6f}, cont_res={res.get('continuity', 0):.6f}")

print(f"\nFinal max u: {np.max(solver.flow.u[:, 0]):.6f}")
print(f"Final max |v|: {np.max(np.abs(solver.flow.u[:, 1])):.6f}")

if np.any(np.isnan(solver.flow.u)):
    print("WARNING: NaN detected in velocity!")
    print(f"NaN count in u: {np.sum(np.isnan(solver.flow.u[:, 0]))}")
    print(f"NaN count in v: {np.sum(np.isnan(solver.flow.u[:, 1]))}")
else:
    print("No NaN detected.")
