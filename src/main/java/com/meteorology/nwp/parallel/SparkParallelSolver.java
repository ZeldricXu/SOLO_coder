package com.meteorology.nwp.parallel;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.*;
import com.meteorology.nwp.physics.*;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.util.LongAccumulator;
import org.apache.spark.api.java.JavaRDD;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;

public class SparkParallelSolver implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(SparkParallelSolver.class);
    private final NWPConfig config;
    private final GridPartitioner partitioner;
    private final HaloExchangeManager haloManager;
    private transient JavaSparkContext sparkContext;
    private final String sparkMaster;
    private final boolean useLocalMode;
    private final int totalPartitions;
    private final int globalStatisticsInterval;
    private final Map<String, LongAccumulator> accumulators;
    private final Map<String, Object> latestGlobalStats;

    public SparkParallelSolver(NWPConfig config) {
        this.config = config;
        this.partitioner = new GridPartitioner(config);
        this.haloManager = new HaloExchangeManager(config, partitioner);
        this.sparkMaster = config.getSparkMaster();
        this.useLocalMode = sparkMaster == null || sparkMaster.startsWith("local");
        this.totalPartitions = partitioner.getTotalPartitions();
        this.globalStatisticsInterval = config.getInt("nwp.parallel.statsInterval", 10);
        this.accumulators = new HashMap<>();
        this.latestGlobalStats = new HashMap<>();
        logger.info("Spark并行求解器初始化: master={} 分区={} halo={}",
                sparkMaster, totalPartitions, partitioner.getHaloWidth());
    }

    public void initializeSpark(JavaSparkContext sc) {
        this.sparkContext = sc;
        accumulators.put("steps", sc.sc().longAccumulator("totalSteps"));
        accumulators.put("dynamicsMs", sc.sc().longAccumulator("dynamicsTimeMs"));
        accumulators.put("physicsMs", sc.sc().longAccumulator("physicsTimeMs"));
        accumulators.put("haloMs", sc.sc().longAccumulator("haloTimeMs"));
        accumulators.put("violationsCFL", sc.sc().longAccumulator("cflViolations"));
    }

    public ModelState runForecast(ModelState initialState, int forecastHours) throws Exception {
        double dt = config.getTimeStep();
        int stepsPerHour = (int) Math.max(1, 3600.0 / dt);
        int totalSteps = forecastHours * stepsPerHour;
        int hoursBetweenOutput = Math.max(1, config.getInt("nwp.output.intervalHours", 1));
        int outputIntervalSteps = hoursBetweenOutput * stepsPerHour;
        logger.info("开始并行预报: {}h 总步={} dt={}s 输出每{}步",
                forecastHours, totalSteps, dt, outputIntervalSteps);

        Broadcast<NWPConfig> configBc = null;
        if (sparkContext != null) {
            try { configBc = sparkContext.broadcast(config); } catch (Exception ignored) {}
        }

        ModelState[] localStates = createLocalStates(initialState);
        FullDynamicsSolver dynSolver = new FullDynamicsSolver(config);
        PhysicsParameterizationManager physicsMgr = new PhysicsParameterizationManager(config);

        for (int step = 0; step < totalSteps; step++) {
            long stepStart = System.nanoTime();
            haloManager.performExchange(localStates, step);
            long haloT = System.nanoTime() - stepStart;
            long tDyn0 = System.nanoTime();
            for (int pid = 0; pid < totalPartitions; pid++) {
                if (localStates[pid] == null) continue;
                GridPartitioner.Partition p = partitioner.getPartition(pid);
                try {
                    dynSolver.step(localStates[pid], dt, 1);
                } catch (Exception e) {
                    logger.error("分区{}动力求解失败 step{}: {}", pid, step, e.getMessage());
                }
            }
            long dynT = System.nanoTime() - tDyn0;
            long tPhys0 = System.nanoTime();
            for (int pid = 0; pid < totalPartitions; pid++) {
                if (localStates[pid] == null) continue;
                try {
                    physicsMgr.applyAll(localStates[pid], dt);
                } catch (Exception e) {
                    logger.error("分区{}物理过程失败 step{}: {}", pid, step, e.getMessage());
                }
            }
            long physT = System.nanoTime() - tPhys0;
            if (step % globalStatisticsInterval == 0) {
                collectGlobalStatistics(localStates, step);
                long cflViolations = checkGlobalCFL(localStates);
                if (cflViolations > 0) {
                    logger.warn("step{}: CFL违规 {} 个网格点", step, cflViolations);
                }
            }
            if ((step + 1) % outputIntervalSteps == 0 || step == totalSteps - 1) {
                int hour = (step + 1) / stepsPerHour;
                logger.info("完成 {}h (step {}/{}): 动力={:.1f}ms 物理={:.1f}ms halo={:.1f}ms",
                        hour, step + 1, totalSteps, dynT / 1e6, physT / 1e6, haloT / 1e6);
            }
            if (step % 100 == 0) {
                System.gc();
            }
        }
        ModelState globalFinal = mergeAllToGlobal(localStates, initialState);
        globalFinal.validTime = initialState.initializationTime + (long) forecastHours * 3600;
        globalFinal.forecastStep = forecastHours;
        globalFinal.computeDiagnosticFields(config);
        physicsMgr.printTimingReport();
        physicsMgr.shutdown();
        haloManager.shutdown();
        return globalFinal;
    }

    public JavaRDD<GridPartitioner.Partition> createPartitionRDD() {
        if (sparkContext == null) return null;
        List<GridPartitioner.Partition> plist = Arrays.asList(partitioner.getAllPartitions());
        return sparkContext.parallelize(plist, totalPartitions);
    }

    private ModelState[] createLocalStates(ModelState global) {
        ModelState[] locals = new ModelState[totalPartitions];
        for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
            locals[p.pid] = partitioner.createLocalState(p);
            partitioner.extractSubdomain(global, p, locals[p.pid]);
        }
        logger.debug("创建 {} 个局部状态", totalPartitions);
        return locals;
    }

    private ModelState mergeAllToGlobal(ModelState[] locals, ModelState template) {
        ModelState global = template.cloneState(true);
        for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
            partitioner.mergeSubdomain(locals[p.pid], p, global);
        }
        global.applyBoundaryConditions(config, partitioner.getHaloWidth());
        return global;
    }

    private void collectGlobalStatistics(ModelState[] locals, int step) {
        double globalSumT = 0, globalRmsU = 0;
        double globalMaxCFL = 0;
        long totalPts = 0;
        double globalMinT = 1e30, globalMaxT = -1e30;
        double globalRainTot = 0;
        for (int pid = 0; pid < totalPartitions; pid++) {
            ModelState s = locals[pid];
            DataField t = s.fields.get(VariableType.T);
            DataField u = s.fields.get(VariableType.U);
            DataField v = s.fields.get(VariableType.V);
            DataField rain = s.fields.get(VariableType.PRECIP);
            if (t != null) {
                for (int i = 0; i < t.getSize(); i++) {
                    double val = t.get(i);
                    if (val > 150) {
                        globalSumT += val;
                        globalMinT = Math.min(globalMinT, val);
                        globalMaxT = Math.max(globalMaxT, val);
                        totalPts++;
                    }
                }
            }
            if (u != null && v != null) {
                int n = Math.min(u.getSize(), v.getSize());
                for (int i = 0; i < n; i++) {
                    double w2 = u.get(i) * u.get(i) + v.get(i) * v.get(i);
                    globalRmsU += w2;
                }
            }
            if (rain != null) {
                for (int i = 0; i < rain.getSize(); i++) globalRainTot += Math.max(0, rain.get(i));
            }
        }
        latestGlobalStats.put("Tmean", totalPts > 0 ? globalSumT / totalPts : 0);
        latestGlobalStats.put("Tmin", globalMinT);
        latestGlobalStats.put("Tmax", globalMaxT);
        latestGlobalStats.put("Urmss", globalRmsU > 0 ? Math.sqrt(globalRmsU / totalPts) : 0);
        latestGlobalStats.put("RainTot", globalRainTot);
        latestGlobalStats.put("step", (long) step);
        if (step % (globalStatisticsInterval * 10) == 0) {
            logger.info("全局统计 step{}: T={:.1f}K [{:.1f},{:.1f}] |Wind|_rms={:.1f}m/s 总降水={:.2f}mm",
                    step, (Double) latestGlobalStats.get("Tmean"),
                    (Double) latestGlobalStats.get("Tmin"), (Double) latestGlobalStats.get("Tmax"),
                    (Double) latestGlobalStats.get("Urmss"), (Double) latestGlobalStats.get("RainTot"));
        }
    }

    private long checkGlobalCFL(ModelState[] locals) {
        long violations = 0;
        GridDefinition grid = config.getGrid();
        double dt = config.getTimeStep();
        for (int pid = 0; pid < totalPartitions; pid++) {
            ModelState s = locals[pid];
            DataField u = s.fields.get(VariableType.U);
            DataField v = s.fields.get(VariableType.V);
            GridPartitioner.Partition p = partitioner.getPartition(pid);
            if (u == null || v == null) continue;
            int h = partitioner.getHaloWidth();
            int npx = p.localNx, npy = p.localNy;
            for (int k = 0; k < config.getNZ(); k++) {
                for (int lj = h; lj < npy - h; lj++) {
                    int gj = p.toGlobalJ(lj);
                    gj = Math.max(0, Math.min(config.getNY() - 1, gj));
                    double dx = grid.dxMeters[gj];
                    double dy = grid.dLatMeters;
                    for (int li = h; li < npx - h; li++) {
                        int idx = li + npx * (lj + npy * k);
                        double cfl = (Math.abs(u.get(idx)) * dt / dx) + (Math.abs(v.get(idx)) * dt / dy);
                        if (cfl > 2.0) violations++;
                    }
                }
            }
        }
        return violations;
    }

    public Map<String, Object> getLatestGlobalStats() {
        return Collections.unmodifiableMap(latestGlobalStats);
    }

    public GridPartitioner getPartitioner() { return partitioner; }

    public void shutdown() {
        haloManager.shutdown();
        if (sparkContext != null) {
            try { sparkContext.stop(); } catch (Exception ignored) {}
        }
    }
}
