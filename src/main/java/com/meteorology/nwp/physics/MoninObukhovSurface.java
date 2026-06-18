package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.DynamicsState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class MoninObukhovSurface implements PhysicsScheme {
    private static final Logger logger = LoggerFactory.getLogger(MoninObukhovSurface.class);
    private NWPConfig config;
    private int nx, ny;

    @Override
    public String getName() { return "Monin-Obukhov Surface"; }

    @Override
    public PhysicsType getType() { return PhysicsType.SURFACE_LAYER; }

    @Override
    public void initialize(NWPConfig config, GridDefinition grid) {
        this.config = config;
        this.nx = grid.getNX();
        this.ny = grid.getNY();
        logger.info("Monin-Obukhov地表方案初始化完成");
    }

    @Override
    public void apply(ModelState state, DynamicsState tendencies, double dt) {
        DataField t = state.getField(VariableType.T);
        DataField qv = state.getField(VariableType.QV);
        DataField u = state.getField(VariableType.U);
        DataField v = state.getField(VariableType.V);

        if (t == null || qv == null || u == null || v == null) return;

        DataField t2 = state.fields.computeIfAbsent(VariableType.T2, v -> new DataField(VariableType.T2, nx, ny, 1));
        DataField q2 = state.fields.computeIfAbsent(VariableType.Q2, v -> new DataField(VariableType.Q2, nx, ny, 1));
        DataField u10 = state.fields.computeIfAbsent(VariableType.U10, v -> new DataField(VariableType.U10, nx, ny, 1));
        DataField v10 = state.fields.computeIfAbsent(VariableType.V10, v -> new DataField(VariableType.V10, nx, ny, 1));

        DataField tendT = tendencies.getTendency(VariableType.T);
        DataField tendQv = tendencies.getTendency(VariableType.QV);
        DataField tendU = tendencies.getTendency(VariableType.U);
        DataField tendV = tendencies.getTendency(VariableType.V);

        double z0 = 0.01, zr = 10.0, zb = 2.0;
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int idx2d = i + nx * j;
                int idx3d = i + nx * j;

                double tk1 = t.get(idx3d);
                double q1 = qv.get(idx3d);
                double u1 = u.get(idx3d), v1 = v.get(idx3d);
                double wind = Math.sqrt(u1 * u1 + v1 * v1) + 0.01;
                double thv = tk1 * (1 + 0.61 * q1);
                double kappa = 0.4;
                double fm = Math.log(zr / z0), fh = fm;
                double ustar = wind * kappa / fm;
                double tstar = 0.3;

                t2.set(idx2d, tk1 + tstar / kappa * Math.log(zb / zr));
                q2.set(idx2d, Math.max(0, q1 - 0.0001));
                double factor = Math.log(zr / z0) / Math.log(zb / z0);
                u10.set(idx2d, u1 * factor);
                v10.set(idx2d, v1 * factor);

                double sfcDragT = -0.001 * (tk1 - 280.0) / dt;
                double sfcDragQ = -0.0001 * q1 / dt;
                double sfcDragU = -0.001 * u1 / dt;
                double sfcDragV = -0.001 * v1 / dt;

                if (tendT != null) tendT.add(idx3d, sfcDragT);
                if (tendQv != null) tendQv.add(idx3d, sfcDragQ);
                if (tendU != null) tendU.add(idx3d, sfcDragU);
                if (tendV != null) tendV.add(idx3d, sfcDragV);
            }
        }
    }

    @Override
    public void applyColumn(int i, int j, ColumnData column, double dt) {
    }

    @Override
    public void cleanup() {
    }
}
