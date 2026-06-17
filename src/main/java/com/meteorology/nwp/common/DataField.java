package com.meteorology.nwp.common;

import java.io.Serializable;
import java.util.Arrays;

public class DataField implements Serializable {
    private final VariableType type;
    private final int nx, ny, nz;
    private final double[] data;
    private final boolean is3D;

    public DataField(VariableType type, int nx, int ny, int nz) {
        this.type = type;
        this.nx = nx;
        this.ny = ny;
        this.is3D = type.is3D();
        this.nz = is3D ? nz : 1;
        this.data = new double[nx * ny * this.nz];
    }

    public DataField(VariableType type, int nx, int ny, int nz, double[] data) {
        this.type = type;
        this.nx = nx;
        this.ny = ny;
        this.is3D = type.is3D();
        this.nz = is3D ? nz : 1;
        this.data = data;
    }

    public VariableType getType() { return type; }
    public int getNX() { return nx; }
    public int getNY() { return ny; }
    public int getNZ() { return nz; }
    public boolean is3D() { return is3D; }
    public double[] getData() { return data; }

    public final double get(int i, int j) {
        return data[i + nx * j];
    }

    public final double get(int i, int j, int k) {
        return data[i + nx * (j + ny * k)];
    }

    public final void set(int i, int j, double value) {
        data[i + nx * j] = value;
    }

    public final void set(int i, int j, int k, double value) {
        data[i + nx * (j + ny * k)] = value;
    }

    public final void add(int i, int j, double value) {
        data[i + nx * j] += value;
    }

    public final void add(int i, int j, int k, double value) {
        data[i + nx * (j + ny * k)] += value;
    }

    public void fill(double value) {
        Arrays.fill(data, value);
    }

    public void copyFrom(DataField other) {
        System.arraycopy(other.data, 0, this.data, 0, Math.min(data.length, other.data.length));
    }

    public DataField deepCopy() {
        DataField copy = new DataField(type, nx, ny, nz);
        System.arraycopy(this.data, 0, copy.data, 0, this.data.length);
        return copy;
    }

    public double min() {
        double min = Double.POSITIVE_INFINITY;
        for (double v : data) if (v < min) min = v;
        return min;
    }

    public double max() {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : data) if (v > max) max = v;
        return max;
    }

    public double mean() {
        double sum = 0.0;
        for (double v : data) sum += v;
        return sum / data.length;
    }

    public double rms() {
        double sumSq = 0.0;
        for (double v : data) sumSq += v * v;
        return Math.sqrt(sumSq / data.length);
    }

    public void addScalar(double scalar) {
        for (int i = 0; i < data.length; i++) data[i] += scalar;
    }

    public void multiplyScalar(double scalar) {
        for (int i = 0; i < data.length; i++) data[i] *= scalar;
    }

    public void addField(DataField other, double factor) {
        double[] odata = other.data;
        for (int i = 0; i < data.length; i++) data[i] += factor * odata[i];
    }

    public double horizontalDiffusion(double[] coef, int halo) {
        double[] tmp = new double[data.length];
        double maxChange = 0.0;
        for (int k = 0; k < nz; k++) {
            for (int j = halo; j < ny - halo; j++) {
                double c = coef[j];
                for (int i = halo; i < nx - halo; i++) {
                    int idx = i + nx * (j + ny * k);
                    double lap = get(i + 1, j, k) + get(i - 1, j, k) + get(i, j + 1, k) + get(i, j - 1, k) - 4.0 * get(i, j, k);
                    double newVal = get(i, j, k) + c * lap;
                    double diff = Math.abs(newVal - get(i, j, k));
                    if (diff > maxChange) maxChange = diff;
                    tmp[idx] = newVal;
                }
            }
        }
        for (int k = 0; k < nz; k++) {
            for (int j = halo; j < ny - halo; j++) {
                for (int i = halo; i < nx - halo; i++) {
                    int idx = i + nx * (j + ny * k);
                    data[idx] = tmp[idx];
                }
            }
        }
        return maxChange;
    }

    public void applyPeriodicBC(int halo) {
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int h = 0; h < halo; h++) {
                    double left = get(nx - halo + h, j, k);
                    double right = get(halo - 1 - h, j, k);
                    set(h, j, k, left);
                    set(nx - 1 - h, j, k, right);
                }
            }
        }
    }

    public void applyPolarBC(int halo) {
        for (int k = 0; k < nz; k++) {
            for (int i = 0; i < nx; i++) {
                int iSym = (nx / 2 - i + nx) % nx;
                for (int h = 0; h < halo; h++) {
                    set(i, h, k, get(iSym, halo, k) * -1.0);
                    set(i, ny - 1 - h, k, get(iSym, ny - 1 - halo, k) * -1.0);
                }
            }
        }
    }
}
