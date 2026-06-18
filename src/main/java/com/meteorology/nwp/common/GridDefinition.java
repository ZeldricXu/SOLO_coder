package com.meteorology.nwp.common;

import java.io.Serializable;

public class GridDefinition implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int nx;
    private final int ny;
    private final int nz;
    private final double earthRadius;
    private final double omega;
    private final double dx;
    private final double dy;

    public GridDefinition(int nx, int ny, double earthRadius) {
        this(nx, ny, 1, earthRadius);
    }

    public GridDefinition(int nx, int ny, int nz, double earthRadius) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.earthRadius = earthRadius;
        this.omega = 7.292e-5;
        this.dx = 2.0 * Math.PI * earthRadius / nx;
        this.dy = Math.PI * earthRadius / ny;
    }

    public int getNX() { return nx; }
    public int getNY() { return ny; }
    public int getNZ() { return nz; }
    public double getEarthRadius() { return earthRadius; }
    public double getOmega() { return omega; }

    public double getLat(int j) {
        return -90.0 + 180.0 * j / (ny - 1);
    }

    public double getLon(int i) {
        return 360.0 * i / nx;
    }

    public double getDXMeters(int j) {
        double latRad = Math.toRadians(getLat(j));
        double cosLat = Math.max(0.01, Math.cos(latRad));
        return 2.0 * Math.PI * earthRadius * cosLat / nx;
    }

    public double getDYMeters(int j) {
        return Math.PI * earthRadius / ny;
    }

    public double getFCoriolis(int j) {
        return 2.0 * omega * Math.sin(Math.toRadians(getLat(j)));
    }

    public double getCellArea(int j) {
        double dxM = getDXMeters(j);
        double dyM = getDYMeters(j);
        return dxM * dyM;
    }

    @Override
    public String toString() {
        return String.format("GridDefinition(%dx%dx%d, R=%.0fkm)", nx, ny, nz, earthRadius / 1000);
    }
}
