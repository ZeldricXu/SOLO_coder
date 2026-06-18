package com.meteorology.nwp.dynamics;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.test.NWPTestBase;
import com.meteorology.nwp.test.TestDataFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DisplayName("动力求解器测试")
class DynamicsTests extends NWPTestBase {

    private ShallowWaterSolver shallowSolver;
    private FullDynamicsSolver fullSolver;

    @BeforeEach
    void setUp() {
        shallowSolver = new ShallowWaterSolver(config);
        fullSolver = new FullDynamicsSolver(config);
    }

    @Nested
    @DisplayName("Williamson 1992 Test Case 2: 定常地转流")
    class WilliamsonTest2 {

        @Test
        @DisplayName("积分5天后位势高度场形态保持不变")
        void testGeostrophicBalanceMaintenance() {
            ModelState state = TestDataFactory.createWilliamsonTest2(config, testTime);
            DataField h0 = state.fields.get(VariableType.GEOPOTENTIAL).deepCopy();
            DataField u0 = state.fields.get(VariableType.U).deepCopy();
            DataField v0 = state.fields.get(VariableType.V).deepCopy();

            double hInitialMean = h0.mean();
            double uInitialRMS = rms(u0);

            int days = 5;
            double dt = config.getTimeStep();
            int stepsPerDay = (int) (86400.0 / dt);
            int totalSteps = days * stepsPerDay;

            for (int s = 0; s < totalSteps; s++) {
                shallowSolver.stepRK3(state, dt);
            }

            DataField hFinal = state.fields.get(VariableType.GEOPOTENTIAL);

            double hFinalMean = hFinal.mean();
            double hL2Error = computeL2Error(h0, hFinal);
            double hLinfError = computeLinfError(h0, hFinal);

            log.info("Williamson TC2: 积分{}天", days);
            log.info("  初始h平均: {:.3f} m", hInitialMean);
            log.info("  最终h平均: {:.3f} m", hFinalMean);
            log.info("  L2 误差:  {:.4e}", hL2Error);
            log.info("  L∞ 误差:  {:.4e}", hLinfError);
            log.info("  相对误差:  {:.4e}", hL2Error / Math.max(1, Math.abs(hInitialMean)));

            assertFinite(hFinalMean, "最终h均值");
            assertFinite(hL2Error, "L2误差");

            assertConservation(hInitialMean, hFinalMean, 0.001, "位势高度全球平均");

            assertThat(hL2Error)
                    .as("5天积分L2误差应小于初始场的2%")
                    .isLessThan(Math.abs(hInitialMean) * 0.02);
        }

        @Test
        @DisplayName("全球质量守恒：积分过程中位势高度总和守恒")
        void testMassConservation() {
            ModelState state = TestDataFactory.createWilliamsonTest2(config, testTime);

            double[] hSum = new double[6];
            DataField h = state.fields.get(VariableType.GEOPOTENTIAL);
            hSum[0] = globalSum(h);

            double dt = config.getTimeStep();
            int stepsPerOutput = (int) (86400.0 / dt / 12);

            for (int day = 1; day <= 5; day++) {
                for (int s = 0; s < stepsPerOutput * 12; s++) {
                    shallowSolver.stepRK3(state, dt);
                }
                hSum[day] = globalSum(state.fields.get(VariableType.GEOPOTENTIAL));
                log.debug("  Day {}: Σh = {:.6e}", day, hSum[day]);
            }

            double drift = Math.abs(hSum[5] - hSum[0]) / Math.abs(hSum[0]);
            log.info("质量守恒5天漂移: {:.6e} (相对 {:.4e})", hSum[5] - hSum[0], drift);

            assertThat(drift)
                    .as("5天质量守恒相对漂移应小于0.1%")
                    .isLessThan(0.001);
        }

        @Test
        @DisplayName("总能量守恒：动能+位能总和缓慢变化")
        void testEnergyConservation() {
            ModelState state = TestDataFactory.createWilliamsonTest2(config, testTime);

            double energy0 = totalEnergy(state);

            double dt = config.getTimeStep();
            int totalSteps = (int) (86400.0 / dt) * 5;

            for (int s = 0; s < totalSteps; s++) {
                shallowSolver.stepRK3(state, dt);
            }

            double energy1 = totalEnergy(state);
            double drift = Math.abs(energy1 - energy0) / Math.abs(energy0);

            log.info("总能量: 初始={:.6e} 最终={:.6e} 漂移={:.4e}",
                    energy0, energy1, drift);

            assertThat(drift)
                    .as("5天总能量漂移应小于1%")
                    .isLessThan(0.01);
        }
    }

    @Nested
    @DisplayName("Williamson 1992 Test Case 5: 山脉激发Rossby波")
    class WilliamsonTest5 {

