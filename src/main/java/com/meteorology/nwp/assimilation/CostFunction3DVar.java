package com.meteorology.nwp.assimilation;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CostFunction3DVar {
    private static final Logger logger = LoggerFactory.getLogger(CostFunction3DVar.class);
    private final NWPConfig config;
    private final ModelState background;
    private final List<Observation> observations;
    private final ObservationOperator hOperator;
    private final BackgroundErrorCovariance B;
    private final int nObs;
    private final double[] obsInnovation;
    private final double[] obsInvR;
    private final DataField xbgState;
    private int nEval;

    public CostFunction3DVar(NWPConfig config, ModelState background,
                             List<Observation> observations,
                             ObservationOperator hOperator,
                             BackgroundErrorCovariance B) {
        this.config = config;
        this.background = background;
        this.observations = observations;
        this.hOperator = hOperator;
        this.B = B;
        this.nObs = observations.size();
        this.obsInnovation = new double[nObs];
        this.obsInvR = new double[nObs];
        this.xbgState = null;
        initObsTerms();
        this.nEval = 0;
    }

    private void initObsTerms() {
        logger.debug("初始化观测项: {} 个观测", nObs);
        int nSkipped = 0;
        for (int i = 0; i < nObs; i++) {
            Observation obs = observations.get(i);
            double hxb = hOperator.forwardOperator(background, obs);
            if (!Double.isFinite(hxb)) {
                obsInnovation[i] = 0;
                obsInvR[i] = 0;
                nSkipped++;
                continue;
            }
            obsInnovation[i] = obs.value - hxb;
            double qcThin = (obs.quality < 0.05) ? 0 : obs.quality;
            double varR = obs.error * obs.error / Math.max(0.1, qcThin);
            if (obs.type == Observation.ObsType.SATELLITE_RADIANCE) varR *= 4;
            if (obs.type == Observation.ObsType.RADAR_REFLECTIVITY) varR *= 2;
            obsInvR[i] = 1.0 / Math.max(1e-300, varR);
        }
        if (nSkipped > 0) {
            logger.warn("跳过 {} 个无效观测（H(xb)非有限）", nSkipped);
        }
    }

    public double evaluate(ModelState xIncr, ModelState gradOut, boolean computeGradient) {
        nEval++;
        double jb = computeJb(xIncr);
        double jo = computeJo(xIncr);
        double jTotal = jb + jo;
        if (computeGradient) {
            computeGradient(xIncr, gradOut);
        }
        if (nEval % 10 == 0) {
            logger.debug("J#{}: J={:.4e} Jb={:.4e} Jo={:.4e}", nEval, jTotal, jb, jo);
        }
        return jTotal;
    }

    private double computeJb(ModelState x) {
        double jb = 0;
        ModelState bx = x.cloneState(false);
        B.applyB(bx);
        for (VariableType var : VariableType.values()) {
            if (!var.isPrognostic()) continue;
            DataField xf = x.fields.get(var);
            DataField bxf = bx.fields.get(var);
            if (xf == null || bxf == null) continue;
            double dot = 0;
            for (int i = 0; i < xf.getSize(); i++) {
                dot += xf.get(i) * bxf.get(i);
            }
            double vol = config.getGrid().getMeanCellArea();
            jb += 0.5 * dot * vol;
        }
        return jb;
    }

    private double computeJo(ModelState x) {
        ModelState hx = background.cloneState(false);
        for (VariableType v : VariableType.values()) {
            DataField xF = x.fields.get(v);
            DataField hxF = hx.fields.get(v);
            if (xF != null && hxF != null) {
                for (int i = 0; i < hxF.getSize(); i++) hxF.add(i, xF.get(i));
            }
        }
        double jo = 0;
        for (int i = 0; i < nObs; i++) {
            if (obsInvR[i] == 0) continue;
            Observation obs = observations.get(i);
            double hxVal = hOperator.forwardOperator(hx, obs);
            if (!Double.isFinite(hxVal)) continue;
            double depart = (obs.value - hxVal);
            jo += 0.5 * depart * depart * obsInvR[i];
        }
        return jo;
    }

    private void computeGradient(ModelState xIncr, ModelState gradOut) {
        ModelState bxt = xIncr.cloneState(false);
        B.applyB(bxt);
        for (VariableType var : VariableType.values()) {
            DataField gF = gradOut.fields.get(var);
            DataField bxF = bxt.fields.get(var);
            if (gF == null || bxF == null) continue;
            for (int i = 0; i < gF.getSize(); i++) gF.set(i, bxF.get(i));
        }
        ModelState hx = background.cloneState(false);
        for (VariableType v : VariableType.values()) {
            DataField xF = xIncr.fields.get(v);
            DataField hxF = hx.fields.get(v);
            if (xF != null && hxF != null) {
                for (int i = 0; i < hxF.getSize(); i++) hxF.add(i, xF.get(i));
            }
        }
        double[] hxAdj = new double[nObs];
        for (int i = 0; i < nObs; i++) {
            if (obsInvR[i] == 0) continue;
            Observation obs = observations.get(i);
            double hxVal = hOperator.forwardOperator(hx, obs);
            if (!Double.isFinite(hxVal)) continue;
            hxAdj[i] = -(obs.value - hxVal) * obsInvR[i];
        }
        ModelState adjState = xIncr.cloneState(true);
        hOperator.adjoint(adjState, observations, hxAdj);
        B.applyB(adjState);
        for (VariableType var : VariableType.values()) {
            DataField gF = gradOut.fields.get(var);
            DataField aF = adjState.fields.get(var);
            if (gF == null || aF == null) continue;
            for (int i = 0; i < gF.getSize(); i++) gF.add(i, aF.get(i));
        }
    }

    public double computeGradientNorm(ModelState grad) {
        double sum = 0;
        for (VariableType v : VariableType.values()) {
            DataField f = grad.fields.get(v);
            if (f == null) continue;
            for (int i = 0; i < f.getSize(); i++) sum += f.get(i) * f.get(i);
        }
        return Math.sqrt(sum / Math.max(1, grad.getTotalPoints()));
    }

    public int getEvaluationCount() { return nEval; }

    public double[] getObsInnovation() { return obsInnovation; }

    public int getValidObsCount() {
        int n = 0;
        for (double r : obsInvR) if (r > 0) n++;
        return n;
    }

    public static double computeReducedChiSquare(double jo, int nObs, int nDOF) {
        int dof = Math.max(1, nObs - nDOF);
        return jo * 2.0 / dof;
    }
}
