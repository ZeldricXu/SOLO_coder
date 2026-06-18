package com.meteorology.nwp.test;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

public final class TestDataFactory {
    private static final Logger logger = LoggerFactory.getLogger(TestDataFactory.class);
    private static final Random RANDOM = new Random(42);

    private TestDataFactory() {}

    public static NWPConfig createTestConfig() {
        return new NWPConfig("test-application.conf");
    }

    public static NWPConfig createSmallConfig(int nx, int ny, int nz) {
        System.setProperty("nwp.grid.nx", String.valueOf(nx));
        System.setProperty("nwp.grid.ny", String.valueOf(ny));
        System.setProperty("nwp.grid.nz", String.valueOf(nz));
        System.setProperty("nwp.dynamics.timeStep", "300");
        System.setProperty("nwp.parallel.numPartitionsX", "2");
        System.setProperty("nwp.parallel.numPartitionsY", "2");
        System.setProperty("nwp.parallel.haloWidth", "2");
        return new NWPConfig();
    }

    public static DataField create2DConstantField(int nx, int ny, double value) {
        DataField f = new DataField(nx, ny);
        f.fill(value);
        return f;
    }

    public static DataField create3DLinearField(int nx, int ny, int nz,
                                                 double min, double max, int axis) {
        DataField f = new DataField(nx, ny, nz);
        int n = switch (axis) {
            case 0 -> nx; case 1 -> ny; case 2 -> nz;
            default -> nx;
        };
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    int idx = i + nx * (j + ny * k);
                    double pos = switch (axis) {
                        case 0 -> (double) i / (nx - 1);
                        case 1 -> (double) j / (ny - 1);
                        case 2 -> (double) k / (nz - 1);
                        default -> 0;
                    };
                    f.set(idx, min + pos * (max - min));
                }
            }
        }
        return f;
    }

    public static DataField create2DGaussian(int nx, int ny,
                                              double xCenter, double yCenter,
                                              double sigmaX, double sigmaY, double amplitude) {
        DataField f = new DataField(nx, ny);
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                double dx = (i - xCenter) / sigmaX;
                double dy = (j - yCenter) / sigmaY;
                double v = amplitude * Math.exp(-0.5 * (dx * dx + dy * dy));
                f.set(i + nx * j, v);
            }
        }
        return f;
    }

    public static DataField createSinusoidal2D(int nx, int ny,
                                                double waveNumberX, double waveNumberY,
                                                double amplitude, double phase) {
        DataField f = new DataField(nx, ny);
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                double x = 2 * Math.PI * i / nx * waveNumberX + phase;
                double y = 2 * Math.PI * j / ny * waveNumberY;
                f.set(i + nx * j, amplitude * Math.sin(x) * Math.cos(y));
            }
        }
        return f;
    }

    public static DataField createSinusoidal3D(int nx, int ny, int nz,
                                                double wx, double wy, double wz,
                                                double amplitude) {
        DataField f = new DataField(nx, ny, nz);
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    double x = 2 * Math.PI * i / nx * wx;
                    double y = 2 * Math.PI * j / ny * wy;
                    double z = 2 * Math.PI * k / nz * wz;
                    f.set(i + nx * (j + ny * k), amplitude * Math.sin(x) * Math.cos(y) * Math.sin(z));
                }
            }
        }
        return f;
    }

    public static ModelState createStandardAtmosphere(NWPConfig config, Instant time) {
        int nx = config.getNX(), ny = config.getNY(), nz = config.getNZ();
        ModelState state = new ModelState(config, nx, ny, nz);
        state.initializationTime = time.getEpochSecond();
        state.validTime = time.getEpochSecond();
        double[] sigma = config.getSigmaLevels();
        double psfc = 101325.0;
        double t0 = 288.15;
        double gamma = 0.0065;
        double tTop = 216.65;
        double pTop = 22632.0;
        double z0 = 0;
        DataField T = state.fields.computeIfAbsent(VariableType.T,
                v -> new DataField(nx, ny, nz));
        DataField P = state.fields.computeIfAbsent(VariableType.PSFC,
                v -> new DataField(nx, ny));
        DataField U = state.fields.computeIfAbsent(VariableType.U,
                v -> new DataField(nx, ny, nz));
        DataField V = state.fields.computeIfAbsent(VariableType.V,
                v -> new DataField(nx, ny, nz));
        DataField QV = state.fields.computeIfAbsent(VariableType.QV,
                v -> new DataField(nx, ny, nz));
        P.fill(psfc);
        U.fill(0); V.fill(0);
        for (int k = 0; k < nz; k++) {
            double sig = sigma[k];
            double pressure = sig * psfc;
            double tK;
            if (pressure > pTop) {
                tK = t0 - gamma * 44330 * (1 - Math.pow(pressure / psfc, PhysicsConstants.Rd * gamma / PhysicsConstants.G));
            } else {
                tK = tTop;
            }
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    T.set(i + nx * (j + ny * k), tK);
                    double es = PhysicsConstants.saturationVaporPressure(tK);
                    double rh = Math.max(0.1, 0.8 - 0.6 * k / nz);
                    double qv = PhysicsConstants.EPSILON * rh * es / Math.max(1, pressure - es);
                    QV.set(i + nx * (j + ny * k), Math.max(1e-6, qv));
                }
            }
        }
        state.ensurePrognosticFields(config);
        state.computeDiagnosticFields(config);
        return state;
    }

    public static ModelState createWilliamsonTest2(NWPConfig config, Instant time) {
        int nx = config.getNX(), ny = config.getNY(), nz = config.getNZ();
        ModelState state = new ModelState(config, nx, ny, nz);
        state.initializationTime = time.getEpochSecond();
        state.validTime = time.getEpochSecond();
        GridDefinition grid = config.getGrid();
        double omega = 2 * Math.PI / 432000.0;
        double u0 = 2 * Math.PI * 6.371e6 / (5 * 86400);
        double alpha = 0.0;
        double g = 9.80616;
        DataField h = state.fields.computeIfAbsent(VariableType.GEOPOTENTIAL,
                v -> new DataField(nx, ny));
        DataField u = state.fields.computeIfAbsent(VariableType.U,
                v -> new DataField(nx, ny, 1));
        DataField v = state.fields.computeIfAbsent(VariableType.V,
                v -> new DataField(nx, ny, 1));
        DataField hField = new DataField(nx, ny);
        for (int j = 0; j < ny; j++) {
            double lat = grid.latRad[j];
            double cosLat = Math.cos(lat);
            double sinLat = Math.sin(lat);
            for (int i = 0; i < nx; i++) {
                double lon = Math.toRadians(grid.lonMin + i * grid.dLon);
                double cosLon = Math.cos(lon - alpha);
                double sinLon = Math.sin(lon - alpha);
                double uu = u0 * cosLat * (cosLon * cosLat + sinLat * Math.sin(alpha));
                double vv = -u0 * cosLat * sinLon * Math.cos(alpha);
                u.set(i + nx * (j + ny * 0), uu);
                v.set(i + nx * (j + ny * 0), vv);
                double a = 6.371e6;
                double f = omega * a * u0 * Math.pow(cosLat, 2)
                        + 0.5 * u0 * u0 * Math.pow(cosLat, 2);
                hField.set(i + nx * j, f / g);
            }
        }
        state.fields.put(VariableType.GEOPOTENTIAL, hField);
        state.ensurePrognosticFields(config);
        return state;
    }

    public static ModelState createWilliamsonTest5(NWPConfig config, Instant time) {
        int nx = config.getNX(), ny = config.getNY();
        ModelState state = createWilliamsonTest2(config, time);
        GridDefinition grid = config.getGrid();
        DataField h = state.fields.get(VariableType.GEOPOTENTIAL);
        double h0 = 8000.0;
        double earthRad = 6.371e6;
        double g = 9.80616;
        double omega = 7.848e-6;
        double K = 7.848e-6;
        double R = 4.0;
        double lat0 = Math.toRadians(30);
        double lon0 = Math.toRadians(270);
        for (int j = 0; j < ny; j++) {
            double lat = grid.latRad[j];
            for (int i = 0; i < nx; i++) {
                double lon = Math.toRadians(grid.lonMin + i * grid.dLon);
                double cosD = Math.sin(lat0) * Math.sin(lat)
                        + Math.cos(lat0) * Math.cos(lat) * Math.cos(lon - lon0);
                double hCos = h0 / g * Math.cos(Math.PI * cosD / R);
                h.add(i + nx * j, hCos);
            }
        }
        return state;
    }

    public static byte[] createCorruptedGrib() {
        byte[] data = new byte[2048];
        RANDOM.nextBytes(data);
        System.arraycopy("GRIB".getBytes(), 0, data, 0, 4);
        data[7] = 2;
        data[8] = 2;
        return data;
    }

    public static byte[] createCorruptedGribPartial(int validLength) {
        byte[] data = new byte[validLength + 500];
        RANDOM.nextBytes(data);
        System.arraycopy("GRIB".getBytes(), 0, data, 0, 4);
        data[7] = 2;
        return Arrays.copyOf(data, validLength);
    }

    public static List<Observation> createSyntheticObs(ModelState reference, int count,
                                                        double noiseSigma, double quality) {
        NWPConfig config = reference.config;
        GridDefinition grid = config.getGrid();
        List<Observation> obs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double lon = grid.lonMin + RANDOM.nextDouble() * 360;
            double lat = grid.latMin + RANDOM.nextDouble() * 180;
            double pressure = 101325 * Math.exp(-RANDOM.nextDouble() * 8);
            int iGrid = (int) Math.floor((lon - grid.lonMin) / grid.dLon);
            int jGrid = (int) Math.floor((lat - grid.latMin) / grid.dLat);
            iGrid = Math.max(0, Math.min(grid.nx - 1, iGrid));
            jGrid = Math.max(0, Math.min(grid.ny - 1, jGrid));
            double trueVal = 288 + 10 * Math.sin(lat);
            double noise = noiseSigma * RANDOM.nextGaussian();
            Instant time = Instant.ofEpochSecond(reference.initializationTime
                    + (long) (RANDOM.nextGaussian() * 3600));
            Observation o = new Observation(
                    Observation.ObsType.SURFACE_STATION,
                    Observation.Platform.LAND_STATION,
                    String.format("SYN-%05d", i),
                    time, lon, lat, pressure, 0,
                    VariableType.T2, trueVal + noise, noiseSigma, quality
            );
            obs.add(o);
        }
        return obs;
    }

    public static ModelState injectNaN(ModelState state, VariableType var, double fraction) {
        DataField f = state.fields.get(var);
        if (f == null) return state;
        int n = (int) (f.getSize() * fraction);
        for (int k = 0; k < n; k++) {
            int idx = RANDOM.nextInt(f.getSize());
            f.set(idx, Double.NaN);
        }
        return state;
    }

    public static int countNaN(DataField f) {
        int count = 0;
        for (int i = 0; i < f.getSize(); i++) {
            if (Double.isNaN(f.get(i))) count++;
        }
        return count;
    }

    public static long seed() { return 42L; }
}
