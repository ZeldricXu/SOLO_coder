package com.meteorology.nwp.assimilation;

import com.meteorology.nwp.common.VariableType;

import java.time.Instant;

public class Observation {
    public enum ObsType {
        SURFACE_STATION, UPPER_AIR_SOUNDING, SATELLITE_RADIANCE,
        RADAR_REFLECTIVITY, AIRCRAFT, GPS_RO, AMV
    }

    public enum Platform {
        LAND_STATION, SHIP, BUOY, RADIOSONDE,
        METEOSAT, GOES, HIMAWARI, MODIS,
        NEXRAD, DOPPLER_RADAR, AIRCRAFT_REPORT
    }

    public final ObsType type;
    public final Platform platform;
    public final String stationId;
    public final Instant obsTime;
    public final double longitude;
    public final double latitude;
    public final double pressure;
    public final double elevation;
    public final VariableType variable;
    public final double value;
    public final double error;
    public final double quality;
    public final int[] gridLocation;
    public final double[] bilinearWeights;

    public Observation(ObsType type, Platform platform, String stationId, Instant obsTime,
                       double longitude, double latitude, double pressure, double elevation,
                       VariableType variable, double value, double error, double quality) {
        this.type = type;
        this.platform = platform;
        this.stationId = stationId;
        this.obsTime = obsTime;
        this.longitude = normalizeLon(longitude);
        this.latitude = Math.max(-90, Math.min(90, latitude));
        this.pressure = pressure;
        this.elevation = elevation;
        this.variable = variable;
        this.value = value;
        this.error = Math.max(1e-10, error);
        this.quality = quality;
        this.gridLocation = new int[4];
        this.bilinearWeights = new double[4];
    }

    private double normalizeLon(double lon) {
        while (lon < 0) lon += 360;
        while (lon >= 360) lon -= 360;
        return lon;
    }

    public double effectiveError() {
        return error / Math.max(0.1, quality);
    }

    public double inverseErrorVariance() {
        double e = effectiveError();
        return 1.0 / (e * e);
    }

    public void computeBilinearWeights(int nx, int ny, double lonMin, double latMin,
                                        double dLon, double dLat) {
        double xNorm = (longitude - lonMin) / dLon;
        double yNorm = (latitude - latMin) / dLat;
        int i0 = (int) Math.floor(xNorm);
        int j0 = (int) Math.floor(yNorm);
        double fx = xNorm - i0;
        double fy = yNorm - j0;
        i0 = Math.max(0, Math.min(nx - 2, i0));
        j0 = Math.max(0, Math.min(ny - 2, j0));
        gridLocation[0] = i0; gridLocation[1] = j0;
        gridLocation[2] = i0 + 1; gridLocation[3] = j0 + 1;
        bilinearWeights[0] = (1 - fx) * (1 - fy);
        bilinearWeights[1] = fx * (1 - fy);
        bilinearWeights[2] = (1 - fx) * fy;
        bilinearWeights[3] = fx * fy;
    }

    public boolean isValid() {
        return Double.isFinite(value)
            && Double.isFinite(longitude) && Double.isFinite(latitude)
            && quality > 0.01 && error > 1e-20;
    }

    @Override
    public String toString() {
        return String.format("Obs[%s/%s %s %.4f,%.4f %.1fhPa %s=%.3f±%.3f q=%.2f]",
                type, platform, stationId, longitude, latitude, pressure / 100.0,
                variable, value, error, quality);
    }
}
