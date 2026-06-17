package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import java.io.Serializable;
import java.util.Arrays;

public class ColumnData implements Serializable {
    public int i, j;
    public double lat, lon;
    public int nz;
    public double[] sigmaTop;
    public double sigmaBot;
    public double[] pFull;
    public double[] pInterface;
    public double[] zFull;
    public double[] zInterface;
    public double[] tFull;
    public double[] tEnd;
    public double[] tSurf;
    public double[] qv;
    public double[] qc;
    public double[] qr;
    public double[] qi;
    public double[] qs;
    public double[] qg;
    public double[] u;
    public double[] v;
    public double[] rho;
    public double[] exner;
    public double[] thetaV;
    public double[] buoyancy;
    public double[] tTend;
    public double[] qvTend;
    public double[] qcTend;
    public double[] qrTend;
    public double[] qiTend;
    public double[] qsTend;
    public double[] qgTend;
    public double[] uTend;
    public double[] vTend;
    public double psfc;
    public double t2;
    public double q2;
    public double u10;
    public double v10;
    public double hfx;
    public double lh;
    public double swdown;
    public double lwdown;
    public double swup;
    public double lwup;
    public double pblh;
    public double cloudFrac;
    public double precip;
    public double tke;
    public double[] wstar;
    public double zlcl;
    public double zLfc;
    public double cape;
    public double cin;
    public double lcl;
    public double lfc;
    public double el;
    public double precipAccum;
    public double[] cloudLiq;
    public double[] cloudIce;
    public double[] rain;
    public double[] wMix;
    public double[] thetaE;

    public ColumnData(int nz) {
        this.nz = nz;
        this.pFull = new double[nz];
        this.pInterface = new double[nz + 1];
        this.zFull = new double[nz];
        this.zInterface = new double[nz + 1];
        this.tFull = new double[nz];
        this.tEnd = new double[nz];
        this.qv = new double[nz];
        this.qc = new double[nz];
        this.qr = new double[nz];
        this.qi = new double[nz];
        this.qs = new double[nz];
        this.qg = new double[nz];
        this.u = new double[nz];
        this.v = new double[nz];
        this.rho = new double[nz];
        this.exner = new double[nz];
        this.thetaV = new double[nz];
        this.buoyancy = new double[nz];
        this.tTend = new double[nz];
        this.qvTend = new double[nz];
        this.qcTend = new double[nz];
        this.qrTend = new double[nz];
        this.qiTend = new double[nz];
        this.qsTend = new double[nz];
        this.qgTend = new double[nz];
        this.uTend = new double[nz];
        this.vTend = new double[nz];
        this.cloudLiq = new double[nz];
        this.cloudIce = new double[nz];
        this.rain = new double[nz];
        this.wMix = new double[nz];
        this.thetaE = new double[nz];
    }

