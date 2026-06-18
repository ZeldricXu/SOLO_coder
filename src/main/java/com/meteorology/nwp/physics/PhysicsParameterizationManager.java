package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.DynamicsState;
import com.meteorology.nwp.dynamics.TendencyAccumulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class PhysicsParameterizationManager {
    private static final Logger logger = LoggerFactory.getLogger(PhysicsParameterizationManager.class);
    private final NWPConfig config;
    private final GridDefinition grid;
    private final Map<PhysicsType, PhysicsScheme> schemes = new EnumMap<>(PhysicsType.class);
    private final List<PhysicsScheme> callOrder = new ArrayList<>();
    private final Map<PhysicsType, String> schemeConfig;
    private final int radiationCallInterval;
    private int stepCounter;
    private long totalTimeNanos;
    private final Map<PhysicsType, Long> schemeTiming = new EnumMap<>(PhysicsType.class);
    private final TendencyAccumulator tendencyAccumulator;

    private static final Map<VariableType, double[]> PHYSICS_LIMITS = new EnumMap<>(VariableType.class);
    static {
        PHYSICS_LIMITS.put(VariableType.T, new double[]{150.0, 400.0});
        PHYSICS_LIMITS.put(VariableType.QV, new double[]{0.0, 0.05});
        PHYSICS_LIMITS.put(VariableType.QC, new double[]{0.0, 0.02});
        PHYSICS_LIMITS.put(VariableType.QR, new double[]{0.0, 0.05});
        PHYSICS_LIMITS.put(VariableType.QI, new double[]{0.0, 0.02});
        PHYSICS_LIMITS.put(VariableType.QS, new double[]{0.0, 0.02});
        PHYSICS_LIMITS.put(VariableType.QG, new double[]{0.0, 0.01});
    }

    public PhysicsParameterizationManager(NWPConfig config) {
        this.config = config;
        this.grid = config.getGrid();
        this.schemeConfig = new EnumMap<>(PhysicsType.class);
        schemeConfig.put(PhysicsType.SURFACE_LAYER, config.getString("nwp.physics.surface", "default"));
        schemeConfig.put(PhysicsType.RADIATION, config.getString("nwp.physics.radiation", "rrtmg"));
        schemeConfig.put(PhysicsType.BOUNDARY_LAYER, config.getString("nwp.physics.boundary", "ysu"));
        schemeConfig.put(PhysicsType.MICROPHYSICS, config.getString("nwp.physics.microphysics", "wsm6"));
        schemeConfig.put(PhysicsType.CUMULUS, config.getString("nwp.physics.cumulus", "kain-fritsch"));
        this.radiationCallInterval = config.getInt("nwp.physics.radiationInterval", 3600);
        this.stepCounter = 0;
        this.tendencyAccumulator = new TendencyAccumulator(grid);
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
            scheme.initialize(config, grid);
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
                    yield new MoninObukhovSurface();
                }
                throw new IllegalArgumentException("Unknown surface: " + name);
            }
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        };
    }

    public void applyAll(ModelState state, double dt) {
        long start = System.nanoTime();

        tendencyAccumulator.reset();

        for (PhysicsScheme scheme : callOrder) {
            boolean shouldCall = true;
            if (scheme.getType() == PhysicsType.RADIATION) {
                int steps = radiationCallInterval / Math.max(1, (int)dt);
                shouldCall = (stepCounter % steps == 0) || stepCounter == 0;
                if (!shouldCall) continue;
            }
            long t0 = System.nanoTime();
            try {
                DynamicsState schemeTendencies = new DynamicsState(grid);
                scheme.apply(state, schemeTendencies, dt);

                for (VariableType var : VariableType.values()) {
                    DataField tend = schemeTendencies.getTendency(var);
                    if (tend != null) {
                        double maxAbs = tend.rms();
                        if (maxAbs > 1e-20) {
                            tendencyAccumulator.accumulate(scheme.getType(), var, tend);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("物理方案 {} 执行异常: {}", scheme.getName(), e.getMessage(), e);
            }
            long elapsed = System.nanoTime() - t0;
            schemeTiming.merge(scheme.getType(), elapsed, Long::sum);
        }

        tendencyAccumulator.sanitizeNaN();
        tendencyAccumulator.applyToStateWithClipping(state, dt, PHYSICS_LIMITS);

        stepCounter++;
        totalTimeNanos += (System.nanoTime() - start);

        if (stepCounter % 100 == 0) {
            tendencyAccumulator.printContributionReport();
        }
    }

    public void applyAllLegacy(ModelState state, double dt) {
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
                scheme.apply(state, new DynamicsState(grid), dt);
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
                scheme.applyColumn(i, j, col, dt);
            } catch (Exception ignored) {}
        }
    }

    public TendencyAccumulator getTendencyAccumulator() {
        return tendencyAccumulator;
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
