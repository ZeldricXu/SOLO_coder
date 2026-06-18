package com.meteorology.nwp.test;

import com.meteorology.nwp.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("NWP测试基类")
public abstract class NWPTestBase {
    protected static final Logger log = LoggerFactory.getLogger(NWPTestBase.class);
    protected static NWPConfig config;
    protected static GridDefinition grid;
    protected static Instant testTime;

    @TempDir
    protected Path tempDir;

    @BeforeAll
    static void setupGlobalConfig() {
        System.setProperty("nwp.grid.nx", "64");
        System.setProperty("nwp.grid.ny", "32");
        System.setProperty("nwp.grid.nz", "15");
        System.setProperty("nwp.dynamics.timeStep", "300");
        System.setProperty("nwp.dynamics.diffusionCoef", "1e5");
        System.setProperty("nwp.physics.cumulus", "kain-fritsch");
        System.setProperty("nwp.physics.boundary", "ysu");
        System.setProperty("nwp.physics.microphysics", "wsm6");
        System.setProperty("nwp.physics.radiation", "rrtmg");
        System.setProperty("nwp.parallel.numPartitionsX", "2");
        System.setProperty("nwp.parallel.numPartitionsY", "2");
        System.setProperty("nwp.parallel.haloWidth", "3");
        config = new NWPConfig();
        grid = config.getGrid();
        testTime = Instant.parse("2024-06-15T12:00:00Z");
        log.info("测试环境初始化: {}x{}x{}, dt={}s",
                config.getNX(), config.getNY(), config.getNZ(), config.getTimeStep());
    }

    @AfterAll
    static void cleanupGlobal() {
        log.info("所有测试完成");
    }

    protected static void assertFinite(double value, String name) {
        assertThat(value)
                .as("%s应该是有限值", name)
                .isFinite()
                .isNotNaN()
                .isNotInfinite();
    }

    protected static void assertAllFinite(DataField f, String name) {
        int nanCount = 0;
        int infCount = 0;
        for (int i = 0; i < f.getSize(); i++) {
            double v = f.get(i);
            if (Double.isNaN(v)) nanCount++;
            if (Double.isInfinite(v)) infCount++;
        }
        assertThat(nanCount)
                .as("%s 有 %d 个NaN", name, nanCount)
                .isEqualTo(0);
        assertThat(infCount)
                .as("%s 有 %d 个Infinite", name, infCount)
                .isEqualTo(0);
    }

    protected static void assertConservation(double initial, double finalValue,
                                              double tolerance, String name) {
        double relative = Math.abs(finalValue - initial) / Math.max(1e-10, Math.abs(initial));
        assertThat(relative)
                .as("%s 守恒相对误差: %.6f, 容差 %.6f", name, relative, tolerance)
                .isLessThanOrEqualTo(tolerance);
    }

    protected static void assertOrderOfAccuracy(double[] dtValues, double[] errorValues,
                                                 double expectedOrder, double tolerance, String name) {
        assertThat(dtValues.length).isEqualTo(errorValues.length);
        assertThat(dtValues.length).isGreaterThanOrEqualTo(2);
        for (int i = 0; i < dtValues.length - 1; i++) {
            double dt1 = dtValues[i], dt2 = dtValues[i + 1];
            double e1 = errorValues[i], e2 = errorValues[i + 1];
            double ratio = dt1 / dt2;
            double expectedRatio = Math.pow(ratio, expectedOrder);
            double actualRatio = e1 / e2;
            log.info("  {} dt={:.0f}→{:.0f}s, 误差比={:.3f} (预期O({})= {:.3f})",
                    name, dt1, dt2, actualRatio, expectedOrder, expectedRatio);
            assertThat(actualRatio)
                    .as("%s 收敛阶 (dt=%f→%f): 实际%.2f vs 预期%.2f",
                            name, dt1, dt2, actualRatio, expectedRatio)
                    .isCloseTo(expectedRatio, within(expectedRatio * tolerance));
        }
    }

    protected static double computeL2Error(DataField a, DataField b) {
        int n = Math.min(a.getSize(), b.getSize());
        double sumSq = 0;
        for (int i = 0; i < n; i++) {
            double d = a.get(i) - b.get(i);
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / n);
    }

    protected static double computeLinfError(DataField a, DataField b) {
        int n = Math.min(a.getSize(), b.getSize());
        double maxAbs = 0;
        for (int i = 0; i < n; i++) {
            double d = Math.abs(a.get(i) - b.get(i));
            maxAbs = Math.max(maxAbs, d);
        }
        return maxAbs;
    }

    protected static String formatTimeNanos(long nanos) {
        if (nanos < 1_000) return nanos + " ns";
        if (nanos < 1_000_000) return String.format("%.2f μs", nanos / 1_000.0);
        if (nanos < 1_000_000_000) return String.format("%.2f ms", nanos / 1_000_000.0);
        return String.format("%.3f s", nanos / 1_000_000_000.0);
    }
}
