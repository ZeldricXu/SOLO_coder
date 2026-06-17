package com.meteorology.nwp.dynamics;

import com.meteorology.nwp.common.*;
import org.apache.commons.math3.complex.Complex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SphericalHarmonics {
    private static final Logger logger = LoggerFactory.getLogger(SphericalHarmonics.class);
    private final int truncation;
    private final int nLat;
    private final int nLon;
    private final int nLatGauss;
    private final double[] gaussWeights;
    private final double[] gaussLats;
    private final double[][][] pLegendre;
    private final Complex[][][] expImLon;

    public SphericalHarmonics(int truncation, GridDefinition grid) {
        this.truncation = truncation;
        this.nLat = grid.getNY();
        this.nLon = grid.getNX();
        this.nLatGauss = (truncation + 1) * 2;

        this.gaussWeights = new double[nLatGauss];
        this.gaussLats = new double[nLatGauss];
        computeGaussLegendre();

        int nSpec = (truncation + 1) * (truncation + 2) / 2;
        this.pLegendre = new double[nLatGauss][nSpec][2];
        computeLegendreFunctions();

        this.expImLon = new Complex[nLon][truncation + 1][2];
        computeLonFactors();

        logger.info("Initialized SphericalHarmonics: T{} truncation, {} gaussian latitudes",
                truncation, nLatGauss);
    }

    private void computeGaussLegendre() {
        int n = nLatGauss / 2;
        for (int i = 0; i < n; i++) {
            double z = Math.cos(Math.PI * (i + 0.75) / (nLatGauss + 0.5));
            double z1 = z + 1.0;
            int iter = 0;
            while (Math.abs(z - z1) > 1e-15 && iter < 100) {
                double pn = 1.0, pn1 = z;
                for (int j = 2; j <= nLatGauss; j++) {
                    double pn2 = ((2.0 * j - 1.0) * z * pn1 - (j - 1.0) * pn) / j;
                    pn = pn1;
                    pn1 = pn2;
                }
                double pp = nLatGauss * (pn - z * pn1) / (1.0 - z * z);
                z1 = z;
                z = z1 - pn1 / pp;
                iter++;
            }
            gaussLats[i] = Math.toDegrees(Math.asin(z));
            gaussLats[nLatGauss - 1 - i] = -gaussLats[i];
            gaussWeights[i] = 2.0 / ((1.0 - z * z) * pp * pp);
            gaussWeights[nLatGauss - 1 - i] = gaussWeights[i];
        }
    }

    private void computeLegendreFunctions() {
        double eps = 1e-10;
        for (int j = 0; j < nLatGauss; j++) {
            double latRad = Math.toRadians(gaussLats[j]);
            double x = Math.sin(latRad);
            int idx = 0;
            for (int m = 0; m <= truncation; m++) {
                for (int n = m; n <= truncation; n++) {
                    double pnm = 0.0, dPnm = 0.0;
                    if (n == 0 && m == 0) {
                        pnm = 1.0;
                        dPnm = 0.0;
                    } else if (n == m) {
                        double fact = 1.0;
                        for (int k = 1; k <= m; k++) fact *= (2.0 * k - 1.0) / (2.0 * k);
                        pnm = fact * Math.pow(1.0 - x * x, m / 2.0);
                        dPnm = -m * x / Math.sqrt(Math.max(eps, 1.0 - x * x)) * pnm;
                    } else if (n == m + 1) {
                        pnm = x * (2.0 * m + 1.0) * pnmRecurse(m, m, x);
                        dPnm = (2.0 * m + 1.0) * (pnmRecurse(m, m, x) + x * dpnmRecurse(m, m, x));
                    } else {
                        double pn1 = pnmRecurse(n - 1, m, x);
                        double pn2 = pnmRecurse(n - 2, m, x);
                        pnm = ((2.0 * n - 1.0) * x * pn1 - (n + m - 1.0) * pn2) / (n - m);
                        double dp1 = dpnmRecurse(n - 1, m, x);
                        double dp2 = dpnmRecurse(n - 2, m, x);
                        dPnm = ((2.0 * n - 1.0) * (pn1 + x * dp1) - (n + m - 1.0) * dp2) / (n - m);
                    }
                    double norm = Math.sqrt((2.0 * n + 1.0) / (4.0 * Math.PI) *
                            factorial(n - m) / factorial(n + m));
                    if (m > 0) norm *= Math.sqrt(2.0);
                    pLegendre[j][idx][0] = norm * pnm;
                    pLegendre[j][idx][1] = norm * dPnm;
                    idx++;
                }
            }
        }
    }

    private double pnmRecurse(int n, int m, double x) {
        if (n == 0 && m == 0) return 1.0;
        if (m > n) return 0.0;
        if (n == m) {
            double fact = 1.0;
            for (int k = 1; k <= m; k++) fact *= (2.0 * k - 1.0) / (2.0 * k);
            return fact * Math.pow(Math.max(0.0, 1.0 - x * x), m / 2.0);
        }
        double pn1 = pnmRecurse(n - 1, m, x);
        double pn2 = pnmRecurse(n - 2, m, x);
        return ((2.0 * n - 1.0) * x * pn1 - (n + m - 1.0) * pn2) / (n - m);
    }

    private double dpnmRecurse(int n, int m, double x) {
        if (n == 0) return 0.0;
        if (n == m) {
            double pnm = pnmRecurse(n, m, x);
            return -m * x / Math.sqrt(Math.max(1e-10, 1.0 - x * x)) * pnm;
        }
        double pn1 = pnmRecurse(n - 1, m, x);
        return n * (x * pnmRecurse(n, m, x) - pn1) / Math.max(1e-10, x * x - 1.0);
    }

    private double factorial(int n) {
        if (n <= 1) return 1.0;
        double f = 1.0;
        for (int i = 2; i <= n; i++) f *= i;
        return f;
    }

    private void computeLonFactors() {
        for (int i = 0; i < nLon; i++) {
            double lonRad = 2.0 * Math.PI * i / nLon;
            for (int m = 0; m <= truncation; m++) {
                expImLon[i][m][0] = new Complex(Math.cos(m * lonRad), Math.sin(m * lonRad));
                expImLon[i][m][1] = new Complex(-m * Math.sin(m * lonRad), m * Math.cos(m * lonRad));
            }
        }
    }

    public Complex[][] gridToSpectral(DataField field) {
        double[] gridData = field.getData();
        int nSpec = (truncation + 1) * (truncation + 2) / 2;
        Complex[][] coeffs = new Complex[nSpec][2];
        for (int s = 0; s < nSpec; s++) {
            coeffs[s][0] = Complex.ZERO;
            coeffs[s][1] = Complex.ZERO;
        }

        Complex[] fft = new Complex[nLon];
        for (int ig = 0; ig < nLatGauss; ig++) {
            double lat = gaussLats[ig];
            int j = (int) Math.round((lat + 90.0) / 180.0 * (nLat - 1));
            j = Math.max(0, Math.min(nLat - 1, j));
            for (int i = 0; i < nLon; i++) {
                fft[i] = new Complex(gridData[i + nLon * j], 0.0);
            }
            Complex[] fourierM = inverseFFT(fft);

            int idx = 0;
            for (int m = 0; m <= truncation; m++) {
                Complex fm = fourierM[m];
                for (int n = m; n <= truncation; n++) {
                    Complex val = fm.multiply(pLegendre[ig][idx][0] * gaussWeights[ig]);
                    coeffs[idx][0] = coeffs[idx][0].add(val);
                    Complex dval = fm.multiply(pLegendre[ig][idx][1] * gaussWeights[ig]);
                    coeffs[idx][1] = coeffs[idx][1].add(dval);
                    idx++;
                }
            }
        }
        return coeffs;
    }

    public DataField spectralToGrid(Complex[][] coeffs, GridDefinition grid) {
        DataField result = new DataField(VariableType.T, nLon, nLat, 1);
        double[] data = result.getData();

        int idx = 0;
        for (int m = 0; m <= truncation; m++) {
            for (int n = m; n <= truncation; n++) {
                idx++;
            }
        }

        for (int ig = 0; ig < nLatGauss; ig++) {
            Complex[] fft = new Complex[nLon];
            for (int i = 0; i < nLon; i++) fft[i] = Complex.ZERO;

            idx = 0;
            for (int m = 0; m <= truncation; m++) {
                for (int n = m; n <= truncation; n++) {
                    Complex cn = coeffs[idx][0].multiply(pLegendre[ig][idx][0]);
                    for (int i = 0; i < nLon; i++) {
                        fft[i] = fft[i].add(cn.conjugate().multiply(expImLon[i][m][0])
                                .add(cn.multiply(expImLon[i][m][0].conjugate())));
                    }
                    idx++;
                }
            }

            Complex[] gridVals = forwardFFT(fft);
            double lat = gaussLats[ig];
            int j = (int) Math.round((lat + 90.0) / 180.0 * (nLat - 1));
            j = Math.max(0, Math.min(nLat - 1, j));
            for (int i = 0; i < nLon; i++) {
                data[i + nLon * j] = gridVals[i].getReal() / nLon;
            }
        }
        return result;
    }

    public Complex[][] computeLaplacian(Complex[][] coeffs) {
        Complex[][] lap = new Complex[coeffs.length][2];
        int idx = 0;
        for (int m = 0; m <= truncation; m++) {
            for (int n = m; n <= truncation; n++) {
                double factor = -n * (n + 1.0) / (gridRadiusSq());
                lap[idx][0] = coeffs[idx][0].multiply(factor);
                lap[idx][1] = coeffs[idx][1].multiply(factor);
                idx++;
            }
        }
        return lap;
    }

    public Complex[][] inverseLaplacian(Complex[][] coeffs) {
        Complex[][] inv = new Complex[coeffs.length][2];
        int idx = 0;
        for (int m = 0; m <= truncation; m++) {
            for (int n = m; n <= truncation; n++) {
                if (n == 0 && m == 0) {
                    inv[idx][0] = Complex.ZERO;
                    inv[idx][1] = Complex.ZERO;
                } else {
                    double factor = -gridRadiusSq() / (n * (n + 1.0));
                    inv[idx][0] = coeffs[idx][0].multiply(factor);
                    inv[idx][1] = coeffs[idx][1].multiply(factor);
                }
                idx++;
            }
        }
        return inv;
    }

    private double gridRadiusSq() {
        return 6371000.0 * 6371000.0;
    }

    private Complex[] forwardFFT(Complex[] x) {
        int n = x.length;
        if (n == 1) return x;
        Complex[] even = new Complex[n / 2];
        Complex[] odd = new Complex[n / 2];
        for (int i = 0; i < n / 2; i++) {
            even[i] = x[2 * i];
            odd[i] = x[2 * i + 1];
        }
        Complex[] fEven = forwardFFT(even);
        Complex[] fOdd = forwardFFT(odd);
        Complex[] freq = new Complex[n];
        for (int k = 0; k < n / 2; k++) {
            double angle = -2.0 * Math.PI * k / n;
            Complex w = new Complex(Math.cos(angle), Math.sin(angle));
            freq[k] = fEven[k].add(w.multiply(fOdd[k]));
            freq[k + n / 2] = fEven[k].subtract(w.multiply(fOdd[k]));
        }
        return freq;
    }

    private Complex[] inverseFFT(Complex[] x) {
        int n = x.length;
        Complex[] y = new Complex[n];
        for (int i = 0; i < n; i++) y[i] = x[i].conjugate();
        y = forwardFFT(y);
        for (int i = 0; i < n; i++) y[i] = y[i].conjugate().divide(n);
        return y;
    }

    public int getTruncation() { return truncation; }
}