    public void extract(ModelState state, int i, int j, GridDefinition grid) {
        this.i = i;
        this.j = j;
        this.lat = grid.getLat(j);
        this.lon = grid.getLon(i);
        double[] sigma = grid.getSigmaLevels();
        double[] sigmaInt = computeSigmaInterfaces(grid);
        double psfc = state.hasField(VariableType.PSFC) ? state.getField(VariableType.PSFC).get(i, j) : PhysicsConstants.REFERENCE_PRESSURE;
        this.psfc = psfc;
        double[] p0 = PhysicsConstants.REFERENCE_PRESSURE;
        double rd = PhysicsConstants.GAS_CONSTANT_DRY_AIR;
        double cp = PhysicsConstants.CP_DRY_AIR;
        double g = PhysicsConstants.GRAVITY;

        DataField T = state.getField(VariableType.T);
        DataField U = state.getField(VariableType.U);
        DataField V = state.getField(VariableType.V);
        DataField QV = state.getField(VariableType.QV);
        DataField QC = state.getField(VariableType.QC);
        DataField QR = state.getField(VariableType.QR);
        DataField QI = state.getField(VariableType.QI);
        DataField QS = state.getField(VariableType.QS);
        DataField QG = state.getField(VariableType.QG);

        double zSum = 0.0;
        for (int k = nz - 1; k >= 0; k--) {
            double pf = sigma[k] * psfc;
            double pi = sigmaInt[k + 1] * psfc;
            pFull[k] = pf;
            pInterface[k + 1] = pi;
            double tv = T != null ? T.get(i, j, k) : 288.0;
            tFull[k] = tv;
            double qvk = QV != null ? Math.max(0.0, QV.get(i, j, k)) : 0.0;
            qv[k] = qvk;
            double tvv = tv * (1.0 + (1.0 / PhysicsConstants.RATIO_GAS_CONSTANTS - 1.0) * qvk);
            thetaV[k] = tvv * Math.pow(p0 / pf, rd / cp);
            if (k == nz - 1) {
                zInterface[k + 1] = 0.0;
            }
            if (k < nz - 1) {
                double dp = (sigma[k + 1] - sigma[k]) * psfc;
                double rhom = 0.5 * (pf + pInterface[k + 1]);
                double rhoAvg = rhom / (rd * 0.5 * (thetaV[k] + (k < nz - 1 ? thetaV[k + 1] : thetaV[k]) * Math.pow(rhom / p0, -rd / cp));
                zInterface[k] = zInterface[k + 1] - rd * 0.5 * (thetaV[k] + (k < nz - 1 ? thetaV[k + 1] : thetaV[k]) * Math.pow(rhom / p0, -rd / cp) * dp / g;
            }
            rho[k] = pf / (rd * tv);
            u[k] = U != null ? U.get(i, j, k) : 0.0;
            v[k] = V != null ? V.get(i, j, k) : 0.0;
            qc[k] = QC != null ? Math.max(0.0, QC.get(i, j, k)) : 0.0;
            qr[k] = QR != null ? Math.max(0.0, QR.get(i, j, k)) : 0.0;
            qi[k] = QI != null ? QI.get(i, j, k) : 0.0;
            qs[k] = QS != null ? Math.max(0.0, QS.get(i, j, k)) : 0.0;
            qg[k] = QG != null ? Math.max(0.0, QG.get(i, j, k)) : 0.0;
            exner[k] = Math.pow(pf / p0, rd / cp);
            buoyancy[k] = g * (thetaV[k] - thetaV[nz - 1]) / thetaV[nz - 1];
            thetaE[k] = PhysicsConstants.equivalentPotentialTemperature(tFull[k], pFull[k], qv[k]);
        }
        this.zFull[nz - 1] = 0.0;
        for (int k = 0; k < nz - 1; k++) {
            zFull[k] = 0.5 * (zInterface[k] + zInterface[k + 1]);
        }
        for (int k = 0; k < nz; k++) {
            tEnd[k] = tFull[k];
        }
    }

    private double[] computeSigmaInterfaces(GridDefinition grid) {
        double[] s = grid.getSigmaLevels();
        double[] sint = new double[nz + 1];
        sint[0] = 0.0;
        for (int k = 0; k < nz; k++) {
            if (k > 0) {
                sint[k] = 0.5 * (s[k - 1] + s[k]);
            }
        }
        sint[nz] = 1.0;
        return sint;
    }

    public void commit(ModelState state, DynamicsState tend, double dt) {
        DataField TTend = tend != null ? tend.getTendency(VariableType.T) : null;
        DataField QVTend = tend != null ? tend.getTendency(VariableType.QV) : null;
        DataField QCTend = tend != null ? tend.getTendency(VariableType.QC) : null;
        DataField QRTend = tend != null ? tend.getTendency(VariableType.QR) : null;
        DataField QITend = tend != null ? tend.getTendency(VariableType.QI) : null;
        DataField QSTend = tend != null ? tend.getTendency(VariableType.QS) : null;
        DataField QGTend = tend != null ? tend.getTendency(VariableType.QG) : null;
        DataField UTend = tend != null ? tend.getTendency(VariableType.U) : null;
        DataField VTend = tend != null ? tend.getTendency(VariableType.V) : null;

        for (int k = 0; k < nz; k++) {
            if (TTend != null) TTend.add(i, j, k, tTend[k]);
            if (QVTend != null) QVTend.add(i, j, k, qvTend[k]);
            if (QCTend != null) QCTend.add(i, j, k, qcTend[k]);
            if (QRTend != null) QRTend.add(i, j, k, qrTend[k]);
            if (QITend != null) QITend.add(i, j, k, qiTend[k]);
            if (QSTend != null) QSTend.add(i, j, k, qsTend[k]);
            if (QGTend != null) QGTend.add(i, j, k, qgTend[k]);
            if (UTend != null) UTend.add(i, j, k, uTend[k]);
            if (VTend != null) VTend.add(i, j, k, vTend[k]);

            if (state.getField(VariableType.T) != null) {
                state.getField(VariableType.T).set(i, j, k, tEnd[k]);
            }
        }

        if (state.hasField(VariableType.PRECIP)) {
            state.getField(VariableType.PRECIP).add(i, j, precip);
        }
        if (state.hasField(VariableType.PBLH)) {
            state.getField(VariableType.PBLH).set(i, j, pblh);
        }
        if (state.hasField(VariableType.HFX)) {
            state.getField(VariableType.HFX).set(i, j, hfx);
        }
        if (state.hasField(VariableType.LH)) {
            state.getField(VariableType.LH).set(i, j, lh);
        }
        if (state.hasField(VariableType.SWDOWN)) {
            state.getField(VariableType.SWDOWN).set(i, j, swdown);
        }
        if (state.hasField(VariableType.LWDOWN)) {
            state.getField(VariableType.LWDOWN).set(i, j, lwdown);
        }
        if (state.hasField(VariableType.T2)) {
            state.getField(VariableType.T2).set(i, j, t2);
        }
        if (state.hasField(VariableType.Q2)) {
            state.getField(VariableType.Q2).set(i, j, q2);
        }
        if (state.hasField(VariableType.U10)) {
            state.getField(VariableType.U10).set(i, j, u10);
        }
        if (state.hasField(VariableType.V10)) {
            state.getField(VariableType.V10).set(i, j, v10);
        }
        if (state.hasField(VariableType.CLDFRA)) {
            state.getField(VariableType.CLDFRA).set(i, j, cloudFrac);
        }
    }

    public void resetTendencies() {
        Arrays.fill(tTend, 0.0);
        Arrays.fill(qvTend, 0.0);
        Arrays.fill(qcTend, 0.0);
        Arrays.fill(qrTend, 0.0);
        Arrays.fill(qiTend, 0.0);
        Arrays.fill(qsTend, 0.0);
        Arrays.fill(qgTend, 0.0);
        Arrays.fill(uTend, 0.0);
        Arrays.fill(vTend, 0.0);
        Arrays.fill(cloudLiq, 0.0);
        Arrays.fill(cloudIce, 0.0);
        precip = 0.0;
        precipAccum = 0.0;
    }

    public int findLevelFromZ(double zTarget) {
        for (int k = 0; k < nz - 1; k++) {
            if ((zFull[k] >= zTarget) return k;
        }
        return nz - 1;
    }

    public double interpolate(double z, double[] field) {
        if (z <= zFull[nz - 1]) return field[nz - 1];
        if (z >= zFull[0]) return field[0];
        for (int k = 0; k < nz - 1; k++) {
            if (zFull[k] <= z && zFull[k + 1] <= z) {
                double frac = (z - zFull[k + 1]) / (zFull[k] - zFull[k + 1]);
                return field[k + 1] + frac * (field[k] - field[k + 1]);
            }
        }
        return field[0];
    }
}
