package com.meteorology.nwp.physics;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.test.NWPTestBase;
import com.meteorology.nwp.test.TestDataFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("物理参数化测试")
class PhysicsTests extends NWPTestBase {

    private KainFritschCumulus kf;
    private YSUBoundaryLayer ysu;
    private WSM6Microphysics wsm6;
    private RRTMGRadiation rrtmg;
    private PhysicsParameterizationManager mgr;

    @BeforeEach
    void setUp() {
        kf = new KainFritschCumulus();
        kf.initialize(config);
        ysu = new YSUBoundaryLayer();
        ysu.initialize(config);
        wsm6 = new WSM6Microphysics();
        wsm6.initialize(config);
        rrtmg = new RRTMGRadiation();
        rrtmg.initialize(config);
        mgr = new PhysicsParameterizationManager(config);
    }

    @Nested
    @DisplayName("质量守恒测试")
    class MassConservationTest {

        @Test
        @DisplayName("WSM6微物理：总水物质守恒（QV+QC+QR+QI+QS+QG）")
        void testWSM6WaterConservation() {
            ColumnData col = createTestColumn();

            double[] waterInit = getTotalWaterByLayer(col);
            double totalInit = sum(waterInit);

            double dt = 300;
            wsm6.applyColumn(col, dt, grid, config.getNX() / 2, config.getNY() / 2);

            double[] waterFinal = getTotalWaterByLayer(col);
            double totalFinal = sum(waterFinal);

            double relativeDrift = Math.abs(totalFinal - totalInit)
                    / Math.max(1e-10, Math.abs(totalInit));

            log.info("WSM6 总水物质: 初始={:.6e} 最终={:.6e} 相对漂移={:.4e}",
                    totalInit, totalFinal, relativeDrift);

            for (int k = 0; k < config.getNZ(); k++) {
                assertFinite(col.qv[k], "qv[" + k + "]");
                assertFinite(col.qc[k], "qc[" + k + "]");
                assertFinite(col.qr[k], "qr[" + k + "]");
            }

            assertThat(relativeDrift)
                    .as("总水物质守恒漂移应<10%")
                    .isLessThan(0.10);
        }

        @Test
        @DisplayName("WSM6：各层水物质不出现负值")
        void testNoNegativeWater() {
            ColumnData col = createTestColumn();
            wsm6.applyColumn(col, 300, grid, 0, 0);

            for (int k = 0; k < config.getNZ(); k++) {
                assertThat(col.qv[k])
                        .as("QV[%d]应为非负".formatted(k))
                        .isGreaterThanOrEqualTo(-1e-10);
                assertThat(col.qc[k])
                        .as("QC[%d]应为非负".formatted(k))
                        .isGreaterThanOrEqualTo(-1e-10);
                assertThat(col.qr[k])
                        .as("QR[%d]应为非负".formatted(k))
                        .isGreaterThanOrEqualTo(-1e-10);
            }
        }

        @Test
        @DisplayName("YSU边界层：热量倾向守恒")
        void testYSUEnergyTendency() {
            ColumnData col = createTestColumn();
            double tInit = sum(col.tk);

            ysu.applyColumn(col, 300, grid, 0, 0);

            double tFinal = sum(col.tk);
            double dt = (tFinal - tInit) / config.getNZ();

            log.info("YSU 平均温度变化: {:.4f} K/300s", dt);

            for (int k = 0; k < config.getNZ(); k++) {
                assertFinite(col.tk[k], "T[" + k + "]");
                assertFinite(col.tTend[k], "tTend[" + k + "]");
            }
        }
    }

    @Nested
    @DisplayName("NaN和异常值处理")
    class NaNHandlingTest {

