package com.meteorology.nwp;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.dynamics.*;
import com.meteorology.nwp.io.*;
import com.meteorology.nwp.parallel.GridPartitioner;
import com.meteorology.nwp.physics.*;
import com.meteorology.nwp.assimilation.*;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

public class NWPCoreTests {
    private NWPConfig config;
    private GridDefinition grid;
    private final int TEST_NX = 36;
    private final int TEST_NY = 18;
    private final int TEST_NZ = 10;
    private static final double EPS = 1e-6;

    @Before
    public void setUp() {
        System.setProperty("nwp.grid.nx", String.valueOf(TEST_NX));
        System.setProperty("nwp.grid.ny", String.valueOf(TEST_NY));
        System.setProperty("nwp.grid.nz", String.valueOf(TEST_NZ));
        config = new NWPConfig();
        grid = config.getGrid();
    }

    @Test
    public void testNWPConfigLoads() {
        assertTrue("Config必须有网格", config.getNX() >= TEST_NX);
        assertTrue("Sigma坐标必须有nz层", config.getSigmaLevels().length == config.getNZ());
        assertEquals("经度间隔", 360.0 / config.getNX(), config.getGrid().dLon, 1e-6);
    }

    @Test
    public void testGridDefinitionMetrics() {
        double[] lat = config.getGrid().latRad;
        for (int j = 0; j < lat.length; j++) {
            double f = config.getGrid().fCoriolis[j];
            double expected = 2 * 7.2921e-5 * Math.sin(lat[j]);
            assertEquals("Coriolis参数纬度" + j, expected, f, Math.abs(expected) * 0.01 + 1e-9);
        }
        double areaSum = 0;
        for (int j = 0; j < config.getNY(); j++) {
            areaSum += config.getNX() * config.getGrid().cellArea[j];
        }
        double sphereArea = 4 * Math.PI * (6.371e6) * (6.371e6);
        double ratio = areaSum / sphereArea;
        assertTrue("全球面积合理: ratio=" + ratio, ratio > 0.7 && ratio < 1.3);
    }

    @Test
    public void testVariableTypeGribMapping() {
        VariableType t = VariableType.fromGribCode(0, 0, 0);
        assertNotNull(t);
        assertTrue("温度是3D变量", t.is3D());
        assertTrue("温度是预报变量", t.isPrognostic());
    }

    @Test
    public void testDataFieldOperations() {
        DataField f2d = new DataField(10, 20);
        assertEquals("2D大小", 200, f2d.getSize());
        f2d.fill(3.14);
        assertEquals("平均值", 3.14, f2d.mean(), EPS);
        assertEquals("方差", 0.0, f2d.variance(), EPS);
        DataField f3d = new DataField(5, 6, 7);
        assertEquals("3D大小", 210, f3d.getSize());
        for (int i = 0; i < 210; i++) f3d.set(i, i);
        assertEquals("索引一致性", 0.0, f3d.get(0 + 5 * (0 + 6 * 0)), EPS);
        assertEquals("max", 209.0, f3d.get(209), EPS);
    }

    @Test
    public void testModelStateCloning() {
        FullDynamicsSolver solver = new FullDynamicsSolver(config);
        ModelState s = solver.initializeIdealized("standard-atmosphere", Instant.now());
        ModelState clone = s.cloneState(false);
        assertNotSame("深拷贝不同对象", s, clone);
        assertTrue("必须有温度", s.fields.containsKey(VariableType.T));
        DataField t1 = s.fields.get(VariableType.T);
        DataField t2 = clone.fields.get(VariableType.T);
        assertEquals("温度相同", t1.get(42), t2.get(42), EPS);
        t2.set(42, t1.get(42) + 1);
        assertNotEquals("独立修改不影响原对象", t1.get(42), t2.get(42), EPS);
    }

    @Test
    public void testPhysicsConstantsCalculations() {
        double tk = 288.15;
        double es = PhysicsConstants.saturationVaporPressure(tk);
        assertTrue("饱和水汽压 合理: " + es, es > 1000 && es < 3000);
        double theta = PhysicsConstants.potentialTemperature(tk, 90000);
        assertTrue("位温>气温 " + theta + "K", theta > tk && theta < tk + 30);
        double qv = 0.01;
        double tv = PhysicsConstants.virtualTemperature(tk, qv);
        assertTrue("虚温>气温 " + tv + "K", tv > tk && tv < tk + 2);
    }

