package com.meteorology.nwp.postprocess;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

public class VerificationStats implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(VerificationStats.class);

    public static class Score implements Serializable {
        public VariableType variable;
        public int forecastHour;
        public int nPoints;
        public double rmse;
        public double bias;
        public double mae;
        public double correlation;
        public double varianceForecast;
        public double varianceObserved;
        public double[] quantiles;
        public Map<String, Double> thresholds;
        public double si;
        public Instant validTime;

        @Override
        public String toString() {
            return String.format("%s +%dh N=%d RMSE=%.3f BIAS=%+.3f R=%.3f SI=%.3f",
                    variable, forecastHour, nPoints, rmse, bias, correlation, si);
        }
    }

    public static class StationObs {
        public final String id;
        public final double longitude;
        public final double latitude;
        public final double elevation;
        public final TreeMap<Instant, Map<VariableType, Double>> timeSeries = new TreeMap<>();

        public StationObs(String id, double lon, double lat, double elev) {
            this.id = id; this.longitude = lon; this.latitude = lat; this.elevation = elev;
        }

        public void addObservation(Instant time, VariableType v, double value) {
            timeSeries.computeIfAbsent(time, k -> new EnumMap<>(VariableType.class)).put(v, value);
        }
    }

    private final NWPConfig config;
    private final GridDefinition grid;
    private final int nx, ny, nz;
    private final Map<String, double[]> stationInterpWeights = new HashMap<>();

    public VerificationStats(NWPConfig config) {
        this.config = config;
        this.grid = config.getGrid();
        this.nx = config.getNX();
        this.ny = config.getNY();
        this.nz = config.getNZ();
    }

    public Map<VariableType, Map<Integer, Score>> verifyForecastSeries(
            List<ModelState> forecasts, List<StationObs> observations) {
        Map<VariableType, Map<Integer, Score>> allScores = new EnumMap<>(VariableType.class);
        for (VariableType v : VariableType.values()) {
            allScores.put(v, new TreeMap<>());
        }
        for (ModelState fcst : forecasts) {
            int fHour = fcst.forecastStep;
            Instant vt = Instant.ofEpochSecond(fcst.validTime);
            Map<String, Map<VariableType, Double>> stationValues = extractAtStations(fcst, observations);
            for (VariableType var : VariableType.values()) {
                if (!isVerifiable(var)) continue;
                List<double[]> pairs = collectObsPairs(fcsts.indexOf(fcst), observations, stationValues, var, vt);
                if (pairs.size() < 10) continue;
                Score sc = computeScore(pairs, var, fHour, vt);
                allScores.get(var).put(fHour, sc);
                logger.info("检验: {}", sc);
            }
        }
        return allScores;
    }

    private boolean isVerifiable(VariableType v) {
        return v == VariableType.T2 || v == VariableType.TEMPERATURE_2M
            || v == VariableType.U10 || v == VariableType.V10
            || v == VariableType.RH2 || v == VariableType.MSLP
            || v == VariableType.T || v == VariableType.U || v == VariableType.V
            || v == VariableType.PRECIP;
    }

    public Map<String, Map<VariableType, Double>> extractAtStations(ModelState state, List<StationObs> stations) {
        Map<String, Map<VariableType, Double>> result = new HashMap<>();
        for (StationObs s : stations) {
            Map<VariableType, Double> vals = new EnumMap<>(VariableType.class);
            for (VariableType var : VariableType.values()) {
                DataField f = state.fields.get(var);
                if (f == null) continue;
                double v;
                if (var.is3D()) {
                    v = interpolate3DStation(f, s.longitude, s.latitude, s.elevation);
                } else {
                    v = interpolate2DStation(f, s.longitude, s.latitude);
                }
                if (Double.isFinite(v)) vals.put(var, v);
            }
            result.put(s.id, vals);
        }
        return result;
    }

    private double interpolate2DStation(DataField f, double lon, double lat) {
        double xn = (lon - grid.lonMin) / grid.dLon;
        double yn = (lat - grid.latMin) / grid.dLat;
        int i0 = (int) Math.floor(xn);
        int j0 = (int) Math.floor(yn);
        if (i0 < 0 || i0 >= nx - 1 || j0 < 0 || j0 >= ny - 1) return Double.NaN;
        double fx = xn - i0, fy = yn - j0;
        double v00 = f.get(i0 + nx * j0);
        double v10 = f.get(i0 + 1 + nx * j0);
        double v01 = f.get(i0 + nx * (j0 + 1));
        double v11 = f.get(i0 + 1 + nx * (j0 + 1));
        return (1 - fx) * (1 - fy) * v00 + fx * (1 - fy) * v10
             + (1 - fx) * fy * v01 + fx * fy * v11;
    }

    private double interpolate3DStation(DataField f, double lon, double lat, double elevMeters) {
        double presApprox = 101325 * Math.exp(-elevMeters / 8400.0);
        double[] wx = new double[4];
        int[] ix = new int[4];
        computeVerticalInterpWeights(presApprox, wx, ix);
        double v = 0;
        for (int w = 0; w < 4; w++) {
            if (wx[w] == 0 || ix[w] < 0 || ix[w] >= nz) continue;
            int k = ix[w];
            double xn = (lon - grid.lonMin) / grid.dLon;
            double yn = (lat - grid.latMin) / grid.dLat;
            int i0 = (int) Math.floor(xn);
            int j0 = (int) Math.floor(yn);
            if (i0 < 0 || i0 >= nx - 1 || j0 < 0 || j0 >= ny - 1) continue;
            double fx = xn - i0, fy = yn - j0;
            double v00 = f.get(i0 + nx * (j0 + ny * k));
            double v10 = f.get(i0 + 1 + nx * (j0 + ny * k));
            double v01 = f.get(i0 + nx * (j0 + 1 + ny * k));
            double v11 = f.get(i0 + 1 + nx * (j0 + 1 + ny * k));
            double h = (1 - fx) * (1 - fy) * v00 + fx * (1 - fy) * v10
                     + (1 - fx) * fy * v01 + fx * fy * v11;
            v += wx[w] * h;
        }
        return v;
    }

    private void computeVerticalInterpWeights(double pressure, double[] w, int[] idx) {
        double[] sigma = config.getSigmaLevels();
        double psfc = 101325.0;
        double[] plevels = new double[nz];
        for (int k = 0; k < nz; k++) plevels[k] = sigma[k] * psfc;
        for (int i = 0; i < 4; i++) { w[i] = 0; idx[i] = -1; }
        int kFound = -1;
        for (int k = 0; k < nz - 1; k++) {
            boolean in = (plevels[k] >= pressure && plevels[k + 1] <= pressure)
                      || (plevels[k] <= pressure && plevels[k + 1] >= pressure);
            if (in) { kFound = k; break; }
        }
        if (kFound < 0) {
            if (pressure > plevels[0]) { w[0] = 1; idx[0] = 0; }
            else { w[0] = 1; idx[0] = nz - 1; }
            return;
        }
        double p1 = plevels[kFound], p2 = plevels[kFound + 1];
        double frac = (Math.log(p2) - Math.log(Math.max(1, pressure))) / (Math.log(p2) - Math.log(p1));
        if (kFound > 0) { w[0] = 0.1 * (1 - frac); idx[0] = kFound - 1; }
        w[1] = frac; idx[1] = kFound;
        w[2] = 1.0 - frac; idx[2] = kFound + 1;
        if (kFound + 2 < nz) { w[3] = 0.1 * frac; idx[3] = kFound + 2; }
        double s = 0; for (double v : w) s += v;
        if (s > 0) for (int i = 0; i < 4; i++) w[i] /= s;
    }

    private List<double[]> collectObsPairs(int fIdx, List<StationObs> stations,
                                            Map<String, Map<VariableType, Double>> stationValues,
                                            VariableType var, Instant validTime) {
        List<double[]> pairs = new ArrayList<>();
        long tolSec = 1800;
        for (StationObs s : stations) {
            Map<VariableType, Double> fVals = stationValues.get(s.id);
            if (fVals == null || !fVals.containsKey(var)) continue;
            double fv = fVals.get(var);
            if (!Double.isFinite(fv)) continue;
            Map.Entry<Instant, Map<VariableType, Double>> best = null;
            long bestD = Long.MAX_VALUE;
            for (Map.Entry<Instant, Map<VariableType, Double>> e : s.timeSeries.entrySet()) {
                long d = Math.abs(e.getKey().getEpochSecond() - validTime.getEpochSecond());
                if (d < bestD && d < tolSec) { best = e; bestD = d; }
            }
            if (best != null && best.getValue().containsKey(var)) {
                pairs.add(new double[] {fv, best.getValue().get(var)});
            }
        }
        return pairs;
    }

    public Score computeScore(List<double[]> pairs, VariableType var, int fHour, Instant vt) {
        Score s = new Score();
        s.variable = var; s.forecastHour = fHour; s.validTime = vt;
        int n = pairs.size(); s.nPoints = n;
        if (n < 2) return s;
        double sf = 0, so = 0, sff = 0, soo = 0, sfo = 0, sabs = 0;
        double[] errors = new double[n];
        for (int i = 0; i < n; i++) {
            double f = pairs.get(i)[0], o = pairs.get(i)[1];
            double e = f - o;
            sf += f; so += o; sff += f * f; soo += o * o; sfo += f * o;
            sabs += Math.abs(e);
            errors[i] = e;
        }
        double mf = sf / n, mo = so / n;
        s.bias = mf - mo;
        s.rmse = Math.sqrt(sff / n - 2 * sfo / n + soo / n);
        s.mae = sabs / n;
        double vf = sff / n - mf * mf;
        double vo = soo / n - mo * mo;
        s.varianceForecast = Math.max(0, vf);
        s.varianceObserved = Math.max(0, vo);
        s.correlation = (vf > 0 && vo > 0) ? (sfo / n - mf * mo) / Math.sqrt(vf * vo) : 0;
        s.correlation = Math.max(-1, Math.min(1, s.correlation));
        s.si = s.rmse / Math.max(1e-10, Math.sqrt(vo));
        Arrays.sort(errors);
        s.quantiles = new double[] {
            errors[0],
            errors[(int) (0.05 * (n - 1))],
            errors[(int) (0.25 * (n - 1))],
            errors[(int) (0.5  * (n - 1))],
            errors[(int) (0.75 * (n - 1))],
            errors[(int) (0.95 * (n - 1))],
            errors[n - 1]
        };
        s.thresholds = new LinkedHashMap<>();
        double[] ths = (var == VariableType.PRECIP) ? new double[] {0.1, 1.0, 10.0, 50.0}
                                                    : new double[] {0.5, 1.0, 2.0, 3.0};
        for (double th : ths) {
            int podHits = 0, podF = 0, farWarn = 0, farF = 0;
            for (double[] p : pairs) {
                boolean fOk = p[0] >= th, oOk = p[1] >= th;
                if (oOk) { podF++; if (fOk) podHits++; }
                if (fOk) { farF++; if (!oOk) farWarn++; }
            }
            double pod = podF > 0 ? (double) podHits / podF : 0;
            double far = farF > 0 ? (double) farWarn / farF : 0;
            s.thresholds.put(String.format("POD@%.1f", th), pod);
            s.thresholds.put(String.format("FAR@%.1f", th), far);
            s.thresholds.put(String.format("TS@%.1f", th), pod * (1 - far));
        }
        return s;
    }

    public Map<VariableType, Score> verifySlice(List<double[]> pairs, VariableType var, int fHour) {
        Map<VariableType, Score> m = new EnumMap<>(VariableType.class);
        m.put(var, computeScore(pairs, var, fHour, Instant.now()));
        return m;
    }

    public static List<StationObs> generateDemoStations(int n) {
        List<StationObs> list = new ArrayList<>(n);
        Random r = new Random(42);
        String[] provinces = {"BJ","SH","GZ","CD","XA","NJ","WH","HZ","SY","UR"};
        for (int i = 0; i < n; i++) {
            String id = "STN-" + String.format("%05d", i + 50000);
            double lon = 73 + 65 * r.nextDouble();
            double lat = 18 + 35 * r.nextDouble();
            double elev = 0 + 4500 * r.nextDouble();
            list.add(new StationObs(id, lon, lat, elev));
        }
        return list;
    }
}
