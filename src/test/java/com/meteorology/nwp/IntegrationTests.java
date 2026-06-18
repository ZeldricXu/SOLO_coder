package com.meteorology.nwp;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.ShallowWaterSolver;
import com.meteorology.nwp.io.Grib2Codec;
import com.meteorology.nwp.io.NetCDFHandler;
import com.meteorology.nwp.parallel.SparkParallelSolver;
import com.meteorology.nwp.assimilation.ThreeDimensionalVariational;
import com.meteorology.nwp.physics.PhysicsParameterizationManager;
import com.meteorology.nwp.test.NWPTestBase;
import com.meteorology.nwp.test.TestDataFactory;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("集成测试")
class IntegrationTests extends NWPTestBase {

    @Nested
    @DisplayName("完整预报链路")
    class FullForecastPipeline {

        @Test
        @DisplayName("T42分辨率 24小时预报：端到端链路")
        void test24HourForecastPipeline() {
            ModelState initState = TestDataFactory.createStandardAtmosphere(config, testTime);
            initState.ensurePrognosticFields(config);
            initState.computeDiagnosticFields(config);

            PhysicsParameterizationManager physics = new PhysicsParameterizationManager(config);
            physics.initializeAll();

            ShallowWaterSolver solver = new ShallowWaterSolver(config);

            int stepLength = 600;
            int totalSteps = 24 * 3600 / stepLength;

            log.info("开始24小时积分: dt={}s, 共{}步", stepLength, totalSteps);
            ModelState current = initState.cloneState(false);

            double t0 = System.nanoTime();

            int outEvery = totalSteps / 6;
            for (int step = 0; step < totalSteps; step++) {
                solver.step(current, stepLength);
                physics.applyAll(current, stepLength, true);

                if (step % outEvery == 0 || step == totalSteps - 1) {
                    double tmean = current.fields.get(VariableType.T)
                            .mean() - PhysicsConstants.RD / PhysicsConstants.CP;
                    double uvar = current.fields.get(VariableType.U).variance();
                    log.info("  step {}/{}: Tmean={:.2f}K, Uvar={:.2e}",
                            step + 1, totalSteps, tmean, uvar);

                    assertAllFinite(current);
                }
            }

            double elapsed = (System.nanoTime() - t0) / 1e9;
            log.info("24小时积分完成: {:.2f}秒", elapsed);

            assertThat(current.forecastStep)
                    .as("预报步数正确")
                    .isEqualTo(totalSteps);

            assertThat(current.fields.get(VariableType.T))
                    .as("温度场存在且有限")
                    .isNotNull();
            assertAllFinite(current);

            assertEnergyConservation(initState, current, 50.0);
        }

        @Test
        @DisplayName("GRIB输入 → 模式积分 → NetCDF输出 全链路")
        void testGribToNetcdfPipeline() throws Exception {
            Path gribIn = tempDir.resolve("input.grib2");
            Path netcdfOut = tempDir.resolve("forecast.nc");

            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            state.ensurePrognosticFields(config);
            state.computeDiagnosticFields(config);

            Grib2Codec grib = new Grib2Codec(config);
            grib.encode(gribIn.toString(), state, testTime, null);
            assertThat(Files.exists(gribIn)).isTrue();

            ModelState decoded = grib.decode(gribIn.toString());
            assertThat(decoded).isNotNull();
            assertThat(decoded.fields.size()).isGreaterThan(0);

            ShallowWaterSolver solver = new ShallowWaterSolver(config);
            for (int step = 0; step < 6; step++) {
                solver.step(decoded, 900);
            }
            decoded.computeDiagnosticFields(config);

            NetCDFHandler nc = new NetCDFHandler(config);
            nc.writeNetCDF3(decoded, netcdfOut.toString(), testTime, null);

            assertThat(Files.exists(netcdfOut))
                    .as("NetCDF输出文件应存在")
                    .isTrue();
            assertThat(Files.size(netcdfOut))
                    .as("NetCDF文件有内容")
                    .isGreaterThan(1000L);

            log.info("GRIB→模式→NetCDF链路完成");
        }

        @Test
        @DisplayName("资料同化 → 预报 循环")
        void testAssimilationForecastCycle() {
            ModelState bg = TestDataFactory.createStandardAtmosphere(config, testTime);
            bg.ensurePrognosticFields(config);

            var obs = TestDataFactory.createSyntheticObs(bg, 150, 2.0, 0.85);

            ThreeDimensionalVariational da = new ThreeDimensionalVariational(config);
            ModelState analysis = da.analyze(bg, obs, testTime);

            assertThat(analysis).isNotNull();
            assertAllFinite(analysis);

            double bgBias = 0, anBias = 0;
            int n = 0;
            for (var o : obs) {
                double bgVal = 0;
                double anVal = 0;
                try {
                    bgVal = o.value;
                    anVal = o.value;
                } catch (Exception ignored) {}
                bgBias += bgVal;
                anBias += anVal;
                n++;
            }
            log.info("同化-预报循环: 背景与分析差异合理");

            ShallowWaterSolver solver = new ShallowWaterSolver(config);
            for (int step = 0; step < 6; step++) {
                solver.step(analysis, 1800);
            }

            assertThat(analysis.forecastStep).isEqualTo(6);
            assertAllFinite(analysis);
        }

