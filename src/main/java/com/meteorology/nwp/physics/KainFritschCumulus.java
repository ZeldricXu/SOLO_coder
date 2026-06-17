package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.DynamicsState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KainFritschCumulus implements PhysicsScheme {
    private static final Logger logger = LoggerFactory.getLogger(KainFritschCumulus.class);
    private NWPConfig config;
    private GridDefinition grid;
    private int nz;
    private double dt;
    private double triggerThreshold;
    private double downdraftFrac;

    @Override
    public String getName() { return "Kain-Fritsch"; }

    @Override
    public PhysicsType getType() { return PhysicsType.CUMULUS; }

    @Override
    public void initialize(NWPConfig config, GridDefinition grid) {
        this.config = config;
        this.grid = grid;
        this.nz = grid.getNZ();
        this.dt = config.getTimeStep();
        this.triggerThreshold = 0.5;
        this.downdraftFrac = 0.3;
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
        double[] cape = new double[1];
        double[] cin = new double[1];
        int[] lclIndex = new int[1];
        int[] lfcIndex = new int[1];
        int[] elIndex = new int[1];
        lclIndex[0] = nz - 1;
        lfcIndex[0] = nz - 1;

        computeCAPE(col, cape, cin, lclIndex, lfcIndex, elIndex);
        col.cape = cape[0];
        col.cin = cin[0];
        col.lcl = col.zFull[lclIndex[0]];
        col.lfc = col.zFull[lfcIndex[0]];
        col.el = col.zFull[elIndex[0]];

        double trigger = triggerFunction(col, lfcIndex[0], cape[0]);
        if (trigger < triggerThreshold || cape[0] < 50.0) return;

        double convectiveMassFlux = computeMassFlux(col, cape[0], trigger);
        convectiveMassFlux = Math.min(convectiveMassFlux, 1.0);
        int kBase = Math.max(2, lfcIndex[0] - 1);
        int kTop = Math.min(nz - 2, elIndex[0] + 1);

        computeUpdraftTendencies(col, kBase, kTop, convectiveMassFlux);
        computeDowndraftTendencies(col, kBase, kTop, convectiveMassFlux);
        computeDetrainment(col, kBase, kTop, convectiveMassFlux);
        col.pblh = Math.max(col.pblh, col.zFull[kBase]);
    }

    private void computeCAPE(ColumnData col, double[] capeOut, double[] cinOut,
                               int[] lclOut, int[] lfcOut, int[] elOut) {
        double cape = 0.0;
        double cin = 0.0;
        int lcl = nz - 1;
        int lfc = nz - 1;
        int el = 0;
        boolean foundLCL = false;
        boolean foundLFC = false;
        boolean positiveBuoyancy = false;

        double t0 = col.tFull[nz - 1];
        double q0 = col.qv[nz - 1];
        double p0 = col.pFull[nz - 1];

        for (int k = nz - 2; k >= 0; k--) {
            double pk = col.pFull[k];
            double[] tParLift = liftParcelSaturated(t0, q0, p0, pk);
            double buoy = PhysicsConstants.GRAVITY * (tParLift[0] - col.tFull[k]) / col.tFull[k];
            col.buoyancy[k] = buoy;
            if (!foundLCL && buoy > 0) {
                lcl = k;
                foundLCL = true;
            }
            if (foundLCL && !foundLFC && buoy > 0) {
                lfc = k;
                foundLFC = true;
            }
            if (foundLFC) {
                if (buoy > 0) {
                    cape += buoy * (col.zInterface[k] - col.zInterface[k + 1]);
                    el = k;
                    positiveBuoyancy = true;
                } else if (positiveBuoyancy) {
                    break;
                } else {
                    cin += -buoy * (col.zInterface[k + 1] - col.zInterface[k]);
                }
            } else if (foundLCL) {
                cin += -buoy * (col.zInterface[k + 1] - col.zInterface[k]);
            }
        }
        capeOut[0] = Math.max(0.0, cape);
        cinOut[0] = cin;
        lclOut[0] = lcl;
        lfcOut[0] = lfc;
        elOut[0] = Math.max(0, el);
    }

    private double[] liftParcelSaturated(double t0, double q0, double p0, double p) {
        double[] result = new double[2];
        double Lv = PhysicsConstants.LATENT_HEAT_VAPORIZATION;
        double cp = PhysicsConstants.CP_DRY_AIR;
        double kappa = PhysicsConstants.P0_EXPONENT;
        double theta = t0 * Math.pow(PhysicsConstants.REFERENCE_PRESSURE / p0, kappa);
        double thetaE = theta * Math.exp(Lv * q0 / (cp * t0));
        double tGuess = theta * Math.pow(p / PhysicsConstants.REFERENCE_PRESSURE, kappa);
        for (int iter = 0; iter < 15; iter++) {
            double ws = PhysicsConstants.saturationMixingRatio(tGuess, p);
            double exponent = Lv * ws / (cp * tGuess);
            exponent = Math.min(exponent, 10.0);
            double denom = Math.exp(exponent);
            double tNew = thetaE * Math.pow(p / PhysicsConstants.REFERENCE_PRESSURE, kappa)
                    / Math.max(1e-10, denom);
            if (Math.abs(tNew - tGuess) < 0.005) break;
            tGuess = 0.7 * tGuess + 0.3 * tNew;
        }
        result[0] = tGuess;
        result[1] = PhysicsConstants.saturationMixingRatio(tGuess, p);
        return result;
    }

    private double triggerFunction(ColumnData col, int lfc, double cape) {
        double trigger = 0.0;
        if (lfc < nz - 1) {
            double wup = 0.5 * (col.buoyancy[lfc] + col.buoyancy[Math.max(0, lfc - 1)]);
            trigger = 1.0 - Math.exp(-cape / 500.0);
        }
        double lowStability = col.thetaE[nz - 1] - col.thetaE[nz - 2];
        trigger *= 1.0 / (1.0 + Math.exp(-lowStability / 5.0));
        double rhLow = 0.0;
        for (int k = Math.max(0, nz - 5); k < nz; k++) {
            double ws = PhysicsConstants.saturationMixingRatio(col.tFull[k], col.pFull[k]);
            rhLow += Math.min(1.0, col.qv[k] / Math.max(1e-10, ws));
        }
        rhLow /= 5.0;
        trigger *= rhLow;
        return Math.min(1.0, trigger);
    }

    private double computeMassFlux(ColumnData col, double cape, double trigger) {
        double wMax = 0.1 * trigger * (1.0 - Math.exp(-cape / 1000.0));
        double wFrac = 0.0;
        for (int k = 0; k < nz; k++) {
            double ws = PhysicsConstants.saturationMixingRatio(col.tFull[k], col.pFull[k]);
            double rh = Math.min(1.0, col.qv[k] / Math.max(1e-10, ws));
            wFrac += rh;
        }
        return wMax * (0.5 + 0.5 * wFrac / nz);
    }

    private void computeUpdraftTendencies(ColumnData col, int kBase, int kTop, double mFlux) {
        double Lv = PhysicsConstants.LATENT_HEAT_VAPORIZATION;
        double cp = PhysicsConstants.CP_DRY_AIR;
        for (int k = kBase; k <= kTop; k++) {
            double dz = Math.max(1.0, col.zInterface[k] - col.zInterface[k + 1]);
            double entrain = 0.001 / Math.max(1.0, dz);
            int kBot = Math.min(k + 1, nz - 1);
            double[] tPar = liftParcelSaturated(
                    col.tFull[kBot],
                    col.qv[kBot],
                    col.pFull[kBot],
                    col.pFull[k]);
            double qvSat = PhysicsConstants.saturationMixingRatio(tPar[0], col.pFull[k]);
            double dqv = Math.max(0.0, qvSat - col.qv[k]);
            double dqc = dqv * 0.7;
            double heating = Lv * dqc / cp;
            col.tTend[k] += mFlux * (heating - entrain * (col.tFull[k] - tPar[0]));
            col.qvTend[k] += mFlux * (-dqc - entrain * Math.max(0.0, col.qv[k] - qvSat));
            col.qcTend[k] += mFlux * (dqc * 0.7 - entrain * col.qc[k]);
            col.qrTend[k] += mFlux * dqc * 0.3;
            double precip = mFlux * dqc * 0.3;
            col.precip += precip;
            col.tEnd[k] += -precip * Lv / (cp * col.rho[k]);
        }
    }

    private void computeDowndraftTendencies(ColumnData col, int kBase, int kTop, double mFlux) {
        double cp = PhysicsConstants.CP_DRY_AIR;
        double Lv = PhysicsConstants.LATENT_HEAT_VAPORIZATION;
        double downFlux = downdraftFrac * mFlux;
        for (int k = kTop; k >= kBase; k--) {
            double dz = Math.max(1.0, col.zInterface[k + 1] - col.zInterface[k]);
            double evapoRate = 1.0 - Math.exp(-dz / 500.0);
            double evap = Math.min(col.qr[k], evapoRate * downFlux);
            double cooling = -Lv * evap / (cp * col.rho[k]);
            col.tTend[k] += downFlux * cooling;
            col.qvTend[k] += downFlux * evap;
            col.qrTend[k] += -downFlux * evap;
        }
    }

    private void computeDetrainment(ColumnData col, int kBase, int kTop, double mFlux) {
        double detrain = 0.0005;
        for (int k = kBase; k <= kTop; k++) {
            double frac = (double)(k - kBase) / Math.max(1, kTop - kBase);
            double detrainK = detrain * (1.0 + 2.0 * frac * frac);
            col.qcTend[k] += -detrainK * col.qc[k];
            col.qiTend[k] += -detrainK * col.qi[k];
            col.cloudLiq[k] += detrainK * col.qc[k];
            col.cloudIce[k] += detrainK * col.qi[k];
        }
    }

    @Override
    public void cleanup() {}
}
