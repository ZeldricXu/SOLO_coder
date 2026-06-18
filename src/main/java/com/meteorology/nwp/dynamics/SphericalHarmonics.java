package com.meteorology.nwp.dynamics;

import com.meteorology.nwp.common.*;
import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SphericalHarmonics {
    private static final Logger logger = LoggerFactory.getLogger(SphericalHarmonics.class);

    private final int truncation;
    private final int nLat;
    private final int nLon;
    private final int nLatGauss;
    private final int nSpec;

    private final double[] gaussWeights;
    private final double[] gaussLats;

    private final double[][][] legendreTable;
    private final double[][][] legendreDerivTable;

    private final double[][] cosMLon;
    private final double[][] sinMLon;
    private final double[][] mCosMLon;
    private final double[][] mSinMLon;

    private final DoubleFFT_1D fftEngine;
    private final double[] fftWork;

    private final double[] normFactors;
    private final double gridRadiusSq;

    private long forwardTransformCount;
    private long inverseTransformCount;
    private long totalForwardNanos;
    private long totalInverseNanos;

    public SphericalHarmonics(int truncation, GridDefinition grid) {
        this.truncation = truncation;
        this.nLat = grid.getNY();
        this.nLon = grid.getNX();
        this.nLatGauss = (truncation + 1) * 2;
        this.nSpec = (truncation + 1) * (truncation + 2) / 2;
        this.gridRadiusSq = 6371000.0 * 6371000.0;

        this.gaussWeights = new double[nLatGauss];
        this.gaussLats = new double[nLatGauss];
        computeGaussLegendre();

        this.normFactors = new double[truncation + 1];
        precomputeNormFactors();

        this.legendreTable = new double[nLatGauss][nSpec][];
        this.legendreDerivTable = new double[nLatGauss][nSpec][];
        precomputeLegendreLUT();

        this.cosMLon = new double[nLon][truncation + 1];
        this.sinMLon = new double[nLon][truncation + 1];
        this.mCosMLon = new double[nLon][truncation + 1];
        this.mSinMLon = new double[nLon][truncation + 1];
        precomputeLonFactors();

        this.fftEngine = new DoubleFFT_1D(nLon);
        this.fftWork = new double[2 * nLon];

        this.forwardTransformCount = 0;
        this.inverseTransformCount = 0;
        this.totalForwardNanos = 0;
        this.totalInverseNanos = 0;

        long memMB = estimateMemoryMB();
        logger.info("SphericalHarmonics T{}: {} gauss lats, {} spectral coeffs, ~{}MB LUT",
                truncation, nLatGauss, nSpec, memMB);
    }

    private long estimateMemoryMB() {
        long bytes = 0L;
        bytes += (long) nLatGauss * nSpec * 2 * 8;
        bytes += (long) nLon * (truncation + 1) * 4 * 8;
        bytes += (long) nLatGauss * 8 * 2;
        return bytes / (1024 * 1024);
    }

    private void computeGaussLegendre() {
        int n = nLatGauss / 2;
        for (int i = 0; i < n; i++) {
            double z = Math.cos(Math.PI * (i + 0.75) / (nLatGauss + 0.5));
            double z1 = z + 1.0;
            int iter = 0;
            double pp = 0;
            while (Math.abs(z - z1) > 1e-15 && iter < 100) {
                double[] pn = computeLegendreP(nLatGauss, z);
                pp = nLatGauss * (pn[0] - z * pn[1]) / (1.0 - z * z);
                z1 = z;
                z = z1 - pn[1] / pp;
                iter++;
            }
            gaussLats[i] = Math.asin(z);
            gaussLats[nLatGauss - 1 - i] = -gaussLats[i];
            gaussWeights[i] = 2.0 / ((1.0 - z * z) * pp * pp);
            gaussWeights[nLatGauss - 1 - i] = gaussWeights[i];
        }
    }

    private double[] computeLegendreP(int n, double x) {
        double p0 = 1.0, p1 = x;
        for (int j = 2; j <= n; j++) {
            double p2 = ((2.0 * j - 1.0) * x * p1 - (j - 1.0) * p0) / j;
            p0 = p1;
            p1 = p2;
        }
        return new double[]{p0, p1};
    }

    private void precomputeNormFactors() {
        for (int m = 0; m <= truncation; m++) {
            double logFact = 0.0;
            for (int k = 1; k <= 2 * m; k++) {
                if (k <= m) logFact -= Math.log(k);
                else logFact += Math.log(k);
            }
            normFactors[m] = Math.exp(0.5 * logFact);
        }
    }

    private void precomputeLegendreLUT() {
        double eps = 1e-30;

        double[][] pmnPrev = new double[truncation + 2][truncation + 2];
        double[][] pmnCurr = new double[truncation + 2][truncation + 2];
        double[][] pmnNext = new double[truncation + 2][truncation + 2];

        for (int j = 0; j < nLatGauss; j++) {
            double sinLat = Math.sin(gaussLats[j]);
            double cosLat = Math.cos(gaussLats[j]);
            double cosLatSafe = cosLat == 0.0 ? eps : cosLat;

            for (int mm = 0; mm <= truncation + 1; mm++) {
                for (int nn = 0; nn <= truncation + 1; nn++) {
                    pmnPrev[mm][nn] = 0.0;
                    pmnCurr[mm][nn] = 0.0;
                    pmnNext[mm][nn] = 0.0;
                }
            }

            pmnCurr[0][0] = 1.0;

            pmnCurr[1][1] = Math.sqrt(1.0 - sinLat * sinLat);
            for (int m = 2; m <= truncation; m++) {
                double factor = Math.sqrt((2.0 * m + 1.0) / (2.0 * m));
                pmnCurr[m][m] = factor * cosLatSafe * pmnCurr[m - 1][m - 1];
            }

            for (int m = 0; m <= truncation; m++) {
                if (m + 1 <= truncation) {
                    double a = Math.sqrt((2.0 * (m + 1) + 1.0) * (2.0 * (m + 1) - 1.0)
                            / ((m + 1 - m) * (m + 1 + m)));
                    pmnCurr[m][m + 1] = a * sinLat * pmnCurr[m][m];
                }
            }

            for (int m = 0; m <= truncation; m++) {
                for (int n = m + 2; n <= truncation; n++) {
                    double annm = Math.sqrt((2.0 * n + 1.0) * (2.0 * n - 1.0)
                            / ((n - m) * (n + m)));
                    double bnnm = Math.sqrt((2.0 * n + 1.0) * (n - m - 1.0) * (n + m - 1.0)
                            / ((n - m) * (n + m) * (2.0 * n - 3.0)));

                    pmnNext[m][n] = annm * sinLat * pmnCurr[m][n] - bnnm * pmnPrev[m][n];
                }
            }

            int idx = 0;
            for (int m = 0; m <= truncation; m++) {
                double normM = m == 0 ? 1.0 : Math.sqrt(2.0);
                for (int n = m; n <= truncation; n++) {
                    double pnm;
                    if (n == m) {
                        double raw = 1.0;
                        for (int k = 1; k <= m; k++) {
                            raw *= Math.sqrt((2.0 * k - 1.0) / (2.0 * k)) * cosLatSafe;
                        }
                        pnm = raw;
                    } else if (n == m + 1) {
                        double sqrtFactor = Math.sqrt((2.0 * (m + 1) + 1.0));
                        pnm = sqrtFactor * sinLat * (m < truncation ? pmnCurr[m][m] : 1.0);
                        if (m > 0) {
                            double basePmm = 1.0;
                            for (int k = 1; k <= m; k++) {
                                basePmm *= Math.sqrt((2.0 * k - 1.0) / (2.0 * k)) * cosLatSafe;
                            }
                            pnm = Math.sqrt((2.0 * (m + 1) + 1.0)) * sinLat * basePmm;
                        }
                    } else {
                        pnm = pmnNext[m][n];
                        if (pnm == 0.0 && n > 0) {
                            pnm = computePnmIterative(n, m, sinLat, cosLatSafe);
                        }
                    }

                    double fullNorm = normM * Math.sqrt((2.0 * n + 1.0) / (4.0 * Math.PI));
                    double normedPnm = fullNorm * pnm;

                    double dpnm;
                    if (n == m) {
                        dpnm = -m * sinLat / cosLatSafe * normedPnm;
                    } else {
                        double pnm1 = (n + 1 <= truncation) ? normedPnm : 0.0;
                        dpnm = (n * sinLat * normedPnm - Math.sqrt((n * n - m * m))
                                * computePnmIterative(n - 1, m, sinLat, cosLatSafe) * fullNorm)
                                / cosLatSafe;
                    }

                    legendreTable[j][idx] = new double[]{normedPnm};
                    legendreDerivTable[j][idx] = new double[]{dpnm};
                    idx++;
                }
            }
        }

        logger.info("Legendre LUT precomputed: {} latitudes × {} spectral coeffs", nLatGauss, nSpec);
    }

    private double computePnmIterative(int n, int m, double sinLat, double cosLat) {
        if (m > n) return 0.0;
        if (n == 0 && m == 0) return 1.0;

        double pmm = 1.0;
        if (m > 0) {
            double somx2 = Math.sqrt(Math.max(1e-30, 1.0 - sinLat * sinLat));
            double fact = 1.0;
            for (int i = 1; i <= m; i++) {
                pmm *= fact * somx2;
                fact += 2.0;
            }
        }
        if (n == m) return pmm;

        double pmmp1 = sinLat * (2.0 * m + 1.0) * pmm;
        if (n == m + 1) return pmmp1;

        double pll = 0.0;
        for (int ll = m + 2; ll <= n; ll++) {
            pll = (sinLat * (2.0 * ll - 1.0) * pmmp1 - (ll + m - 1.0) * pmm) / (ll - m);
            pmm = pmmp1;
            pmmp1 = pll;
        }
        return pll;
    }

    private void precomputeLonFactors() {
        for (int i = 0; i < nLon; i++) {
            double lonRad = 2.0 * Math.PI * i / nLon;
            for (int m = 0; m <= truncation; m++) {
                double mLon = m * lonRad;
                cosMLon[i][m] = Math.cos(mLon);
                sinMLon[i][m] = Math.sin(mLon);
                mCosMLon[i][m] = m * cosMLon[i][m];
                mSinMLon[i][m] = m * sinMLon[i][m];
            }
        }
    }

    public double[][] gridToSpectral(DataField field) {
        long t0 = System.nanoTime();

        double[] gridData = field.getData();
        double[][] coeffs = new double[nSpec][2];
        double[] fourierReal = new double[truncation + 1];
        double[] fourierImag = new double[truncation + 1];

        double[] lonSlice = new double[nLon];

        for (int ig = 0; ig < nLatGauss; ig++) {
            double lat = gaussLats[ig];
            int j = (int) Math.round((lat / Math.PI + 0.5) * (nLat - 1));
            j = Math.max(0, Math.min(nLat - 1, j));

            int rowOffset = nLon * j;
            for (int i = 0; i < nLon; i++) {
                fftWork[2 * i] = gridData[rowOffset + i];
                fftWork[2 * i + 1] = 0.0;
            }

            fftEngine.complexForward(fftWork);

            for (int m = 0; m <= Math.min(truncation, nLon / 2); m++) {
                fourierReal[m] = fftWork[2 * m] / nLon;
                fourierImag[m] = fftWork[2 * m + 1] / nLon;
            }

            double wt = gaussWeights[ig];
            int idx = 0;
            for (int m = 0; m <= truncation; m++) {
                double fr = fourierReal[m];
                double fi = fourierImag[m];
                for (int n = m; n <= truncation; n++) {
                    double pnm = legendreTable[ig][idx][0];
                    coeffs[idx][0] += wt * pnm * fr;
                    coeffs[idx][1] += wt * pnm * fi;
                    idx++;
                }
            }
        }

        totalForwardNanos += System.nanoTime() - t0;
        forwardTransformCount++;

        return coeffs;
    }

    public DataField spectralToGrid(double[][] coeffs, GridDefinition grid) {
        long t0 = System.nanoTime();

        DataField result = new DataField(VariableType.T, nLon, nLat, 1);
        double[] data = result.getData();

        for (int ig = 0; ig < nLatGauss; ig++) {
            double lat = gaussLats[ig];
            int j = (int) Math.round((lat / Math.PI + 0.5) * (nLat - 1));
            j = Math.max(0, Math.min(nLat - 1, j));

            double[] realPart = new double[truncation + 1];
            double[] imagPart = new double[truncation + 1];

            int idx = 0;
            for (int m = 0; m <= truncation; m++) {
                double sumR = 0.0, sumI = 0.0;
                for (int n = m; n <= truncation; n++) {
                    double pnm = legendreTable[ig][idx][0];
                    sumR += pnm * coeffs[idx][0];
                    sumI += pnm * coeffs[idx][1];
                    idx++;
                }
                realPart[m] = sumR;
                imagPart[m] = sumI;
            }

            for (int i = 0; i < nLon; i++) {
                double val = 0.0;
                for (int m = 0; m <= truncation; m++) {
                    val += realPart[m] * cosMLon[i][m] - imagPart[m] * sinMLon[i][m];
                    if (m > 0) {
                        val += realPart[m] * cosMLon[i][m] + imagPart[m] * sinMLon[i][m];
                    }
                }
                data[i + nLon * j] = val;
            }
        }

        totalInverseNanos += System.nanoTime() - t0;
        inverseTransformCount++;

        return result;
    }

    public ComplexCoeffs gridToSpectralComplex(DataField field) {
        double[][] raw = gridToSpectral(field);
        return new ComplexCoeffs(raw, truncation);
    }

    public DataField spectralToGrid(ComplexCoeffs coeffs, GridDefinition grid) {
        return spectralToGrid(coeffs.data, grid);
    }

    public double[][] computeLaplacian(double[][] coeffs) {
        double[][] lap = new double[coeffs.length][2];
        int idx = 0;
        for (int m = 0; m <= truncation; m++) {
            for (int n = m; n <= truncation; n++) {
                double factor = -n * (n + 1.0) / gridRadiusSq;
                lap[idx][0] = coeffs[idx][0] * factor;
                lap[idx][1] = coeffs[idx][1] * factor;
                idx++;
            }
        }
        return lap;
    }

    public double[][] inverseLaplacian(double[][] coeffs) {
        double[][] inv = new double[coeffs.length][2];
        int idx = 0;
        for (int m = 0; m <= truncation; m++) {
            for (int n = m; n <= truncation; n++) {
                if (n == 0 && m == 0) {
                    inv[idx][0] = 0.0;
                    inv[idx][1] = 0.0;
                } else {
                    double factor = -gridRadiusSq / (n * (n + 1.0));
                    inv[idx][0] = coeffs[idx][0] * factor;
                    inv[idx][1] = coeffs[idx][1] * factor;
                }
                idx++;
            }
        }
        return inv;
    }

    public void printPerformanceReport() {
        if (forwardTransformCount > 0) {
            double avgForward = (totalForwardNanos / (double) forwardTransformCount) / 1e6;
            logger.info("谱变换性能: 正变换 {} 次, 平均 {:.3f} ms/次", forwardTransformCount, avgForward);
        }
        if (inverseTransformCount > 0) {
            double avgInverse = (totalInverseNanos / (double) inverseTransformCount) / 1e6;
            logger.info("谱变换性能: 逆变换 {} 次, 平均 {:.3f} ms/次", inverseTransformCount, avgInverse);
        }
    }

    public DataField spectralToGrid(double[][] coeffs) {
        int totalSize = nLon * nLat;
        DataField result = new DataField(VariableType.T, nLon, nLat, 1);
        double[] data = result.getData();

        for (int ig = 0; ig < nLatGauss; ig++) {
            double lat = gaussLats[ig];
            int j = (int) Math.round((lat / Math.PI + 0.5) * (nLat - 1));
            j = Math.max(0, Math.min(nLat - 1, j));

            double[] realPart = new double[truncation + 1];
            double[] imagPart = new double[truncation + 1];

            int idx = 0;
            for (int m = 0; m <= truncation; m++) {
                double sumR = 0.0, sumI = 0.0;
                for (int n = m; n <= truncation; n++) {
                    double pnm = legendreTable[ig][idx][0];
                    sumR += pnm * coeffs[idx][0];
                    sumI += pnm * coeffs[idx][1];
                    idx++;
                }
                realPart[m] = sumR;
                imagPart[m] = sumI;
            }

            for (int i = 0; i < nLon; i++) {
                double val = 0.0;
                for (int m = 0; m <= truncation; m++) {
                    val += realPart[m] * cosMLon[i][m] - imagPart[m] * sinMLon[i][m];
                    if (m > 0) {
                        val += realPart[m] * cosMLon[i][m] + imagPart[m] * sinMLon[i][m];
                    }
                }
                data[i + nLon * j] = val;
            }
        }

        totalInverseNanos += 0;
        inverseTransformCount++;
        return result;
    }

    public double[][] spectralLaplacian(double[][] coeffs) {
        return computeLaplacian(coeffs);
    }

    public int getTruncation() { return truncation; }
    public int getNSpec() { return nSpec; }
    public int getNLatGauss() { return nLatGauss; }
    public double[] getGaussWeights() { return gaussWeights; }
    public double[] getGaussLats() { return gaussLats; }
    public double[][][] getLegendreTable() { return legendreTable; }

    public static class ComplexCoeffs {
        public final double[][] data;
        public final int truncation;

        public ComplexCoeffs(double[][] data, int truncation) {
            this.data = data;
            this.truncation = truncation;
        }

        public double getReal(int m, int n) {
            int idx = spectralIndex(m, n, truncation);
            return idx < data.length ? data[idx][0] : 0.0;
        }

        public double getImag(int m, int n) {
            int idx = spectralIndex(m, n, truncation);
            return idx < data.length ? data[idx][1] : 0.0;
        }

        public void setReal(int m, int n, double val) {
            int idx = spectralIndex(m, n, truncation);
            if (idx < data.length) data[idx][0] = val;
        }

        public void setImag(int m, int n, double val) {
            int idx = spectralIndex(m, n, truncation);
            if (idx < data.length) data[idx][1] = val;
        }

        public static int spectralIndex(int m, int n, int trunc) {
            return m * (2 * trunc + 3 - m) / 2 + n - m;
        }
    }
}
