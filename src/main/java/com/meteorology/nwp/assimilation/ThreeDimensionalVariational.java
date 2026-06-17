package com.meteorology.nwp.assimilation;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ThreeDimensionalVariational {
    private static final Logger logger = LoggerFactory.getLogger(ThreeDimensionalVariational.class);

    private final NWPConfig config;
    private final GridDefinition grid;
    private final BackgroundErrorCovariance B;
    private final ObservationOperator H;
    private final LBFGSMinimizer minimizer;

    private int outerLoops;
    private int innerIterations;
    private double gradTol;
    private double timeWindowHours;
    private int qualityControlThreshold;

    private final LBFGSMinimizer.VectorSpace<ModelState> modelStateSpace =
            new LBFGSMinimizer.VectorSpace<ModelState>() {
                @Override
                public ModelState clone(ModelState v, boolean zero) {
                    ModelState c = v.cloneState(zero);
                    return c;
                }

                @Override
                public void addScaled(ModelState out, ModelState v1, double s, ModelState v2) {
                    for (VariableType var : VariableType.values()) {
                        DataField of = out.fields.get(var);
                        DataField f1 = v1.fields.get(var);
                        if (of == null || f1 == null) continue;
                        for (int i = 0; i < of.getSize(); i++) of.set(i, s * f1.get(i));
                        if (v2 != null) {
                            DataField f2 = v2.fields.get(var);
                            if (f2 != null) for (int i = 0; i < of.getSize(); i++) of.add(i, f2.get(i));
                        }
                    }
                }

                @Override
                public double dot(ModelState a, ModelState b) {
                    double d = 0;
                    for (VariableType var : VariableType.values()) {
                        DataField af = a.fields.get(var);
                        DataField bf = b.fields.get(var);
                        if (af == null || bf == null) continue;
                        for (int i = 0; i < af.getSize(); i++) d += af.get(i) * bf.get(i);
                    }
                    return d;
                }

                @Override
                public void scale(ModelState v, double s) {
                    for (VariableType var : VariableType.values()) {
                        DataField f = v.fields.get(var);
                        if (f == null) continue;
                        for (int i = 0; i < f.getSize(); i++) f.mult(i, s);
                    }
                }
            };

    public ThreeDimensionalVariational(NWPConfig config) {
        this.config = config;
        this.grid = config.getGrid();
        this.B = new BackgroundErrorCovariance(config);
        this.H = new ObservationOperator(config);
        this.outerLoops = config.getInt("nwp.assimilation.outerLoops", 2);
        this.innerIterations = config.getInt("nwp.assimilation.innerIterations", 50);
        this.gradTol = config.getDouble("nwp.assimilation.gradientTolerance", 1e-3);
        this.timeWindowHours = config.getDouble("nwp.assimilation.timeWindowHours", 3.0);
        this.qualityControlThreshold = config.getInt("nwp.assimilation.qcThreshold", 3);
        this.minimizer = new LBFGSMinimizer(
                config.getInt("nwp.assimilation.lbfgs.historySize", 10),
                innerIterations,
                gradTol,
                config.getDouble("nwp.assimilation.fTolerance", 1e-8),
                config.getDouble("nwp.assimilation.maxStep", 10.0)
        );
        logger.info("3D-Var初始化: 外循环x{} 内迭代x{} 窗口{:.1f}h QC阈值{}σ",
                outerLoops, innerIterations, timeWindowHours, qualityControlThreshold);
    }

    public ModelState analyze(ModelState background, List<Observation> allObservations,
                              Instant analysisTime) {
        logger.info("===== 开始3D-Var同化分析 {} =====", analysisTime);
        long startTime = System.nanoTime();
        List<Observation> validObs = screenObservations(allObservations, analysisTime);
        if (validObs.isEmpty()) {
            logger.warn("无有效观测，返回背景场");
            return background.cloneState(false);
        }
        H.precomputeObsLocations(validObs);
        ModelState analysis = background.cloneState(false);
        Map<VariableType, double[]> obsStatistics = new EnumMap<>(VariableType.class);
        for (int outer = 0; outer < outerLoops; outer++) {
            logger.info("--- 外循环 {}/{} ---", outer + 1, outerLoops);
            CostFunction3DVar cost = new CostFunction3DVar(config, analysis, validObs, H, B);
            ModelState incr0 = analysis.cloneState(true);
            LBFGSMinimizer.CostFunction<ModelState> costFunc = (x, gOut, calcG) ->
                    cost.evaluate(x, gOut, calcG);
            ModelState analysisIncr = minimizer.minimize(modelStateSpace, costFunc, incr0);
            applyInnovations(analysis, analysisIncr, validObs, cost);
            collectStatistics(validObs, cost, obsStatistics, outer);
        }
        analysis.computeDiagnosticFields(config);
        printAnalysisReport(background, analysis, validObs, obsStatistics);
        double elapsedSec = (System.nanoTime() - startTime) / 1e9;
        logger.info("===== 3D-Var完成: {:.2f}s 观测总数={} =====", elapsedSec, validObs.size());
        return analysis;
    }

    private List<Observation> screenObservations(List<Observation> raw, Instant analysisTime) {
        List<Observation> filtered = new ArrayList<>(raw.size());
        Duration window = Duration.ofMillis((long) (timeWindowHours * 3_600_000));
        Instant tMinus = analysisTime.minus(window);
        Instant tPlus = analysisTime.plus(window);
        int skippedTime = 0, skippedDomain = 0, skippedQc = 0, skippedVar = 0;
        for (Observation obs : raw) {
            if (!obs.isValid()) { skippedQc++; continue; }
            if (obs.obsTime.isBefore(tMinus) || obs.obsTime.isAfter(tPlus)) { skippedTime++; continue; }
            if (obs.latitude < grid.latMin - 1 || obs.latitude > grid.latMax + 1) { skippedDomain++; continue; }
            VariableType mv = VariableType.fromGribCode(0, obs.variable.ordinal(), obs.variable.ordinal());
            if (mv == null && !obs.variable.is3D() && !obs.variable.isPrognostic()) { skippedVar++; continue; }
            if (obs.quality < 0.1) { skippedQc++; continue; }
            filtered.add(obs);
        }
        logger.info("观测筛选: 原始{}→有效{} (时间{} 域外{} QC{} 变量{})",
                raw.size(), filtered.size(), skippedTime, skippedDomain, skippedQc, skippedVar);
        return filtered;
    }

    private void applyInnovations(ModelState analysis, ModelState increment,
                                   List<Observation> obs, CostFunction3DVar cost) {
        for (VariableType var : VariableType.values()) {
            DataField aF = analysis.fields.get(var);
            DataField iF = increment.fields.get(var);
            if (aF == null || iF == null) continue;
            double maxIncr = 0;
            for (int i = 0; i < aF.getSize(); i++) {
                aF.add(i, iF.get(i));
                maxIncr = Math.max(maxIncr, Math.abs(iF.get(i)));
            }
            if (var.isPrognostic()) {
                logger.debug("  变量 {} 最大增量: {:.4e}", var, maxIncr);
            }
        }
        for (int oi = 0; oi < obs.size(); oi++) {
            Observation o = obs.get(oi);
            double dep = o.value - H.forwardOperator(analysis, o);
            double sigmaO = o.effectiveError();
            if (Math.abs(dep) > qualityControlThreshold * sigmaO) {
                // quality control flag
            }
        }
    }

    private void collectStatistics(List<Observation> obsList, CostFunction3DVar cost,
                                    Map<VariableType, double[]> stats, int outerLoop) {
        double[] innov = cost.getObsInnovation();
        for (int i = 0; i < obsList.size(); i++) {
            Observation o = obsList.get(i);
            double[] s = stats.computeIfAbsent(o.variable, k -> new double[6]);
            double d = innov[i];
            double sigma = o.effectiveError();
            s[0] += 1; s[1] += d; s[2] += d * d; s[3] += d / sigma; s[4] += (d / sigma) * (d / sigma);
            if (Math.abs(d) > qualityControlThreshold * sigma) s[5]++;
        }
    }

    private void printAnalysisReport(ModelState bg, ModelState an, List<Observation> obs,
                                      Map<VariableType, double[]> stats) {
        logger.info("----- 同化分析诊断报告 -----");
        Map<VariableType, double[]> changeMap = new EnumMap<>(VariableType.class);
        for (VariableType v : VariableType.values()) {
            DataField bf = bg.fields.get(v); DataField af = an.fields.get(v);
            if (bf == null || af == null || !v.isPrognostic()) continue;
            double bias = 0, rms = 0, maxAbs = 0;
            int n = bf.getSize();
            for (int i = 0; i < n; i++) {
                double d = af.get(i) - bf.get(i);
                bias += d; rms += d * d; maxAbs = Math.max(maxAbs, Math.abs(d));
            }
            bias /= n; rms = Math.sqrt(rms / n);
            logger.info("  Δ{}: BIAS={:+.3e} RMS={:.3e} MAX={:.3e}", v, bias, rms, maxAbs);
        }
        logger.info("  按变量统计观测 (N/O-BIAS/OMF-RMS/Normalized/QC-reject):");
        for (Map.Entry<VariableType, double[]> e : stats.entrySet()) {
            double[] s = e.getValue();
            if (s[0] < 1) continue;
            double n = s[0], bias = s[1] / n;
            double rms = Math.sqrt(s[2] / n - bias * bias);
            double gcv = Math.sqrt(s[4] / Math.max(1, n - 1));
            logger.info("    {}: N={} BIAS={:+.3f} RMS={:.3f} σ-norm={:.2f} reject={}",
                    e.getKey(), (int) s[0], bias, rms, gcv, (int) s[5]);
        }
    }

    public BackgroundErrorCovariance getB() { return B; }
    public ObservationOperator getH() { return H; }
}
