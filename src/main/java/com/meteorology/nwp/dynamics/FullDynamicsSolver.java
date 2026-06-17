package com.meteorology.nwp.dynamics;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FullDynamicsSolver {
    private static final Logger logger = LoggerFactory.getLogger(FullDynamicsSolver.class);
    private final GridDefinition grid;
    private final NWPConfig config;
    private final ShallowWaterSolver shallowWater;
    private final double dt;
    private final int nz;

    public FullDynamicsSolver(NWPConfig config, GridDefinition grid) {
        this.config = config;
        this.grid = grid;
        this.shallowWater = new ShallowWaterSolver(config, grid);
        this.dt = config.getTimeStep();
        this.nz = grid.getNZ();
        initializeFields();
    }

    private void initializeFields() {
        logger.info("Initializing full dynamics solver: dt={}s, nz={}", dt, nz);
    }

    public void timeStep(ModelState state, DynamicsState tend) {
        rk3Step(state, tend);
        applyVerticalAdvection(state, dt);
        applyHorizontalDiffusion(state, dt);
    }

    private void rk3Step(ModelState state, DynamicsState tend) {
        ModelState stage0 = state.cloneState();
        computeFullTendencies(state, tend);
        updateState(state, stage0, tend, dt / 3.0);

        ModelState stage1 = state.cloneState();
        computeFullTendencies(state, tend);
        updateState(state, stage0, tend, dt / 2.0);

        computeFullTendencies(state, tend);
        updateState(state, stage0, tend, dt);
    }

    public void computeFullTendencies(ModelState state, DynamicsState tend) {
        shallowWater.computeTendencies(state);
        extendTendenciesTo3D(state, tend);
        computeTemperatureTendency(state, tend);
        computeMoistureTendency(state, tend);
        computePressureGradient(state, tend);
    }

    private void extendTendenciesTo3D(ModelState state, DynamicsState tend) {
        int nx = grid.getNX(), ny = grid.getNY();
        double[] sigma = grid.getSigmaLevels();
        double[] uTend2D = shallowWater.uTend.getData();
        double[] vTend2D = shallowWater.vTend.getData();
        double[] hTend2D = shallowWater.heightTend.getData();

        for (VariableType type : new VariableType[]{VariableType.U, VariableType.V}) {
            DataField field = tend.getTendency(type);
            if (field == null) continue;
            double[] src = (type == VariableType.U) ? uTend2D : vTend2D;
            for (int k = 0; k < nz; k++) {
                double shear = Math.exp(-5.0 * (1.0 - sigma[k]));
                for (int j = 0; j < ny; j++) {
                    for (int i = 0; i < nx; i++) {
                        int idx2D = i + nx * j;
                        field.set(i, j, k, shear * src[idx2D]);
                    }
                }
            }
        }

        DataField psfcTend = tend.getTendency(VariableType.PSFC);
        if (psfcTend != null) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    psfcTend.set(i, j, hTend2D[i + nx * j]);
                }
            }
        }
    }

    private void computeTemperatureTendency(ModelState state, DynamicsState tend) {
        DataField T = state.getField(VariableType.T);
        DataField U = state.getField(VariableType.U);
        DataField V = state.getField(VariableType.V);
        DataField W = state.getField(VariableType.W);
        DataField PSFC = state.getField(VariableType.PSFC);
        DataField TTend = tend.getTendency(VariableType.T);

        if (T == null || U == null || V == null || TTend == null) return;

        int nx = grid.getNX(), ny = grid.getNY();
        double Rd = PhysicsConstants.GAS_CONSTANT_DRY_AIR;
        double Cp = PhysicsConstants.CP_DRY_AIR;
        double kappa = PhysicsConstants.P0_EXPONENT;
        double[] sigma = grid.getSigmaLevels();
        double[] dSigma = computeDSigma();

        for (int k = 0; k < nz; k++) {
            for (int j = 1; j < ny - 1; j++) {
                double dx = grid.getDXMeters(j);
                double dy = grid.getDYMeters(j);
                for (int i = 0; i < nx; i++) {
                    int ip = (i + 1) % nx;
                    int im = (i - 1 + nx) % nx;

                    double t = T.get(i, j, k);
                    double u = U.get(i, j, k);
                    double v = V.get(i, j, k);
                    double ps = PSFC != null ? PSFC.get(i, j) : PhysicsConstants.REFERENCE_PRESSURE;
                    double p = sigma[k] * ps;

                    double dTdx = (T.get(ip, j, k) - T.get(im, k < nz - 1 ? j : j, Math.min(k + 1, nz - 1) < nz ? j : j)) / (2.0 * dx);
                    double dTdy = (T.get(i, Math.min(j + 1, ny - 1), k) - T.get(i, Math.max(j - 1, 0), k)) / (2.0 * dy);
                    double horAdv = -(u * dTdx + v * dTdy);

                    double omega = 0.0;
                    if (W != null) {
                        omega = W.get(i, j, k);
                        double theta = t * Math.pow(PhysicsConstants.REFERENCE_PRESSURE / p, kappa);
                        double dThetaDp = 0.0;
                        if (k > 0 && k < nz - 1) {
                            double p1 = sigma[k - 1] * ps;
                            double p2 = sigma[k + 1] * ps;
                            double theta1 = T.get(i, j, k - 1) * Math.pow(PhysicsConstants.REFERENCE_PRESSURE / p1, kappa);
                            double theta2 = T.get(i, j, k + 1) * Math.pow(PhysicsConstants.REFERENCE_PRESSURE / p2, kappa);
                            dThetaDp = (theta2 - theta1) / (p2 - p1);
                        }
                        double vertAdv = -omega * dThetaDp * Math.pow(p / PhysicsConstants.REFERENCE_PRESSURE, kappa);
                        horAdv += vertAdv;
                    }

                    double diabatic = 0.0;
                    TTend.set(i, j, k, horAdv + diabatic);
                }
            }
        }
    }

    private void computeMoistureTendency(ModelState state, DynamicsState tend) {
        for (VariableType moistType : new VariableType[]{VariableType.QV, VariableType.QC, VariableType.QR,
                VariableType.QI, VariableType.QS, VariableType.QG}) {
            DataField q = state.getField(moistType);
            DataField U = state.getField(VariableType.U);
            DataField V = state.getField(VariableType.V);
            DataField qTend = tend.getTendency(moistType);
            if (q == null || U == null || V == null || qTend == null) continue;

            int nx = grid.getNX(), ny = grid.getNY();
            for (int k = 0; k < nz; k++) {
                for (int j = 1; j < ny - 1; j++) {
                    double dx = grid.getDXMeters(j);
                    double dy = grid.getDYMeters(j);
                    for (int i = 0; i < nx; i++) {
                        int ip = (i + 1) % nx;
                        int im = (i - 1 + nx) % nx;
                        double dqdx = (q.get(ip, j, k) - q.get(im, j, k)) / (2.0 * dx);
                        double dqdy = (q.get(i, Math.min(j + 1, ny - 1), k) - q.get(i, Math.max(j - 1, 0), k)) / (2.0 * dy);
                        qTend.set(i, j, k, -(U.get(i, j, k) * dqdx + V.get(i, j, k) * dqdy));
                    }
                }
            }
        }
    }

    private void computePressureGradient(ModelState state, DynamicsState tend) {
        DataField T = state.getField(VariableType.T);
        DataField PSFC = state.getField(VariableType.PSFC);
        DataField QV = state.getField(VariableType.QV);
        DataField UTend = tend.getTendency(VariableType.U);
        DataField VTend = tend.getTendency(VariableType.V);
        if (T == null || PSFC == null || UTend == null || VTend == null) return;

        int nx = grid.getNX(), ny = grid.getNY();
        double Rd = PhysicsConstants.GAS_CONSTANT_DRY_AIR;
        double[] sigma = grid.getSigmaLevels();
        double[] dSigma = computeDSigma();

        DataField phi = new DataField(VariableType.GEOPOTENTIAL, nx, ny, nz);
        integrateHydrostatic(T, QV, PSFC, phi);

        for (int k = 0; k < nz; k++) {
            for (int j = 1; j < ny - 1; j++) {
                double dx = grid.getDXMeters(j);
                double dy = grid.getDYMeters(j);
                for (int i = 0; i < nx; i++) {
                    int ip = (i + 1) % nx;
                    int im = (i - 1 + nx) % nx;
                    double dPhidx = (phi.get(ip, j, k) - phi.get(im, j, k)) / (2.0 * dx);
                    double dPhidy = (phi.get(i, Math.min(j + 1, ny - 1), k) - phi.get(i, Math.max(j - 1, 0), k)) / (2.0 * dy);
                    UTend.add(i, j, k, -dPhidx);
                    VTend.add(i, j, k, -dPhidy);
                }
            }
        }
    }

    private void integrateHydrostatic(DataField T, DataField QV, DataField PSFC, DataField phi) {
        int nx = grid.getNX(), ny = grid.getNY();
        double g = PhysicsConstants.GRAVITY;
        double Rd = PhysicsConstants.GAS_CONSTANT_DRY_AIR;
        double epsilon = PhysicsConstants.RATIO_GAS_CONSTANTS;
        double[] sigma = grid.getSigmaLevels();
        double[] sigmaInterfaces = new double[nz + 1];
        sigmaInterfaces[0] = 0.0;
        for (int k = 0; k < nz; k++) {
            if (k > 0) sigmaInterfaces[k] = 0.5 * (sigma[k - 1] + sigma[k]);
        }
        sigmaInterfaces[nz] = 1.0;

        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                double ps = PSFC.get(i, j);
                double prevPhi = 0.0;
                for (int k = nz - 1; k >= 0; k--) {
                    double t = T.get(i, j, k);
                    double qv = QV != null ? Math.max(0.0, QV.get(i, j, k)) : 0.0;
                    double tv = t * (1.0 + (1.0 / epsilon - 1.0) * qv);
                    double dSigmaK = sigmaInterfaces[k + 1] - sigmaInterfaces[k];
                    double dPhiSigma = -Rd * tv * dSigmaK;
                    double sigmaFactor = k < nz - 1 ? 0.5 * (sigma[k] + sigma[k + 1]) : sigma[k];
                    prevPhi += -g * dPhiSigma * ps / (g * 1.0);
                    phi.set(i, j, k, prevPhi);
                }
            }
        }
    }

    private double[] computeDSigma() {
        double[] dSigma = new double[nz];
        double[] sigma = grid.getSigmaLevels();
        double[] interfaces = new double[nz + 1];
        interfaces[0] = 0.0;
        for (int k = 0; k < nz; k++) {
            if (k > 0) interfaces[k] = 0.5 * (sigma[k - 1] + sigma[k]);
        }
        interfaces[nz] = 1.0;
        for (int k = 0; k < nz; k++) {
            dSigma[k] = interfaces[k + 1] - interfaces[k];
        }
        return dSigma;
    }

    private void applyVerticalAdvection(ModelState state, double dt) {
        double[] dSigma = computeDSigma();
        for (VariableType type : new VariableType[]{VariableType.T, VariableType.U, VariableType.V,
                VariableType.QV, VariableType.QC, VariableType.QI}) {
            DataField f = state.getField(type);
            if (f == null || !f.is3D()) continue;
            int nx = grid.getNX(), ny = grid.getNY();
            double[] flux = new double[nx * ny * (nz + 1)];
            for (int k = 1; k < nz; k++) {
                for (int j = 0; j < ny; j++) {
                    for (int i = 0; i < nx; i++) {
                        int idx = i + nx * j;
                        double wInterface = 0.01;
                        if (wInterface >= 0) {
                            flux[idx + nx * (j + ny * k)] = wInterface * f.get(i, j, k - 1);
                        } else {
                            flux[idx + nx * (j + ny * k)] = wInterface * f.get(i, j, k);
                        }
                    }
                }
            }
            for (int k = 0; k < nz; k++) {
                double ds = Math.max(1e-6, dSigma[k]);
                for (int j = 0; j < ny; j++) {
                    for (int i = 0; i < nx; i++) {
                        int idx = i + nx * j;
                        int idxK = idx + nx * (j + ny * k);
                        int idxK1 = idx + nx * (j + ny * (k + 1));
                        double divF = (flux[idxK1] - flux[idxK]) / ds;
                        f.getData()[idxK] -= dt * divF;
                    }
                }
            }
        }
    }

    private void applyHorizontalDiffusion(ModelState state, double dt) {
        double[] coef = new double[grid.getNY()];
        double dxMin = grid.getDXMeters(grid.getNY() / 2);
        double nu = Math.min(config.getDiffusionCoef(), dxMin * dxMin / (6.0 * dt));
        for (int j = 0; j < grid.getNY(); j++) {
            double dx = grid.getDXMeters(j);
            coef[j] = nu * dt / (dx * dx);
        }

        for (VariableType type : VariableType.values()) {
            DataField field = state.getField(type);
            if (field != null) {
                field.horizontalDiffusion(coef, 1);
            }
        }
    }

    private void updateState(ModelState state, ModelState initial, DynamicsState tend, double alpha) {
        for (VariableType type : VariableType.values()) {
            DataField field = state.getField(type);
            DataField init = initial.getField(type);
            DataField t = tend.getTendency(type);
            if (field != null && init != null && t != null) {
                double[] d = field.getData();
                double[] i0 = init.getData();
                double[] tt = t.getData();
                for (int idx = 0; idx < d.length; idx++) {
                    d[idx] = i0[idx] + alpha * tt[idx];
                }
            }
        }
    }

    public void initializeIdealized(ModelState state, String caseType) {
        logger.info("Initializing idealized case: {}", caseType);
        state.ensurePrognosticFields();
        int nx = grid.getNX(), ny = grid.getNY();
        double[] sigma = grid.getSigmaLevels();

        DataField T = state.getField(VariableType.T);
        DataField U = state.getField(VariableType.U);
        DataField V = state.getField(VariableType.V);
        DataField QV = state.getField(VariableType.QV);
        DataField PSFC = state.getField(VariableType.PSFC);
        DataField PBLH = state.getField(VariableType.PBLH);
        state.addField(VariableType.QC);
        DataField QC = state.getField(VariableType.QC);

        switch (caseType.toLowerCase()) {
            case "barotropic-instability" -> initBarotropicInstability(T, U, V, QV, PSFC, sigma, nx, ny);
            case "density-current" -> initDensityCurrent(T, U, V, QV, PSFC, QC, sigma, nx, ny);
            case "rossby-wave" -> initRossbyWave(T, U, V, QV, PSFC, sigma, nx, ny);
            case "thermal-low" -> initThermalLow(T, U, V, QV, PSFC, sigma, nx, ny);
            default -> initStandardAtmosphere(T, U, V, QV, PSFC, sigma, nx, ny);
        }

        if (PBLH != null) PBLH.fill(500.0);
        shallowWater.balanceState(state);
        state.computeDiagnosticFields();
    }

    private void initStandardAtmosphere(DataField T, DataField U, DataField V, DataField QV,
                                         DataField PSFC, double[] sigma, int nx, int ny) {
        double lapseRate = 6.5e-3;
        double t0 = 288.15;
        double p0 = PhysicsConstants.REFERENCE_PRESSURE;
        for (int k = 0; k < nz; k++) {
            double height = -PhysicsConstants.GAS_CONSTANT_DRY_AIR * 288.0 / PhysicsConstants.GRAVITY
                    * Math.log(sigma[k]);
            for (int j = 0; j < ny; j++) {
                double latFactor = 1.0 - 0.3 * Math.abs(Math.sin(Math.toRadians(grid.getLat(j))));
                for (int i = 0; i < nx; i++) {
                    double temp = Math.max(180.0, (t0 - lapseRate * height) * latFactor);
                    T.set(i, j, k, temp);
                    double uGeo = 10.0 * Math.sin(Math.toRadians(grid.getLat(j))) * Math.exp(-height / 8000.0);
                    U.set(i, j, k, uGeo);
                    V.set(i, j, k, 0.0);
                    double rh = 0.8 * Math.exp(-Math.pow((height - 2000.0) / 3000.0, 2));
                    double p = sigma[k] * p0;
                    double ws = PhysicsConstants.saturationMixingRatio(temp, p);
                    QV.set(i, j, k, Math.max(0.0, Math.min(0.04, rh * ws)));
                }
            }
        }
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                double latPert = 1.0 - 0.1 * Math.sin(Math.toRadians(grid.getLat(j)));
                double lonPert = 1.0 + 0.005 * Math.cos(Math.toRadians(grid.getLon(i)) * 4.0);
                PSFC.set(i, j, p0 * latPert * lonPert);
            }
        }
    }

    private void initBarotropicInstability(DataField T, DataField U, DataField V, DataField QV,
                                            DataField PSFC, double[] sigma, int nx, int ny) {
        initStandardAtmosphere(T, U, V, QV, PSFC, sigma, nx, ny);
        double jetLat = 45.0;
        double jetWidth = 10.0;
        double jetMax = 40.0;
        for (int k = 0; k < nz; k++) {
            double vertical = 1.0 - Math.exp(-(1.0 - sigma[k]) * 3.0);
            for (int j = 0; j < ny; j++) {
                double latDiff = grid.getLat(j) - jetLat;
                double jetProfile = jetMax * Math.exp(-(latDiff * latDiff) / (2.0 * jetWidth * jetWidth));
                for (int i = 0; i < nx; i++) {
                    double pert = 5.0 * Math.sin(Math.toRadians(8.0 * grid.getLon(i)))
                            * Math.exp(-Math.pow((grid.getLat(j) - jetLat) / 15.0, 2));
                    U.set(i, j, k, U.get(i, j, k) + jetProfile * vertical + pert);
                    V.set(i, j, k, V.get(i, j, k) + 2.0 * pert * Math.cos(Math.toRadians(8.0 * grid.getLon(i))));
                }
            }
        }
    }

    private void initDensityCurrent(DataField T, DataField U, DataField V, DataField QV,
                                     DataField PSFC, DataField QC, double[] sigma, int nx, int ny) {
        initStandardAtmosphere(T, U, V, QV, PSFC, sigma, nx, ny);
        double cx = 180.0, cy = 0.0;
        double rx = 30.0, ry = 15.0;
        double dtPert = -15.0;
        for (int k = 0; k < nz; k++) {
            double zFactor = Math.exp(-Math.pow((sigma[k] - 0.85) / 0.1, 2));
            for (int j = 0; j < ny; j++) {
                double dy = grid.getLat(j) - cy;
                for (int i = 0; i < nx; i++) {
                    double dxi = (grid.getLon(i) - cx + 180.0) % 360.0 - 180.0;
                    double r2 = (dxi * dxi) / (rx * rx) + (dy * dy) / (ry * ry);
                    if (r2 < 1.0) {
                        double pert = dtPert * Math.cos(Math.PI * Math.sqrt(r2) / 2.0);
                        T.set(i, j, k, T.get(i, j, k) + pert * zFactor);
                        QC.set(i, j, k, QC.get(i, j, k) + 1e-3 * zFactor * Math.max(0.0, 1.0 - r2));
                    }
                }
            }
        }
    }

    private void initRossbyWave(DataField T, DataField U, DataField V, DataField QV,
                                 DataField PSFC, double[] sigma, int nx, int ny) {
        initStandardAtmosphere(T, U, V, QV, PSFC, sigma, nx, ny);
        int waveNumber = 4;
        double amp = 200.0;
        double beta = 2.0 * PhysicsConstants.EARTH_OMEGA * Math.cos(Math.PI / 4.0) / grid.getEarthRadius();
        double uBar = 15.0;
        double k = waveNumber / (grid.getEarthRadius() * Math.cos(Math.PI / 4.0));
        double phaseSpeed = uBar - beta / (k * k);
        for (int j = 0; j < ny; j++) {
            double midLat = Math.sin(Math.toRadians(grid.getLat(j)));
            for (int i = 0; i < nx; i++) {
                double stream = amp * Math.cos(waveNumber * Math.toRadians(grid.getLon(i)))
                        * Math.pow(midLat, 2);
                double pPert = stream / 10.0;
                PSFC.set(i, j, PSFC.get(i, j) + pPert);
                for (int k = 0; k < nz; k++) {
                    double tv = 5.0 * Math.sin(Math.toRadians(grid.getLon(i))) * midLat;
                    T.set(i, j, k, T.get(i, j, k) + tv * (1.0 - sigma[k]));
                }
            }
        }
    }

    private void initThermalLow(DataField T, DataField U, DataField V, DataField QV,
                                 DataField PSFC, double[] sigma, int nx, int ny) {
        initStandardAtmosphere(T, U, V, QV, PSFC, sigma, nx, ny);
        double clon = 120.0, clat = 35.0;
        double radius = 15.0;
        for (int j = 0; j < ny; j++) {
            double dy = grid.getLat(j) - clat;
            for (int i = 0; i < nx; i++) {
                double dxi = (grid.getLon(i) - clon + 180.0) % 360.0 - 180.0;
                double r = Math.sqrt(dxi * dxi + dy * dy);
                double envelope = Math.exp(-(r * r) / (2.0 * radius * radius));
                double psPert = -500.0 * envelope;
                PSFC.set(i, j, PSFC.get(i, j) + psPert);
                for (int k = Math.max(0, nz - 10); k < nz; k++) {
                    double zFactor = Math.exp(-Math.pow((sigma[k] - 0.9) / 0.08, 2));
                    double tPert = 5.0 * envelope * zFactor;
                    T.set(i, j, k, T.get(i, j, k) + tPert);
                }
            }
        }
    }

    public DynamicsState createTendencyState() {
        return new DynamicsState(grid);
    }

    public ShallowWaterSolver getShallowWaterSolver() { return shallowWater; }
}
