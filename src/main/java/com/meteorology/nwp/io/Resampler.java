package com.meteorology.nwp.io;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Resampler {
    private static final Logger logger = LoggerFactory.getLogger(Resampler.class);

    public enum Method {
        NEAREST,
        BILINEAR,
        BICUBIC,
        CONSERVATIVE
    }

    private final GridDefinition srcGrid;
    private final GridDefinition dstGrid;

    public Resampler(GridDefinition srcGrid, GridDefinition dstGrid) {
        this.srcGrid = srcGrid;
        this.dstGrid = dstGrid;
    }

    public DataField resample(DataField srcField, Method method) {
        logger.info("Resampling {} from {}x{} to {}x{} using {}",
                srcField.getType(), srcGrid.getNX(), srcGrid.getNY(),
                dstGrid.getNX(), dstGrid.getNY(), method);

        DataField dstField = new DataField(srcField.getType(), dstGrid.getNX(), dstGrid.getNY(), dstGrid.getNZ());

        if (srcField.is3D()) {
            for (int k = 0; k < Math.min(srcField.getNZ(), dstField.getNZ()); k++) {
                for (int j = 0; j < dstGrid.getNY(); j++) {
                    for (int i = 0; i < dstGrid.getNX(); i++) {
                        double value = switch (method) {
                            case NEAREST -> nearest3D(srcField, dstGrid.getLon(i), dstGrid.getLat(j), k);
                            case BILINEAR -> bilinear3D(srcField, dstGrid.getLon(i), dstGrid.getLat(j), k);
                            case BICUBIC -> bicubic3D(srcField, dstGrid.getLon(i), dstGrid.getLat(j), k);
                            case CONSERVATIVE -> conservative3D(srcField, dstGrid.getLon(i), dstGrid.getLat(j), k);
                        };
                        dstField.set(i, j, k, value);
                    }
                }
            }
        } else {
            for (int j = 0; j < dstGrid.getNY(); j++) {
                for (int i = 0; i < dstGrid.getNX(); i++) {
                    double value = switch (method) {
                        case NEAREST -> nearest2D(srcField, dstGrid.getLon(i), dstGrid.getLat(j));
                        case BILINEAR -> bilinear2D(srcField, dstGrid.getLon(i), dstGrid.getLat(j));
                        case BICUBIC -> bicubic2D(srcField, dstGrid.getLon(i), dstGrid.getLat(j));
                        case CONSERVATIVE -> conservative2D(srcField, dstGrid.getLon(i), dstGrid.getLat(j));
                    };
                    dstField.set(i, j, value);
                }
            }
        }

        return dstField;
    }

    private double nearest2D(DataField field, double lon, double lat) {
        int i = srcGrid.findNearestLon(lon);
        int j = srcGrid.findNearestLat(lat);
        return field.get(i, j);
    }

    private double nearest3D(DataField field, double lon, double lat, int k) {
        int i = srcGrid.findNearestLon(lon);
        int j = srcGrid.findNearestLat(lat);
        return field.get(i, j, k);
    }

    private double bilinear2D(DataField field, double lon, double lat) {
        double di = (lon - srcGrid.getLonMin()) / srcGrid.getDX();
        double dj = (lat - srcGrid.getLatMin()) / srcGrid.getDY();
        int i0 = (int) Math.floor(di);
        int j0 = (int) Math.floor(dj);
        int i1 = i0 + 1;
        int j1 = j0 + 1;
        double fx = di - i0;
        double fy = dj - j0;
        i0 = Math.max(0, Math.min(srcGrid.getNX() - 1, i0));
        i1 = Math.max(0, Math.min(srcGrid.getNX() - 1, i1));
        j0 = Math.max(0, Math.min(srcGrid.getNY() - 1, j0));
        j1 = Math.max(0, Math.min(srcGrid.getNY() - 1, j1));
        if (srcGrid.getNX() > 1 && i1 == srcGrid.getNX()) i1 = 0;
        double v00 = field.get(i0, j0);
        double v10 = field.get(i1, j0);
        double v01 = field.get(i0, j1);
        double v11 = field.get(i1, j1);
        return (1.0 - fx) * (1.0 - fy) * v00 + fx * (1.0 - fy) * v10 +
               (1.0 - fx) * fy * v01 + fx * fy * v11;
    }

    private double bilinear3D(DataField field, double lon, double lat, int k) {
        double di = (lon - srcGrid.getLonMin()) / srcGrid.getDX();
        double dj = (lat - srcGrid.getLatMin()) / srcGrid.getDY();
        int i0 = (int) Math.floor(di);
        int j0 = (int) Math.floor(dj);
        int i1 = i0 + 1;
        int j1 = j0 + 1;
        double fx = di - i0;
        double fy = dj - j0;
        i0 = Math.max(0, Math.min(srcGrid.getNX() - 1, i0));
        i1 = Math.max(0, Math.min(srcGrid.getNX() - 1, i1));
        j0 = Math.max(0, Math.min(srcGrid.getNY() - 1, j0));
        j1 = Math.max(0, Math.min(srcGrid.getNY() - 1, j1));
        if (srcGrid.getNX() > 1 && i1 == srcGrid.getNX()) i1 = 0;
        double v00 = field.get(i0, j0, k);
        double v10 = field.get(i1, j0, k);
        double v01 = field.get(i0, j1, k);
        double v11 = field.get(i1, j1, k);
        return (1.0 - fx) * (1.0 - fy) * v00 + fx * (1.0 - fy) * v10 +
               (1.0 - fx) * fy * v01 + fx * fy * v11;
    }

    private double bicubic2D(DataField field, double lon, double lat) {
        double di = (lon - srcGrid.getLonMin()) / srcGrid.getDX();
        double dj = (lat - srcGrid.getLatMin()) / srcGrid.getDY();
        int iBase = (int) Math.floor(di) - 1;
        int jBase = (int) Math.floor(dj) - 1;
        double fx = di - Math.floor(di);
        double fy = dj - Math.floor(dj);
        double[] col = new double[4];
        for (int j = 0; j < 4; j++) {
            double[] row = new double[4];
            for (int i = 0; i < 4; i++) {
                int ii = (iBase + i + srcGrid.getNX()) % srcGrid.getNX();
                int jj = Math.max(0, Math.min(srcGrid.getNY() - 1, jBase + j));
                row[i] = field.get(ii, jj);
            }
            col[j] = cubicInterpolate(row, fx);
        }
        return cubicInterpolate(col, fy);
    }

    private double bicubic3D(DataField field, double lon, double lat, int k) {
        double di = (lon - srcGrid.getLonMin()) / srcGrid.getDX();
        double dj = (lat - srcGrid.getLatMin()) / srcGrid.getDY();
        int iBase = (int) Math.floor(di) - 1;
        int jBase = (int) Math.floor(dj) - 1;
        double fx = di - Math.floor(di);
        double fy = dj - Math.floor(dj);
        double[] col = new double[4];
        for (int j = 0; j < 4; j++) {
            double[] row = new double[4];
            for (int i = 0; i < 4; i++) {
                int ii = (iBase + i + srcGrid.getNX()) % srcGrid.getNX();
                int jj = Math.max(0, Math.min(srcGrid.getNY() - 1, jBase + j));
                row[i] = field.get(ii, jj, k);
            }
            col[j] = cubicInterpolate(row, fx);
        }
        return cubicInterpolate(col, fy);
    }

    private double cubicInterpolate(double[] p, double x) {
        return p[1] + 0.5 * x * (p[2] - p[0] +
               x * (2.0 * p[0] - 5.0 * p[1] + 4.0 * p[2] - p[3] +
               x * (3.0 * (p[1] - p[2]) + p[3] - p[0])));
    }

    private double conservative2D(DataField field, double lon, double lat) {
        double cellAreaSum = 0.0;
        double weightedSum = 0.0;
        int i0 = Math.max(0, srcGrid.findNearestLon(lon) - 2);
        int i1 = Math.min(srcGrid.getNX() - 1, srcGrid.findNearestLon(lon) + 2);
        int j0 = Math.max(0, srcGrid.findNearestLat(lat) - 2);
        int j1 = Math.min(srcGrid.getNY() - 1, srcGrid.findNearestLat(lat) + 2);
        double dxh = dstGrid.getDX() / 2.0;
        double dyh = dstGrid.getDY() / 2.0;
        for (int j = j0; j <= j1; j++) {
            double area = srcGrid.getCellArea(j);
            for (int i = i0; i <= i1; i++) {
                int ii = (i + srcGrid.getNX()) % srcGrid.getNX();
                double si = srcGrid.getLon(ii);
                double sj = srcGrid.getLat(j);
                double overlap = rectOverlap(lon - dxh, lon + dxh, lat - dyh, lat + dyh,
                                              si - srcGrid.getDX() / 2, si + srcGrid.getDX() / 2,
                                              sj - srcGrid.getDY() / 2, sj + srcGrid.getDY() / 2);
                weightedSum += overlap * area * field.get(ii, j);
                cellAreaSum += overlap * area;
            }
        }
        return cellAreaSum > 0 ? weightedSum / cellAreaSum : bilinear2D(field, lon, lat);
    }

    private double conservative3D(DataField field, double lon, double lat, int k) {
        double cellAreaSum = 0.0;
        double weightedSum = 0.0;
        int i0 = Math.max(0, srcGrid.findNearestLon(lon) - 2);
        int i1 = Math.min(srcGrid.getNX() - 1, srcGrid.findNearestLon(lon) + 2);
        int j0 = Math.max(0, srcGrid.findNearestLat(lat) - 2);
        int j1 = Math.min(srcGrid.getNY() - 1, srcGrid.findNearestLat(lat) + 2);
        double dxh = dstGrid.getDX() / 2.0;
        double dyh = dstGrid.getDY() / 2.0;
        for (int j = j0; j <= j1; j++) {
            double area = srcGrid.getCellArea(j);
            for (int i = i0; i <= i1; i++) {
                int ii = (i + srcGrid.getNX()) % srcGrid.getNX();
                double si = srcGrid.getLon(ii);
                double sj = srcGrid.getLat(j);
                double overlap = rectOverlap(lon - dxh, lon + dxh, lat - dyh, lat + dyh,
                                              si - srcGrid.getDX() / 2, si + srcGrid.getDX() / 2,
                                              sj - srcGrid.getDY() / 2, sj + srcGrid.getDY() / 2);
                weightedSum += overlap * area * field.get(ii, j, k);
                cellAreaSum += overlap * area;
            }
        }
        return cellAreaSum > 0 ? weightedSum / cellAreaSum : bilinear3D(field, lon, lat, k);
    }

    private double rectOverlap(double x1min, double x1max, double y1min, double y1max,
                               double x2min, double x2max, double y2min, double y2max) {
        double dx = Math.max(0, Math.min(x1max, x2max) - Math.max(x1min, x2min));
        double dy = Math.max(0, Math.min(y1max, y2max) - Math.max(y1min, y2min));
        return dx * dy;
    }

    public ModelState resampleState(ModelState srcState, Method method) {
        ModelState dstState = new ModelState(dstGrid);
        dstState.setInitializationTime(srcState.getInitializationTime());
        dstState.setCurrentTime(srcState.getCurrentTime());
        dstState.setForecastStep(srcState.getForecastStep());
        dstState.setSimulationTime(srcState.getSimulationTime());
        dstState.setVersionTag(srcState.getVersionTag());
        dstState.setExperimentId(srcState.getExperimentId());

        for (Map.Entry<VariableType, DataField> entry : srcState.getAllFields().entrySet()) {
            dstState.addField(resample(entry.getValue(), method));
        }

        return dstState;
    }

    public double verticalInterpolate(DataField field, DataField pressureField,
                                       double targetPressure, int i, int j) {
        int nz = field.getNZ();
        if (nz < 2) return field.get(i, j, 0);

        double pBot = pressureField.get(i, j, nz - 1);
        double pTop = pressureField.get(i, j, 0);

        if (targetPressure >= pBot) return field.get(i, j, nz - 1);
        if (targetPressure <= pTop) return field.get(i, j, 0);

        for (int k = 0; k < nz - 1; k++) {
            double p1 = pressureField.get(i, j, k);
            double p2 = pressureField.get(i, j, k + 1);
            if ((p1 - targetPressure) * (p2 - targetPressure) <= 0) {
                if (Math.abs(p2 - p1) < 1e-10) return field.get(i, j, k);
                double alpha = (targetPressure - p1) / (p2 - p1);
                return field.get(i, j, k) * (1.0 - alpha) + field.get(i, j, k + 1) * alpha;
            }
        }

        return field.get(i, j, nz / 2);
    }
}
