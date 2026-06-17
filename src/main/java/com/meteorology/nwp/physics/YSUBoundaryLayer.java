package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.DynamicsState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YSUBoundaryLayer implements PhysicsScheme {
    private static final Logger logger = LoggerFactory.getLogger(YSUBoundaryLayer.class);
    private NWPConfig config;
    private GridDefinition grid;
    private int nz;
    private double dt;
    private static final double KARMAN = PhysicsConstants.VON_KARMAN_CONSTANT;
    private static final double RIC_CRIT = 0.0;

    @Override
    public String getName() { return "YSU"; }

    @Override
    public PhysicsType getType() { return PhysicsType.BOUNDARY_LAYER; }

    @Override
    public void initialize(NWPConfig config, GridDefinition grid) {
        this.config = config;
        this.grid = grid;
        this.nz = grid.getNZ();
        this.dt = config.getTimeStep();
    }

    @Override
    public void apply(ModelState state, DynamicsState tendencies, double dt) {
        ColumnData column = new ColumnData(nz);
        int nx = grid.getNX();
        int ny = grid.getNY();
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                column.extract(state, i, j, grid);
                column.resetTendencies();
                applyColumn(i, j, column, dt);
                column.commit(state, tendencies, dt);
            }
        }
    }

    @Override
    public void applyColumn(int i, int j, ColumnData col, double dt) {
        double zSfc = 0.01;
        double z0m = 0.01;
        double z0h = 0.001;
        double uStar = computeUStar(col, z0m);
        double tStar = computeTStar(col, z0h, uStar);
        double qStar = computeQStar(col, z0h, uStar, tStar);
        double hfx = uStar * tStar;
        double lh = uStar * qStar;
        col.hfx = PhysicsConstants.CP_DRY_AIR * col.rho[nz - 1] * hfx;
        col.lh = PhysicsConstants.LATENT_HEAT_VAPORIZATION * col.rho[nz - 1] * lh;

        double obukhovLength = computeObukhovLength(col, uStar, tStar);
        double pblh = computePBLHeight(col, uStar, tStar, obukhovLength);
        col.pblh = pblh;

        double wStar = computeConvectiveVelocity(col, hfx, pblh);
        computeSurfaceFluxes(col, uStar, tStar, qStar, z0m, z0h);

        int kPBL = Math.max(1, col.findLevelFromZ(pblh));
        computeVerticalDiffusion(col, kPBL, uStar, obukhovLength, wStar, pblh, dt);
        computeExplicitMixing(col, kPBL, wStar, pblh, dt);
        computeNonlocalFluxes(col, kPBL, wStar, pblh, hfx, dt);
        compute10MeterFields(col, uStar, tStar, qStar, z0m, z0h);
    }

    private double computeUStar(ColumnData col, double z0m) {
        int kLow = nz - 1;
        int kMid = Math.max(0, nz - 3);
        double z1 = Math.max(z0m * 10, col.zFull[kLow]);
        double z2 = col.zFull[kMid];
        double u1 = Math.sqrt(col.u[kLow] * col.u[kLow] + col.v[kLow] * col.v[kLow]);
        double u2 = Math.sqrt(col.u[kMid] * col.u[kMid] + col.v[kMid] * col.v[kMid]);
        double du = Math.max(0.5, Math.abs(u2 - u1));
        double dz = Math.max(1.0, z2 - z1);
        double uStar = KARMAN * du / Math.log(Math.max(2.0, dz / z0m));
        return Math.max(0.01, uStar);
    }

    private double computeTStar(ColumnData col, double z0h, double uStar) {
        int kLow = nz - 1;
        int kMid = Math.max(0, nz - 3);
        double z1 = Math.max(z0h * 10, col.zFull[kLow]);
        double z2 = col.zFull[kMid];
        double dT = col.tFull[kMid] - col.tFull[kLow];
        double dz = Math.max(1.0, z2 - z1);
        double tStar = KARMAN * dT / (0.74 * Math.log(Math.max(2.0, dz / z0h)));
        if (Math.abs(tStar) > 5.0) tStar = Math.signum(tStar) * 5.0;
        return tStar;
    }

    private double computeQStar(ColumnData col, double z0h, double uStar, double tStar) {
        int kLow = nz - 1;
        int kMid = Math.max(0, nz - 3);
        double z1 = Math.max(z0h * 10, col.zFull[kLow]);
        double z2 = col.zFull[kMid];
        double dQ = col.qv[kMid] - col.qv[kLow];
        double dz = Math.max(1.0, z2 - z1);
        double qStar = KARMAN * dQ / (0.74 * Math.log(Math.max(2.0, dz / z0h)));
        return qStar;
    }

    private double computeObukhovLength(ColumnData col, double uStar, double tStar) {
        double tvSfc = col.thetaV[nz - 1] * col.exner[nz - 1];
        double numerator = uStar * uStar * uStar * tvSfc;
        double denominator = KARMAN * PhysicsConstants.GRAVITY * Math.max(1e-10, uStar * tStar);
        double L = Math.abs(numerator / denominator);
        if (tStar < 0) L = -L;
        return L;
    }

    private double computePBLHeight(ColumnData col, double uStar, double tStar, double L) {
        double hUnstable = 0.0;
        double hStable = 0.0;
        double bulkRich = 0.0;
        double tSfc = col.tFull[nz - 1];
        double uSfc = Math.sqrt(col.u[nz - 1] * col.u[nz - 1] + col.v[nz - 1] * col.v[nz - 1]);
        for (int k = nz - 2; k >= 0; k--) {
            double z = col.zFull[k];
            double dThetaV = col.thetaV[k] - col.thetaV[nz - 1];
            double uMean = 0.5 * (Math.sqrt(col.u[k] * col.u[k] + col.v[k] * col.v[k]) + uSfc);
            if (uMean > 0.1 && z > 10.0) {
                bulkRich = PhysicsConstants.GRAVITY * z * dThetaV /
                        (Math.max(1e-10, col.thetaV[nz - 1] * uMean * uMean));
                if (bulkRich > 0.25 && hStable == 0.0) {
                    hStable = z;
                }
                if (dThetaV > 0.05 && hUnstable == 0.0 && tStar > 0) {
                    hUnstable = z;
                }
                if (hStable > 0 && hUnstable > 0) break;
            }
        }

        if (L < 0) {
            double hConv = Math.pow(-col.rho[nz - 1] * PhysicsConstants.CP_DRY_AIR *
                    col.thetaV[nz - 1] * Math.max(0, uStar * tStar * PhysicsConstants.GRAVITY),
                    -1.0 / 3.0);
            hConv = 1.0 / Math.max(1e-10, hConv);
            return Math.max(Math.max(100.0, Math.min(3000.0, hUnstable)), hConv * 0.1);
        } else {
            double hNeutral = KARMAN * uStar * 3600.0 / 0.3;
            return Math.max(100.0, Math.min(2000.0, Math.min(hStable > 0 ? hStable : 1000.0, hNeutral)));
        }
    }

    private double computeConvectiveVelocity(ColumnData col, double hfx, double pblh) {
        if (hfx > 0) {
            double wStar3 = PhysicsConstants.GRAVITY / Math.max(1e-10, col.thetaV[nz - 1])
                    * Math.max(0, hfx) * pblh;
            return Math.pow(Math.max(0.0, wStar3), 1.0 / 3.0);
        }
        return 0.0;
    }

    private void computeSurfaceFluxes(ColumnData col, double uStar, double tStar, double qStar,
                                       double z0m, double z0h) {
        double rho = col.rho[nz - 1];
        double cp = PhysicsConstants.CP_DRY_AIR;
        double Lv = PhysicsConstants.LATENT_HEAT_VAPORIZATION;
        col.t2 = col.tFull[nz - 1] + tStar / 0.74 * Math.log(2.0 / Math.max(1e-5, z0h)) / KARMAN;
        col.q2 = Math.max(0.0, col.qv[nz - 1] + qStar / 0.74 * Math.log(2.0 / Math.max(1e-5, z0h)) / KARMAN);
        double dragCoeff = KARMAN * KARMAN / (Math.log(10.0 / Math.max(1e-5, z0m)) *
                                    Math.log(10.0 / Math.max(1e-5, z0h)));
        double uMag = Math.sqrt(col.u[nz - 1] * col.u[nz - 1] + col.v[nz - 1] * col.v[nz - 1]);
        if (uMag > 0.1) {
            col.u10 = col.u[nz - 1] - KARMAN * uStar * Math.log(10.0 / Math.max(1e-5, z0m)) *
                    col.u[nz - 1] / uMag;
            col.v10 = col.v[nz - 1] - KARMAN * uStar * Math.log(10.0 / Math.max(1e-5, z0m)) *
                    col.v[nz - 1] / uMag;
        } else {
            col.u10 = 0.0;
            col.v10 = 0.0;
        }
    }

    private void computeVerticalDiffusion(ColumnData col, int kPBL, double uStar,
                                           double L, double wStar, double pblh, double dt) {
        double[] kh = new double[nz];
        double[] km = new double[nz];
        for (int k = kPBL; k < nz; k++) {
            double z = Math.max(1.0, col.zFull[k]);
            double zeta = z / Math.max(1e-10, L);
            double phim, phih;
            if (L < 0) {
                double x = Math.pow(Math.max(1.0 - 16.0 * zeta), 0.25);
                phim = Math.log((1.0 + x * x) * (1.0 + x) / 2.0);
                phih = 2.0 * Math.log((1.0 + x * x) / 2.0);
            } else {
                double a = 1.0 + 5.0 * Math.min(2.0, zeta);
                phim = a;
                phih = 1.0 + 5.0 * Math.min(2.0, zeta);
            }
            double kz = KARMAN * uStar * z / Math.max(1e-5, phim);
            double thz = KARMAN * uStar * z / Math.max(1e-5, phih);
            km[k] = kz;
            kh[k] = thz;
            if (wStar > 0.1 && z <= pblh) {
                double fZ = z / Math.max(1e-5, pblh);
                double enhancement = 6.8 * wStar * pblh * fZ * (1.0 - fZ);
                km[k] += enhancement * 0.5;
                kh[k] += enhancement * 1.3;
            }
        }

        for (int k = kPBL; k < nz - 1; k++) {
            double dzInterface = Math.max(1.0, col.zInterface[k] - col.zInterface[k + 1]);
            double kmInterp = 0.5 * (km[k] + km[k + 1]);
            double khInterp = 0.5 * (kh[k] + kh[k + 1]);
            double rhoKm = col.rho[k] * kmInterp / Math.max(1e-10, dzInterface);
            double rhoKh = col.rho[k] * khInterp / Math.max(1e-10, dzInterface);

            double dU = col.u[k] - col.u[k + 1];
            double dV = col.v[k] - col.v[k + 1];
            double dT = col.tFull[k] - col.tFull[k + 1];
            double dQ = col.qv[k] - col.qv[k + 1];

            col.uTend[k] += -rhoKm * dU / Math.max(1e-10, col.rho[k] * dzInterface);
            col.uTend[k + 1] += rhoKm * dU / Math.max(1e-10, col.rho[k + 1] * dzInterface);
            col.vTend[k] += -rhoKm * dV / Math.max(1e-10, col.rho[k] * dzInterface);
            col.vTend[k + 1] += rhoKm * dV / Math.max(1e-10, col.rho[k + 1] * dzInterface);
            col.tTend[k] += -rhoKh * dT / Math.max(1e-10, col.rho[k] * dzInterface);
            col.tTend[k + 1] += rhoKh * dT / Math.max(1e-10, col.rho[k + 1] * dzInterface);
            col.qvTend[k] += -rhoKh * dQ / Math.max(1e-10, col.rho[k] * dzInterface);
            col.qvTend[k + 1] += rhoKh * dQ / Math.max(1e-10, col.rho[k + 1] * dzInterface);
        }
    }

    private void computeExplicitMixing(ColumnData col, int kPBL, double wStar, double pblh, double dt) {
        if (wStar < 0.1) return;
        double coef = 0.1;
        for (int k = kPBL; k < nz - 1; k++) {
            double fZ = col.zFull[k] / Math.max(1e-5, pblh);
            if (fZ > 1.0) continue;
            double mixRate = coef * wStar * fZ * (1.0 - fZ) /
                    Math.max(1.0, (col.zFull[k] - col.zFull[k + 1]));
            mixRate = Math.min(mixRate, 0.5 / Math.max(1e-10, dt));

            for (int var = 0; var < 6; var++) {
                double[] vTop = new double[][]{
                        col.tTend, col.qvTend, col.uTend, col.vTend, col.qcTend, col.qiTend
                }[var];
                double[] vBot = new double[][]{
                        col.tTend, col.qvTend, col.uTend, col.vTend, col.qcTend, col.qiTend
                }[var];
                double[] field = new double[][]{
                        col.tFull, col.qv, col.u, col.v, col.qc, col.qi
                }[var];
                double delta = field[k] - field[k + 1];
                vTop[k] += -mixRate * delta;
                vBot[k + 1] += mixRate * delta;
            }
        }
    }

    private void computeNonlocalFluxes(ColumnData col, int kPBL, double wStar, double pblh,
                                        double hfx, double dt) {
        if (wStar < 0.1 || hfx <= 0) return;
        double coef = 2.5;
        double counterGradient = 0.85;
        double[] fluxProfile = new double[nz];
        double totalFlux = hfx;
        for (int k = kPBL; k < nz; k++) {
            double fZ = col.zFull[k] / Math.max(1e-5, pblh);
            if (fZ <= 1.0) {
                fluxProfile[k] = totalFlux * (1.0 - fZ * fZ);
            }
        }
        for (int k = kPBL; k < nz - 1; k++) {
            double fZ = col.zInterface[k] / Math.max(1e-5, pblh);
            if (fZ > 1.0) continue;
            double gradTerm = (col.tFull[k] - col.tFull[k + 1]) /
                    Math.max(1e-5, col.zFull[k] - col.zFull[k + 1]);
            double gamma = counterGradient * hfx / (col.rho[k] * PhysicsConstants.CP_DRY_AIR *
                    Math.max(1e-5, wStar * pblh));
            double totalGrad = gradTerm + gamma;
            double flux = -col.rho[k] * PhysicsConstants.CP_DRY_AIR * coef * wStar * pblh * totalGrad;
            flux = Math.max(-2.0 * totalFlux, Math.min(2.0 * totalFlux, flux));
            double dz = Math.max(1e-5, col.zInterface[k] - col.zInterface[k + 1]);
            col.tTend[k] += -flux / (col.rho[k] * PhysicsConstants.CP_DRY_AIR * dz);
            col.tTend[k + 1] += flux / (col.rho[k + 1] * PhysicsConstants.CP_DRY_AIR * dz);
        }
    }

    private void compute10MeterFields(ColumnData col, double uStar, double tStar, double qStar,
                                       double z0m, double z0h) {
        int kBot = nz - 1;
        double uMag = Math.max(0.1, Math.sqrt(col.u[kBot] * col.u[kBot] + col.v[kBot] * col.v[kBot]));
        double logFactor = KARMAN * Math.log(10.0 / Math.max(1e-5, z0m));
        col.u10 = col.u[kBot] * (1.0 - logFactor * uStar / uMag);
        col.v10 = col.v[kBot] * (1.0 - logFactor * uStar / uMag);
        col.t2 = col.tFull[kBot] + tStar / 0.74 * Math.log(2.0 / Math.max(1e-5, z0h)) / KARMAN;
        col.q2 = Math.max(0.0, col.qv[kBot] + qStar / 0.74 *
                Math.log(2.0 / Math.max(1e-5, z0h)) / KARMAN);
    }

    @Override
    public void cleanup() {}
}
