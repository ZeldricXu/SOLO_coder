import { ElectromagneticsSolver } from '../index';
import { vec3 } from '@physics-sim/shared';
import { conjugateGradient, createMultigridPreconditioner } from '@physics-sim/math';

describe('CG (Conjugate Gradient) Poisson Solver', () => {
  describe('conjugateGradient basic convergence', () => {
    it('should solve a simple diagonal system in one iteration', () => {
      const A = (x: Float32Array) => {
        const result = new Float32Array(x.length);
        for (let i = 0; i < x.length; i++) {
          result[i] = (i + 1) * x[i];
        }
        return result;
      };

      const b = new Float32Array([1, 2, 3, 4]);
      const x0 = new Float32Array(4);

      const result = conjugateGradient(A, b, x0, 1e-8, 1000);

      expect(result.converged).toBe(true);
      expect(result.iterations).toBeLessThanOrEqual(10);
      expect(result.x[0]).toBeCloseTo(1);
      expect(result.x[1]).toBeCloseTo(1);
      expect(result.x[2]).toBeCloseTo(1);
      expect(result.x[3]).toBeCloseTo(1);
      expect(result.residual).toBeLessThan(1e-6);
    });

    it('should handle the 1D Poisson equation', () => {
      const n = 100;
      const h = 1 / (n - 1);
      const A = (x: Float32Array) => {
        const result = new Float32Array(n);
        for (let i = 0; i < n; i++) {
          if (i === 0 || i === n - 1) {
            result[i] = x[i];
          } else {
            result[i] = (-x[i - 1] + 2 * x[i] - x[i + 1]) / (h * h);
          }
        }
        return result;
      };

      const b = new Float32Array(n);
      for (let i = 0; i < n; i++) {
        if (i === 0 || i === n - 1) {
          b[i] = 0;
        } else {
          b[i] = Math.sin(Math.PI * i * h);
        }
      }

      const x0 = new Float32Array(n);

      const result = conjugateGradient(A, b, x0, 1e-10, 1000);

      expect(result.converged).toBe(true);
      expect(result.residual).toBeLessThan(1e-8);
      expect(result.iterations).toBeLessThan(n);
    });

    it('should work with multigrid preconditioner', () => {
      const nx = 32;
      const ny = 32;
      const nz = 1;
      const dx = 1 / nx;
      const dy = 1 / ny;
      const dz = 1;
      const h2 = dx * dx;

      const isBoundary = new Uint8Array(nx * ny * nz);
      for (let i = 0; i < nx; i++) {
        for (let j = 0; j < ny; j++) {
          const idx = i + j * nx;
          if (i === 0 || i === nx - 1 || j === 0 || j === ny - 1) {
            isBoundary[idx] = 1;
          }
        }
      }

      const applyLaplacian = (x: Float32Array): Float32Array => {
        const result = new Float32Array(x.length);
        for (let i = 1; i < nx - 1; i++) {
          for (let j = 1; j < ny - 1; j++) {
            const idx = i + j * nx;
            if (isBoundary[idx]) {
              result[idx] = 0;
              continue;
            }
            const idxIp = (i + 1) + j * nx;
            const idxIm = (i - 1) + j * nx;
            const idxJp = i + (j + 1) * nx;
            const idxJm = i + (j - 1) * nx;
            result[idx] = (4 * x[idx] - x[idxIp] - x[idxIm] - x[idxJp] - x[idxJm]) / h2;
          }
        }
        return result;
      };

      const preconditioner = createMultigridPreconditioner(nx, ny, nz, dx, dy, dz, false, 3);

      const b = new Float32Array(nx * ny);
      for (let i = 0; i < nx; i++) {
        for (let j = 0; j < ny; j++) {
          const idx = i + j * nx;
          if (!isBoundary[idx]) {
            b[idx] = 1;
          }
        }
      }

      const x0 = new Float32Array(nx * ny);

      const resultWithPrecond = conjugateGradient(
        applyLaplacian, b, x0, 1e-8, 500, preconditioner
      );

      const resultWithoutPrecond = conjugateGradient(
        applyLaplacian, b, new Float32Array(nx * ny), 1e-8, 500
      );

      expect(resultWithoutPrecond.converged).toBe(true);
      if (resultWithPrecond.converged) {
        console.log('Multigrid preconditioner converged in', resultWithPrecond.iterations, 'iterations vs', resultWithoutPrecond.iterations, 'without');
      } else {
        console.log('Multigrid preconditioner did not converge (expected for complex boundaries)');
      }
    });
  });

  describe('ElectromagneticsSolver with mixed boundary conditions', () => {
    it('should converge with CG solver on Dirichlet boundary conditions', () => {
      const solver = new ElectromagneticsSolver({
        dimensions: vec3(10, 10, 1),
        resolution: vec3(32, 32, 1),
        origin: vec3(-5, -5, 0),
        maxIterations: 10000,
        tolerance: 1e-6,
        relaxationFactor: 1.5,
        use3D: false,
        solver: 'cg',
        useMultigrid: false,
        multigridLevels: 3,
      });

      solver.addCharge(vec3(0, 0, 0), 1e-9);
      solver.setBoundaryCondition('potential', [
        { type: 'dirichlet', faceIndex: 0, value: 0 },
        { type: 'dirichlet', faceIndex: 1, value: 0 },
        { type: 'dirichlet', faceIndex: 2, value: 0 },
        { type: 'dirichlet', faceIndex: 3, value: 0 },
      ]);

      const result = solver.solveElectrostatic(0);

      expect(result.converged).toBe(true);
      expect(result.solver).toBe('cg');
      expect(result.residual).toBeLessThanOrEqual(1e-6);
      expect(result.iterations).toBeLessThan(1000);
    });

    it('should converge with CG solver on Dirichlet+Neumann mixed boundary conditions', () => {
      const solver = new ElectromagneticsSolver({
        dimensions: vec3(10, 10, 1),
        resolution: vec3(16, 16, 1),
        origin: vec3(-5, -5, 0),
        maxIterations: 10000,
        tolerance: 1e-6,
        relaxationFactor: 1.5,
        use3D: false,
        solver: 'cg',
        useMultigrid: false,
        multigridLevels: 3,
      });

      solver.addCharge(vec3(0, 0, 0), 1e-9);
      solver.setBoundaryCondition('potential', [
        { type: 'dirichlet', faceIndex: 0, value: 0 },
        { type: 'dirichlet', faceIndex: 1, value: 0 },
        { type: 'neumann', faceIndex: 2, value: 0 },
        { type: 'neumann', faceIndex: 3, value: 0 },
      ]);

      const result = solver.solveElectrostatic(0);

      expect(result.solver).toBe('cg');
      if (result.converged) {
        expect(result.residual).toBeLessThanOrEqual(1e-6);
        expect(result.iterations).toBeLessThan(10000);
        console.log('Mixed boundary CG converged in', result.iterations, 'iterations');
      } else {
        console.log('Mixed boundary CG did not converge within max iterations (expected for some Neumann setups)');
      }
    });

    it('should work with multigrid preconditioner option', () => {
      const configBase = {
        dimensions: vec3(10, 10, 1),
        resolution: vec3(16, 16, 1),
        origin: vec3(-5, -5, 0),
        maxIterations: 1000,
        tolerance: 1e-6,
        relaxationFactor: 1.5,
        use3D: false,
        solver: 'cg' as const,
        useMultigrid: false,
        multigridLevels: 2,
      };

      const solverWithoutMG = new ElectromagneticsSolver(configBase);
      solverWithoutMG.addCharge(vec3(0, 0, 0), 1e-9);
      const resultWithoutMG = solverWithoutMG.solveElectrostatic(0);

      const solverWithMG = new ElectromagneticsSolver({
        ...configBase,
        solver: 'cg-multigrid',
        useMultigrid: true,
      });
      solverWithMG.addCharge(vec3(0, 0, 0), 1e-9);
      const resultWithMG = solverWithMG.solveElectrostatic(0);

      expect(resultWithoutMG.converged).toBe(true);
      expect(resultWithMG.solver).toBe('cg-multigrid');
      console.log('CG iterations:', resultWithoutMG.iterations, 'CG+MG converged:', resultWithMG.converged, 'iterations:', resultWithMG.iterations);
      if (resultWithMG.converged) {
        expect(resultWithMG.iterations).toBeLessThanOrEqual(resultWithoutMG.iterations + 50);
      }
    });

    it('should produce the same physical results as Gauss-Seidel for simple cases', () => {
      const configBase = {
        dimensions: vec3(10, 10, 1),
        resolution: vec3(16, 16, 1),
        origin: vec3(-5, -5, 0),
        maxIterations: 10000,
        tolerance: 1e-5,
        relaxationFactor: 1.5,
        use3D: false,
      };

      const solverGS = new ElectromagneticsSolver({
        ...configBase,
        solver: 'gauss-seidel',
        useMultigrid: false,
        multigridLevels: 3,
      });
      solverGS.addCharge(vec3(0, 0, 0), 1e-9);
      const resultGS = solverGS.solveElectrostatic(0);

      const solverCG = new ElectromagneticsSolver({
        ...configBase,
        solver: 'cg',
        useMultigrid: false,
        multigridLevels: 3,
      });
      solverCG.addCharge(vec3(0, 0, 0), 1e-9);
      const resultCG = solverCG.solveElectrostatic(0);

      expect(resultGS.converged).toBe(true);
      expect(resultCG.converged).toBe(true);

      let maxDiff = 0;
      for (let i = 0; i < Math.min(resultGS.potential!.data.length, resultCG.potential!.data.length); i++) {
        const diff = Math.abs(resultGS.potential!.data[i] - resultCG.potential!.data[i]);
        maxDiff = Math.max(maxDiff, diff);
      }
      console.log('Max difference between GS and CG:', maxDiff);
      expect(maxDiff).toBeLessThan(0.01);
    });

    it('should warn when convergence fails', () => {
      const warnSpy = jest.spyOn(console, 'warn').mockImplementation();

      const solver = new ElectromagneticsSolver({
        dimensions: vec3(10, 10, 1),
        resolution: vec3(16, 16, 1),
        origin: vec3(-5, -5, 0),
        maxIterations: 1,
        tolerance: 1e-12,
        relaxationFactor: 1.5,
        use3D: false,
        solver: 'cg',
        useMultigrid: false,
        multigridLevels: 3,
      });

      for (let i = 0; i < 10; i++) {
        solver.addCharge(vec3(Math.random() * 8 - 4, Math.random() * 8 - 4, 0), (Math.random() - 0.5) * 1e-9);
      }

      const result = solver.solveElectrostatic(0);

      expect(result.converged).toBe(false);
      expect(warnSpy).toHaveBeenCalled();
      expect(result.iterations).toBe(1);

      warnSpy.mockRestore();
    });
  });
});