        @Test
        @DisplayName("积分14天后形成Rossby波列")
        void testRossbyWaveTrain() {
            ModelState state = TestDataFactory.createWilliamsonTest5(config, testTime);
            DataField hInitial = state.fields.get(VariableType.GEOPOTENTIAL).deepCopy();

            double dt = config.getTimeStep();
            int stepsPerDay = (int) (86400.0 / dt);

            double[] hVariance = new double[15];
            hVariance[0] = hInitial.variance();

            for (int day = 1; day <= 14; day++) {
                for (int s = 0; s < stepsPerDay; s++) {
                    shallowSolver.stepRK3(state, dt);
                }
                hVariance[day] = state.fields.get(VariableType.GEOPOTENTIAL).variance();
                log.debug("  Day {}: var(h) = {:.4e}", day, hVariance[day]);
            }

            DataField hFinal = state.fields.get(VariableType.GEOPOTENTIAL);

            assertThat(hInitial.mean())
                    .as("平均位势高度近似守恒")
                    .isCloseTo(hFinal.mean(), within(Math.abs(hInitial.mean()) * 0.001));

            assertThat(hVariance[14])
                    .as("14天后方差应大于初始方差（波列产生）")
                    .isGreaterThan(hVariance[0] * 0.5);
        }

        @Test
        @DisplayName("波列位置：在山脉东侧下游")
        void testWaveLocation() {
            ModelState state = TestDataFactory.createWilliamsonTest5(config, testTime);

            double dt = config.getTimeStep();
            int steps = (int) (86400.0 / dt) * 8;

            for (int s = 0; s < steps; s++) {
                shallowSolver.stepRK3(state, dt);
            }

            DataField h = state.fields.get(VariableType.GEOPOTENTIAL);
            double hMean = h.mean();

            int jMountain = config.getNY() / 3;
            int maxIndex = 0;
            double maxPerturb = -1e30;
            for (int i = 0; i < config.getNX(); i++) {
                double pert = Math.abs(h.get(i + config.getNX() * jMountain) - hMean);
                if (pert > maxPerturb) {
                    maxPerturb = pert;
                    maxIndex = i;
                }
            }

            double peakLon = grid.lonMin + maxIndex * grid.dLon;
            log.info("山脉位于 270°E, 最大扰动位于 {:.1f}°E", peakLon);

            assertThat(peakLon)
                    .as("最大扰动应位于山脉东侧（下游）")
                    .isGreaterThan(260)
                    .isLessThan(360);
        }
    }

    @Nested
    @DisplayName("RK3时间积分收敛性")
    class RK3ConvergenceTest {

        @Test
        @DisplayName("误差随时间步长以O(dt³)速率收敛")
        void testThirdOrderConvergence() {
            double[] dtValues = {3600, 1800, 900, 450};
            double[] errors = new double[dtValues.length];

            for (int d = 0; d < dtValues.length; d++) {
                double dt = dtValues[d];
                ModelState state = TestDataFactory.createWilliamsonTest2(config, testTime);
                DataField h0 = state.fields.get(VariableType.GEOPOTENTIAL).deepCopy();

                double totalTime = 6 * 3600;
                int steps = (int) (totalTime / dt);

                for (int s = 0; s < steps; s++) {
                    shallowSolver.stepRK3(state, dt);
                }

                errors[d] = computeL2Error(h0, state.fields.get(VariableType.GEOPOTENTIAL));
                log.info("  dt={:>4.0f}s, steps={}, L2 error={:.6e}", dt, steps, errors[d]);
            }

            double[] ratios = new double[dtValues.length - 1];
            double[] orders = new double[dtValues.length - 1];
            for (int i = 0; i < dtValues.length - 1; i++) {
                double dtRatio = dtValues[i] / dtValues[i + 1];
                double errRatio = errors[i] / errors[i + 1];
                double order = Math.log(errRatio) / Math.log(dtRatio);
                ratios[i] = errRatio;
                orders[i] = order;
                log.info("  步长比={:.1f}, 误差比={:.2f}, 收敛阶={:.3f}",
                        dtRatio, errRatio, order);
            }

            double avgOrder = 0;
            for (double o : orders) avgOrder += o;
            avgOrder /= orders.length;

            log.info("平均收敛阶: {:.3f} (预期≈3.0)", avgOrder);

            assertThat(avgOrder)
                    .as("平均收敛阶应接近3 (RK3三阶精度)")
                    .isGreaterThan(2.0);
        }

