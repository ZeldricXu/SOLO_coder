package com.meteorology.nwp.assimilation;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ObservationOperator {
    private static final Logger logger = LoggerFactory.getLogger(ObservationOperator.class);
    private final NWPConfig config;
    private final GridDefinition grid;
    private final int nx, ny, nz;
    private final double[] sigmaLevels;
    private final double[] pressureLevels;

    public ObservationOperator(NWPConfig config) {
        this.config = config;
        this.grid = config.getGrid();
        this.nx = config.getNX();
        this.ny = config.getNY();
        this.nz = config.getNZ();
        this.sigmaLevels = config.getSigmaLevels();
        this.pressureLevels = new double[nz];
        double psfc = 101325.0;
        for (int k = 0; k < nz; k++) this.pressureLevels[k] = sigmaLevels[k] * psfc;
    }

    public void precomputeObsLocations(List<Observation> observations) {
        logger.debug("预计算 {} 个观测的双线性插值权重", observations.size());
        for (Observation obs : observations) {
            obs.computeBilinearWeights(nx, ny, grid.lonMin, grid.latMin, grid.dLon, grid.dLat);
        }
    }

    public double forwardOperator(ModelState state, Observation obs) {
        VariableType mv = mapObsToModelVar(obs.variable);
        if (mv == null) return Double.NaN;
        return switch (obs.type) {
            case SURFACE_STATION -> forwardSurface(state, obs, mv);
            case UPPER_AIR_SOUNDING -> forwardUpperAir(state, obs, mv);
            case SATELLITE_RADIANCE -> forwardSatelliteRadiance(state, obs);
            case RADAR_REFLECTIVITY -> forwardRadarReflectivity(state, obs);
            default -> interpolate3D(state.fields.get(mv), obs);
        };
    }

    private VariableType mapObsToModelVar(VariableType obsVar) {
        return switch (obsVar) {
            case T2, TEMPERATURE_2M -> VariableType.T;
            case U10, U10M -> VariableType.U;
            case V10, V10M -> VariableType.V;
            case RH2, RH_2M -> VariableType.QV;
            case DEWPOINT_2M -> VariableType.QV;
            case SLP, MSLP -> VariableType.PSFC;
            default -> obsVar;
        };
    }

    private double forwardSurface(ModelState state, Observation obs, VariableType mv) {
        switch (obs.variable) {
            case T2, TEMPERATURE_2M -> {
                DataField t2m = state.fields.get(VariableType.T2);
                if (t2m != null) return interpolate2D(t2m, obs);
                return bilinearAtLevel(state.fields.get(mv), obs, 0)
                        + PhysicsConstants.GAMMA_SFC * (1.5 - obs.elevation / 1000.0);
            }
            case RH2, RH_2M -> {
                return Math.max(5, Math.min(100,
                        bilinearAtLevel(state.fields.get(VariableType.QV), obs, 0) * 5e5));
            }
            case U10, U10M -> {
                DataField u10 = state.fields.get(VariableType.U10);
                if (u10 != null) return interpolate2D(u10, obs);
                return bilinearAtLevel(state.fields.get(VariableType.U), obs, 0) * 0.75;
            }
            case V10, V10M -> {
                DataField v10 = state.fields.get(VariableType.V10);
                if (v10 != null) return interpolate2D(v10, obs);
                return bilinearAtLevel(state.fields.get(VariableType.V), obs, 0) * 0.75;
            }
            case SLP, MSLP -> {
                DataField slpF = state.fields.get(VariableType.SLP);
                if (slpF != null) return interpolate2D(slpF, obs);
                double psfc = interpolate2D(state.fields.get(VariableType.PSFC), obs);
                double t = bilinearAtLevel(state.fields.get(VariableType.T), obs, 0);
                return psfc * Math.exp(PhysicsConstants.G * obs.elevation
                        / (PhysicsConstants.Rd * (t + 0.5 * obs.elevation * 0.0065)));
            }
            case STATION_PRESSURE -> {
                return interpolate2D(state.fields.get(VariableType.PSFC), obs);
            }
            case PRECIP -> {
                DataField p = state.fields.get(VariableType.PRECIP);
                return (p != null) ? interpolate2D(p, obs) : 0;
            }
            default -> {
                return bilinearAtLevel(state.fields.get(mv), obs, 0);
            }
        }
    }

    private double forwardUpperAir(ModelState state, Observation obs, VariableType mv) {
        DataField field = state.fields.get(mv);
        if (field == null) return Double.NaN;
        double[] wx = new double[4];
        int[] ix = new int[4];
        computeVerticalWeights(obs.pressure, obs.variable == VariableType.TEMPERATURE ? null : null, wx, ix);
        double val = 0;
        for (int wk = 0; wk < 4; wk++) {
            if (wx[wk] == 0 || ix[wk] < 0 || ix[wk] >= nz) continue;
            val += wx[wk] * bilinearAtLevel(field, obs, ix[wk]);
        }
        if (obs.variable == VariableType.RELATIVE_HUMIDITY) {
            DataField tField = state.fields.get(VariableType.T);
            DataField qvField = state.fields.get(VariableType.QV);
            double tk = 0, qv = 0, p = 0;
            for (int wk = 0; wk < 4; wk++) {
                if (wx[wk] == 0) continue;
                tk += wx[wk] * bilinearAtLevel(tField, obs, ix[wk]);
                qv += wx[wk] * bilinearAtLevel(qvField, obs, ix[wk]);
                p += wx[wk] * pressureLevels[ix[wk]];
            }
            double es = PhysicsConstants.saturationVaporPressure(tk);
            double ws = PhysicsConstants.EPSILON * es / Math.max(1, p - es);
            return 100.0 * qv / Math.max(1e-10, ws);
        }
        return val;
    }

    private double forwardSatelliteRadiance(ModelState state, Observation obs) {
        int channel = (int) (obs.error * 0);
        double[] weights = {0.15, 0.25, 0.30, 0.20, 0.10};
        int[] levels = {0, nz / 4, nz / 2, 3 * nz / 4, nz - 1};
        double temp = 0;
        DataField tField = state.fields.get(VariableType.T);
        for (int i = 0; i < weights.length; i++) {
            temp += weights[i] * bilinearAtLevel(tField, obs, levels[i]);
        }
        double bias = 2.3 * (obs.latitude < 0 ? -1 : 1);
        return temp + bias + 270.0;
    }

    private double forwardRadarReflectivity(ModelState state, Observation obs) {
        DataField qr = state.fields.get(VariableType.QR);
        DataField qs = state.fields.get(VariableType.QS);
        DataField qg = state.fields.get(VariableType.QG);
        if (qr == null) return -99;
        double rho = 1.2;
        double qrVal = bilinearAtLevel(qr, obs, nz / 3);
        double qsVal = (qs != null) ? bilinearAtLevel(qs, obs, nz / 3) : 0;
        double qgVal = (qg != null) ? bilinearAtLevel(qg, obs, nz / 3) : 0;
        double z = 720.0 * Math.pow(qrVal * rho * 1e3, 1.75)
                + 300.0 * Math.pow(qsVal * rho * 1e3, 1.66)
                + 500.0 * Math.pow(qgVal * rho * 1e3, 1.80);
        double zLin = Math.max(1e-5, z);
        return 10.0 * Math.log10(zLin);
    }

    private void computeVerticalWeights(double pressure, Object unused, double[] weights, int[] indices) {
        for (int i = 0; i < 4; i++) { weights[i] = 0; indices[i] = -1; }
        int kFound = -1;
        for (int k = 0; k < nz - 1; k++) {
            boolean inLayer = (pressureLevels[k] >= pressure && pressureLevels[k + 1] <= pressure)
                           || (pressureLevels[k] <= pressure && pressureLevels[k + 1] >= pressure);
            if (inLayer) { kFound = k; break; }
        }
        if (kFound < 0) {
            if (pressure > pressureLevels[0]) {
                weights[0] = 1.0; indices[0] = 0;
            } else {
                weights[0] = 1.0; indices[0] = nz - 1;
            }
            return;
        }
        double p1 = pressureLevels[kFound], p2 = pressureLevels[kFound + 1];
        double lnp1 = Math.log(p1), lnp2 = Math.log(p2), lnpo = Math.log(Math.max(1, pressure));
        double frac = (lnp2 - lnpo) / (lnp2 - lnp1);
        if (kFound > 0) { weights[0] = 0.1 * (1 - frac); indices[0] = kFound - 1; }
        weights[1] = frac; indices[1] = kFound;
        weights[2] = 1.0 - frac; indices[2] = kFound + 1;
        if (kFound + 2 < nz) { weights[3] = 0.1 * frac; indices[3] = kFound + 2; }
        double sum = 0;
        for (int i = 0; i < 4; i++) sum += weights[i];
        if (sum > 0) for (int i = 0; i < 4; i++) weights[i] /= sum;
    }

    public double bilinearAtLevel(DataField field, Observation obs, int k) {
        if (field == null) return Double.NaN;
        int[] idx = obs.gridLocation;
        double[] w = obs.bilinearWeights;
        int nx = this.nx, ny = this.ny;
        int i0 = idx[0], j0 = idx[1], i1 = idx[2], j1 = idx[3];
        double v00, v10, v01, v11;
        if (field.getNDim() == 3) {
            v00 = field.get(i0 + nx * (j0 + ny * k));
            v10 = field.get(i1 + nx * (j0 + ny * k));
            v01 = field.get(i0 + nx * (j1 + ny * k));
            v11 = field.get(i1 + nx * (j1 + ny * k));
        } else {
            v00 = field.get(i0 + nx * j0);
            v10 = field.get(i1 + nx * j0);
            v01 = field.get(i0 + nx * j1);
            v11 = field.get(i1 + nx * j1);
        }
        return w[0] * v00 + w[1] * v10 + w[2] * v01 + w[3] * v11;
    }

    private double interpolate2D(DataField field, Observation obs) {
        if (field == null) return Double.NaN;
        return bilinearAtLevel(field, obs, 0);
    }

    private double interpolate3D(DataField field, Observation obs) {
        if (field == null) return Double.NaN;
        double[] wx = new double[4]; int[] ix = new int[4];
        computeVerticalWeights(obs.pressure, null, wx, ix);
        double v = 0;
        for (int wki = 0; wki < 4; wki++) {
            if (ix[wki] < 0 || ix[wki] >= nz) continue;
            v += wx[wki] * bilinearAtLevel(field, obs, ix[wki]);
        }
        return v;
    }

    public void tangentLinear(ModelState increment, ModelState HxIncr, List<Observation> obList,
                              double[] obsIncrValues) {
        for (int oi = 0; oi < obList.size(); oi++) {
            Observation obs = obList.get(oi);
            obsIncrValues[oi] = forwardOperator(increment, obs);
        }
    }

    public void adjoint(ModelState stateOut, List<Observation> obsList, double[] obsAdjValues) {
        for (VariableType var : VariableType.values()) {
            DataField f = stateOut.fields.get(var);
            if (f != null) for (int i = 0; i < f.getSize(); i++) f.set(i, 0);
        }
        for (int oi = 0; oi < obsList.size(); oi++) {
            if (obsAdjValues[oi] == 0) continue;
            Observation obs = obsList.get(oi);
            VariableType mv = mapObsToModelVar(obs.variable);
            if (mv == null) continue;
            DataField fOut = stateOut.fields.get(mv);
            if (fOut == null) continue;
            double wAdj = obsAdjValues[oi];
            int[] idx = obs.gridLocation;
            double[] w = obs.bilinearWeights;
            if (fOut.getNDim() == 2) {
                fOut.add(idx[0] + nx * idx[1], w[0] * wAdj);
                fOut.add(idx[2] + nx * idx[1], w[1] * wAdj);
                fOut.add(idx[0] + nx * idx[3], w[2] * wAdj);
                fOut.add(idx[2] + nx * idx[3], w[3] * wAdj);
            } else {
                double[] wx = new double[4]; int[] ix = new int[4];
                computeVerticalWeights(obs.pressure, null, wx, ix);
                for (int wk = 0; wk < 4; wk++) {
                    if (ix[wk] < 0 || wx[wk] == 0) continue;
                    double wTotal = wAdj * wx[wk];
                    fOut.add(idx[0] + nx * (idx[1] + ny * ix[wk]), w[0] * wTotal);
                    fOut.add(idx[2] + nx * (idx[1] + ny * ix[wk]), w[1] * wTotal);
                    fOut.add(idx[0] + nx * (idx[3] + ny * ix[wk]), w[2] * wTotal);
                    fOut.add(idx[2] + nx * (idx[3] + ny * ix[wk]), w[3] * wTotal);
                }
            }
        }
    }
}