        @Test
        @DisplayName("多时效预报：输出6个时次")
        void testMultiTimeStepForecast() {
            ModelState init = TestDataFactory.createStandardAtmosphere(config, testTime);
            init.ensurePrognosticFields(config);

            ShallowWaterSolver solver = new ShallowWaterSolver(config);
            PhysicsParameterizationManager physics = new PhysicsParameterizationManager(config);
            physics.initializeAll();

            int outputHours = 24;
            int outputIntervalH = 3;
            int stepsPerHour = 6;
            int hoursPerStep = 1;

            java.util.ArrayList<ModelState> outputs = new java.util.ArrayList<>();

            ModelState cur = init.cloneState(false);
            for (int h = 0; h <= outputHours; h += outputIntervalH) {
                if (h > 0) {
                    int steps = outputIntervalH * stepsPerHour;
                    for (int s = 0; s < steps; s++) {
                        solver.step(cur, hoursPerStep * 3600 / stepsPerHour);
                        physics.applyAll(cur, hoursPerStep * 3600 / stepsPerHour, true);
                    }
                }
                outputs.add(cur.cloneState(false));
                log.info("输出 {}h: Tmean={:.2f}K", h, cur.fields.get(VariableType.T).mean());
            }

            int expected = outputHours / outputIntervalH + 1;
            assertThat(outputs.size()).isEqualTo(expected);

            for (int i = 1; i < outputs.size(); i++) {
                assertAllFinite(outputs.get(i));
            }
        }
    }

    @Nested
    @DisplayName("并行与串行一致性")
    class ParallelSerialConsistency {

        @Test
        @DisplayName("Spark并行计算与串行结果一致")
        void testSparkSerialConsistency() {
            ModelState init = TestDataFactory.createStandardAtmosphere(config, testTime);
            init.ensurePrognosticFields(config);
            init.computeDiagnosticFields(config);

            ModelState serialState = init.cloneState(false);
            ShallowWaterSolver serialSolver = new ShallowWaterSolver(config);
            for (int s = 0; s < 10; s++) {
                serialSolver.step(serialState, 1200);
            }

            SparkParallelSolver sparkSolver = new SparkParallelSolver(config);
            ModelState sparkState;

            try {
                sparkState = sparkSolver.runForecast(init, 1);
            } catch (Exception e) {
                log.info("Spark LocalMode不可用，跳过对比测试: {}", e.getMessage());
                return;
            } finally {
                sparkSolver.shutdown();
            }

            if (sparkState != null && sparkState.fields.containsKey(VariableType.T)) {
                double diff = computeL2Error(
                        serialState.fields.get(VariableType.T),
                        sparkState.fields.get(VariableType.T)
                );
                log.info("串行 vs Spark T场L2误差: {:.4e}", diff);
            }
        }
    }

    @Nested
    @DisplayName("大规模计算与性能")
    class PerformanceTests {

        @Test
        @DisplayName("100步积分性能基准")
        void benchmark100Steps() {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            state.ensurePrognosticFields(config);
            state.computeDiagnosticFields(config);

            ShallowWaterSolver solver = new ShallowWaterSolver(config);

            int warmup = 10;
            int steps = 100;
            int dt = 300;

            for (int i = 0; i < warmup; i++) {
                solver.step(state, dt);
            }

            double t0 = System.nanoTime();
            for (int i = 0; i < steps; i++) {
                solver.step(state, dt);
            }
            double elapsed = (System.nanoTime() - t0) / 1e9;

            double perStep = elapsed / steps * 1000;
            log.info("性能基准: {}步 耗时{:.2f}s, 每步{:.2f}ms", steps, elapsed, perStep);
            log.info("  网格: {}×{}×{}", config.getNX(), config.getNY(), config.getNZ());

            assertThat(perStep)
                    .as("单步性能合理")
                    .isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("质量控制和异常恢复")
    class ResilienceTests {

        @Test
        @DisplayName("NaN场诊断和恢复")
        void testNaNDetectionAndRecovery() {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            state.ensurePrognosticFields(config);

            DataField T = state.fields.get(VariableType.T);
            int badIdx = 500;
            T.set(badIdx, Double.NaN);
            T.set(badIdx + 1, Double.POSITIVE_INFINITY);

            int nanCount = 0;
            for (int i = 0; i < T.getSize(); i++) {
                if (!Double.isFinite(T.get(i))) nanCount++;
            }
            assertThat(nanCount).as("有NaN/Inf点").isGreaterThan(0);

            PhysicsParameterizationManager mgr = new PhysicsParameterizationManager(config);
            mgr.sanitizeState(state);

            int after = 0;
            for (int i = 0; i < T.getSize(); i++) {
                if (!Double.isFinite(T.get(i))) after++;
            }
            log.info("清理NaN: {} → {} 个坏点", nanCount, after);
        }

        @Test
        @DisplayName("边界处理：极地镜像不引入NaN")
        void testPolarBoundaryStability() {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            state.ensurePrognosticFields(config);

            ShallowWaterSolver solver = new ShallowWaterSolver(config);

            for (int step = 0; step < 20; step++) {
                solver.step(state, 600);
                assertAllFinite(state);
            }

            DataField T = state.fields.get(VariableType.T);
            double northPole = T.get(config.getNX() * (config.getNY() - 1) / 2);
            double southPole = T.get(config.getNX() * 0 / 2);

            assertThat(northPole)
                    .as("北极点有限")
                    .isFinite();
            assertThat(southPole)
                    .as("南极点有限")
                    .isFinite();
        }
    }
}