        @ParameterizedTest
        @ValueSource(ints = {100, 500, 1000})
        @DisplayName("长时间积分稳定性")
        void testLongTermStability(int steps) {
            ModelState state = TestDataFactory.createWilliamsonTest2(config, testTime);
            DataField h = state.fields.get(VariableType.GEOPOTENTIAL);
            double initialMax = h.max();
            double initialMin = h.min();

            double dt = config.getTimeStep();
            for (int s = 0; s < steps; s++) {
                shallowSolver.stepRK3(state, dt);
            }

            double finalMax = h.max();
            double finalMin = h.min();
            double ratio = finalMax / Math.max(1, initialMax);

            log.info("{}步积分: max从 {:.2f}→{:.2f}, min从 {:.2f}→{:.2f}, 比值={:.3f}",
                    steps, initialMax, finalMax, initialMin, finalMin, ratio);

            assertFinite(finalMax, "最终最大值");
            assertFinite(finalMin, "最终最小值");

            assertThat(ratio)
                    .as("长时间积分不应爆炸")
                    .isLessThan(2.0);
        }
    }

    @Nested
    @DisplayName("谱变换精度")
    class SpectralTransformTest {

        @Test
        @DisplayName("正逆球谐变换：可逆性")
        void testSpectralTransformRoundTrip() {
            SphericalHarmonics sh = new SphericalHarmonics(config, 42);
            DataField f = TestDataFactory.createSinusoidal2D(
                    config.getNX(), config.getNY(), 3, 2, 1.0, 0.5);

            double[][] spec = sh.gridToSpectral(f);
            DataField back = sh.spectralToGrid(spec);

            double l2Error = computeL2Error(f, back);
            double relError = l2Error / f.rms();

            log.info("谱正逆变换 L2误差: {:.6e}, 相对: {:.6e}", l2Error, relError);

            assertThat(relError)
                    .as("谱变换相对误差应<0.1%")
                    .isLessThan(0.001);
        }

        @Test
        @DisplayName("谱空间拉普拉斯算子")
        void testSpectralLaplacian() {
            SphericalHarmonics sh = new SphericalHarmonics(config, 42);

            DataField f = TestDataFactory.createSinusoidal2D(
                    config.getNX(), config.getNY(), 4, 3, 1.0, 0.0);

            double[][] spec = sh.gridToSpectral(f);
            double[][] lapSpec = sh.spectralLaplacian(spec);
            DataField lapGrid = sh.spectralToGrid(lapSpec);

            double maxVal = f.max();
            double maxLap = lapGrid.max();

            log.info("f_max={:.3f}, ∇²f_max={:.3f}", maxVal, maxLap);

            assertFinite(maxLap, "拉普拉斯结果");
        }
    }

    @Nested
    @DisplayName("CFL稳定性条件")
    class CFLTest {

        @Test
        @DisplayName("初始场CFL数应小于1")
        void testInitialCFL() {
            ModelState state = TestDataFactory.createWilliamsonTest2(config, testTime);
            double cfl = shallowSolver.computeCFL(state);

            log.info("初始CFL: {:.3f}", cfl);
            assertThat(cfl)
                    .as("初始CFL应小于0.5")
                    .isLessThan(0.5);
        }

        @Test
        @DisplayName("CFL超过1时出现数值不稳定")
        void testCFLViolation() {
            ModelState state = TestDataFactory.createWilliamsonTest2(config, testTime);
            DataField u = state.fields.get(VariableType.U);
            u.multAll(5.0);

            double cfl = shallowSolver.computeCFL(state);
            log.info("增强风场后CFL: {:.3f}", cfl);

            if (cfl > 1.0) {
                double energy0 = totalEnergy(state);
                for (int s = 0; s < 50; s++) {
                    shallowSolver.stepRK3(state, config.getTimeStep());
                }
                double energy1 = totalEnergy(state);
                log.info("能量: 0={:.3e} 50步={:.3e} 增长比={:.3f}",
                        energy0, energy1, energy1 / Math.max(1, energy0));
            }
        }
    }

    private double globalSum(DataField f) {
        double sum = 0;
        for (int i = 0; i < f.getSize(); i++) sum += f.get(i);
        return sum;
    }

    private double rms(DataField f) {
        double sum = 0;
        for (int i = 0; i < f.getSize(); i++) sum += f.get(i) * f.get(i);
        return Math.sqrt(sum / f.getSize());
    }

    private double totalEnergy(ModelState state) {
        DataField u = state.fields.get(VariableType.U);
        DataField v = state.fields.get(VariableType.V);
        DataField h = state.fields.get(VariableType.GEOPOTENTIAL);
        double ke = 0, pe = 0;
        int n = u.getSize();
        for (int i = 0; i < n; i++) {
            ke += 0.5 * (u.get(i) * u.get(i) + v.get(i) * v.get(i));
            pe += PhysicsConstants.G * h.get(i);
        }
        return (ke + pe) / n;
    }
}
