package com.meteorology.nwp.common;

import java.io.Serializable;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public class ModelState implements Serializable {
    private final GridDefinition grid;
    private final Map<VariableType, DataField> fields;
    private Instant initializationTime;
    private Instant currentTime;
    private int forecastStep;
    private double simulationTime;
    private String versionTag;
    private String experimentId;

    public ModelState(GridDefinition grid) {
        this.grid = grid;
        this.fields = new EnumMap<>(VariableType.class);
        this.forecastStep = 0;
        this.simulationTime = 0.0;
    }

    public GridDefinition getGrid() { return grid; }
    public Instant getInitializationTime() { return initializationTime; }
    public void setInitializationTime(Instant t) { this.initializationTime = t; }
    public Instant getCurrentTime() { return currentTime; }
    public void setCurrentTime(Instant t) { this.currentTime = t; }
    public int getForecastStep() { return forecastStep; }
    public void setForecastStep(int s) { this.forecastStep = s; }
    public double getSimulationTime() { return simulationTime; }
    public void setSimulationTime(double t) { this.simulationTime = t; }
    public String getVersionTag() { return versionTag; }
    public void setVersionTag(String v) { this.versionTag = v; }
    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String e) { this.experimentId = e; }

    public void addField(VariableType type) {
        if (!fields.containsKey(type)) {
            fields.put(type, new DataField(type, grid.getNX(), grid.getNY(), grid.getNZ()));
        }
    }

    public void addField(DataField field) {
        fields.put(field.getType(), field);
    }

    public DataField getField(VariableType type) {
        return fields.get(type);
    }

    public Map<VariableType, DataField> getAllFields() {
        return fields;
    }

    public boolean hasField(VariableType type) {
        return fields.containsKey(type);
    }

    public void ensurePrognosticFields() {
        for (VariableType t : VariableType.values()) {
            if (t.isPrognostic()) addField(t);
        }
    }

    public void advanceTime(double dt) {
        simulationTime += dt;
        forecastStep++;
        if (currentTime != null) {
            currentTime = currentTime.plusSeconds((long) dt);
        }
    }

    public void computeDiagnosticFields() {
        if (hasField(VariableType.T) && hasField(VariableType.QV)) {
            computeRH();
        }
        if (hasField(VariableType.U) && hasField(VariableType.V)) {
            computeVorticity();
            computeDivergence();
        }
    }

    private void computeRH() {
        DataField T = getField(VariableType.T);
        DataField QV = getField(VariableType.QV);
        DataField PSFC = getField(VariableType.PSFC);
        addField(VariableType.RH);
        DataField RH = getField(VariableType.RH);
        double Rd = 287.0;
        double Rv = 461.0;
        double epsilon = Rd / Rv;
        int nz = grid.getNZ();
        double[] sigma = grid.getSigmaLevels();
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < grid.getNY(); j++) {
                for (int i = 0; i < grid.getNX(); i++) {
                    double temp = T.get(i, j, k);
                    double qv = Math.max(0.0, Math.min(0.5, QV.get(i, j, k)));
                    double ps = PSFC != null ? PSFC.get(i, j) : 101325.0;
                    double p = sigma[k] * ps;
                    double es = 611.2 * Math.exp(17.67 * (temp - 273.15) / (temp - 29.65));
                    double ws = epsilon * es / Math.max(1.0, (p - es));
                    double rh = 100.0 * qv / Math.max(1e-10, ws);
                    RH.set(i, j, k, Math.max(0.0, Math.min(100.0, rh)));
                }
            }
        }
    }

    private void computeVorticity() {
        DataField U = getField(VariableType.U);
        DataField V = getField(VariableType.V);
        addField(VariableType.VOR);
        DataField VOR = getField(VariableType.VOR);
        for (int k = 0; k < grid.getNZ(); k++) {
            for (int j = 1; j < grid.getNY() - 1; j++) {
                double dx = grid.getDXMeters(j);
                double dy = grid.getDYMeters(j);
                for (int i = 1; i < grid.getNX() - 1; i++) {
                    double dvdx = (V.get(i + 1, j, k) - V.get(i - 1, j, k)) / (2.0 * dx);
                    double dudy = (U.get(i, j + 1, k) - U.get(i, j - 1, k)) / (2.0 * dy);
                    VOR.set(i, j, k, dvdx - dudy + grid.getFCoriolis(j));
                }
            }
        }
    }

    private void computeDivergence() {
        DataField U = getField(VariableType.U);
        DataField V = getField(VariableType.V);
        addField(VariableType.DIV);
        DataField DIV = getField(VariableType.DIV);
        for (int k = 0; k < grid.getNZ(); k++) {
            for (int j = 1; j < grid.getNY() - 1; j++) {
                double dx = grid.getDXMeters(j);
                double dy = grid.getDYMeters(j);
                for (int i = 1; i < grid.getNX() - 1; i++) {
                    double dudx = (U.get(i + 1, j, k) - U.get(i - 1, j, k)) / (2.0 * dx);
                    double dvdy = (V.get(i, j + 1, k) - V.get(i, j - 1, k)) / (2.0 * dy);
                    DIV.set(i, j, k, dudx + dvdy);
                }
            }
        }
    }

    public ModelState cloneState() {
        ModelState clone = new ModelState(grid);
        clone.initializationTime = this.initializationTime;
        clone.currentTime = this.currentTime;
        clone.forecastStep = this.forecastStep;
        clone.simulationTime = this.simulationTime;
        clone.versionTag = this.versionTag;
        clone.experimentId = this.experimentId;
        for (Map.Entry<VariableType, DataField> entry : this.fields.entrySet()) {
            clone.fields.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return clone;
    }
}
