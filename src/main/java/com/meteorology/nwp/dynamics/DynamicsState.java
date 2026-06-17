package com.meteorology.nwp.dynamics;

import com.meteorology.nwp.common.*;

import java.io.Serializable;
import java.util.EnumMap;
import java.util.Map;

public class DynamicsState implements Serializable {
    private final GridDefinition grid;
    private final Map<VariableType, DataField> tendencies;

    public DynamicsState(GridDefinition grid) {
        this.grid = grid;
        this.tendencies = new EnumMap<>(VariableType.class);
        initializeTendencies();
    }

    private void initializeTendencies() {
        for (VariableType type : VariableType.values()) {
            if (type.isPrognostic()) {
                tendencies.put(type, new DataField(type, grid.getNX(), grid.getNY(), grid.getNZ()));
            }
        }
    }

    public DataField getTendency(VariableType type) {
        return tendencies.get(type);
    }

    public Map<VariableType, DataField> getAllTendencies() {
        return tendencies;
    }

    public void zeroAll() {
        for (DataField f : tendencies.values()) {
            f.fill(0.0);
        }
    }

    public void addTendency(VariableType type, DataField field, double factor) {
        DataField tend = tendencies.get(type);
        if (tend != null) {
            tend.addField(field, factor);
        }
    }

    public double maxAbsTendency(VariableType type) {
        DataField tend = tendencies.get(type);
        if (tend == null) return 0.0;
        double max = 0.0;
        double[] d = tend.getData();
        for (double v : d) {
            double a = Math.abs(v);
            if (a > max) max = a;
        }
        return max;
    }

    public void combine(DynamicsState other, double weight) {
        for (Map.Entry<VariableType, DataField> entry : other.tendencies.entrySet()) {
            DataField myField = this.tendencies.get(entry.getKey());
            if (myField != null) {
                myField.addField(entry.getValue(), weight);
            }
        }
    }

    public GridDefinition getGrid() { return grid; }
}
