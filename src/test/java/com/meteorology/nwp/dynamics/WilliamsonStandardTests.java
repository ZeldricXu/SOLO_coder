package com.meteorology.nwp.dynamics;

import com.meteorology.nwp.common.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Williamson标准测试用例 - 重构验证")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WilliamsonStandardTests {
    private static final Logger log = LoggerFactory.getLogger(WilliamsonStandardTests.class);
    private static NWPConfig config;
    private static final double EARTH_RADIUS = 6371000.0;
    private static final double OMEGA = 7.292e-5;
    private static final double GRAVITY = 9.80616;

    @BeforeAll
    static void setup() {
        config = NWPConfig.getInstance();
        log.info("Williamson测试初始化完成");
    }

    @Nested
    @DisplayName("Case 2: 稳态非线性绕极涡旋")
    class Case2SteadyVortex {

        @Test
        @Order(1)
        @DisplayName("谱变换性能：单次正变换应<100ms（T42）")
        void testSpectralTransformPerformanceT42() {
            int trunc = 42;
            int nLon = 128;
            int nLat = 64;
            SphericalHarmonics sh = new SphericalHarmonics(trunc,
                    createTestGrid(nLon, nLat));

            DataField field = new DataField(VariableType.T, nLon, nLat, 1);
            double[] data = field.getData();
            for (int j = 0; j < nLat; j++) {
                double lat = -Math.PI / 2 + Math.PI * j / (nLat - 1);
                for (int i = 0; i < nLon; i++) {
                    double lon = 2 * Math.PI * i / nLon;
                    data[i + nLon * j] = Math.cos(lat) * Math.sin(3 * lon) + Math.sin(2 * lat);
                }
            }

            int warmup = 3;
            for (int w = 0; w < warmup; w++) {
                sh.gridToSpectral(field);
            }

            int nRuns = 10;
            long totalNanos = 0;
            for (int r = 0; r < nRuns; r++) {
                long t0 = System.nanoTime();
                sh.gridToSpectral(field);
                totalNanos += System.nanoTime() - t0;
            }

            double avgMs = (totalNanos / (double) nRuns) / 1e6;
            log.info("T42正变换: 平均 {:.3f} ms/次 ({}次平均)", avgMs, nRuns);
            assertThat(avgMs)
                    .as("T42单次正变换应<100ms")
                    .isLessThan(100.0);
        }

        @Test
        @Order(2)
        @DisplayName("谱变换性能：单次逆变换应<100ms（T42）")
        void testInverseTransformPerformanceT42() {
            int trunc = 42;
            int nLon = 128;
            int nLat = 64;
            SphericalHarmonics sh = new SphericalHarmonics(trunc,
                    createTestGrid(nLon, nLat));

            DataField field = new DataField(VariableType.T, nLon, nLat, 1);
            double[] data = field.getData();
            for (int j = 0; j < nLat; j++) {
                for (int i = 0; i < nLon; i++) {
                    data[i + nLon * j] = Math.sin(2 * Math.PI * i / nLon) * Math.cos(Math.PI * j / nLat);
                }
            }

            double[][] coeffs = sh.gridToSpectral(field);

            int warmup = 3;
            for (int w = 0; w < warmup; w++) {
                sh.spectralToGrid(coeffs);
            }

            int nRuns = 10;
            long totalNanos = 0;
            for (int r = 0; r < nRuns; r++) {
                long t0 = System.nanoTime();
                sh.spectralToGrid(coeffs);
                totalNanos += System.nanoTime() - t0;
            }

            double avgMs = (totalNanos / (double) nRuns) / 1e6;
            log.info("T42逆变换: 平均 {:.3f} ms/次", avgMs);
            assertThat(avgMs)
                    .as("T42单次逆变换应<100ms")
                    .isLessThan(100.0);
        }

        @Test
        @Order(3)
        @DisplayName("谱变换精度：正逆变换可逆性")
        void testRoundTripAccuracy() {
            int trunc = 42;
            int nLon = 128;
            int nLat = 64;
            SphericalHarmonics sh = new SphericalHarmonics(trunc,
                    createTestGrid(nLon, nLat));

            DataField original = new DataField(VariableType.T, nLon, nLat, 1);
            double[] data = original.getData();
            for (int j = 0; j < nLat; j++) {
                double lat = -Math.PI / 2 + Math.PI * j / (nLat - 1);
                for (int i = 0; i < nLon; i++) {
                    double lon = 2 * Math.PI * i / nLon;
                    data[i + nLon * j] = 2.0 + Math.cos(lat) * (Math.cos(lon) + 0.5 * Math.cos(2 * lon));
                }
            }

            double[][] coeffs = sh.gridToSpectral(original);
            DataField reconstructed = sh.spectralToGrid(coeffs);

            double l2 = 0.0;
            double rms = original.rms();
            double[] origData = original.getData();
            double[] recData = reconstructed.getData();
            int n = Math.min(origData.length, recData.length);
            for (int i = 0; i < n; i++) {
                double diff = origData[i] - recData[i];
                l2 += diff * diff;
            }
            l2 = Math.sqrt(l2 / n);
            double relError = rms > 0 ? l2 / rms : l2;

            log.info("谱正逆变换: L2={:.6e}, 相对误差={:.6e}", l2, relError);
            assertThat(relError)
                    .as("谱变换相对误差应<0.1%")
                    .isLessThan(0.001);
        }
    }

    @Nested
    @DisplayName("Tendency累加器验证")
    class TendencyAccumulatorTest {

        @Test
        @DisplayName("累加器：多方案倾向不互相覆盖")
        void testAccumulatorNoOverwrite() {
            GridDefinition grid = createTestGrid(64, 32);
            TendencyAccumulator acc = new TendencyAccumulator(grid);

            DataField tend1 = new DataField(VariableType.T, 64, 32, 1);
            tend1.fill(1.0);
            acc.accumulate(PhysicsType.RADIATION, VariableType.T, tend1);

            DataField tend2 = new DataField(VariableType.T, 64, 32, 1);
            tend2.fill(2.0);
            acc.accumulate(PhysicsType.BOUNDARY_LAYER, VariableType.T, tend2);

            double[] accumulated = acc.getAccumulated(VariableType.T);
            double expected = 3.0;

            assertThat(accumulated[0])
                    .as("两个方案倾向应累加为3.0，非覆盖")
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-10));
        }

        @Test
        @DisplayName("累加器：reset清零")
        void testAccumulatorReset() {
            GridDefinition grid = createTestGrid(64, 32);
            TendencyAccumulator acc = new TendencyAccumulator(grid);

            DataField tend = new DataField(VariableType.T, 64, 32, 1);
            tend.fill(5.0);
            acc.accumulate(PhysicsType.RADIATION, VariableType.T, tend);

            acc.reset();

            double[] accumulated = acc.getAccumulated(VariableType.T);
            assertThat(accumulated[0]).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-15));
        }

        @Test
        @DisplayName("累加器：applyToState正确更新模式变量")
        void testAccumulatorApply() {
            GridDefinition grid = createTestGrid(64, 32);
            ModelState state = new ModelState(grid);
            state.addField(VariableType.T);
            state.getField(VariableType.T).fill(300.0);

            TendencyAccumulator acc = new TendencyAccumulator(grid);

            DataField tend = new DataField(VariableType.T, 64, 32, 1);
            tend.fill(-0.1);
            acc.accumulate(PhysicsType.RADIATION, VariableType.T, tend);

            double dt = 60.0;
            acc.applyToState(state, dt);

            double expected = 300.0 + (-0.1) * 60.0;
            assertThat(state.getField(VariableType.T).getData()[0])
                    .as("模式变量应被倾向项正确更新")
                    .isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-10));
        }
    }

    @Nested
    @DisplayName("浅水方程T42 24h积分验证")
    class ShallowWaterIntegration {

        @Test
        @DisplayName("浅水方程24h积分：能量守恒")
        void testEnergyConservation24h() {
            int nLon = 128;
            int nLat = 64;
            int trunc = 42;
            GridDefinition grid = createTestGrid(nLon, nLat);

            ShallowWaterSolver solver = new ShallowWaterSolver(config, grid);
            ModelState state = createWilliamsonCase2State(grid);

            double dt = 600.0;
            int stepsPerHour = 6;
            int totalHours = 24;
            int totalSteps = stepsPerHour * totalHours;

            double initialEnergy = solver.computeTotalEnergy(state);

            long startTime = System.nanoTime();
            for (int step = 0; step < totalSteps; step++) {
                solver.stepForwardEuler(state, dt);
                if ((step + 1) % stepsPerHour == 0) {
                    int hour = (step + 1) / stepsPerHour;
                    double energy = solver.computeTotalEnergy(state);
                    double energyChange = Math.abs(energy - initialEnergy) / Math.abs(initialEnergy) * 100;
                    if (hour % 6 == 0) {
                        log.info("Hour {}: 能量变化 {:.6f}%", hour, energyChange);
                    }
                }
            }
            long elapsed = System.nanoTime() - startTime;
            double elapsedSeconds = elapsed / 1e9;

            double finalEnergy = solver.computeTotalEnergy(state);
            double energyChangePct = Math.abs(finalEnergy - initialEnergy) / Math.abs(initialEnergy) * 100;

            log.info("24h积分完成: 耗时 {:.1f}s, 能量变化 {:.6f}%", elapsedSeconds, energyChangePct);
            log.info("性能: {:.1f}步/秒", totalSteps / elapsedSeconds);

            assertThat(energyChangePct)
                    .as("24h积分能量变化应<5%")
                    .isLessThan(5.0);

            assertThat(elapsedSeconds)
                    .as("T42 24h积分单机应在600秒内完成")
                    .isLessThan(600.0);
        }

        @Test
        @DisplayName("浅水方程CFL稳定性检查")
        void testCFLStability() {
            GridDefinition grid = createTestGrid(128, 64);
            ShallowWaterSolver solver = new ShallowWaterSolver(config, grid);
            ModelState state = createWilliamsonCase2State(grid);

            double dt = 600.0;
            for (int step = 0; step < 144; step++) {
                solver.stepForwardEuler(state, dt);
                double cfl = solver.computeCFL(state);
                if (step % 36 == 0) {
                    log.info("Step {}: CFL={:.4f}", step, cfl);
                }
                if (step > 10) {
                    assertThat(cfl)
                            .as("CFL数应保持稳定<2.0")
                            .isLessThan(2.0);
                }
            }
        }
    }

    private ModelState createWilliamsonCase2State(GridDefinition grid) {
        ModelState state = new ModelState(grid);
        state.ensurePrognosticFields();

        DataField psfc = state.getField(VariableType.PSFC);
        DataField u = state.getField(VariableType.U);
        DataField v = state.getField(VariableType.V);

        int nx = grid.getNX(), ny = grid.getNY();
        double alpha = 0.0;
        double u0 = 2 * Math.PI * EARTH_RADIUS / (86400.0 * 6);
        double meanH = 5960.0;

        for (int j = 0; j < ny; j++) {
            double lat = -Math.PI / 2 + Math.PI * j / (ny - 1);
            double cosLat = Math.cos(lat);
            double sinLat = Math.sin(lat);

            for (int i = 0; i < nx; i++) {
                double lon = 2 * Math.PI * i / nx;
                int idx = i + nx * j;

                double uVal = u0 * (Math.cos(lat) * Math.cos(alpha) + Math.cos(lon) * Math.sin(alpha) * sinLat);
                double vVal = -u0 * Math.sin(lon) * Math.sin(alpha);
                double hVal = meanH - (EARTH_RADIUS * OMEGA * u0 + 0.5 * u0 * u0) * sinLat * sinLat / GRAVITY;

                u.set(idx, uVal);
                v.set(idx, vVal);
                psfc.set(idx, hVal * GRAVITY);
            }
        }

        return state;
    }

    private GridDefinition createTestGrid(int nLon, int nLat) {
        return new GridDefinition(nLon, nLat, 1, EARTH_RADIUS);
    }
}
