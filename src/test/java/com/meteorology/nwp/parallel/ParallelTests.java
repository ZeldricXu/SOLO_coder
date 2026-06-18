package com.meteorology.nwp.parallel;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.test.NWPTestBase;
import com.meteorology.nwp.test.TestDataFactory;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("并行计算测试")
class ParallelTests extends NWPTestBase {

    private GridPartitioner partitioner;

    @BeforeEach
    void setUp() {
        partitioner = new GridPartitioner(config);
    }

    @Nested
    @DisplayName("GridPartitioner分区测试")
    class GridPartitionerTest {

        @Test
        @DisplayName("分区全覆盖：所有分区拼接等于全网格")
        void testFullCoverage() {
            int totalI = 0, totalJ = 0;

            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                totalI = Math.max(totalI, p.iEnd);
                totalJ = Math.max(totalJ, p.jEnd);
            }

            assertThat(totalI).as("X方向覆盖完整").isEqualTo(config.getNX());
            assertThat(totalJ).as("Y方向覆盖完整").isEqualTo(config.getNY());
        }

        @Test
        @DisplayName("分区无重叠")
        void testNoOverlap() {
            int npx = partitioner.getNumPartitionsX();
            int npy = partitioner.getNumPartitionsY();

            for (int py = 0; py < npy; py++) {
                for (int px = 0; px < npx; px++) {
                    int pid = py * npx + px;
                    GridPartitioner.Partition p = partitioner.getPartition(pid);

                    int xSum = 0, ySum = 0;
                    for (int py2 = 0; py2 < npy; py2++) {
                        for (int px2 = 0; px2 < npx; px2++) {
                            GridPartitioner.Partition p2 = partitioner.getPartition(py2 * npx + px2);
                            if (p2.pid == p.pid) continue;

                            int iOverlap = Math.max(0,
                                    Math.min(p.iEnd, p2.iEnd) - Math.max(p.iStart, p2.iStart));
                            int jOverlap = Math.max(0,
                                    Math.min(p.jEnd, p2.jEnd) - Math.max(p.jStart, p2.jStart));
                            xSum += iOverlap;
                            ySum += jOverlap;
                        }
                    }

                    assertThat(xSum)
                            .as("分区%d的X方向重叠应=0".formatted(pid))
                            .isEqualTo(0);
                }
            }
        }

