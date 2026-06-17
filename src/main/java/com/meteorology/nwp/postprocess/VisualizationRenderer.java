package com.meteorology.nwp.postprocess;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

public class VisualizationRenderer implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(VisualizationRenderer.class);

    public static class Colormap {
        public final String name;
        public final Color[] colors;
        public final double vmin, vmax;
        public Colormap(String name, Color[] colors, double vmin, double vmax) {
            this.name = name; this.colors = colors;
            this.vmin = vmin; this.vmax = vmax;
        }
        public Color valueToColor(double v) {
            if (v <= vmin) return colors[0];
            if (v >= vmax) return colors[colors.length - 1];
            double frac = (v - vmin) / (vmax - vmin);
            int idx = (int) (frac * (colors.length - 1));
            idx = Math.max(0, Math.min(colors.length - 2, idx));
            double subFrac = frac * (colors.length - 1) - idx;
            return blend(colors[idx], colors[idx + 1], subFrac);
        }
        private static Color blend(Color a, Color b, double t) {
            return new Color(
                    (int) (a.getRed()   + (b.getRed()   - a.getRed())   * t),
                    (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * t),
                    (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t)
            );
        }
    }

    public static class MapRenderOptions {
        public int width = 1200, height = 800;
        public String title;
        public String units;
        public VariableType variable;
        public int levelK = 0;
        public boolean drawCountries = true;
        public boolean drawProvinces = true;
        public boolean drawGrid = true;
        public String colormap = "viridis";
        public Double vmin, vmax;
        public String outputPath;
        public double lonMin = 0, lonMax = 360;
        public double latMin = -90, latMax = 90;
        public boolean drawWindBarbs = false;
        public int barbSpacing = 20;
    }

    private final NWPConfig config;
    private final GridDefinition grid;
    private final int nx, ny;
    private final Map<String, Colormap> colormaps = new HashMap<>();
    private static final DateTimeFormatter TITLE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm UTC").withZone(ZoneOffset.UTC);

    public VisualizationRenderer(NWPConfig config) {
        this.config = config;
        this.grid = config.getGrid();
        this.nx = config.getNX();
        this.ny = config.getNY();
        buildColormaps();
    }

    private void buildColormaps() {
        colormaps.put("viridis", buildViridis());
        colormaps.put("temperature", buildTemperature());
        colormaps.put("rainbow", buildRainbow());
        colormaps.put("precipitation", buildPrecip());
        colormaps.put("wind", buildWindSpeed());
        colormaps.put("humidity", buildHumidity());
        colormaps.put("pressure", buildPressure());
    }

    private Colormap buildViridis() {
        int n = 256;
        Color[] cs = new Color[n];
        for (int i = 0; i < n; i++) {
            float t = (float) i / (n - 1);
            cs[i] = new Color(
                    (int) (255 * Math.min(1, 0.17 + t * 1.3 - 0.6 * t * t)),
                    (int) (255 * Math.min(1, Math.max(0, 0.004 + 1.5 * t - 1.2 * t * t))),
                    (int) (255 * Math.min(1, Math.max(0, 0.35 + 0.8 * t * (1 - t * 0.6))))
            );
        }
        return new Colormap("viridis", cs, 0, 1);
    }

    private Colormap buildTemperature() {
        Color[] stops = {
                new Color(0, 0, 80), new Color(0, 50, 150), new Color(30, 120, 220),
                new Color(120, 200, 255), new Color(210, 255, 220), new Color(255, 255, 180),
                new Color(255, 200, 90), new Color(255, 120, 50), new Color(230, 60, 20),
                new Color(150, 0, 30), new Color(80, 0, 80)
        };
        return new Colormap("temperature", expandStops(stops, 256), -40, 45);
    }

    private Colormap buildRainbow() {
        Color[] stops = {
                Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.ORANGE, Color.RED,
                new Color(255, 0, 255)
        };
        return new Colormap("rainbow", expandStops(stops, 256), 0, 100);
    }

    private Colormap buildPrecip() {
        Color[] stops = {
                new Color(255, 255, 255, 0), new Color(173, 216, 230, 80),
                new Color(70, 130, 180, 160), new Color(32, 178, 170, 200),
                new Color(50, 205, 50, 220), new Color(255, 255, 0, 230),
                new Color(255, 140, 0, 235), new Color(255, 0, 0, 240),
                new Color(178, 34, 34, 245), new Color(139, 0, 139, 250),
                new Color(75, 0, 130, 255)
        };
        return new Colormap("precipitation", expandStops(stops, 256), 0, 100);
    }

    private Colormap buildWindSpeed() {
        Color[] stops = {
                new Color(240, 248, 255), new Color(180, 210, 240),
                new Color(120, 180, 230), new Color(70, 140, 210),
                new Color(50, 205, 50), new Color(180, 255, 0),
                new Color(255, 255, 0), new Color(255, 165, 0),
                new Color(255, 90, 30), new Color(220, 20, 60),
                new Color(120, 0, 30)
        };
        return new Colormap("wind", expandStops(stops, 256), 0, 50);
    }

    private Colormap buildHumidity() {
        Color[] stops = {
                new Color(255, 230, 180), new Color(250, 250, 210),
                new Color(200, 240, 200), new Color(150, 220, 230),
                new Color(100, 180, 230), new Color(50, 130, 220),
                new Color(30, 70, 180)
        };
        return new Colormap("humidity", expandStops(stops, 256), 0, 100);
    }

    private Colormap buildPressure() {
        Color[] stops = {
                new Color(150, 0, 90), new Color(210, 60, 130),
                new Color(255, 140, 170), new Color(255, 220, 220),
                new Color(220, 240, 255), new Color(150, 200, 255),
                new Color(60, 130, 240), new Color(10, 50, 150)
        };
        return new Colormap("pressure", expandStops(stops, 256), 98000, 104000);
    }

    private Color[] expandStops(Color[] stops, int n) {
        Color[] out = new Color[n];
        for (int i = 0; i < n; i++) {
            float t = (float) i / (n - 1) * (stops.length - 1);
            int idx = (int) t;
            float f = t - idx;
            idx = Math.max(0, Math.min(stops.length - 2, idx));
            out[i] = Colormap.blend(stops[idx], stops[idx + 1], f);
        }
        return out;
    }

    public Colormap getDefaultColormap(VariableType var) {
        switch (var) {
            case T, T2, TEMPERATURE_2M: return colormaps.get("temperature");
            case PRECIP: return colormaps.get("precipitation");
            case U, V, U10, V10, WIND_SPEED: return colormaps.get("wind");
            case RH, RH2, RELATIVE_HUMIDITY: return colormaps.get("humidity");
            case PSFC, SLP, MSLP: return colormaps.get("pressure");
            default: return colormaps.get("viridis");
        }
    }

    public File renderField(ModelState state, MapRenderOptions opts) throws Exception {
        VariableType var = opts.variable;
        DataField field = state.fields.get(var);
        if (field == null) {
            throw new IllegalArgumentException("状态中不存在变量: " + var);
        }
        boolean is3D = field.getNDim() == 3;
        if (is3D) opts.levelK = Math.max(0, Math.min(nz() - 1, opts.levelK));
        double[][] vals = extractLayer(field, opts.levelK, is3D);
        if (opts.vmin == null) opts.vmin = quantile(vals, 0.02);
        if (opts.vmax == null) opts.vmax = quantile(vals, 0.98);
        if (Math.abs(opts.vmax - opts.vmin) < 1e-6) {
            opts.vmax = opts.vmin + 1.0;
        }
        Colormap cmap = colormaps.getOrDefault(opts.colormap, getDefaultColormap(var));
        Colormap effective = new Colormap(cmap.name, cmap.colors, opts.vmin, opts.vmax);
        BufferedImage img = new BufferedImage(opts.width, opts.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setColor(new Color(245, 245, 245));
        g2.fillRect(0, 0, opts.width, opts.height);
        drawDataGrid(g2, opts, vals, effective);
        if (opts.drawGrid) drawLatLonGrid(g2, opts);
        if (opts.drawCountries) drawWorldCoastline(g2, opts);
        if (opts.drawWindBarbs && var == VariableType.WIND_SPEED) {
            drawWindBarbs(g2, opts, state);
        }
        drawColorBar(g2, opts, effective);
        drawTitle(g2, opts, state);
        g2.dispose();
        File outFile = new File(opts.outputPath);
        outFile.getParentFile().mkdirs();
        ImageIO.write(img, "PNG", outFile);
        logger.info("渲染完成: {}x{} → {}", opts.width, opts.height, outFile.getAbsolutePath());
        return outFile;
    }

    private double[][] extractLayer(DataField f, int k, boolean is3D) {
        double[][] v = new double[ny][nx];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int idx = is3D ? (i + nx * (j + ny * k)) : (i + nx * j);
                v[j][i] = f.get(idx);
            }
        }
        return v;
    }

    private void drawDataGrid(Graphics2D g, MapRenderOptions opts, double[][] data, Colormap cmap) {
        int px, py;
        double dLon = (opts.lonMax - opts.lonMin);
        double dLat = (opts.latMax - opts.latMin);
        int padX = 80, padY = 70;
        int plotW = opts.width - padX - 60;
        int plotH = opts.height - padY - 90;
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                double lon = grid.lonMin + i * grid.dLon;
                double lat = grid.latMin + j * grid.dLat;
                if (lon < opts.lonMin || lon > opts.lonMax) continue;
                if (lat < opts.latMin || lat > opts.latMax) continue;
                double xNorm = (lon - opts.lonMin) / dLon;
                double yNorm = 1 - (lat - opts.latMin) / dLat;
                px = padX + (int) (xNorm * plotW);
                py = padY + (int) (yNorm * plotH);
                double v = data[j][i];
                Color c = cmap.valueToColor(v);
                int pw = Math.max(1, (int) (plotW / (nx * (dLon / 360.0))));
                int ph = Math.max(1, (int) (plotH / (ny * (dLat / 180.0))));
                g.setColor(c);
                g.fillRect(px - pw / 2, py - ph / 2, pw, ph);
            }
        }
        g.setColor(Color.BLACK);
        g.drawRect(padX, padY, plotW, plotH);
    }

    private void drawLatLonGrid(Graphics2D g, MapRenderOptions opts) {
        int padX = 80, padY = 70;
        int plotW = opts.width - padX - 60, plotH = opts.height - padY - 90;
        double dLon = opts.lonMax - opts.lonMin;
        double dLat = opts.latMax - opts.latMin;
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.setColor(new Color(0, 0, 0, 50));
        g.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1, new float[] {4, 4}, 0));
        for (int lonStep = 30; lonStep <= dLon; lonStep += 30) {
            for (double lon = Math.ceil(opts.lonMin / 30) * 30; lon <= opts.lonMax; lon += lonStep) {
                double xn = (lon - opts.lonMin) / dLon;
                int x = padX + (int) (xn * plotW);
                g.drawLine(x, padY, x, padY + plotH);
                g.setColor(Color.DARK_GRAY);
                g.drawString(String.format("%.0f°", lon), x - 10, padY + plotH + 15);
                g.setColor(new Color(0, 0, 0, 50));
                break;
            }
            break;
        }
        for (double lat = Math.ceil(opts.latMin / 30) * 30; lat <= opts.latMax; lat += 30) {
            double yn = 1 - (lat - opts.latMin) / dLat;
            int y = padY + (int) (yn * plotH);
            g.setColor(new Color(0, 0, 0, 50));
            g.drawLine(padX, y, padX + plotW, y);
            g.setColor(Color.DARK_GRAY);
            g.drawString(String.format("%+.0f°", lat), padX - 50, y + 4);
        }
        g.setStroke(new BasicStroke(1));
    }

    private void drawWorldCoastline(Graphics2D g, MapRenderOptions opts) {
        int padX = 80, padY = 70;
        int plotW = opts.width - padX - 60, plotH = opts.height - padY - 90;
        double dLon = opts.lonMax - opts.lonMin;
        double dLat = opts.latMax - opts.latMin;
        g.setColor(new Color(30, 30, 30, 200));
        g.setStroke(new BasicStroke(0.7f));
        Path2D outline = new Path2D.Double();
        boolean first = true;
        for (int m = 0; m < 6; m++) {
            double startLon, startLat, endLon, endLat, stepLat;
            switch (m) {
                case 0: startLon = 10; startLat = -45; stepLat = 1; break;
                case 1: startLon = -170; startLat = 25; stepLat = 1; break;
                case 2: startLon = -10; startLat = 70; stepLat = 1; break;
                case 3: startLon = 70; startLat = 0; stepLat = 1; break;
                case 4: startLon = 130; startLat = -40; stepLat = 1; break;
                default: startLon = -80; startLat = -60; stepLat = 1;
            }
            endLon = startLon + 80; endLat = startLat + 80;
            first = true;
            for (double lat = startLat; lat <= endLat; lat += stepLat) {
                double lng = startLon + Math.sin(lat * 0.1 + m) * 20;
                if (lng < -180) lng += 360;
                if (lng > 180) lng -= 360;
                double plotLng = (lng < 0) ? lng + 360 : lng;
                if (plotLng < opts.lonMin || plotLng > opts.lonMax) continue;
                if (lat < opts.latMin || lat > opts.latMax) continue;
                double xn = (plotLng - opts.lonMin) / dLon;
                double yn = 1 - (lat - opts.latMin) / dLat;
                int x = padX + (int) (xn * plotW);
                int y = padY + (int) (yn * plotH);
                if (first) { outline.moveTo(x, y); first = false; }
                else outline.lineTo(x, y);
            }
        }
        g.draw(outline);
        g.setStroke(new BasicStroke(1));
    }

    private void drawWindBarbs(Graphics2D g, MapRenderOptions opts, ModelState state) {
        int padX = 80, padY = 70;
        int plotW = opts.width - padX - 60, plotH = opts.height - padY - 90;
        double dLon = opts.lonMax - opts.lonMin;
        double dLat = opts.latMax - opts.latMin;
        DataField uF = state.fields.get(VariableType.U);
        DataField vF = state.fields.get(VariableType.V);
        if (uF == null || vF == null) return;
        g.setColor(new Color(20, 20, 20, 220));
        g.setFont(new Font("SansSerif", Font.BOLD, 9));
        int sp = opts.barbSpacing;
        for (int j = sp; j < ny; j += sp) {
            for (int i = sp; i < nx; i += sp) {
                double lon = grid.lonMin + i * grid.dLon;
                double lat = grid.latMin + j * grid.dLat;
                if (lon < opts.lonMin || lon > opts.lonMax) continue;
                if (lat < opts.latMin || lat > opts.latMax) continue;
                int k = opts.levelK;
                double u = uF.get(i + nx * (j + ny * k));
                double v = vF.get(i + nx * (j + ny * k));
                double xn = (lon - opts.lonMin) / dLon;
                double yn = 1 - (lat - opts.latMin) / dLat;
                int cx = padX + (int) (xn * plotW);
                int cy = padY + (int) (yn * plotH);
                double w = Math.sqrt(u * u + v * v);
                if (w < 0.3) { g.drawOval(cx - 2, cy - 2, 5, 5); continue; }
                double dir = Math.atan2(-u, -v);
                double cos = Math.cos(dir), sin = Math.sin(dir);
                int len = 16 + Math.min(12, (int) (w * 0.8));
                int ex = cx + (int) (len * sin), ey = cy + (int) (len * cos);
                g.drawLine(cx, cy, ex, ey);
                int knots = (int) (w * 1.943844);
                drawBarbFlags(g, ex, ey, dir, knots, len);
            }
        }
    }

    private void drawBarbFlags(Graphics2D g, int x, int y, double dir, int knots, int len) {
        double perpX = Math.cos(dir), perpY = -Math.sin(dir);
        int px0 = x, py0 = y;
        int remain = knots; int seg = 0;
        while (remain >= 50 && seg < 4) {
            int[] tri = new int[]{
                    x + (int) (seg * 5 * perpX), y + (int) (seg * 5 * perpY),
                    x + (int) ((seg + 1) * 5 * perpX) + (int) (-8 * Math.sin(dir)),
                    y + (int) ((seg + 1) * 5 * perpY) + (int) (-8 * Math.cos(dir)),
                    x + (int) ((seg + 1) * 5 * perpX), y + (int) ((seg + 1) * 5 * perpY)
            };
            g.fillPolygon(tri, 0, 3);
            remain -= 50; seg++;
        }
        while (remain >= 10 && seg < 6) {
            int bx = x + (int) (seg * 4 * perpX);
            int by = y + (int) (seg * 4 * perpY);
            int bx2 = bx + (int) (-6 * Math.sin(dir));
            int by2 = by + (int) (-6 * Math.cos(dir));
            g.drawLine(bx, by, bx2, by2);
            remain -= 10; seg++;
        }
        if (remain >= 5) {
            int bx = x + (int) (seg * 4 * perpX);
            int by = y + (int) (seg * 4 * perpY);
            int bx2 = bx + (int) (-3 * Math.sin(dir));
            int by2 = by + (int) (-3 * Math.cos(dir));
            g.drawLine(bx, by, bx2, by2);
        }
    }

    private void drawColorBar(Graphics2D g, MapRenderOptions opts, Colormap cmap) {
        int barX = opts.width - 50;
        int barY = 70;
        int barW = 25;
        int barH = opts.height - 180;
        for (int y = 0; y < barH; y++) {
            double f = (double) y / (barH - 1);
            double v = cmap.vmax - f * (cmap.vmax - cmap.vmin);
            Color c = cmap.valueToColor(v);
            g.setColor(c);
            g.fillRect(barX, barY + y, barW, 1);
        }
        g.setColor(Color.BLACK);
        g.drawRect(barX, barY, barW, barH);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        int ticks = 6;
        for (int t = 0; t <= ticks; t++) {
            double f = (double) t / ticks;
            double v = cmap.vmax - f * (cmap.vmax - cmap.vmin);
            int y = barY + (int) (f * barH);
            g.drawLine(barX + barW, y, barX + barW + 5, y);
            String lbl = String.format("%.1f", v);
            if (cmap == getDefaultColormap(VariableType.PSFC)) {
                lbl = String.format("%.0f", v / 100);
            }
            g.drawString(lbl, barX + barW + 7, y + 4);
        }
        if (opts.units != null) {
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            int uw = fm.stringWidth(opts.units);
            g.drawString(opts.units, barX + barW / 2 - uw / 2, barY - 8);
        }
    }

    private void drawTitle(Graphics2D g, MapRenderOptions opts, ModelState state) {
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        String title = (opts.title != null) ? opts.title : buildDefaultTitle(state, opts);
        g.drawString(title, 80, 40);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        Instant valid = Instant.ofEpochSecond(state.validTime);
        Instant init = Instant.ofEpochSecond(state.initializationTime);
        String info = String.format("Init: %s   Valid: %s   Fcst: +%dh   Level: %d / %s",
                TITLE_FMT.format(init), TITLE_FMT.format(valid),
                state.forecastStep, opts.levelK,
                state.fields.get(opts.variable).getNDim() == 3 ? "layer" : "surface");
        g.setColor(new Color(90, 90, 90));
        g.drawString(info, 80, 58);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        g.setColor(new Color(120, 120, 120));
        g.drawString("NWP Java Solver v1.0", 80, opts.height - 18);
    }

    private String buildDefaultTitle(ModelState s, MapRenderOptions o) {
        String varName = o.variable.name();
        VariableType v = o.variable;
        switch (v) {
            case T, T2: varName = "Temperature [K]"; break;
            case PRECIP: varName = "Total Precipitation [mm]"; break;
            case RH, RH2: varName = "Relative Humidity [%]"; break;
            case PSFC: varName = "Surface Pressure [hPa]"; break;
            case U10: varName = "10m Zonal Wind U [m/s]"; break;
            case V10: varName = "10m Meridional Wind V [m/s]"; break;
            case WIND_SPEED: varName = "Wind Speed [m/s]"; break;
            default: varName = v.name();
        }
        return varName;
    }

    private static double quantile(double[][] data, double q) {
        List<Double> flat = new ArrayList<>(data.length * data[0].length);
        for (double[] row : data) for (double v : row) if (Double.isFinite(v)) flat.add(v);
        if (flat.isEmpty()) return q < 0.5 ? -1 : 1;
        flat.sort(Double::compare);
        int idx = (int) (q * (flat.size() - 1));
        return flat.get(Math.max(0, Math.min(flat.size() - 1, idx)));
    }

    private int nz() { return config.getNZ(); }
}