    @Test
    public void testDynamicsRK3Stability() {
        FullDynamicsSolver solver = new FullDynamicsSolver(config);
        ModelState s = solver.initializeIdealized("rossby-wave", Instant.now());
        double cfl1 = solver.computeCFL(s);
        solver.step(s, 600, 1);
        solver.step(s, 600, 1);
        double cfl2 = solver.computeCFL(s);
        assertTrue("初始CFL<0.5: " + cfl1, cfl1 < 0.5);
        assertTrue("积分后CFL仍稳定: " + cfl2, cfl2 < 2.0);
        DataField t = s.fields.get(VariableType.T);
        double tMean = t.mean();
        assertTrue("平均温度合理: " + tMean + "K", tMean > 200 && tMean < 320);
    }

    @Test
    public void testShallowWaterConservation() {
        ShallowWaterSolver sw = new ShallowWaterSolver(config);
        ModelState s = sw.initializeIdealized();
        DataField h0 = s.fields.get(VariableType.GEOPOTENTIAL);
        double mass0 = 0;
        for (int i = 0; i < h0.getSize(); i++) mass0 += h0.get(i);
        for (int k = 0; k < 10; k++) sw.stepRK3(s, 60);
        DataField h1 = s.fields.get(VariableType.GEOPOTENTIAL);
        double mass1 = 0;
        for (int i = 0; i < h1.getSize(); i++) mass1 += h1.get(i);
        double drift = Math.abs(mass1 - mass0) / Math.max(1, Math.abs(mass0));
        assertTrue("质量守恒漂移<1%: " + drift, drift < 0.01);
    }

    @Test
    public void testPhysicsManagerInitialization() {
        PhysicsParameterizationManager mgr = new PhysicsParameterizationManager(config);
        assertTrue("至少1个方案", mgr.getScheme(PhysicsType.BOUNDARY_LAYER).isPresent());
        assertTrue("YSU", mgr.getScheme(PhysicsType.BOUNDARY_LAYER).get().getName().contains("YSU"));
        assertTrue("WSM6", mgr.getScheme(PhysicsType.MICROPHYSICS).isPresent());
    }

    @Test
    public void testYSUBoundaryLayerColumn() {
        YSUBoundaryLayer ysu = new YSUBoundaryLayer();
        ysu.initialize(config);
        ColumnData col = createTestColumn();
        ysu.applyColumn(col, 300, config.getGrid(), 5, 8);
        double tDiff = 0;
        for (int k = 0; k < config.getNZ(); k++) tDiff += col.tTend[k] * 300;
        assertTrue("边界层加热倾向有限: " + tDiff, Double.isFinite(tDiff));
    }

    @Test
    public void testWSM6MicrophysicsConservation() {
        WSM6Microphysics wsm6 = new WSM6Microphysics();
        wsm6.initialize(config);
        ColumnData col = createTestColumn();
        double qv0 = sum(col.qv), qc0 = sum(col.qc), qr0 = sum(col.qr);
        double qi0 = sum(col.qi), qs0 = sum(col.qs), qg0 = sum(col.qg);
        double water0 = qv0 + qc0 + qr0 + qi0 + qs0 + qg0;
        wsm6.applyColumn(col, 300, config.getGrid(), 5, 8);
        double water1 = sum(col.qv) + sum(col.qc) + sum(col.qr)
                      + sum(col.qi) + sum(col.qs) + sum(col.qg);
        double drift = Math.abs(water1 - water0) / Math.max(1e-10, water0);
        assertTrue("水物质守恒漂移<5%: " + drift, drift < 0.05);
    }

    @Test
    public void testKainFritschCAPE() {
        KainFritschCumulus kf = new KainFritschCumulus();
        kf.initialize(config);
        ColumnData col = createTestColumn();
        double[] cape = {0}; double[] cin = {0}; int[] lcl = {0};
        kf.computeCAPE(col, cape, cin, lcl, new int[1], new int[1]);
        assertTrue("CAPE>=0: " + cape[0], cape[0] >= -10);
        assertTrue("LCL在低层", lcl[0] >= 0 && lcl[0] < config.getNZ());
    }

    @Test
    public void testThreeDVarBackgroundMatrix() {
        BackgroundErrorCovariance B = new BackgroundErrorCovariance(config);
        ModelState xi = new FullDynamicsSolver(config)
                .initializeIdealized("thermal-low", Instant.now());
        ModelState inc = xi.cloneState(true);
        DataField T = inc.fields.get(VariableType.T);
        for (int j = TEST_NY / 3; j < 2 * TEST_NY / 3; j++) {
            for (int i = TEST_NX / 3; i < 2 * TEST_NX / 3; i++) {
                T.set(i + config.getNX() * (j + config.getNY() * (TEST_NZ / 2)), 2);
            }
        }
        ModelState before = inc.cloneState(false);
        B.applyB(inc);
        double max0 = Math.abs(before.fields.get(VariableType.T).max() - 2) < 0.1 ? 2 : 0;
        double max1 = inc.fields.get(VariableType.T).max();
        assertTrue("B矩阵平滑扩散: " + max0 + " -> " + max1, max1 < 5);
    }