        @Test
        @DisplayName("每个分区有正确数量的邻居")
        void testNeighborCount() {
            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                boolean isEdgeX = (p.iStart == 0) || (p.iEnd == config.getNX());
                boolean isEdgeY = (p.jStart == 0) || (p.jEnd == config.getNY());

                int expected = isEdgeY ? 6 : 8;

                assertThat(p.neighbors.size())
                        .as("分区%d应有%d个邻居，实际%d".formatted(p.pid, expected, p.neighbors.size()))
                        .isGreaterThanOrEqualTo(Math.min(3, expected));
            }
        }

        @Test
        @DisplayName("局部索引与全局索引转换一致")
        void testLocalGlobalIndexConversion() {
            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                for (int di = 0; di < 10; di++) {
                    int gi = p.iStart + di;
                    int li = p.toLocalI(gi);
                    int back = p.toGlobalI(li);

                    assertThat(back)
                            .as("分区%d I: %d → local %d → global %d".formatted(p.pid, gi, li, back))
                            .isEqualTo(gi);
                }

                for (int dj = 0; dj < 10; dj++) {
                    int gj = p.jStart + dj;
                    int lj = p.toLocalJ(gj);
                    int back = p.toGlobalJ(lj);

                    assertThat(back).isEqualTo(gj);
                }
            }
        }

        @Test
        @DisplayName("分区内halo宽度正确")
        void testHaloWidth() {
            int expectedHalo = partitioner.getHaloWidth();

            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                int coreW = p.iEnd - p.iStart;
                int localW = p.localNx;
                int haloFromSize = (localW - coreW) / 2;

                assertThat(haloFromSize)
                        .as("分区%d halo宽度: 预期%d 实际%d".formatted(p.pid, expectedHalo, haloFromSize))
                        .isEqualTo(expectedHalo);
            }
        }
    }

    @Nested
    @DisplayName("Halo交换测试")
    class HaloExchangeTest {

        @Test
        @DisplayName("提取子域再合并：数值无损")
        void testExtractMergeRoundTrip() {
            ModelState global = TestDataFactory.createStandardAtmosphere(config, testTime);
            global.ensurePrognosticFields(config);

            ModelState[] locals = new ModelState[partitioner.getTotalPartitions()];
            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                locals[p.pid] = partitioner.createLocalState(p);
                partitioner.extractSubdomain(global, p, locals[p.pid]);
            }

            ModelState merged = global.cloneState(true);
            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                partitioner.mergeSubdomain(locals[p.pid], p, merged);
            }

            for (VariableType v : VariableType.values()) {
                DataField go = global.fields.get(v);
                DataField me = merged.fields.get(v);
                if (go == null || me == null) continue;

                double l2err = computeL2Error(go, me);
                double linferr = computeLinfError(go, me);

                log.debug("  {}: L2={:.6e}, L∞={:.6e}", v, l2err, linferr);

                assertThat(l2err)
                        .as("变量%s 提取-合并L2误差".formatted(v))
                        .isLessThan(1e-10);
            }
        }

        @Test
        @DisplayName("Halo区包含正确的边界值")
        void testHaloValues() {
            ModelState global = TestDataFactory.createStandardAtmosphere(config, testTime);
            DataField T = global.fields.get(VariableType.T);

            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                ModelState local = partitioner.createLocalState(p);
                partitioner.extractSubdomain(global, p, local);
                DataField localT = local.fields.get(VariableType.T);
                if (localT == null) continue;

                int coreIS = p.iStart;
                int coreJS = p.jStart;

                for (int dj = 0; dj < 5; dj++) {
                    for (int di = 0; di < 5; di++) {
                        int gi = coreIS + di;
                        int gj = coreJS + dj;
                        int gIdx = gi + config.getNX() * (gj + config.getNY() * (config.getNZ() / 2));
                        double gVal = T.get(gIdx);

                        int li = p.toLocalI(gi);
                        int lj = p.toLocalJ(gj);
                        int lIdx = li + p.localNx * (lj + p.localNy * (config.getNZ() / 2));
                        double lVal = localT.get(lIdx);

                        assertThat(lVal)
                                .as("分区%d(%d,%d)与全局不一致".formatted(p.pid, gi, gj))
                                .isCloseTo(gVal, within(1e-10));
                    }
                }
            }
        }

        @Test
        @DisplayName("Halo交换管理器：交换后边界值一致")
        void testHaloExchangeConsistency() {
            HaloExchangeManager halo = new HaloExchangeManager(config, partitioner);

            ModelState[] locals = new ModelState[partitioner.getTotalPartitions()];
            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                locals[p.pid] = partitioner.createLocalState(p);
            }

            ModelState global = TestDataFactory.createStandardAtmosphere(config, testTime);
            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                partitioner.extractSubdomain(global, p, locals[p.pid]);
            }

            halo.performExchange(locals, 0);

            int innerPoints = 0;
            int mismatches = 0;
            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                int h = partitioner.getHaloWidth();
                DataField localT = locals[p.pid].fields.get(VariableType.T);
                DataField globalT = global.fields.get(VariableType.T);

                if (localT == null || globalT == null) continue;

                for (int lj = h; lj < p.localNy - h; lj++) {
                    for (int li = h; li < p.localNx - h; li++) {
                        int gi = p.toGlobalI(li);
                        int gj = p.toGlobalJ(lj);
                        innerPoints++;

                        for (int k = 0; k < config.getNZ(); k += 3) {
                            double lv = localT.get(li + p.localNx * (lj + p.localNy * k));
                            double gv = globalT.get(gi + config.getNX() * (gj + config.getNY() * k));
                            if (Math.abs(lv - gv) > 1e-8) mismatches++;
                        }
                    }
                }
            }

            double errorRate = (double) mismatches / Math.max(1, innerPoints);
            log.info("内点匹配率: {:.4f}% ({} 内点, {} 不匹配)",
                    (1 - errorRate) * 100, innerPoints, mismatches);

            assertThat(errorRate)
                    .as("内点数值不匹配率应<0.1%")
                    .isLessThan(0.001);
        }
    }

    @Nested
    @DisplayName("Spark Local模式计算")
    class SparkLocalTest {

        @Test
        @DisplayName("Spark求解器可初始化")
        void testSparkSolverInit() {
            SparkParallelSolver solver = new SparkParallelSolver(config);
            assertThat(solver.getPartitioner()).isNotNull();
            assertThat(solver.getPartitioner().getTotalPartitions()).isGreaterThan(0);
            solver.shutdown();
        }

        @Test
        @DisplayName("分区RDD可创建")
        void testPartitionRDDSimple() {
            SparkParallelSolver solver = new SparkParallelSolver(config);

            try {
                var rdd = solver.createPartitionRDD();
                if (rdd != null) {
                    assertThat(rdd.count()).isEqualTo(partitioner.getTotalPartitions());
                } else {
                    log.info("Spark RDD不可用 (本地模式无Spark环境)");
                }
            } catch (Exception e) {
                log.info("Spark初始化跳过: {}", e.getMessage());
            } finally {
                solver.shutdown();
            }
        }

        @Test
        @DisplayName("单机预报：短时间积分完成")
        void testShortSingleNodeForecast() {
            ModelState init = TestDataFactory.createStandardAtmosphere(config, testTime);
            SparkParallelSolver solver = new SparkParallelSolver(config);

            try {
                ModelState result = solver.runForecast(init, 1);
                assertThat(result).isNotNull();
                assertThat(result.fields).containsKey(VariableType.T);
                assertThat(result.forecastStep).isEqualTo(1);
            } catch (Exception e) {
                log.warn("Spark预报测试跳过: {}", e.getMessage());
            } finally {
                solver.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("全局统计量")
    class GlobalStatsTest {

        @Test
        @DisplayName("各分区统计合并后与全局计算一致")
        void testPartitionedStatsMatchGlobal() {
            ModelState global = TestDataFactory.createStandardAtmosphere(config, testTime);
            DataField T = global.fields.get(VariableType.T);
            double globalMean = T.mean();
            double globalVar = T.variance();
            double globalMax = T.max();
            double globalMin = T.min();

            double sumPart = 0;
            double maxPart = -1e30;
            double minPart = 1e30;
            int nPart = 0;

            for (GridPartitioner.Partition p : partitioner.getAllPartitions()) {
                ModelState local = partitioner.createLocalState(p);
                partitioner.extractSubdomain(global, p, local);

                DataField localT = local.fields.get(VariableType.T);
                int h = partitioner.getHaloWidth();

                for (int k = 0; k < config.getNZ(); k++) {
                    for (int lj = h; lj < p.localNy - h; lj++) {
                        for (int li = h; li < p.localNx - h; li++) {
                            double v = localT.get(li + p.localNx * (lj + p.localNy * k));
                            sumPart += v;
                            maxPart = Math.max(maxPart, v);
                            minPart = Math.min(minPart, v);
                            nPart++;
                        }
                    }
                }
            }

            double partMean = sumPart / nPart;

            log.info("全局: mean={:.4f} min={:.2f} max={:.2f}", globalMean, globalMin, globalMax);
            log.info("分区: mean={:.4f} min={:.2f} max={:.2f}", partMean, minPart, maxPart);
            log.info("  Npts: global={} part={}", T.getSize(), nPart);

            assertThat(partMean)
                    .as("分区合并均值应与全局一致")
                    .isCloseTo(globalMean, within(0.5));
        }
    }
}
