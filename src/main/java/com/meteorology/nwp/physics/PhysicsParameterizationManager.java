package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class PhysicsParameterizationManager {
    private static final Logger logger = LoggerFactory.getLogger(PhysicsParameterizationManager.class);
    private final NWPConfig config;
    private final Map<PhysicsType, PhysicsScheme> schemes = new EnumMap<>(PhysicsType.class);
    private final List<PhysicsScheme> callOrder = new ArrayList<>();
    private final Map<PhysicsType, String> schemeConfig;
    private final int radiationCallInterval;
    private int stepCounter;
    private long totalTimeNanos;
    private final Map<PhysicsType, Long> schemeTiming = new EnumMap<>(PhysicsType.class);

    public PhysicsParameterizationManager(NWPConfig config) {
        this.config = config;
        this.schemeConfig = new EnumMap<>(PhysicsType.class);
        schemeConfig.put(PhysicsType.SURFACE_LAYER, config.getString("nwp.physics.surface", "default"));
        schemeConfig.put(PhysicsType.RADIATION, config.getString("nwp.physics.radiation", "rrtmg"));
        schemeConfig.put(PhysicsType.BOUNDARY_LAYER, config.getString("nwp.physics.boundary", "ysu"));
        schemeConfig.put(PhysicsType.MICROPHYSICS, config.getString("nwp.physics.microphysics", "wsm6"));
        schemeConfig.put(PhysicsType.CUMULUS, config.getString("nwp.physics.cumulus", "kain-fritsch"));
        this.radiationCallInterval = config.getInt("nwp.physics.radiationInterval", 3600);
        this.stepCounter = 0;
        initializeSchemes();
    }

    private void initializeSchemes() {
        logger.info("===== 初始化物理参数化方案 =====");
        tryLoadScheme(PhysicsType.SURFACE_LAYER);
        tryLoadScheme(PhysicsType.RADIATION);
        tryLoadScheme(PhysicsType.BOUNDARY_LAYER);
        tryLoadScheme(PhysicsType.MICROPHYSICS);
        tryLoadScheme(PhysicsType.CUMULUS);
        for (PhysicsType type : PhysicsType.values()) {
            if (schemes.containsKey(type)) callOrder.add(schemes.get(type));
        }
        callOrder.sort(Comparator.comparingInt(s -> s.getType().ordinal()));
        logger.info("已加载 {} 个物理方案，调用顺序：", callOrder.size());
        for (PhysicsScheme s : callOrder) {
            logger.info("  [{}] {}", s.getType(), s.getName());
        }
    }

    private void tryLoadScheme(PhysicsType type) {
        String schemeName = schemeConfig.get(type);
        if (schemeName == null || "off".equalsIgnoreCase(schemeName) || "none".equalsIgnoreCase(schemeName)) {
            logger.info("物理类型 {} 已禁用", type);
            return;
        }
        try {
            PhysicsScheme scheme = createScheme(type, schemeName);
            scheme.initialize(config);
            schemes.put(type, scheme);
            schemeTiming.put(type, 0L);
            logger.info("  ✓ 加载: {} → {}", type, scheme.getName());
        } catch (Exception e) {
            logger.error("✗ 加载失败 {}: {} - {}", type, schemeName, e.getMessage());
        }
    }

    private PhysicsScheme createScheme(PhysicsType type, String name) throws Exception {
        return switch (type) {
            case RADIATION -> {
                if ("rrtmg".equalsIgnoreCase(name)) yield new RRTMGRadiation();
                throw new IllegalArgumentException("Unknown radiation: " + name);
            }
            case BOUNDARY_LAYER -> {
                if ("ysu".equalsIgnoreCase(name)) yield new YSUBoundaryLayer();
                throw new IllegalArgumentException("Unknown boundary layer: " + name);
            }
            case MICROPHYSICS -> {
                if ("wsm6".equalsIgnoreCase(name)) yield new WSM6Microphysics();
                throw new IllegalArgumentException("Unknown microphysics: " + name);
            }
            case CUMULUS -> {
                if ("kain-fritsch".equalsIgnoreCase(name) || "kf".equalsIgnoreCase(name)) yield new KainFritschCumulus();
                throw new IllegalArgumentException("Unknown cumulus: " + name);
            }
            case SURFACE_LAYER -> {
                if ("default".equalsIgnoreCase(name) || "monin-obukhov".equalsIgnoreCase(name)) {
                    yield new PhysicsScheme() {
                        @Override public String getName() { return "Monin-Obukhov Surface"; }
                        @Override public PhysicsType getType() { return PhysicsType.SURFACE_LAYER; }
                        @Override public void initialize(NWPConfig cfg) {}
                        @Override public void configure(Map<String, Object> params) {}
                        @Override public void apply(ModelState s, double dt) {
                            GridDefinition grid = config.getGrid();
                            DataField psfc = s.fields.get(VariableType.PSFC);
                            DataField t2 = s.fields.computeIfAbsent(VariableType.T2, v -> new DataField(nx(), ny()));
                            DataField q2 = s.fields.computeIfAbsent(VariableType.Q2, v -> new DataField(nx(), ny()));
                            DataField u10 = s.fields.computeIfAbsent(VariableType.U10, v -> new DataField(nx(), ny()));
                            DataField v10 = s.fields.computeIfAbsent(VariableType.V10, v -> new DataField(nx(), ny()));
                            DataField t = s.fields.get(VariableType.T);
                            DataField qv = s.fields.get(VariableType.QV);
                            DataField u = s.fields.get(VariableType.U);
                            DataField v = s.fields.get(VariableType.V);
                            double z0 = 0.01, zr = 10.0, zb = 2.0;
                            for (int j = 0; j < ny(); j++) for (int i = 0; i < nx(); i++) {
                                int idx2d = i + nx() * j;
                                int idx3d = i + nx() * (j + ny() * 0);
                                double tk1 = t.get(idx3d);
                                double q1 = qv.get(idx3d);
                                double u1 = u.get(idx3d), v1 = v.get(idx3d);
                                double wind = Math.sqrt(u1*u1 + v1*v1) + 0.01;
                                double thv = tk1 * (1 + 0.61 * q1);
                                double kappa = 0.4;
                                double fm = Math.log(zr / z0), fh = fm;
                                double ustar = wind * kappa / fm;
                                double tstar = 0.3;
                                t2.set(idx2d, tk1 + tstar / kappa * Math.log(zb / zr));
                                q2.set(idx2d, Math.max(0, q1 - 0.0001));
                                double factor = Math.log(zr/z0)/Math.log(zb/z0);
                                u10.set(idx2d, u1 * factor);
                                v10.set(idx2d, v1 * factor);
                            }
                        }
                        @Override public void applyColumn(ColumnData col, double dt, GridDefinition g, int i, int j) {}
                        @Override public void cleanup() {}
                        private int nx() { return config.getNX(); }
                        private int ny() { return config.getNY(); }
                    };
                }
                throw new IllegalArgumentException("Unknown surface: " + name);
            }
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };
    }

    public void applyAll(ModelState state, double dt) {
        long start = System.nanoTime();
        for (PhysicsScheme scheme : callOrder) {
            boolean shouldCall = true;
            if (scheme.getType() == PhysicsType.RADIATION) {
                int steps = radiationCallInterval / Math.max(1, (int)dt);
                shouldCall = (stepCounter % steps == 0) || stepCounter == 0;
                if (!shouldCall) continue;
            }
            long t0 = System.nanoTime();
            try {
                scheme.apply(state, dt);
            } catch (Exception e) {
                logger.error("物理方案 {} 执行异常: {}", scheme.getName(), e.getMessage(), e);
            }
            long elapsed = System.nanoTime() - t0;
            schemeTiming.merge(scheme.getType(), elapsed, Long::sum);
        }
        stepCounter++;
        totalTimeNanos += (System.nanoTime() - start);
    }

    public void applyColumnPhysics(ColumnData col, double dt, GridDefinition grid, int i, int j) {
        for (PhysicsScheme scheme : callOrder) {
            if (scheme.getType() == PhysicsType.RADIATION && stepCounter > 0) continue;
            try {
                scheme.applyColumn(col, dt, grid, i, j);
            } catch (Exception e) {
                // ignore per-column errors, just log
            }
        }
    }

    public Optional<PhysicsScheme> getScheme(PhysicsType type) {
        return Optional.ofNullable(schemes.get(type));
    }

    public void printTimingReport() {
        logger.info("===== 物理方案耗时报告 =====");
        logger.info("总调用次数: {}", stepCounter);
        logger.info("总耗时: {:.3f} 秒", totalTimeNanos / 1e9);
        for (Map.Entry<PhysicsType, Long> e : schemeTiming.entrySet()) {
            double sec = e.getValue() / 1e9;
            double pct = totalTimeNanos > 0 ? 100.0 * e.getValue() / totalTimeNanos : 0;
            PhysicsScheme s = schemes.get(e.getKey());
            logger.info("  [{}] {}: {:.3f}s ({:.1f}%)", e.getKey(), s != null ? s.getName() : "?", sec, pct);
        }
    }

    public void shutdown() {
        for (PhysicsScheme scheme : callOrder) {
            try { scheme.cleanup(); } catch (Exception ignored) {}
        }
        schemes.clear();
        callOrder.clear();
    }
}
