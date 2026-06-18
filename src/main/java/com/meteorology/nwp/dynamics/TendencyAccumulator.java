package com.meteorology.nwp.dynamics;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TendencyAccumulator {
    private static final Logger logger = LoggerFactory.getLogger(TendencyAccumulator.class);

    private final GridDefinition grid;
    private final Map<VariableType, double[]> accumulated;
    private final Map<VariableType, Map<PhysicsType, double[]>> contributions;
    private final Map<VariableType, Double> maxTendencies;
    private boolean locked;

    public TendencyAccumulator(GridDefinition grid) {
        this.grid = grid;
        this.accumulated = new EnumMap<>(VariableType.class);
        this.contributions = new EnumMap<>(VariableType.class);
        this.maxTendencies = new EnumMap<>(VariableType.class);
        this.locked = false;

        for (VariableType type : VariableType.values()) {
            if (type.isPrognostic()) {
                int size = grid.getNX() * grid.getNY() * grid.getNZ();
                accumulated.put(type, new double[size]);
                contributions.put(type, new EnumMap<>(PhysicsType.class));
                maxTendencies.put(type, 0.0);
            }
        }
    }

    public void reset() {
        for (double[] arr : accumulated.values()) {
            Arrays.fill(arr, 0.0);
        }
        for (Map<PhysicsType, double[]> inner : contributions.values()) {
            inner.clear();
        }
        for (VariableType type : maxTendencies.keySet()) {
            maxTendencies.put(type, 0.0);
        }
        locked = false;
    }

    public void accumulate(PhysicsType source, VariableType varType, DataField tendency) {
        if (locked) {
            throw new IllegalStateException("TendencyAccumulator is locked, cannot accumulate");
        }
        double[] target = accumulated.get(varType);
        if (target == null) return;

        double[] src = tendency.getData();
        int n = Math.min(target.length, src.length);

        double[] contrib = contributions.get(varType)
                .computeIfAbsent(source, k -> new double[target.length]);

        for (int i = 0; i < n; i++) {
            target[i] += src[i];
            contrib[i] += src[i];
        }
    }

    public void accumulate(PhysicsType source, VariableType varType, double[] tendencyData) {
        if (locked) return;
        double[] target = accumulated.get(varType);
        if (target == null) return;

        int n = Math.min(target.length, tendencyData.length);
        double[] contrib = contributions.get(varType)
                .computeIfAbsent(source, k -> new double[target.length]);

        for (int i = 0; i < n; i++) {
            target[i] += tendencyData[i];
            contrib[i] += tendencyData[i];
        }
    }

    public void accumulateScaled(PhysicsType source, VariableType varType,
                                  DataField tendency, double scale) {
        if (locked) return;
        double[] target = accumulated.get(varType);
        if (target == null) return;

        double[] src = tendency.getData();
        int n = Math.min(target.length, src.length);

        double[] contrib = contributions.get(varType)
                .computeIfAbsent(source, k -> new double[target.length]);

        for (int i = 0; i < n; i++) {
            double val = scale * src[i];
            target[i] += val;
            contrib[i] += val;
        }
    }

    public double[] getAccumulated(VariableType varType) {
        return accumulated.get(varType);
    }

    public DataField getAccumulatedField(VariableType varType) {
        double[] data = accumulated.get(varType);
        if (data == null) return null;
        DataField f = new DataField(varType, grid.getNX(), grid.getNY(), grid.getNZ());
        System.arraycopy(data, 0, f.getData(), 0, Math.min(data.length, f.getData().length));
        return f;
    }

    public double[] getContribution(VariableType varType, PhysicsType source) {
        Map<PhysicsType, double[]> inner = contributions.get(varType);
        if (inner == null) return null;
        return inner.get(source);
    }

    public void applyToState(ModelState state, double dt) {
        locked = true;

        for (Map.Entry<VariableType, double[]> entry : accumulated.entrySet()) {
            VariableType varType = entry.getKey();
            double[] tend = entry.getValue();
            DataField field = state.getField(varType);
            if (field == null) continue;

            double[] fieldData = field.getData();
            int n = Math.min(fieldData.length, tend.length);

            double maxAbs = 0.0;
            for (int i = 0; i < n; i++) {
                double val = dt * tend[i];
                if (Double.isFinite(val)) {
                    fieldData[i] += val;
                    double abs = Math.abs(tend[i]);
                    if (abs > maxAbs) maxAbs = abs;
                }
            }
            maxTendencies.put(varType, maxAbs);
        }
    }

    public void applyToStateWithClipping(ModelState state, double dt,
                                          Map<VariableType, double[]> limits) {
        locked = true;

        for (Map.Entry<VariableType, double[]> entry : accumulated.entrySet()) {
            VariableType varType = entry.getKey();
            double[] tend = entry.getValue();
            DataField field = state.getField(varType);
            if (field == null) continue;

            double[] fieldData = field.getData();
            int n = Math.min(fieldData.length, tend.length);
            double[] limit = limits.get(varType);

            for (int i = 0; i < n; i++) {
                double val = dt * tend[i];
                if (Double.isFinite(val)) {
                    fieldData[i] += val;
                    if (limit != null && limit.length >= 2) {
                        fieldData[i] = Math.max(limit[0], Math.min(limit[1], fieldData[i]));
                    }
                }
            }
        }
    }

    public double getMaxTendency(VariableType varType) {
        return maxTendencies.getOrDefault(varType, 0.0);
    }

    public void sanitizeNaN() {
        int nanFixed = 0;
        for (Map.Entry<VariableType, double[]> entry : accumulated.entrySet()) {
            double[] tend = entry.getValue();
            for (int i = 0; i < tend.length; i++) {
                if (!Double.isFinite(tend[i])) {
                    tend[i] = 0.0;
                    nanFixed++;
                }
            }
        }
        if (nanFixed > 0) {
            logger.warn("TendencyAccumulator: 修复了 {} 个NaN/Inf倾向值", nanFixed);
        }
    }

    public void printContributionReport() {
        logger.info("===== 倾向项累加报告 =====");
        for (VariableType var : accumulated.keySet()) {
            double[] tend = accumulated.get(var);
            double total = 0.0;
            for (double v : tend) total += v * v;
            double rms = Math.sqrt(total / tend.length);

            Map<PhysicsType, double[]> contribs = contributions.get(var);
            if (contribs != null && !contribs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("  %s: RMS=%.6e", var, rms));
                for (Map.Entry<PhysicsType, double[]> ce : contribs.entrySet()) {
                    double cTotal = 0.0;
                    for (double v : ce.getValue()) cTotal += v * v;
                    double cRms = Math.sqrt(cTotal / ce.getValue().length);
                    double pct = rms > 0 ? 100.0 * cRms / rms : 0;
                    sb.append(String.format(" | %s: %.1f%%", ce.getKey(), pct));
                }
                logger.info(sb.toString());
            }
        }
    }

    public int getVariableCount() {
        return accumulated.size();
    }

    public boolean isLocked() {
        return locked;
    }
}
