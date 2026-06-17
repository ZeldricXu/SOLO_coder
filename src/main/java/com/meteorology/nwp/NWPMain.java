package com.meteorology.nwp;

import com.meteorology.nwp.assimilation.*;
import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.*;
import com.meteorology.nwp.io.*;
import com.meteorology.nwp.parallel.SparkParallelSolver;
import com.meteorology.nwp.physics.PhysicsParameterizationManager;
import com.meteorology.nwp.postprocess.VerificationStats;
import com.meteorology.nwp.postprocess.VisualizationRenderer;
import com.meteorology.nwp.storage.HdfsStorageManager;
import com.meteorology.nwp.storage.KafkaTaskCoordinator;
import com.meteorology.nwp.storage.MetadataManager;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class NWPMain {
    private static final Logger logger = LoggerFactory.getLogger(NWPMain.class);
    private final NWPConfig config;
    private final String runId;

    public NWPMain(NWPConfig config, String runId) {
        this.config = config;
        this.runId = runId != null ? runId : UUID.randomUUID().toString().substring(0, 8);
    }

    public static void main(String[] args) {
        logger.info("================================================================");
        logger.info("NWP Java Solver v1.0 - 数值天气预报核心求解器");
        logger.info("================================================================");
        Options opts = buildCLIOptions();
        CommandLine cmd;
        try {
            cmd = new DefaultParser().parse(opts, args);
        } catch (ParseException e) {
            new HelpFormatter().printHelp("NWPMain", opts);
            System.exit(1);
            return;
        }

        String mode = cmd.getOptionValue("mode", "forecast");
        String runId = cmd.getOptionValue("runId", "RUN-" + System.currentTimeMillis());
        String configPath = cmd.getOptionValue("config", null);

        NWPConfig cfg = (configPath != null && new File(configPath).exists())
                ? new NWPConfig(configPath) : new NWPConfig();
        NWPMain app = new NWPMain(cfg, runId);

        try {
            switch (mode.toLowerCase()) {
                case "forecast": app.runForecast(cmd); break;
                case "assimilation": app.runAssimilation(cmd); break;
                case "cycle": app.runCycle(cmd); break;
                case "benchmark": app.runBenchmark(cmd); break;
                case "verify": app.runVerification(cmd); break;
                case "visualize": app.runVisualize(cmd); break;
                case "test": app.runSelfTest(cmd); break;
                default:
                    logger.error("未知模式: {}", mode);
                    new HelpFormatter().printHelp("NWPMain", opts);
                    System.exit(2);
            }
        } catch (Exception e) {
            logger.error("执行异常: {}", e.getMessage(), e);
            System.exit(3);
        }
    }

    private static Options buildCLIOptions() {
        Options opts = new Options();
        opts.addOption("m", "mode", true, "运行模式: forecast/assimilation/cycle/benchmark/verify/visualize/test");
        opts.addOption("r", "runId", true, "运行ID");
        opts.addOption("c", "config", true, "配置文件路径");
        opts.addOption("i", "init", true, "初始场GRIB文件路径");
        opts.addOption("o", "output", true, "输出目录");
        opts.addOption("f", "hours", true, "预报小时数 (默认72)");
        opts.addOption("t", "initTime", true, "起报时间 ISO格式 (默认当前时间)");
        opts.addOption("d", "domain", true, "区域: global/china/east-asia (默认global)");
        opts.addOption("n", "case", true, "理想测试个例名称");
        opts.addOption("v", "variable", true, "变量名 (用于可视化/检验)");
        opts.addOption("s", "step", true, "预报时效小时 (用于可视化)");
        opts.addOption(null, "parallel", false, "启用Spark并行");
        opts.addOption(null, "hdfs", false, "启用HDFS存储");
        opts.addOption(null, "kafka", false, "启用Kafka协调");
        opts.addOption(null, "postgres", false, "启用PostgreSQL元数据");
        return opts;
    }

    private void runForecast(CommandLine cmd) throws Exception {
        int hours = Integer.parseInt(cmd.getOptionValue("hours", "72"));
        Instant initTime = parseInstant(cmd.getOptionValue("initTime"));
        String initFile = cmd.getOptionValue("init");
        String outputDir = cmd.getOptionValue("output", "./output");
        Files.createDirectories(Paths.get(outputDir));
        ModelState state = initializeState(initFile, cmd.getOptionValue("case"), initTime);
        MetadataManager meta = null; HdfsStorageManager hdfs = null;
        KafkaTaskCoordinator kafka = null;
        long metaRunId = -1;
        if (cmd.hasOption("postgres")) {
            meta = new MetadataManager(config);
            MetadataManager.ForecastRun run = meta.createRun(
                    initTime, hours, "v1.0",
                    cmd.getOptionValue("domain", "global"),
                    state.nx, state.ny, state.nz);
            metaRunId = run.id;
            if (cmd.hasOption("kafka")) {
                kafka = new KafkaTaskCoordinator(config);
                kafka.submitForecastTask(initTime, hours, run.domainName, run.modelVersion);
                meta.updateRunStatus(metaRunId, "RUNNING", 0, 0);
            }
        }
        if (cmd.hasOption("hdfs")) hdfs = new HdfsStorageManager(config);
        ModelState finalState;
        long t0 = System.nanoTime();
        if (cmd.hasOption("parallel")) {
            try (SparkParallelSolver spark = new SparkParallelSolver(config)) {
                finalState = spark.runForecast(state, hours);
            }
        } else {
            finalState = runSingleNode(state, hours, cmd.hasOption("hdfs") ? hdfs : null,
                    outputDir, initTime, meta, metaRunId, cmd.hasOption("kafka") ? kafka : null);
        }
        double wallSec = (System.nanoTime() - t0) / 1e9;
        logger.info("========== 预报完成 ==========");
        logger.info("起报时间: {}", initTime);
        logger.info("预报时效: +{}h", hours);
        logger.info("总耗时: {:.1f} 秒 ({:.2f} 分钟)", wallSec, wallSec / 60);
        double gridPts = (double) config.getNX() * config.getNY() * config.getNZ();
        double ghs = gridPts * hours * 3600 / (wallSec * 1e9);
        logger.info("计算吞吐量: {:.2f} GGridPoints/秒", ghs);
        if (meta != null) meta.updateRunStatus(metaRunId, "COMPLETED", hours, 100);
        if (meta != null) meta.close();
        if (hdfs != null) hdfs.close();
        if (kafka != null) kafka.shutdown();
    }

    private ModelState runSingleNode(ModelState state, int forecastHours, HdfsStorageManager hdfs,
                                      String outputDir, Instant initTime, MetadataManager meta,
                                      long metaRunId, KafkaTaskCoordinator kafka) throws Exception {
        double dt = config.getTimeStep();
        int stepsPerHour = (int) Math.max(1, 3600.0 / dt);
        int totalSteps = forecastHours * stepsPerHour;
        int outputEvery = stepsPerHour;
        FullDynamicsSolver dynSolver = new FullDynamicsSolver(config);
        PhysicsParameterizationManager physics = new PhysicsParameterizationManager(config);
        NetCDFHandler ncOut = new NetCDFHandler(config);
        String initTimeStr = initTime.toString().replace(":", "-");
        logger.info("单机模式: dt={}s totalSteps={} outputEvery={}", dt, totalSteps, outputEvery);
        if (meta != null) meta.updateRunStatus(metaRunId, "RUNNING", 0, 0);
        for (int step = 0; step < totalSteps; step++) {
            long tStep0 = System.nanoTime();
            long tDyn0 = System.nanoTime();
            dynSolver.step(state, dt, 1);
            long dynNs = System.nanoTime() - tDyn0;
            long tPhys0 = System.nanoTime();
            physics.applyAll(state, dt);
            long physNs = System.nanoTime() - tPhys0;
            int stepNs = (int) ((System.nanoTime() - tStep0) / 1_000_000);
            if (step % 10 == 0 || step == totalSteps - 1) {
                int fHour = step / stepsPerHour;
                double cfl = dynSolver.computeCFL(state);
                logger.info("step {:5d}/{} (+{:2d}h) CFL={:.3f} [动力={:.1f}ms 物理={:.1f}ms 总计={}ms]",
                        step + 1, totalSteps, fHour, cfl, dynNs / 1e6, physNs / 1e6, stepNs);
                if (kafka != null && step % 100 == 0) {
                    Map<String, Object> stats = new HashMap<>();
                    stats.put("CFL", cfl);
                    stats.put("Tmean", state.fields.get(VariableType.T).mean());
                    kafka.updateStatus(String.valueOf(metaRunId), step, totalSteps, stats);
                }
            }
            if ((step + 1) % outputEvery == 0 || step == totalSteps - 1) {
                state.forecastStep = (step + 1) / stepsPerHour;
                state.validTime = state.initializationTime + (long) state.forecastStep * 3600;
                state.computeDiagnosticFields(config);
                String ncFile = String.format("%s/forecast_%s_f%03d.nc", outputDir, initTimeStr, state.forecastStep);
                ncOut.writeForecast(state, ncFile);
                logger.info("  写入NetCDF: {}", ncFile);
                if (hdfs != null) {
                    byte[] bytes = Files.readAllBytes(Paths.get(ncFile));
                    String hdfsPath = hdfs.storeFile("forecast", initTime, state.forecastStep,
                            "allvars", "nc", bytes);
                    logger.info("  上传HDFS: {}", hdfsPath);
                    if (meta != null) {
                        meta.registerDataset(metaRunId, state.forecastStep, "FULL",
                                "netcdf4", bytes.length, hdfsPath);
                    }
                }
                if (meta != null) {
                    meta.updateRunStatus(metaRunId, "RUNNING", step + 1,
                            100.0 * (step + 1) / totalSteps);
                }
            }
        }
        physics.printTimingReport();
        physics.shutdown();
        return state;
    }

    private ModelState initializeState(String initFile, String idealCase, Instant initTime) throws Exception {
        ModelState state;
        if (initFile != null && new File(initFile).exists()) {
            logger.info("从GRIB文件加载初始场: {}", initFile);
            Grib2Codec grib = new Grib2Codec(config);
            state = grib.decodeToState(initFile, initTime);
        } else {
            logger.info("使用理想测试个例初始化: {}", idealCase);
            FullDynamicsSolver dyn = new FullDynamicsSolver(config);
            state = dyn.initializeIdealized(idealCase != null ? idealCase : "standard-atmosphere", initTime);
        }
        state.ensurePrognosticFields(config);
        state.computeDiagnosticFields(config);
        logger.info("状态初始化完成: {}x{}x{} 变量数={} 总网格点={}",
                state.nx, state.ny, state.nz, state.fields.size(), state.getTotalPoints());
        return state;
    }

    private void runAssimilation(CommandLine cmd) throws Exception {
        Instant analysisTime = parseInstant(cmd.getOptionValue("initTime"));
        String initFile = cmd.getOptionValue("init");
        logger.info("===== 3D-Var资料同化 {} =====", analysisTime);
        ModelState background = initializeState(initFile, "standard-atmosphere", analysisTime);
        ThreeDimensionalVariational da = new ThreeDimensionalVariational(config);
        ObservationReader or = new ObservationReader(config);
        List<Observation> allObs = or.generateSyntheticObservations(analysisTime, 5000);
        ModelState analysis = da.analyze(background, allObs, analysisTime);
        String outputDir = cmd.getOptionValue("output", "./output/analysis");
        Files.createDirectories(Paths.get(outputDir));
        String fn = String.format("%s/analysis_%s.nc", outputDir, analysisTime.toString().replace(":", "-"));
        new NetCDFHandler(config).writeForecast(analysis, fn);
        logger.info("分析场已写入: {}", fn);
    }

    private void runCycle(CommandLine cmd) throws Exception {
        logger.info("===== 冷启动完整同化+预报循环 =====");
        String outputDir = cmd.getOptionValue("output", "./output/cycle");
        Files.createDirectories(Paths.get(outputDir));
        int cycleHours = 6;
        int totalCycles = Integer.parseInt(cmd.getOptionValue("hours", "24")) / cycleHours;
        Instant cycleTime = parseInstant(cmd.getOptionValue("initTime"));
        ModelState coldStart = new FullDynamicsSolver(config)
                .initializeIdealized("barotropic-instability", cycleTime);
        ThreeDimensionalVariational da = new ThreeDimensionalVariational(config);
        ObservationReader obsReader = new ObservationReader(config);
        NetCDFHandler nc = new NetCDFHandler(config);
        for (int c = 0; c < totalCycles; c++) {
            logger.info("--- 循环 {}/{} 基线时间: {} ---", c + 1, totalCycles, cycleTime);
            List<Observation> obs = obsReader.generateSyntheticObservations(cycleTime, 2500);
            ModelState analysis = da.analyze(coldStart, obs, cycleTime);
            String afn = String.format("%s/analysis_cycle%02d_%s.nc", outputDir, c,
                    cycleTime.toString().replace(":", "-"));
            nc.writeForecast(analysis, afn);
            int fHours = cycleHours;
            logger.info("积分 {}h 预报...", fHours);
            analysis.initializationTime = cycleTime.getEpochSecond();
            analysis.validTime = analysis.initializationTime;
            analysis.forecastStep = 0;
            ModelState forecast = runSingleNodeInnerNoIO(analysis, fHours);
            cycleTime = cycleTime.plus(fHours, ChronoUnit.HOURS);
            coldStart = forecast;
            coldStart.initializationTime = cycleTime.getEpochSecond();
        }
        logger.info("循环完成，共 {} 轮", totalCycles);
    }

    private ModelState runSingleNodeInnerNoIO(ModelState state, int hours) {
        double dt = config.getTimeStep();
        int sph = (int) Math.max(1, 3600.0 / dt);
        int total = hours * sph;
        FullDynamicsSolver dyn = new FullDynamicsSolver(config);
        PhysicsParameterizationManager phys = new PhysicsParameterizationManager(config);
        for (int s = 0; s < total; s++) {
            dyn.step(state, dt, 1);
            phys.applyAll(state, dt);
        }
        phys.shutdown();
        return state;
    }

    private void runBenchmark(CommandLine cmd) throws Exception {
        int minutes = Integer.parseInt(cmd.getOptionValue("hours", "1"));
        int benchHours = Math.max(1, minutes * 1);
        Instant t = parseInstant(cmd.getOptionValue("initTime"));
        String[] cases = {"standard-atmosphere", "rossby-wave", "density-current",
                "barotropic-instability", "thermal-low"};
        Map<String, double[]> results = new LinkedHashMap<>();
        for (String cas : cases) {
            logger.info("BENCH: 运行 {}", cas);
            ModelState s = new FullDynamicsSolver(config).initializeIdealized(cas, t);
            long t0 = System.nanoTime();
            s = runSingleNodeInnerNoIO(s, benchHours);
            double w = (System.nanoTime() - t0) / 1e9;
            DataField T = s.fields.get(VariableType.T);
            DataField P = s.fields.get(VariableType.PSFC);
            results.put(cas, new double[]{
                    w,
                    T != null ? T.mean() : 0,
                    T != null ? T.rms() : 0,
                    P != null ? P.mean() : 0,
                    dynSolverSafetyCheck(s)
            });
            logger.info("  {}: {:.2f}s Tmean={:.1f}K PsfcMean={:.1f}hPa CFL={:.2f}",
                    cas, w,
                    results.get(cas)[1],
                    results.get(cas)[3] / 100,
                    results.get(cas)[4]);
        }
        logger.info("===== 基准测试报告 {}h =====", benchHours);
        logger.info("  Case                耗时(s)   T平均(K)   T方差     CFL");
        for (Map.Entry<String, double[]> e : results.entrySet()) {
            logger.info("  {:<20}{:<10.2f}{:<10.1f}{:<10.2e}{:<8.3f}",
                    e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[4]);
        }
    }

    private double dynSolverSafetyCheck(ModelState s) {
        return new FullDynamicsSolver(config).computeCFL(s);
    }

    private void runVerification(CommandLine cmd) throws Exception {
        String outputDir = cmd.getOptionValue("output", "./output/verify");
        Files.createDirectories(Paths.get(outputDir));
        Instant init = parseInstant(cmd.getOptionValue("initTime"));
        FullDynamicsSolver dyn = new FullDynamicsSolver(config);
        PhysicsParameterizationManager phys = new PhysicsParameterizationManager(config);
        List<ModelState> forecasts = new ArrayList<>();
        int hours = Integer.parseInt(cmd.getOptionValue("hours", "24"));
        int fEvery = 1;
        double dt = config.getTimeStep();
        int sph = (int) Math.max(1, 3600.0 / dt);
        ModelState s = dyn.initializeIdealized(
                cmd.getOptionValue("case") != null ? cmd.getOptionValue("case") : "standard-atmosphere", init);
        for (int h = 0; h <= hours; h += fEvery) {
            int total = h * sph;
            s = runShort(s, dyn, phys, sph);
            s.forecastStep = h;
            s.validTime = s.initializationTime + (long) h * 3600;
            s.computeDiagnosticFields(config);
            forecasts.add(s.cloneState(false));
        }
        phys.shutdown();
        List<VerificationStats.StationObs> stations = VerificationStats.generateDemoStations(200);
        Random r = new Random(42);
        Instant now = Instant.now();
        for (VerificationStats.StationObs sta : stations) {
            for (Map.Entry<VariableType, DataField> e : forecasts.get(0).fields.entrySet()) {
                if (!e.getKey().isPrognostic()) continue;
                for (int h = 0; h <= hours; h += 3) {
                    Instant vt = init.plus(h, ChronoUnit.HOURS);
                    DataField f = forecasts.get(h / fEvery).fields.get(e.getKey());
                    if (f == null) continue;
                    int i0 = (int) (config.getNX() * (sta.longitude - config.getGrid().lonMin) / 360);
                    int j0 = (int) (config.getNY() * (sta.latitude - config.getGrid().latMin + 90) / 180);
                    i0 = Math.max(0, Math.min(config.getNX() - 1, i0));
                    j0 = Math.max(0, Math.min(config.getNY() - 1, j0));
                    double fcst = f.get(i0 + config.getNX() * j0);
                    double truthBias = (e.getKey() == VariableType.T) ? 2.0 * Math.sin(h * 0.3) : 0.5;
                    double noise = (e.getKey() == VariableType.T ? 0.8 : 0.3) * r.nextGaussian();
                    sta.addObservation(vt, e.getKey(), fcst + truthBias + noise);
                }
            }
        }
        VerificationStats vs = new VerificationStats(config);
        Map<VariableType, Map<Integer, VerificationStats.Score>> scores =
                vs.verifyForecastSeries(forecasts, stations);
        logger.info("===== 检验报告 (N={} 站点) =====", stations.size());
        for (Map.Entry<VariableType, Map<Integer, VerificationStats.Score>> e : scores.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            logger.info("-- 变量 {} --", e.getKey());
            for (Map.Entry<Integer, VerificationStats.Score> fE : e.getValue().entrySet()) {
                VerificationStats.Score sc = fE.getValue();
                logger.info("  +{:2d}h: N={} RMSE={:.3f} BIAS={:+.3f} R={:.3f}",
                        fE.getKey(), sc.nPoints, sc.rmse, sc.bias, sc.correlation);
            }
        }
    }

    private ModelState runShort(ModelState s, FullDynamicsSolver d, PhysicsParameterizationManager p, int steps) {
        double dt = config.getTimeStep();
        for (int i = 0; i < steps; i++) {
            d.step(s, dt, 1);
            p.applyAll(s, dt);
        }
        return s;
    }

    private void runVisualize(CommandLine cmd) throws Exception {
        String out = cmd.getOptionValue("output", "./output/plots");
        Files.createDirectories(Paths.get(out));
        Instant init = parseInstant(cmd.getOptionValue("initTime"));
        String varName = cmd.getOptionValue("variable", "T2");
        int fHour = Integer.parseInt(cmd.getOptionValue("step", "24"));
        ModelState s;
        String initFile = cmd.getOptionValue("init");
        if (initFile != null && new File(initFile).exists()) {
            s = new Grib2Codec(config).decodeToState(initFile, init);
        } else {
            s = new FullDynamicsSolver(config).initializeIdealized(cmd.getOptionValue("case"), init);
            s = runShort(s, new FullDynamicsSolver(config), new PhysicsParameterizationManager(config),
                    fHour * ((int) Math.max(1, 3600.0 / config.getTimeStep())));
        }
        s.forecastStep = fHour;
        s.validTime = s.initializationTime + (long) fHour * 3600;
        s.computeDiagnosticFields(config);
        VisualizationRenderer ren = new VisualizationRenderer(config);
        VariableType v = VariableType.valueOf(varName.toUpperCase().replace("-", "_"));
        VariableType[] vars;
        if (cmd.hasOption("variable")) {
            vars = new VariableType[]{v};
        } else {
            vars = new VariableType[]{VariableType.T, VariableType.T2, VariableType.U10,
                    VariableType.V10, VariableType.PSFC, VariableType.RH2, VariableType.PRECIP};
        }
        for (VariableType var : vars) {
            VisualizationRenderer.MapRenderOptions o = new VisualizationRenderer.MapRenderOptions();
            o.variable = var;
            o.width = config.getInt("nwp.visualization.width", 1200);
            o.height = config.getInt("nwp.visualization.height", 800);
            o.colormap = config.getString("nwp.visualization.colormap", "viridis");
            o.drawCountries = true;
            o.drawGrid = true;
            if (var == VariableType.T || var == VariableType.U || var == VariableType.V || var == VariableType.RH) {
                o.levelK = config.getNZ() / 2;
            }
            String fn = String.format("%s/map_%s_f%03dh.png", out, var, fHour);
            o.outputPath = fn;
            ren.renderField(s, o);
        }
    }

    private void runSelfTest(CommandLine cmd) {
        logger.info("====== 自检模式: 验证核心模块 ======");
        int passed = 0, failed = 0;
        String[] moduleTests = {"Config/Grid", "DataField/Math", "动力求解器RK3", "物理方案Kain-Fritsch",
                "资料同化B矩阵", "Spark分区/Halo"};
        for (String mod : moduleTests) {
            try {
                boolean ok = runModuleTest(mod);
                logger.info("  [{}] {}", ok ? "✓ PASS" : "✗ FAIL", mod);
                if (ok) passed++; else failed++;
            } catch (Exception e) {
                logger.warn("  [✗ FAIL] {} 异常: {}", mod, e.getMessage());
                failed++;
            }
        }
        logger.info("自检结果: 通过 {} / 失败 {} / 共 {}", passed, failed, moduleTests.length);
    }

    private boolean runModuleTest(String name) {
        switch (name) {
            case "Config/Grid":
                NWPConfig c = new NWPConfig();
                GridDefinition g = c.getGrid();
                return Math.abs(g.lonMax - 360) < 1 && Math.abs(g.latMax - 90) < 1
                        && c.getNX() > 0 && c.getNY() > 0;
            case "DataField/Math":
                DataField f = new DataField(10, 10);
                f.fill(5);
                DataField f2 = f.deepCopy();
                f2.multAll(2);
                return Math.abs(f.mean() - 5) < 1e-6 && Math.abs(f2.mean() - 10) < 1e-6;
            case "动力求解器RK3":
                NWPConfig c3 = new NWPConfig();
                FullDynamicsSolver d = new FullDynamicsSolver(c3);
                ModelState s = d.initializeIdealized("rossby-wave", Instant.now());
                double cfl1 = d.computeCFL(s);
                d.step(s, 60, 3);
                double cfl2 = d.computeCFL(s);
                return cfl1 < 2 && cfl2 < 2;
            case "物理方案Kain-Fritsch":
                NWPConfig cc = new NWPConfig();
                com.meteorology.nwp.physics.KainFritschCumulus kf =
                        new com.meteorology.nwp.physics.KainFritschCumulus();
                kf.initialize(cc);
                ModelState ss = new FullDynamicsSolver(cc)
                        .initializeIdealized("standard-atmosphere", Instant.now());
                ss.fields.computeIfAbsent(VariableType.CAPE, vv -> new DataField(cc.getNX(), cc.getNY()))
                        .fill(800);
                kf.apply(ss, 300);
                return true;
            case "资料同化B矩阵":
                NWPConfig cb = new NWPConfig();
                BackgroundErrorCovariance B = new BackgroundErrorCovariance(cb);
                ModelState xi = new FullDynamicsSolver(cb).initializeIdealized("thermal-low", Instant.now());
                ModelState xinc = xi.cloneState(true);
                xinc.fields.get(VariableType.T).fill(1);
                B.applyB(xinc);
                double m = xinc.fields.get(VariableType.T).mean();
                return Double.isFinite(m) && m > 0;
            case "Spark分区/Halo":
                NWPConfig cs = new NWPConfig();
                GridPartitioner gp = new GridPartitioner(cs);
                return gp.getTotalPartitions() > 0
                        && gp.getHaloWidth() > 0;
            default:
                return true;
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null) return Instant.now();
        try { return Instant.parse(s); } catch (Exception e) { return Instant.now(); }
    }
}
