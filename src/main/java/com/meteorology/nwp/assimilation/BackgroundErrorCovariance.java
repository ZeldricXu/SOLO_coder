package com.meteorology.nwp.assimilation;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackgroundErrorCovariance {
    private static final Logger logger = LoggerFactory.getLogger(BackgroundErrorCovariance.class);
    private final NWPConfig config;
    private final int nx, ny, nz;
    private final GridDefinition grid;

    private final double[] sigmaB;
    private final double[] lengthScaleH;
    private final double lengthScaleV;
    private final int numIter;

    private final double[] balanceUV, balanceT;
    private final double[][] verticalCorrelation;

    public BackgroundErrorCovariance(NWPConfig config) {
        this.config = config;
        this.nx = config.getNX();
        this.ny = config.getNY();
        this.nz = config.getNZ();
        this.grid = config.getGrid();
        this.numIter = config.getInt("nwp.assimilation.bec.numIterations", 15);
        this.lengthScaleV = config.getDouble("nwp.assimilation.bec.verticalScaleKm", 2.0) * 1000;
        this.sigmaB = new double[VariableType.values().length];
        initSigmaB();
        this.lengthScaleH = new double[ny];
        initLengthScales();
        this.balanceUV = new double[nz];
        this.balanceT = new double[nz];
        initBalanceCoefficients();
        this.verticalCorrelation = new double[nz][nz];
        initVerticalCorrelation();
        logger.info("B矩阵初始化: 水平长度~{:.1f}km(低纬)-{:.1f}km(极区), 垂直{:.1f}层, {}次递归滤波",
                lengthScaleH[ny/2]/1000, lengthScaleH[0]/1000, lengthScaleV/1000, numIter);
    }

    private void initSigmaB() {
        sigmaB[VariableType.T.ordinal()] = 1.5;
        sigmaB[VariableType.U.ordinal()] = 2.5;
        sigmaB[VariableType.V.ordinal()] = 2.5;
        sigmaB[VariableType.QV.ordinal()] = 0.0015;
        sigmaB[VariableType.PSFC.ordinal()] = 150.0;
        sigmaB[VariableType.QC.ordinal()] = 0.0003;
        sigmaB[VariableType.QI.ordinal()] = 0.0001;
        for (VariableType v : VariableType.values()) {
            if (sigmaB[v.ordinal()] == 0) sigmaB[v.ordinal()] = 1.0;
        }
    }

    private void initLengthScales() {
        double baseScale = config.getDouble("nwp.assimilation.bec.horizontalScaleKm", 300.0) * 1000;
        for (int j = 0; j < ny; j++) {
            double cosLat = Math.cos(grid.latRad[j]);
            lengthScaleH[j] = baseScale * Math.max(0.2, cosLat);
        }
    }

    private void initBalanceCoefficients() {
        double[] sigma = config.getSigmaLevels();
        for (int k = 0; k < nz; k++) {
            double sig = sigma[k];
            balanceUV[k] = 0.05 * (1 - sig);
            double pressureRatio = Math.pow(sig, 0.2857);
            balanceT[k] = -10.0 * pressureRatio * (1 - pressureRatio) / 9.81;
        }
    }

    private void initVerticalCorrelation() {
        double[] heights = config.getPressureLevels();
        if (heights == null) {
            for (int k = 0; k < nz; k++) for (int l = 0; l < nz; l++) {
                double dk = Math.abs(k - l) / (double) Math.max(1, nz / 3);
                verticalCorrelation[k][l] = Math.exp(-dk * dk);
            }
        } else {
            for (int k = 0; k < nz; k++) for (int l = 0; l < nz; l++) {
                double dz = Math.abs(heights[k] - heights[l]) / lengthScaleV;
                verticalCorrelation[k][l] = Math.exp(-dz * dz);
            }
        }
    }

    public void applyB(ModelState analysisIncr) {
        applyBalanceOperator(analysisIncr);
        for (VariableType var : VariableType.values()) {
            DataField f = analysisIncr.fields.get(var);
            if (f == null) continue;
            applyScaling(f, var);
            if (f.getNDim() == 3) {
                applyVerticalCorrelation3D(f);
                applyHorizontalCorrelation3D(f);
            } else {
                applyHorizontalCorrelation2D(f);
            }
            applyScaling(f, var);
        }
    }

    private void applyBalanceOperator(ModelState incr) {
        DataField psfc = incr.fields.get(VariableType.PSFC);
        DataField t = incr.fields.get(VariableType.T);
        DataField u = incr.fields.get(VariableType.U);
        DataField v = incr.fields.get(VariableType.V);
        if (t == null || u == null || v == null || psfc == null) return;
        for (int k = 0; k < nz; k++) {
            double buv = balanceUV[k];
            double bt = balanceT[k];
            for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
                int idx2d = i + nx * j;
                int idx3d = i + nx * (j + ny * k);
                double psIncr = psfc.get(idx2d);
                t.add(idx3d, bt * psIncr);
            }
        }
    }

    private void applyScaling(DataField f, VariableType var) {
        double s = sigmaB[var.ordinal()];
        if (Math.abs(s - 1.0) < 1e-6) return;
        for (int i = 0; i < f.getSize(); i++) {
            f.mult(i, s);
        }
    }

    private void applyVerticalCorrelation3D(DataField f) {
        double[] tmp = new double[nz];
        for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
            for (int k = 0; k < nz; k++) {
                tmp[k] = f.get(i + nx * (j + ny * k));
            }
            for (int k = 0; k < nz; k++) {
                double sum = 0, wsum = 0;
                for (int l = 0; l < nz; l++) {
                    double w = verticalCorrelation[k][l];
                    sum += w * tmp[l];
                    wsum += w;
                }
                f.set(i + nx * (j + ny * k), wsum > 0 ? sum / wsum : tmp[k]);
            }
        }
    }

    private void applyHorizontalCorrelation2D(DataField f) {
        DataField tmp = f.deepCopy();
        for (int iter = 0; iter < numIter; iter++) {
            for (int j = 0; j < ny; j++) {
                double lh = lengthScaleH[j];
                double dx = grid.dxMeters[j];
                double coeffX = Math.min(0.5, dx * dx / (8.0 * lh * lh / (numIter) + dx * dx));
                for (int i = 0; i < nx; i++) {
                    int ip = (i + 1) % nx;
                    int im = (i - 1 + nx) % nx;
                    int idx = i + nx * j;
                    double val = tmp.get(idx);
                    val += coeffX * (tmp.get(ip + nx * j) + tmp.get(im + nx * j) - 2 * tmp.get(idx));
                    tmp.set(idx, val);
                }
            }
            double[] tmpRow = new double[nx];
            for (int j = 1; j < ny - 1; j++) {
                double lh = (lengthScaleH[j - 1] + lengthScaleH[j] + lengthScaleH[j + 1]) / 3.0;
                double dy = grid.dLatMeters;
                double coeffY = Math.min(0.5, dy * dy / (8.0 * lh * lh / numIter + dy * dy));
                for (int i = 0; i < nx; i++) tmpRow[i] = tmp.get(i + nx * j);
                for (int i = 0; i < nx; i++) {
                    int idx = i + nx * j;
                    double v = tmpRow[i] + coeffY * (tmp.get(i + nx * (j + 1)) + tmp.get(i + nx * (j - 1)) - 2 * tmpRow[i]);
                    tmp.set(idx, v);
                }
            }
        }
        for (int i = 0; i < f.getSize(); i++) f.set(i, tmp.get(i));
    }

    private void applyHorizontalCorrelation3D(DataField f) {
        DataField tmp = f.deepCopy();
        for (int iter = 0; iter < numIter; iter++) {
            for (int k = 0; k < nz; k++) {
                for (int j = 0; j < ny; j++) {
                    double lh = lengthScaleH[j];
                    double dx = grid.dxMeters[j];
                    double coeffX = Math.min(0.5, dx * dx / (8.0 * lh * lh / numIter + dx * dx));
                    for (int i = 0; i < nx; i++) {
                        int ip = (i + 1) % nx;
                        int im = (i - 1 + nx) % nx;
                        int idx = i + nx * (j + ny * k);
                        double val = tmp.get(idx);
                        val += coeffX * (tmp.get(ip + nx * (j + ny * k))
                                       + tmp.get(im + nx * (j + ny * k))
                                       - 2 * tmp.get(idx));
                        tmp.set(idx, val);
                    }
                }
                for (int j = 1; j < ny - 1; j++) {
                    double lh = (lengthScaleH[j-1]+lengthScaleH[j]+lengthScaleH[j+1])/3.0;
                    double dy = grid.dLatMeters;
                    double coeffY = Math.min(0.5, dy*dy/(8.0*lh*lh/numIter + dy*dy));
                    for (int i = 0; i < nx; i++) {
                        int idx = i + nx * (j + ny * k);
                        double v = tmp.get(idx) + coeffY * (
                                tmp.get(i + nx * (j + 1 + ny * k))
                              + tmp.get(i + nx * (j - 1 + ny * k))
                              - 2 * tmp.get(idx));
                        tmp.set(idx, v);
                    }
                }
            }
        }
        for (int i = 0; i < f.getSize(); i++) f.set(i, tmp.get(i));
    }

    public double getStandardDeviation(VariableType var) {
        return sigmaB[var.ordinal()];
    }

    public double getHorizontalScaleKm(double latRad) {
        int j = grid.findNearestLat(Math.toDegrees(latRad));
        return lengthScaleH[Math.max(0, Math.min(ny-1, j))] / 1000.0;
    }
}