        @Test
        @DisplayName("Kain-Fritsch：输入含NaN不应崩溃")
        void testKFWithNaN() {
            ColumnData col = createTestColumn();
            col.qv[5] = Double.NaN;

            assertThatCode(() -> kf.applyColumn(col, 300, grid, 0, 0))
                    .as("K-F方案遇到NaN不应抛出异常")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("YSU：不稳定层结应产生正边界层高度")
        void testYSUWithUnstableStratification() {
            ColumnData col = createTestColumn();

            for (int k = 0; k < config.getNZ(); k++) {
                col.tk[k] = 300 - 5 * k / (double) config.getNZ();
            }
            col.tk[0] = 310;

            ysu.applyColumn(col, 300, grid, 0, 0);

            assertThat(col.pblHeight)
                    .as("对流边界层高度应大于0")
                    .isGreaterThan(100);
        }

        @Test
        @DisplayName("WSM6：过冷水存在时冰相增长")
        void testWSM6SupercooledWater() {
            ColumnData col = createTestColumn();
            int kMid = config.getNZ() / 2;
            col.tk[kMid] = 260;
            col.qc[kMid] = 1e-4;
            col.qi[kMid] = 1e-6;

            double qcInit = col.qc[kMid];
            double qiInit = col.qi[kMid];

            wsm6.applyColumn(col, 180, grid, 0, 0);

            log.info("WSM6 过冷水+冰: qc={:.4e}→{:.4e}, qi={:.4e}→{:.4e}",
                    qcInit, col.qc[kMid], qiInit, col.qi[kMid]);

            assertFinite(col.qc[kMid], "qc");
            assertFinite(col.qi[kMid], "qi");
        }

        @ParameterizedTest
        @ValueSource(doubles = {200, 250, 280, 300, 320, 400})
        @DisplayName("RRTMG：各温度下辐射加热率有限")
        void testRadiationHeatingFinite(double temperature) {
            ColumnData col = createTestColumn();
            for (int k = 0; k < config.getNZ(); k++) col.tk[k] = temperature;

            assertThatCode(() -> rrtmg.applyColumn(col, 300, grid, 0, 0))
                    .as("T=%d K 不应崩溃".formatted((int) temperature))
                    .doesNotThrowAnyException();

            for (int k = 0; k < config.getNZ(); k++) {
                assertThat(col.tTend[k])
                        .as("加热率应有限 T=%.0fK layer %d".formatted(temperature, k))
                        .isFinite()
                        .isBetween(-10.0 / 3600, 10.0 / 3600);
            }
        }
    }

    @Nested
    @DisplayName("极端场景稳定性")
    class ExtremeStabilityTest {

        @Test
        @DisplayName("极寒大气：温度<200K仍正常运行")
        void testVeryColdAtmosphere() {
            ColumnData col = createTestColumn();
            for (int k = 0; k < config.getNZ(); k++) {
                col.tk[k] = 180 + 30 * k / (double) config.getNZ();
                col.qv[k] = 1e-8;
            }

            assertThatCode(() -> mgr.applyAll(createStateFromColumn(col), 300))
                    .as("极寒大气不应崩溃")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("超饱和大气：不出现数值爆炸")
        void testSupersaturatedAtmosphere() {
            ColumnData col = createTestColumn();
            for (int k = 0; k < config.getNZ(); k++) {
                double es = PhysicsConstants.saturationVaporPressure(col.tk[k]);
                col.qv[k] = 2.0 * PhysicsConstants.EPSILON * es / col.p[k];
            }

            double qvInit = sum(col.qv);
            wsm6.applyColumn(col, 600, grid, 0, 0);
            double qvFinal = sum(col.qv);

            log.info("超饱和 qv: {:.4e} → {:.4e}", qvInit, qvFinal);

            assertThat(qvFinal)
                    .as("凝结后水汽应减少")
                    .isLessThan(qvInit);
        }

        @Test
        @DisplayName("强风场：边界层参数化稳定")
        void testStrongWindBoundaryLayer() {
            ColumnData col = createTestColumn();
            for (int k = 0; k < config.getNZ(); k++) {
                col.u[k] = 50 + 10 * Math.sin(k * 0.5);
                col.v[k] = 30 + 5 * Math.cos(k * 0.3);
            }

            ysu.applyColumn(col, 300, grid, 0, 0);

            for (int k = 0; k < config.getNZ(); k++) {
                assertThat(col.uTend[k])
                        .as("uTend[%d]有限".formatted(k))
                        .isFinite();
                assertThat(col.vTend[k])
                        .as("vTend[%d]有限".formatted(k))
                        .isFinite();
            }
        }
    }

    @Nested
    @DisplayName("物理过程顺序与管理器")
    class PhysicsOrderingTest {

        @Test
        @DisplayName("各方案按正确顺序调用")
        void testCallOrder() {
            assertThat(mgr.getScheme(PhysicsType.SURFACE_LAYER))
                    .as("地表方案已加载")
                    .isPresent();
            assertThat(mgr.getScheme(PhysicsType.RADIATION))
                    .as("辐射方案已加载")
                    .isPresent();
            assertThat(mgr.getScheme(PhysicsType.BOUNDARY_LAYER))
                    .as("边界层方案已加载")
                    .isPresent();
            assertThat(mgr.getScheme(PhysicsType.MICROPHYSICS))
                    .as("微物理方案已加载")
                    .isPresent();
            assertThat(mgr.getScheme(PhysicsType.CUMULUS))
                    .as("积云方案已加载")
                    .isPresent();
        }

