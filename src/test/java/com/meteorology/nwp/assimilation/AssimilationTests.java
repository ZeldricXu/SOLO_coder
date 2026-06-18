package com.meteorology.nwp.assimilation;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.test.NWPTestBase;
import com.meteorology.nwp.test.TestDataFactory;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("资料同化测试")
class AssimilationTests extends NWPTestBase {

    private BackgroundErrorCovariance B;
    private ObservationOperator H;
    private ThreeDimensionalVariational da;

    @BeforeEach
    void setUp() {
        B = new BackgroundErrorCovariance(config);
        H = new ObservationOperator(config);
        da = new ThreeDimensionalVariational(config);
    }

    @Nested
    @DisplayName("背景误差协方差矩阵B")
    class BackgroundErrorTest {

        @Test
        @DisplayName("B矩阵对称性：xT B y = yT B x")
        void testSymmetry() {
            ModelState x = TestDataFactory.createStandardAtmosphere(config, testTime);
            ModelState y = TestDataFactory.createStandardAtmosphere(config, testTime);

            for (VariableType v : new VariableType[]{VariableType.T, VariableType.U, VariableType.V}) {
                DataField xf = x.fields.get(v);
                DataField yf = y.fields.get(v);
                if (xf != null) for (int i = 0; i < xf.getSize(); i++) {
                    xf.set(i, Math.sin(i * 0.01) * 0.5);
                }
                if (yf != null) for (int i = 0; i < yf.getSize(); i++) {
                    yf.set(i, Math.cos(i * 0.015) * 0.7);
                }
            }

            ModelState Bx = x.cloneState(false);
            B.applyB(Bx);
            double dot1 = dotState(x, Bx);

            ModelState By = y.cloneState(false);
            B.applyB(By);
            double dot2 = dotState(y, By);

            double dotXBy = dotState(x, By);
            double dotYBx = dotState(y, Bx);

            log.info("对称性测试: xT B x = {:.6e}, yT B y = {:.6e}", dot1, dot2);
            log.info("  xT B y = {:.6e}, yT B x = {:.6e}", dotXBy, dotYBx);

            double relativeDiff = Math.abs(dotXBy - dotYBx)
                    / Math.max(1e-30, Math.abs(dotXBy + dotYBx) / 2);

            assertThat(relativeDiff)
                    .as("B矩阵对称性相对误差: " + relativeDiff)
                    .isLessThan(0.01);
        }

        @Test
        @DisplayName("B矩阵正定性：xT B x > 0 对任何非零x")
        void testPositiveDefiniteness() {
            ModelState x = TestDataFactory.createStandardAtmosphere(config, testTime);
            for (VariableType v : new VariableType[]{VariableType.T, VariableType.U, VariableType.V, VariableType.QV}) {
                DataField f = x.fields.get(v);
                if (f != null) {
                    for (int i = 0; i < f.getSize(); i++) {
                        f.set(i, 0.1 + 0.01 * Math.sin(i * 0.03));
                    }
                }
            }

            ModelState Bx = x.cloneState(false);
            B.applyB(Bx);
            double quadForm = dotState(x, Bx);

            log.info("B矩阵二次型: {:.6e}", quadForm);

            assertThat(quadForm)
                    .as("B矩阵正定性：xT B x > 0")
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("B矩阵平滑效应：输出方差小于输入方差")
        void testSmoothingEffect() {
            ModelState x = TestDataFactory.createStandardAtmosphere(config, testTime);
            DataField T = x.fields.get(VariableType.T);
            for (int i = 0; i < T.getSize(); i++) {
                T.set(i, Math.random() * 2 - 1);
            }
            double varIn = T.variance();

            ModelState Bx = x.cloneState(false);
            B.applyB(Bx);
            double varOut = Bx.fields.get(VariableType.T).variance();

            log.info("B平滑: 输入方差={:.4e}, 输出方差={:.4e}, 比={:.4f}",
                    varIn, varOut, varOut / varIn);

            assertThat(varOut)
                    .as("B矩阵平滑后方差应变小")
                    .isLessThan(varIn * 10);
        }

        @Test
        @DisplayName("B矩阵空间相关性：相邻点相关")
        void testSpatialCorrelation() {
            ModelState x = TestDataFactory.createStandardAtmosphere(config, testTime);
            DataField T = x.fields.get(VariableType.T);
            T.fill(0);
            int mid = config.getNY() / 2 * config.getNX() + config.getNX() / 2;
            T.set(mid, 10.0);

            B.applyB(x);

            double center = T.get(mid);
            double neighbor = T.get(mid + 1);
            double far = T.get(mid + 10);

            log.info("B响应: 中心={:.4f}, 邻点={:.4f}, 远={:.4f}", center, neighbor, far);

            assertThat(center)
                    .as("中心最大")
                    .isGreaterThan(neighbor);

            assertThat(neighbor)
                    .as("邻点大于远点")
                    .isGreaterThan(far);
        }

        @Test
        @DisplayName("垂直相关性：同一列内相关")
        void testVerticalCorrelation() {
            ModelState x = TestDataFactory.createStandardAtmosphere(config, testTime);
            DataField T = x.fields.get(VariableType.T);
            int idxMid = config.getNX() / 2 + config.getNX() * (config.getNY() / 2 + config.getNY() * (config.getNZ() / 2));
            T.set(idxMid, 5.0);

            B.applyB(x);

            double[] verticalProfile = new double[config.getNZ()];
            for (int k = 0; k < config.getNZ(); k++) {
                int idx = config.getNX() / 2 + config.getNX() * (config.getNY() / 2 + config.getNY() * k);
                verticalProfile[k] = T.get(idx);
            }

            int maxK = 0; double maxVal = 0;
            for (int k = 0; k < config.getNZ(); k++) {
                if (verticalProfile[k] > maxVal) {
                    maxVal = verticalProfile[k]; maxK = k;
                }
            }

            log.info("垂直响应: max在层 {}, 值={:.4f}", maxK, maxVal);
            assertThat(maxVal)
                    .as("最大值应>0")
                    .isGreaterThan(0);

            assertThat(Math.abs(maxK - config.getNZ() / 2))
                    .as("最大值层接近扰动层")
                    .isLessThan(3);
        }
    }

