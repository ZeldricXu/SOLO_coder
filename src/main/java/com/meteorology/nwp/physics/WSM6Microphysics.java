package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.DynamicsState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WSM6Microphysics implements PhysicsScheme {
    private static final Logger logger = LoggerFactory.getLogger(WSM6Microphysics.class);
    private NWPConfig config;
    private GridDefinition grid;
    private int nz;
    private double dt;
    private static final double N0R = 8e6;
    private static final double N0S = 2e7;
    private static final double N0G = 4e6;
    private static final double RHO_W = PhysicsConstants.WATER_DENSITY;
    private static final double RHO_I = PhysicsConstants.ICE_DENSITY;
    private static final double G = PhysicsConstants.GRAVITY;

    @Override
    public String getName() { return "WSM6"; }

    @Override
    public PhysicsType getType() { return PhysicsType.MICROPHYSICS; }

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
        for (int k = 0; k < nz; k++) {
            double t = col.tFull[k];
            double p = col.pFull[k];
            double qv = Math.max(0.0, col.qv[k]);
            double qc = Math.max(0.0, col.qc[k]);
            double qr = Math.max(0.0, col.qr[k]);
            double qi = Math.max(0.0, col.qi[k]);
            double qs = Math.max(0.0, col.qs[k]);
            double qg = Math.max(0.0, col.qg[k]);
            double rho = col.rho[k];
            double dz = Math.max(10.0, col.zInterface[k] - col.zInterface[k + 1]);
            double qvs = PhysicsConstants.saturationMixingRatio(t, p);
            double dqv = qv - qvs;

            processCondensation(col, k, t, p, rho, dqv, dt);
            processEvaporation(col, k, t, p, rho, dz, dt);
            processCloudWaterToRain(col, k, t, rho, dz, dt);
            processRainCollection(col, k, t, rho, dz, dt);
            processIceNucleation(col, k, t, p, rho, dt);
            processIceDeposition(col, k, t, p, rho, dt);
            processSnowFormation(col, k, t, rho, dz, dt);
            processSnowCollection(col, k, t, rho, dz, dt);
            processSnowToRain(col, k, t, rho, dz, dt);
            processGraupelFormation(col, k, t, rho, dz, dt);
            processGraupelMelting(col, k, t, rho, dz, dt);
            computeSettling(col, k, rho, dz, dt);
            computeRadarReflectivity(col, k);
        }
        computeSurfacePrecip(col);
        checkCloudFraction(col);
    }

    private void processCondensation(ColumnData col, int k, double t, double p,
                                      double rho, double dqv, double dt) {
        if (dqv > 0) {
            double cond = Math.min(dqv, col.qv[k] * 0.9);
            double freezeFrac = t < PhysicsConstants.FREEZING_TEMP
                    ? Math.min(1.0, (PhysicsConstants.FREEZING_TEMP - t) / 40.0) : 0.0;
            double dqc = cond * (1.0 - freezeFrac);
            double dqi = cond * freezeFrac;
            double Lv = PhysicsConstants.LATENT_HEAT_VAPORIZATION;
            double Lf = PhysicsConstants.LATENT_HEAT_FUSION;
            double cp = PhysicsConstants.CP_DRY_AIR;
            col.qvTend[k] += -cond / dt;
            col.qcTend[k] += dqc / dt;
            col.qiTend[k] += dqi / dt;
            col.tTend[k] += (Lv * dqc + (Lv + Lf) * dqi) / (cp * dt);
            col.cloudLiq[k] += dqc;
            col.cloudIce[k] += dqi;
        }
    }

    private void processEvaporation(ColumnData col, int k, double t, double p,
                                     double rho, double dz, double dt) {
        if (col.qr[k] > 0 || col.qs[k] > 0 || col.qg[k] > 0) {
            double qvs = PhysicsConstants.saturationMixingRatio(t, p);
            double satDeficit = Math.max(0.0, qvs - col.qv[k]);
            if (satDeficit <= 0) return;
            double lambdaR = Math.pow(Math.PI * RHO_W * N0R / (rho * Math.max(1e-10, col.qr[k])), 0.25);
            double evapRate = Math.PI * RHO_W * G / PhysicsConstants.RATIO_GAS_CONSTANTS;
            evapRate *= Math.pow(Math.abs(satDeficit), 0.5) / (Math.pow(t, 0.375) * lambdaR);
            evapRate = Math.min(col.qr[k] / Math.max(1e-10, dt), evapRate * 1e-4);
            double evapR = Math.min(col.qr[k] * 0.5, evapRate * dt);
            if (t < PhysicsConstants.FREEZING_TEMP) {
                evapR *= Math.exp(-(PhysicsConstants.FREEZING_TEMP - t) / 3.0);
            }
            col.qrTend[k] += -evapR / dt;
            col.qvTend[k] += evapR / dt;
            col.tTend[k] += -PhysicsConstants.LATENT_HEAT_VAPORIZATION * evapR /
                    (PhysicsConstants.CP_DRY_AIR * dt);
        }
    }

    private void processCloudWaterToRain(ColumnData col, int k, double t, double rho,
                                          double dz, double dt) {
        if (col.qc[k] > 0 && t >= PhysicsConstants.FREEZING_TEMP - 5.0) {
            double qcThreshold = 0.5e-3;
            if (col.qc[k] > qcThreshold) {
                double autoConv = 1e-3 * Math.max(0.0, col.qc[k] - qcThreshold);
                double accel = 1.0;
                if (t < PhysicsConstants.FREEZING_TEMP) {
                    double frozenFrac = Math.pow((PhysicsConstants.FREEZING_TEMP - t) / 5.0, 2);
                    double toSnow = autoConv * frozenFrac;
                    col.qsTend[k] += toSnow / dt;
                    autoConv *= (1.0 - frozenFrac);
                }
                autoConv = Math.min(col.qc[k] * 0.5 / Math.max(1e-10, dt), autoConv / dt);
                col.qcTend[k] += -autoConv;
                col.qrTend[k] += autoConv;
            }
        }
    }

    private void processRainCollection(ColumnData col, int k, double t, double rho,
                                        double dz, double dt) {
        if (col.qr[k] > 0 && col.qc[k] > 0) {
            double qr = col.qr[k];
            double qc = col.qc[k];
            double terminalR = 3634.0 * Math.pow(Math.max(1e-10, rho * qr), 0.2);
            double accrEff = 0.55 * (1.0 - Math.exp(-Math.pow(rho * qc / 1e-3, 0.5)));
            double accr = accrEff * Math.PI * RHO_W * qr * qc * terminalR /
                    (6.0 * Math.pow(qr / Math.max(1e-10, rho), 0.25) * Math.sqrt(qr / Math.max(1e-10, rho)));
            accr = Math.min(qc / Math.max(1e-10, dt), accr);
            col.qcTend[k] += -accr;
            col.qrTend[k] += accr;
        }
    }

    private void processIceNucleation(ColumnData col, int k, double t, double p, double rho, double dt) {
        if (t < 268.15 && col.qv[k] > 0) {
            double deltaT = 268.15 - t;
            double Nice = Math.min(1e5, Math.pow(10, -0.6 + 0.0391 * deltaT));
            double qiNew = Nice * 1e-10 * Math.pow(Math.max(0, deltaT), 2);
            qiNew = Math.min(qiNew, col.qv[k] * 0.01);
            double Ls = PhysicsConstants.LATENT_HEAT_SUBLIMATION;
            col.qvTend[k] += -qiNew / dt;
            col.qiTend[k] += qiNew / dt;
            col.tTend[k] += Ls * qiNew / (PhysicsConstants.CP_DRY_AIR * dt);
        }
    }

    private void processIceDeposition(ColumnData col, int k, double t, double p, double rho, double dt) {
        if (col.qi[k] > 0 && t < PhysicsConstants.FREEZING_TEMP) {
            double qvsIce = PhysicsConstants.saturationMixingRatio(Math.min(t, PhysicsConstants.FREEZING_TEMP), p);
            double supersat = Math.max(0.0, col.qv[k] - qvsIce);
            double Ls = PhysicsConstants.LATENT_HEAT_SUBLIMATION;
            double depoRate = 0.01 * Math.max(0.0, supersat);
            depoRate = Math.min(depoRate / dt, col.qv[k] * 0.1 / Math.max(1e-10, dt));
            col.qvTend[k] += -depoRate;
            col.qiTend[k] += depoRate;
            col.tTend[k] += Ls * depoRate / PhysicsConstants.CP_DRY_AIR;
        }
    }

    private void processSnowFormation(ColumnData col, int k, double t, double rho,
                                       double dz, double dt) {
        if (col.qi[k] > 1e-6 && (col.qc[k] > 1e-6 || col.qv[k] > 1e-4)) {
            double aggEff = 0.1;
            double rimedQc = 0.0;
            if (col.qc[k] > 0 && t < PhysicsConstants.FREEZING_TEMP) {
                double vSnow = Math.sqrt(col.qi[k] * 1e3) * 0.5;
                double rimedFrac = 1.0 - Math.exp(-1e-4 * vSnow * rho * col.qc[k] * dt);
                rimedQc = col.qc[k] * rimedFrac;
                col.qcTend[k] += -rimedQc / dt;
            }
            double toSnow = 0.1 * col.qi[k] / dt + rimedQc / dt;
            toSnow = Math.min(toSnow, col.qi[k] / Math.max(1e-10, dt) * 0.5);
            col.qiTend[k] += -toSnow;
            col.qsTend[k] += toSnow;
        }
    }

    private void processSnowCollection(ColumnData col, int k, double t, double rho,
                                        double dz, double dt) {
        if (col.qs[k] > 0 && col.qc[k] > 0 && t < PhysicsConstants.FREEZING_TEMP + 2.0) {
            double vSnow = 1.5 * Math.pow(rho * col.qs[k] / Math.max(1e-10, RHO_I), 0.125);
            double coll = 0.5 * rho * Math.abs(vSnow) * col.qc[k] * col.qs[k];
            coll = Math.min(col.qc[k] / Math.max(1e-10, dt), coll);
            col.qcTend[k] += -coll;
            col.qsTend[k] += coll;
        }
        if (col.qs[k] > 0 && col.qr[k] > 0 && t < PhysicsConstants.FREEZING_TEMP) {
            double conv = Math.min(col.qr[k], col.qs[k] * 0.1);
            col.qrTend[k] += -conv / dt;
            col.qsTend[k] += conv / dt;
        }
    }

    private void processSnowToRain(ColumnData col, int k, double t, double rho,
                                    double dz, double dt) {
        if (col.qs[k] > 0 && t > PhysicsConstants.FREEZING_TEMP) {
            double deltaT = t - PhysicsConstants.FREEZING_TEMP;
            double meltRate = col.qs[k] * PhysicsConstants.CP_DRY_AIR * deltaT /
                    Math.max(1e-10, PhysicsConstants.LATENT_HEAT_FUSION);
            double qsToQr = Math.min(col.qs[k] / Math.max(1e-10, dt), meltRate / dt);
            col.qsTend[k] += -qsToQr;
            col.qrTend[k] += qsToQr;
            col.tTend[k] += -PhysicsConstants.LATENT_HEAT_FUSION * qsToQr / PhysicsConstants.CP_DRY_AIR;
        }
    }

    private void processGraupelFormation(ColumnData col, int k, double t, double rho,
                                          double dz, double dt) {
        if ((col.qs[k] > 0 || col.qi[k] > 0) && col.qr[k] > 1e-6 && t < PhysicsConstants.FREEZING_TEMP + 5.0) {
            double coldContent = Math.max(0.0, PhysicsConstants.FREEZING_TEMP - t);
            double qWet = col.qr[k] * 0.5 * (1.0 - Math.exp(-coldContent / 5.0));
            double availableIce = Math.min(col.qs[k] + col.qi[k], qWet);
            double toQg = Math.min(availableIce / Math.max(1e-10, dt),
                    (col.qs[k] + col.qi[k]) * 0.05 / Math.max(1e-10, dt));
            if (col.qs[k] > 0 && col.qi[k] > 0) {
                col.qsTend[k] += -toQg * col.qs[k] / (col.qs[k] + col.qi[k]);
                col.qiTend[k] += -toQg * col.qi[k] / (col.qs[k] + col.qi[k]);
            } else if (col.qs[k] > 0) {
                col.qsTend[k] += -toQg;
            } else {
                col.qiTend[k] += -toQg;
            }
            col.qgTend[k] += toQg;
            col.qrTend[k] += -qWet / Math.max(1e-10, dt) * 0.5;
        }
    }

    private void processGraupelMelting(ColumnData col, int k, double t, double rho,
                                        double dz, double dt) {
        if (col.qg[k] > 0 && t > PhysicsConstants.FREEZING_TEMP) {
            double deltaT = t - PhysicsConstants.FREEZING_TEMP;
            double melt = col.qg[k] * Math.min(1.0, 2.0 * deltaT / 5.0);
            melt = Math.min(col.qg[k] / Math.max(1e-10, dt), melt / dt);
            col.qgTend[k] += -melt;
            col.qrTend[k] += melt;
            col.tTend[k] += -PhysicsConstants.LATENT_HEAT_FUSION * melt / PhysicsConstants.CP_DRY_AIR;
        }
    }

    private void computeSettling(ColumnData col, int k, double rho, double dz, double dt) {
        double rhoAir = rho;
        if (col.qr[k] > 0 && k < nz - 1) {
            double vR = 3634.0 * Math.pow(Math.max(1e-10, rhoAir * col.qr[k]), 0.2) /
                    Math.pow(Math.max(1e-10, rhoAir), 0.5);
            double fluxDown = vR * col.qr[k] * rhoAir;
            double settled = Math.min(col.qr[k], fluxDown * dt / Math.max(1e-5, rhoAir * dz));
            col.qrTend[k] += -settled / dt;
            col.qrTend[k + 1] += settled / dt;
        }
        if (col.qs[k] > 0 && k < nz - 1) {
            double vS = 11.72 * Math.pow(Math.max(1e-10, rhoAir * col.qs[k]) / RHO_I, 0.125) /
                    Math.pow(Math.max(1e-10, rhoAir / 1.225), 0.5);
            double fluxS = Math.abs(vS) * col.qs[k] * rhoAir;
            double settledS = Math.min(col.qs[k], fluxS * dt / Math.max(1e-5, rhoAir * dz));
            col.qsTend[k] += -settledS / dt;
            col.qsTend[k + 1] += settledS / dt;
        }
        if (col.qg[k] > 0 && k < nz - 1) {
            double vG = 20.0;
            double fluxG = vG * col.qg[k] * rhoAir;
            double settledG = Math.min(col.qg[k], fluxG * dt / Math.max(1e-5, rhoAir * dz));
            col.qgTend[k] += -settledG / dt;
            col.qgTend[k + 1] += settledG / dt;
        }
    }

    private void computeRadarReflectivity(ColumnData col, int k) {
        double reflectivity = 0.0;
        if (col.qr[k] > 0) {
            double zR = 720.0 * Math.pow(col.qr[k] * col.rho[k] * 1e3, 1.75);
            reflectivity += zR;
        }
        if (col.qs[k] > 0) {
            double zS = 300.0 * Math.pow(col.qs[k] * col.rho[k] * 1e3, 1.66);
            reflectivity += zS;
        }
        if (col.qg[k] > 0) {
            double zG = 500.0 * Math.pow(col.qg[k] * col.rho[k] * 1e3, 1.8);
            reflectivity += zG;
        }
        col.rain[k] = Math.max(0.0, Math.log10(Math.max(1e-10, reflectivity)));
    }

    private void computeSurfacePrecip(ColumnData col) {
        col.precip = 0.0;
        int kSfc = nz - 1;
        double rhoSfc = col.rho[kSfc];
        double totalLiquid = (col.qr[kSfc] + col.qg[kSfc]) * rhoSfc;
        double solidPrecip = col.qs[kSfc] * rhoSfc;
        col.precip = (totalLiquid + solidPrecip * 0.1) * 3600.0;
    }

    private void checkCloudFraction(ColumnData col) {
        int pblIdx = Math.min(nz - 2, Math.max(0, col.findLevelFromZ(Math.max(100.0, col.pblh))));
        double totalCloud = 0.0;
        int cloudLevels = 0;
        for (int k = 0; k < nz; k++) {
            double rh = Math.min(1.0, col.qv[k] / Math.max(1e-10,
                    PhysicsConstants.saturationMixingRatio(col.tFull[k], col.pFull[k])));
            double cld = col.cloudLiq[k] + col.cloudIce[k];
            double cloudFracK = 0.0;
            if (cld > 1e-7) {
                cloudFracK = Math.min(1.0, cld / 1e-4);
            } else if (rh > 0.95) {
                cloudFracK = Math.pow(rh, 10.0);
            }
            if (k >= pblIdx && rh > 0.9) {
                cloudFracK = Math.max(cloudFracK, 0.3 * (rh - 0.9) / 0.1);
            }
            if (cloudFracK > 0.01) {
                totalCloud += cloudFracK;
                cloudLevels++;
            }
        }
        col.cloudFrac = cloudLevels > 0 ? Math.min(1.0, totalCloud / Math.max(1.0, cloudLevels)) : 0.0;
    }

    @Override
    public void cleanup() {}
}