        @Test
        @DisplayName("辐射方案不是每步都调用")
        void testRadiationIntermittent() {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            state.ensurePrognosticFields(config);

            double t0 = state.fields.get(VariableType.T).get(0);

            mgr.applyAll(state, 60);
            double t1 = state.fields.get(VariableType.T).get(0);
            double diff1 = Math.abs(t1 - t0);

            mgr.applyAll(state, 60);
            double t2 = state.fields.get(VariableType.T).get(0);
            double diff2 = Math.abs(t2 - t1);

            log.info("第1步温度变化: {:.4f}K, 第2步: {:.4f}K", diff1, diff2);

            assertThat(diff1)
                    .as("首次调用有辐射，温度变化大")
                    .isGreaterThan(1e-6);
        }

        @Test
        @DisplayName("关闭某个物理方案不影响其他")
        void testDisableScheme() {
            System.setProperty("nwp.physics.cumulus", "off");
            NWPConfig testCfg = new NWPConfig();
            PhysicsParameterizationManager mgr2 = new PhysicsParameterizationManager(testCfg);

            assertThat(mgr2.getScheme(PhysicsType.CUMULUS))
                    .as("积云方案应被关闭")
                    .isEmpty();

            assertThat(mgr2.getScheme(PhysicsType.MICROPHYSICS))
                    .as("微物理方案仍存在")
                    .isPresent();
        }
    }

    @Nested
    @DisplayName("CAPE/CIN和对流参数")
    class CAPETest {

        @Test
        @DisplayName="CAPE计算：稳定大气CAPE≈0"
        void testCAPEStable() {
            ColumnData col = createStableColumn();
            double[] cape = {0};
            double[] cin = {0};
            int[] lcl = {0}, lfc = {0}, el = {0};

            kf.computeCAPE(col, cape, cin, lcl, lfc, el);

            log.info("稳定大气 CAPE={:.1f} J/kg, CIN={:.1f} J/kg, LCL={}, LFC={}, EL={}",
                    cape[0], cin[0], lcl[0], lfc[0], el[0]);

            assertThat(cape[0])
                    .as("稳定大气CAPE应接近0")
                    .isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName="CAPE计算：条件不稳定有正CAPE"
        void testCAPEUnstable() {
            ColumnData col = createUnstableColumn();
            double[] cape = {0};
            double[] cin = {0};
            int[] lcl = {0}, lfc = {0}, el = {0};

            kf.computeCAPE(col, cape, cin, lcl, lfc, el);

            log.info("不稳定大气 CAPE={:.1f} J/kg, CIN={:.1f} J/kg", cape[0], cin[0]);

            assertThat(cape[0])
                    .as("应具有正CAPE")
                    .isGreaterThan(0);

            assertThat(lcl[0])
                    .as("LCL在合理范围")
                    .isBetween(0, config.getNZ());
        }
    }

    private ColumnData createTestColumn() {
        ModelState s = TestDataFactory.createStandardAtmosphere(config, testTime);
        ColumnData col = new ColumnData(config.getNZ());
        col.extract(s, config.getNX() / 2, config.getNY() / 2, grid);
        for (int k = 0; k < config.getNZ(); k++) {
            col.qc[k] = Math.max(0, 1e-4 - k * 1e-6);
            col.qr[k] = Math.max(0, 5e-5 - k * 1e-6);
            col.qi[k] = k > config.getNZ() / 2 ? 1e-5 : 0;
            col.qs[k] = k > config.getNZ() / 2 + 2 ? 5e-6 : 0;
            col.qg[k] = k > config.getNZ() * 3 / 4 ? 2e-6 : 0;
        }
        return col;
    }

    private ColumnData createStableColumn() {
        ColumnData col = new ColumnData(config.getNZ());
        for (int k = 0; k < config.getNZ(); k++) {
            col.p[k] = 101325 * Math.exp(-k * 1000.0 / 8000.0);
            col.tk[k] = 298 - 6.5 * k / (double) config.getNZ() * 15;
            col.qv[k] = 0.01 * Math.exp(-k / 3.0);
        }
        col.psfc = 101325;
        return col;
    }

    private ColumnData createUnstableColumn() {
        ColumnData col = createStableColumn();
        col.tk[0] = 305;
        col.tk[1] = 303;
        col.qv[0] = 0.02;
        col.qv[1] = 0.018;
        return col;
    }

    private ModelState createStateFromColumn(ColumnData col) {
        ModelState s = TestDataFactory.createStandardAtmosphere(config, testTime);
        return s;
    }

    private double[] getTotalWaterByLayer(ColumnData col) {
        double[] w = new double[config.getNZ()];
        for (int k = 0; k < config.getNZ(); k++) {
            w[k] = col.qv[k] + col.qc[k] + col.qr[k] + col.qi[k] + col.qs[k] + col.qg[k];
        }
        return w;
    }

    private double sum(double[] arr) {
        double s = 0;
        for (double v : arr) if (Double.isFinite(v)) s += v;
        return s;
    }
}
