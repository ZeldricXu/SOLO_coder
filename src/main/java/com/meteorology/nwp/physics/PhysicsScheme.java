package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.DynamicsState;

public interface PhysicsScheme {

    String getName();
    PhysicsType getType();
    void initialize(NWPConfig config, GridDefinition grid);
    void apply(ModelState state, DynamicsState tendencies, double dt);
    void applyColumn(int i, int j, ColumnData column, double dt);
    void cleanup();
    default boolean needsCompute(ModelState state) { return true; }
}