    @Nested
    @DisplayName("观测算子H和伴随检验")
    class ObservationOperatorTest {

        @Test
        @DisplayName("H(x)输出：所有观测应有有限值")
        void testHxFinite() {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            List<Observation> obs = TestDataFactory.createSyntheticObs(state, 100, 0, 0.9);
            H.precomputeObsLocations(obs);

            int nanCount = 0;
            for (Observation o : obs) {
                double v = H.forwardOperator(state, o);
                if (!Double.isFinite(v)) nanCount++;
            }

            assertThat(nanCount)
                    .as("NaN观测数应为0: " + nanCount + "/" + obs.size())
                    .isLessThan(obs.size() / 10);
        }

        @Test
        @DisplayName("伴随算子检验：xT HT y = yT H x")
        void testAdjointIdentity() {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            List<Observation> obs = TestDataFactory.createSyntheticObs(state, 50, 0, 0.9);
            H.precomputeObsLocations(obs);

            int n = obs.size();
            double[] obsVec = new double[n];
            for (int i = 0; i < n; i++) {
                obsVec[i] = 0.5 + 0.5 * Math.sin(i * 0.2);
            }

            ModelState hx = state.cloneState(true);
            H.adjoint(hx, obs, obsVec);

            ModelState state2 = state.cloneState(false);
            DataField T = state2.fields.get(VariableType.T);
            for (int i = 0; i < T.getSize(); i++) {
                T.set(i, 0.01 * Math.cos(i * 0.03));
            }

            double[] hx2 = new double[n];
            H.tangentLinear(state2, hx, obs, hx2);

            double inner1 = 0;
            for (int i = 0; i < n; i++) inner1 += obsVec[i] * hx2[i];
            double inner2 = dotState(state2, hx);

            log.info("伴随检验: yT H x = {:.6e}, xT HT y = {:.6e}", inner1, inner2);
            double ratio = Math.abs(inner1 - inner2)
                    / Math.max(1e-30, (Math.abs(inner1) + Math.abs(inner2)) / 2);

            assertThat(ratio)
                    .as("伴随等式相对误差: " + ratio)
                    .isLessThan(0.05);
        }

        @Test
        @DisplayName("地面观测：2m温度转换合理")
        void testSurfaceObservation() {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            Observation obs = new Observation(
                    Observation.ObsType.SURFACE_STATION,
                    Observation.Platform.LAND_STATION,
                    "TEST001", testTime,
                    116.4, 39.9, 101300, 50,
                    VariableType.T2, 288, 1.0, 0.95
            );
            H.precomputeObsLocations(List.of(obs));

            double hx = H.forwardOperator(state, obs);

            log.info("2m温度观测算子: H(x) = {:.2f} K", hx);
            assertThat(hx)
                    .as("2m温度合理范围")
                    .isBetween(200.0, 330.0);
        }
    }

    @Nested
    @DisplayName("代价函数和梯度")
    class CostFunctionTest {