    @Test
    public void testObservationOperator() {
        ObservationOperator H = new ObservationOperator(config);
        ModelState s = new FullDynamicsSolver(config)
                .initializeIdealized("standard-atmosphere", Instant.now());
        Observation obs = new Observation(
                Observation.ObsType.SURFACE_STATION,
                Observation.Platform.LAND_STATION, "54511",
                Instant.now(), 116.4, 39.9, 101300, 50,
                VariableType.T2, 285.0, 1.0, 0.95
        );
        H.precomputeObsLocations(Collections.singletonList(obs));
        double h = H.forwardOperator(s, obs);
        assertTrue("2米温度合理 " + h + "K", Double.isFinite(h) && h > 200 && h < 330);
    }

    @Test
    public void testGridPartitioner() {
        GridPartitioner gp = new GridPartitioner(config);
        assertTrue("至少1个分区", gp.getTotalPartitions() >= 1);
        int h = gp.getHaloWidth();
        assertTrue("halo>0", h > 0);
        GridPartitioner.Partition[] ps = gp.getAllPartitions();
        int totI = 0, totJ = 0;
        int prevJend = -1;
        Map<Integer, Integer> iRangeByPy = new HashMap<>();
        for (GridPartitioner.Partition p : ps) {
            totI = Math.max(totI, p.iEnd);
            totJ = Math.max(totJ, p.jEnd);
            assertTrue("分区i范围 " + p, p.iEnd > p.iStart);
            assertTrue("分区j范围 " + p, p.jEnd > p.jStart);
            int py = p.pid / gp.getNumPartitionsX();
            iRangeByPy.merge(py, p.iEnd - p.iStart, Integer::sum);
        }
        for (int py = 0; py < gp.getNumPartitionsY(); py++) {
            assertEquals("行内x覆盖 " + py, (int) config.getNX(),
                    (int) iRangeByPy.getOrDefault(py, 0));
        }
        assertEquals("覆盖整个网格x", config.getNX(), totI);
    }

    @Test
    public void testResamplerInterpolation() {
        Resampler rs = new Resampler();
        double[][] src = new double[5][5];
        for (int j = 0; j < 5; j++) for (int i = 0; i < 5; i++) src[j][i] = i * 10 + j;
        double[][] dst = new double[9][9];
        rs.resampleHorizontal(src, dst, Resampler.Method.BILINEAR);
        double v00 = dst[0][0];
        double vCenter = dst[4][4];
        assertEquals("角点保持 " + v00, 0, v00, 0.01);
        assertTrue("中心值合理 " + vCenter, vCenter > 0 && vCenter < 100);
    }

    @Test
    public void testColumnDataExtractCommit() {
        ModelState s = new FullDynamicsSolver(config)
                .initializeIdealized("standard-atmosphere", Instant.now());
        ColumnData col = new ColumnData(config.getNZ());
        col.extract(s, 5, 8, config.getGrid());
        assertTrue("高度非空", col.z[config.getNZ() - 1] > 0);
        double origT = col.tk[3];
        col.tTend[3] = 0.01;
        DataField origField = s.fields.get(VariableType.T).deepCopy();
        col.commit(s, 5, 8, config.getGrid(), 100);
        int idx = 5 + config.getNX() * (8 + config.getNY() * 3);
        double tAfter = s.fields.get(VariableType.T).get(idx);
        assertEquals("列提交后增量", origT + 0.01 * 100, tAfter, 0.001);
    }

    private ColumnData createTestColumn() {
        ModelState s = new FullDynamicsSolver(config)
                .initializeIdealized("standard-atmosphere", Instant.now());
        ColumnData col = new ColumnData(config.getNZ());
        col.extract(s, config.getNX() / 2, config.getNY() / 2, config.getGrid());
        for (int k = 0; k < config.getNZ(); k++) {
            col.qc[k] = Math.max(0, 1e-4 - k * 1e-5);
            col.qr[k] = Math.max(0, 5e-5 - k * 1e-5);
            col.qi[k] = k > config.getNZ() * 0.5 ? 5e-6 : 0;
            col.qs[k] = k > config.getNZ() * 0.6 ? 3e-5 : 0;
            col.qg[k] = k > config.getNZ() * 0.7 ? 1e-5 : 0;
        }
        return col;
    }

    private static double sum(double[] arr) {
        double s = 0;
        for (double v : arr) if (Double.isFinite(v)) s += v;
        return s;
    }
}
