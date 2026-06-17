package com.meteorology.nwp.dynamics;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShallowWaterSolver {
    private static final Logger logger = LoggerFactory.getLogger(ShallowWaterSolver.class);
    private final GridDefinition grid;
    private final NWPConfig config;
    private final SphericalHarmonics spectral;
    private final double gravity;
    private final double meanDepth;
    private final double earthRadius;
    private final double omega;
    private final double diffusionCoef;

    private DataField heightTend;
    private DataField uTend;
    private DataField vTend;
    private double[] diffusionCoefLat;

    public ShallowWaterSolver(NWPConfig config, GridDefinition grid) {
        this.config = config;
        this.grid = grid;
        this.spectral = new SphericalHarmonics(config.getSpectralTruncation(), grid);
        this.gravity = PhysicsConstants.GRAVITY;
        this.meanDepth = 10000.0;
        this.earthRadius = grid.getEarthRadius();
        this.omega = grid.getOmega();
        this.diffusionCoef = config.getDiffusionCoef();

        this.heightTend = new DataField(VariableType.PSFC, grid.getNX(), grid.getNY(), 1);
        this.uTend = new DataField(VariableType.U, grid.getNX(), grid.getNY(), 1);
        this.vTend = new DataField(VariableType.V, grid.getNX(), grid.getNY(), 1);

        this.diffusionCoefLat = new double[grid.getNY()];
        double dxMin = grid.getDXMeters(grid.getNY() / 2);
        double stabilityLimit = dxMin * dxMin / 6.0;
        for (int j = 0; j < grid.getNY(); j++) {
            diffusionCoefLat[j] = Math.min(diffusionCoef, stabilityLimit / config.getTimeStep());
        }
    }

    public void computeTendencies(ModelState state) {
        DataField h = state.getField(VariableType.PSFC);
        DataField u = state.getField(VariableType.U);
        DataField v = state.getField(VariableType.V);

        if (h == null) {
            state.addField(VariableType.PSFC);
            h = state.getField(VariableType.PSFC);
            h.fill(meanDepth * gravity);
        }
        if (u == null) {
            state.addField(VariableType.U);
            u = state.getField(VariableType.U);
        }
        if (v == null) {
            state.addField(VariableType.V);
            v = state.getField(VariableType.V);
        }

        int nx = grid.getNX();
        int ny = grid.getNY();

        DataField geopot = convertPressureToGeopot(h);
        double[] gh = geopot.getData();
        double[] ud = u.is3D() ? extractLevel(u, 0) : u.getData();
        double[] vd = v.is3D() ? extractLevel(v, 0) : v.getData();
        double[] ht = heightTend.getData();
        double[] ut = uTend.getData();
        double[] vt = vTend.getData();

        double[] cosLat = new double[ny];
        double[] cosLatSq = new double[ny];
        for (int j = 0; j < ny; j++) {
            double c = Math.cos(Math.toRadians(grid.getLat(j)));
            cosLat[j] = Math.max(0.01, c);
            cosLatSq[j] = c * c;
        }

        for (int j = 1; j < ny - 1; j++) {
            double dx = grid.getDXMeters(j);
            double dy = grid.getDYMeters(j);
            double f = grid.getFCoriolis(j);
            for (int i = 0; i < nx; i++) {
                int ip = (i + 1) % nx;
                int im = (i - 1 + nx) % nx;

                int ij = i + nx * j;
                int ipj = ip + nx * j;
                int imj = im + nx * j;
                int ijp = i + nx * (j + 1);
                int ijm = i + nx * (j - 1);

                double uij = ud[ij];
                double vij = vd[ij];
                double ghij = gh[ij];

                double dudx = (ud[ipj] - ud[imj]) / (2.0 * dx);
                double dvdy = (vd[ijp] - vd[ijdvdyHelper(vd, i, j, nx)]) / (2.0 * dy);
                double dhdx = (gh[ipj] - gh[imj]) / (2.0 * dx);
                double dhdy = (gh[ijp] - gh[ijdhdyHelper(gh, i, j, nx)]) / (2.0 * dy);

                ht[ij] = -(ghij * (dudx + dvdy) + uij * dhdx + vij * dhdy);
                ut[ij] = -uij * dudx - vij * (vd[ijp] - vd[ijdvdyHelper(vd, i, j, nx)]) / (2.0 * dy)
                        + f * vij - dhdx;
                vt[ij] = -uij * (ud[ipj] - ud[imj]) / (2.0 * dx) - vij * dvdy
                        - f * uij - dhdy;
            }
        }

        applyBoundaryConditions(ht, nx, ny);
        applyBoundaryConditions(ut, nx, ny);
        applyBoundaryConditions(vt, nx, ny);
    }

    private int ijdvdyHelper(double[] vd, int i, int j, int nx) { return i + nx * (j - 1); }
    private int ijdhdyHelper(double[] gh, int i, int j, int nx) { return i + nx * (j - 1); }

    private DataField convertPressureToGeopot(DataField psfc) {
        DataField gh = new DataField(VariableType.GEOPOTENTIAL, grid.getNX(), grid.getNY(), 1);
        double[] p = psfc.getData();
        double[] g = gh.getData();
        for (int i = 0; i < p.length; i++) {
            g[i] = p[i] * meanDepth * gravity / PhysicsConstants.REFERENCE_PRESSURE;
        }
        return gh;
    }

    private double[] extractLevel(DataField field, int k) {
        if (!field.is3D()) return field.getData();
        int nx = grid.getNX(), ny = grid.getNY();
        double[] data = new double[nx * ny];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                data[i + nx * j] = field.get(i, j, k);
            }
        }
        return data;
    }

    private void applyBoundaryConditions(double[] data, int nx, int ny) {
        for (int j = 1; j < ny - 1; j++) {
            data[j * nx] = data[nx * j + nx - 2];
            data[nx * j + nx - 1] = data[nx * j + 1];
        }
    }

    public void addDiffusion(DataField field, double dt) {
        double[] data = field.getData();
        int nx = grid.getNX(), ny = grid.getNY();
        double[] tmp = new double[data.length];
        for (int k = 0; k < (field.is3D() ? field.getNZ() : 1); k++) {
            for (int j = 1; j < ny - 1; j++) {
                double coef = diffusionCoefLat[j] * dt;
                for (int i = 0; i < nx; i++) {
                    int idx = i + nx * (j + ny * k);
                    int ip = (i + 1) % nx + nx * (j + ny * k);
                    int im = (i - 1 + nx) % nx + nx * (j + ny * k);
                    int jp = i + nx * (Math.min(j + 1, ny - 1) + ny * k);
                    int jm = i + nx * (Math.max(j - 1, 0) + ny * k);
                    double lap = data[ip] + data[im] + data[jp] + data[jm] - 4.0 * data[idx];
                    tmp[idx] = data[idx] + coef * lap;
                }
            }
        }
        for (int k = 0; k < (field.is3D() ? field.getNZ() : 1); k++) {
            for (int j = 1; j < ny - 1; j++) {
                for (int i = 0; i < nx; i++) {
                    int idx = i + nx * (j + ny * k);
                    data[idx] = tmp[idx];
                }
            }
        }
    }

    public void stepRK3(ModelState state, double dt) {
        double[] a = {1.0 / 3.0, 1.0 / 2.0, 1.0};
        double[] b = {1.0 / 3.0, 1.0 / 2.0, 1.0};
        double[] c = {0.0, 1.0 / 3.0, 3.0 / 4.0};

        DataField h = state.getField(VariableType.PSFC);
        DataField u = state.getField(VariableType.U);
        DataField v = state.getField(VariableType.V);
        DataField h0 = h.deepCopy();
        DataField u0 = u.deepCopy();
        DataField v0 = v.deepCopy();

        for (int stage = 0; stage < 3; stage++) {
            computeTendencies(state);

            double coeff = dt * b[stage];
            addTendency(h, h0, heightTend, coeff, c[stage] * dt);
            addTendency(u, u0, uTend, coeff, c[stage] * dt);
            addTendency(v, v0, vTend, coeff, c[stage] * dt);

            addDiffusion(h, dt * 0.5);
            addDiffusion(u, dt * 0.5);
            addDiffusion(v, dt * 0.5);
        }
    }

    private void addTendency(DataField field, DataField initial, DataField tendency,
                              double coeff, double _ignored) {
        double[] d = field.getData();
        double[] t = tendency.getData();
        int n = Math.min(d.length, t.length);
        for (int i = 0; i < n; i++) {
            d[i] += coeff * t[i];
        }
    }

    public void stepForwardEuler(ModelState state, double dt) {
        computeTendencies(state);
        addTendencySimple(state.getField(VariableType.PSFC), heightTend, dt);
        addTendencySimple(state.getField(VariableType.U), uTend, dt);
        addTendencySimple(state.getField(VariableType.V), vTend, dt);
        addDiffusion(state.getField(VariableType.PSFC), dt);
        addDiffusion(state.getField(VariableType.U), dt);
        addDiffusion(state.getField(VariableType.V), dt);
    }

    private void addTendencySimple(DataField field, DataField tendency, double dt) {
        double[] d = field.getData();
        double[] t = tendency.getData();
        int n = Math.min(d.length, t.length);
        for (int i = 0; i < n; i++) {
            d[i] += dt * t[i];
        }
    }

    public void balanceState(ModelState state) {
        logger.info("Performing nonlinear normal mode initialization");
        DataField h = state.getField(VariableType.PSFC);
        DataField u = state.getField(VariableType.U);
        DataField v = state.getField(VariableType.V);

        int nx = grid.getNX(), ny = grid.getNY();
        for (int j = 1; j < ny - 1; j++) {
            double dx = grid.getDXMeters(j);
            double dy = grid.getDYMeters(j);
            double f = grid.getFCoriolis(j);
            for (int i = 0; i < nx; i++) {
                int ip = (i + 1) % nx;
                int im = (i - 1 + nx) % nx;
                double dhdx = (h.get(ip, j) - h.get(im, j)) / (2.0 * dx);
                double dhdy = (h.get(i, Math.min(j + 1, ny - 1)) - h.get(i, Math.max(j - 1, 0))) / (2.0 * dy);
                double windMag = Math.sqrt(u.get(i, j) * u.get(i, j) + v.get(i, j) * v.get(i, j));
                if (windMag > 1.0 && Math.abs(f) > 1e-6) {
                    double gradH = Math.sqrt(dhdx * dhdx + dhdy * dhdy);
                    double adjust = Math.min(0.1, gradH / (Math.abs(f) * windMag));
                    u.set(i, j, u.get(i, j) * (1.0 - adjust * 0.5));
                    v.set(i, j, v.get(i, j) * (1.0 - adjust * 0.5));
                }
            }
        }
    }

    public double computeCFL(ModelState state) {
        DataField u = state.getField(VariableType.U);
        DataField v = state.getField(VariableType.V);
        double dt = config.getTimeStep();
        double maxCFL = 0.0;
        int nx = grid.getNX(), ny = grid.getNY();
        for (int j = 0; j < ny; j++) {
            double dx = grid.getDXMeters(j);
            double dy = grid.getDYMeters(j);
            for (int i = 0; i < nx; i++) {
                double cfl = Math.abs(u.get(i, j)) * dt / dx + Math.abs(v.get(i, j)) * dt / dy;
                if (cfl > maxCFL) maxCFL = cfl;
            }
        }
        return maxCFL;
    }

    public double computeTotalEnergy(ModelState state) {
        DataField h = state.getField(VariableType.PSFC);
        DataField u = state.getField(VariableType.U);
        DataField v = state.getField(VariableType.V);
        double energy = 0.0;
        double areaSum = 0.0;
        int nx = grid.getNX(), ny = grid.getNY();
        for (int j = 0; j < ny; j++) {
            double area = grid.getCellArea(j);
            for (int i = 0; i < nx; i++) {
                double gh = h.get(i, j) * meanDepth * gravity / PhysicsConstants.REFERENCE_PRESSURE;
                double ke = 0.5 * (u.get(i, j) * u.get(i, j) + v.get(i, j) * v.get(i, j));
                double pe = 0.5 * gravity * gh * gh / (gravity * meanDepth);
                energy += area * (ke + pe);
                areaSum += area;
            }
        }
        return energy / Math.max(1e-10, areaSum);
    }

    public SphericalHarmonics getSpectralTransform() { return spectral; }
}