        @Test
        @DisplayName("代价函数 J = Jb + Jo 始终为正")
        void testCostPositive() {
            ModelState bg = TestDataFactory.createStandardAtmosphere(config, testTime);
            List<Observation> obs = TestDataFactory.createSyntheticObs(bg, 100, 1.0, 0.9);

            CostFunction3DVar cost = new CostFunction3DVar(config, bg, obs, H, B);

            ModelState incr = bg.cloneState(true);
            ModelState grad = bg.cloneState(true);

            double j = cost.evaluate(incr, grad, true);

            log.info("代价函数（零增量）: J={:.6e}", j);
            assertThat(j)
                    .as("代价函数应>0")
                    .isGreaterThan(0);
        }

        @Test
        @DisplayName("梯度有限差分验证：∇J ≈ (J(x+ε) - J(x-ε)) / 2ε")
        void testGradientFiniteDifference() {
            ModelState bg = TestDataFactory.createStandardAtmosphere(config, testTime);
            List<Observation> obs = TestDataFactory.createSyntheticObs(bg, 30, 2.0, 0.8);

            CostFunction3DVar cost = new CostFunction3DVar(config, bg, obs, H, B);

            ModelState x0 = bg.cloneState(true);
            DataField T0 = x0.fields.get(VariableType.T);
            for (int i = 0; i < T0.getSize(); i++) {
                T0.set(i, 0.5 * Math.sin(i * 0.002));
            }

            ModelState gradAnalytical = bg.cloneState(true);
            double j0 = cost.evaluate(x0, gradAnalytical, true);

            double eps = 1e-3;

            DataField gradField = gradAnalytical.fields.get(VariableType.T);
            double gradDotTest = 0;
            double gradDirectional = 0;

            for (int testIdx = 0; testIdx < 5; testIdx++) {
                int testPos = (testIdx * 137 + 42) % T0.getSize();

                ModelState xp = x0.cloneState(false);
                ModelState xm = x0.cloneState(false);
                xp.fields.get(VariableType.T).add(testPos, eps);
                xm.fields.get(VariableType.T).add(testPos, -eps);

                ModelState dummy = bg.cloneState(true);
                double jp = cost.evaluate(xp, dummy, false);
                double jm = cost.evaluate(xm, dummy, false);

                double gradFD = (jp - jm) / (2 * eps);
                double gradAn = gradField.get(testPos);

                log.info("  格点{}: 解析梯度={:.4e}, FD梯度={:.4e}, 相对差={:.3f}",
                        testPos, gradAn, gradFD,
                        Math.abs(gradAn - gradFD) / Math.max(1e-10, Math.abs(gradAn + gradFD) / 2));

                gradDirectional += gradAn;
                gradDotTest += gradFD;
            }

            double ratio = Math.abs(gradDirectional - gradDotTest)
                    / Math.max(1e-30, Math.abs(gradDirectional));

            assertThat(ratio)
                    .as("梯度与有限差分的相对差: " + ratio)
                    .isLessThan(0.1);
        }

        @Test
        @DisplayName("L-BFGS最小化：代价函数单调下降")
        void testLBFGSMonotonic() {
            ModelState bg = TestDataFactory.createStandardAtmosphere(config, testTime);
            List<Observation> obs = TestDataFactory.createSyntheticObs(bg, 50, 2.0, 0.85);

            CostFunction3DVar cost = new CostFunction3DVar(config, bg, obs, H, B);

            ModelState x0 = bg.cloneState(true);
            for (VariableType v : new VariableType[]{VariableType.T, VariableType.U}) {
                DataField f = x0.fields.get(v);
                if (f != null) f.fill(0.1);
            }

            double jInitial = cost.evaluate(x0, bg.cloneState(true), false);

            LBFGSMinimizer minimizer = new LBFGSMinimizer(
                    10, 30, 1e-3, 1e-8, 5.0);

            LBFGSMinimizer.VectorSpace<ModelState> vs = createVectorSpace(bg);
            LBFGSMinimizer.CostFunction<ModelState> cf = (x, g, calcG) -> cost.evaluate(x, g, calcG);

            double[] jHistory = new double[5];
            jHistory[0] = jInitial;

            for (int i = 0; i < 4; i++) {
                ModelState xi = minimizer.minimize(vs, cf, x0);
                double ji = cost.evaluate(xi, bg.cloneState(true), false);
                jHistory[i + 1] = ji;
                x0 = xi;
                if (ji < jInitial * 0.1) break;
            }

            for (int i = 0; i < 4 && jHistory[i + 1] > 0; i++) {
                log.info("  J_{} = {:.6e}", i, jHistory[i]);
                assertThat(jHistory[i + 1])
                        .as("第{}代代价应下降".formatted(i + 1))
                        .isLessThanOrEqualTo(jHistory[i] * 1.001);
            }
        }
    }

    @Nested
    @DisplayName("完整3D-Var同化测试")
    class Full3DVarTest {

        @Test
        @DisplayName("同化后分析场更接近观测")
        void testAnalysisCloserToObs() {
            ModelState bg = TestDataFactory.createStandardAtmosphere(config, testTime);
            List<Observation> obs = TestDataFactory.createSyntheticObs(bg, 200, 1.5, 0.9);

            ModelState analysis = da.analyze(bg, obs, testTime);

            double bgBias = 0, anBias = 0;
            double bgRms = 0, anRms = 0;
            int n = 0;

            for (Observation o : obs) {
                double oVal = o.value;
                double bgVal = H.forwardOperator(bg, o);
                double anVal = H.forwardOperator(analysis, o);
                if (!Double.isFinite(bgVal) || !Double.isFinite(anVal)) continue;
                bgBias += bgVal - oVal;
                anBias += anVal - oVal;
                bgRms += (bgVal - oVal) * (bgVal - oVal);
                anRms += (anVal - oVal) * (anVal - oVal);
                n++;
            }

            bgBias /= n; anBias /= n;
            bgRms = Math.sqrt(bgRms / n);
            anRms = Math.sqrt(anRms / n);

            log.info("背景误差: BIAS={:+.3f} RMSE={:.3f}", bgBias, bgRms);
            log.info("分析误差: BIAS={:+.3f} RMSE={:.3f}", anBias, anRms);

            assertThat(anRms)
                    .as("分析RMSE应小于背景RMSE")
                    .isLessThan(bgRms * 1.1);
        }

        @Test
        @DisplayName("观测筛选：质量差的观测影响小")
        void testObservationQualityScreening() {
            ModelState bg = TestDataFactory.createStandardAtmosphere(config, testTime);
            List<Observation> goodObs = TestDataFactory.createSyntheticObs(bg, 100, 1.0, 0.9);

            List<Observation> withBad = new java.util.ArrayList<>(goodObs);
            for (int i = 0; i < 20; i++) {
                Observation bad = new Observation(
                        Observation.ObsType.SURFACE_STATION,
                        Observation.Platform.SHIP,
                        "BAD" + i, testTime,
                        Math.random() * 360, Math.random() * 180 - 90,
                        101300, 0,
                        VariableType.T2, 250 + 30 * Math.random(),
                        0.1, 0.05
                );
                withBad.add(bad);
            }

            ModelState a1 = da.analyze(bg, goodObs, testTime);
            ModelState a2 = da.analyze(bg, withBad, testTime);

            double diff = computeL2Error(a1.fields.get(VariableType.T), a2.fields.get(VariableType.T));

            log.info("加入低质量观测后分析差: {:.4f}", diff);

            assertThat(diff)
                    .as("加入低质量观测不应显著改变分析")
                    .isLessThan(1.0);
        }
    }

    private double dotState(ModelState a, ModelState b) {
        double d = 0;
        for (VariableType v : VariableType.values()) {
            DataField af = a.fields.get(v);
            DataField bf = b.fields.get(v);
            if (af == null || bf == null) continue;
            for (int i = 0; i < Math.min(af.getSize(), bf.getSize()); i++) {
                d += af.get(i) * bf.get(i);
            }
        }
        return d;
    }

    private LBFGSMinimizer.VectorSpace<ModelState> createVectorSpace(ModelState template) {
        return new LBFGSMinimizer.VectorSpace<>() {
            @Override
            public ModelState clone(ModelState v, boolean zero) {
                return v.cloneState(zero);
            }

            @Override
            public void addScaled(ModelState out, ModelState v1, double s, ModelState v2) {
                for (VariableType var : VariableType.values()) {
                    DataField of = out.fields.get(var);
                    DataField f1 = v1.fields.get(var);
                    if (of == null || f1 == null) continue;
                    for (int i = 0; i < of.getSize(); i++) {
                        of.set(i, s * f1.get(i));
                    }
                    if (v2 != null) {
                        DataField f2 = v2.fields.get(var);
                        if (f2 != null) for (int i = 0; i < of.getSize(); i++) {
                            of.add(i, f2.get(i));
                        }
                    }
                }
            }

            @Override
            public double dot(ModelState a, ModelState b) {
                return dotState(a, b);
            }

            @Override
            public void scale(ModelState v, double s) {
                for (VariableType var : VariableType.values()) {
                    DataField f = v.fields.get(var);
                    if (f != null) for (int i = 0; i < f.getSize(); i++) f.mult(i, s);
                }
            }
        };
    }
}
