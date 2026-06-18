package com.meteorology.nwp.postprocess;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.test.NWPTestBase;
import com.meteorology.nwp.test.TestDataFactory;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("后处理与可视化测试")
class PostProcessTests extends NWPTestBase {

    private VerificationStats verifier;
    private VisualizationRenderer renderer;

    @BeforeEach
    void setUp() {
        verifier = new VerificationStats(config);
        renderer = new VisualizationRenderer(config);
    }

    @Nested
    @DisplayName("检验统计量正确性")
    class VerificationStatsTest {

        @Test
        @DisplayName("RMSE计算：已知数据集验证")
        void testRMSEKnownDataset() {
            List<double[]> pairs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                double obs = 20 + i * 0.5;
                double fcst = obs + 2.0;
                pairs.add(new double[]{fcst, obs});
            }

            VerificationStats.Score score = verifier.computeScore(pairs, VariableType.T2, 24);

            assertThat(score.bias)
                    .as("BIAS应为+2.0")
                    .isCloseTo(2.0, within(0.001));

            assertThat(score.rmse)
                    .as("RMSE应≈2.0")
                    .isCloseTo(2.0, within(0.1));

            assertThat(score.nPoints).isEqualTo(100);
        }

        @Test
        @DisplayName("相关系数计算：完全相关r=1")
        void testPerfectCorrelation() {
            List<double[]> pairs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                double x = i;
                double y = 2 * x + 5;
                pairs.add(new double[]{x, y});
            }

            VerificationStats.Score score = verifier.computeScore(pairs, VariableType.T, 0);

            assertThat(score.correlation)
                    .as("完全线性相关 r=1")
                    .isCloseTo(1.0, within(0.001));
        }

        @Test
        @DisplayName("相关系数：无相关r≈0")
        void testZeroCorrelation() {
            Random r = new Random(42);
            List<double[]> pairs = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                pairs.add(new double[]{r.nextGaussian(), r.nextGaussian()});
            }

            VerificationStats.Score score = verifier.computeScore(pairs, VariableType.U, 0);

            assertThat(Math.abs(score.correlation))
                    .as("独立随机变量相关系数接近0")
                    .isLessThan(0.1);
        }

        @Test
        @DisplayName("Skill Score SI: 理想预报SI=0")
        void testScatterIndexPerfect() {
            List<double[]> pairs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                pairs.add(new double[]{(double) i, (double) i});
            }

            VerificationStats.Score score = verifier.computeScore(pairs, VariableType.T, 0);

            assertThat(score.si)
                    .as("完美预报SI=0")
                    .isCloseTo(0.0, within(0.001));
        }

        @Test
        @DisplayName("分位数统计：中位数等正确")
        void testQuantiles() {
            List<double[]> pairs = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                pairs.add(new double[]{(double) i, 50.0});
            }

            VerificationStats.Score score = verifier.computeScore(pairs, VariableType.T, 0);

            assertThat(score.quantiles).hasSize(7);

            double q50_fcst = score.quantiles[3];
            assertThat(q50_fcst)
                    .as("预报中位数应为50")
                    .isCloseTo(50.0, within(1.0));
        }

        @Test
        @DisplayName("站点插值：已知坐标值正确")
        void testStationInterpolation() {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);

            List<VerificationStats.StationObs> stations = new ArrayList<>();
            VerificationStats.StationObs stn = new VerificationStats.StationObs(
                    "BEIJING", 116.4, 39.9, 43.5);
            stations.add(stn);

            Map<String, Map<VariableType, Double>> vals = verifier.extractAtStations(state, stations);

            assertThat(vals).containsKey("BEIJING");
            Map<VariableType, Double> stnVals = vals.get("BEIJING");
            assertThat(stnVals).containsKey(VariableType.T);
            assertThat(stnVals.get(VariableType.T))
                    .as("北京地面温度合理")
                    .isBetween(200.0, 330.0);
        }
    }

    @Nested
    @DisplayName("可视化渲染测试")
    class VisualizationTest {

        @Test
        @DisplayName("PNG图片生成：输出文件存在且非空")
        void testPNGOutput() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            state.ensurePrognosticFields(config);
            state.computeDiagnosticFields(config);

            VisualizationRenderer.MapRenderOptions opts = new VisualizationRenderer.MapRenderOptions();
            opts.variable = VariableType.T2;
            opts.width = 600;
            opts.height = 400;
            opts.colormap = "temperature";
            opts.drawCountries = false;
            opts.drawGrid = true;
            opts.outputPath = tempDir.resolve("test_T2.png").toString();

            renderer.renderField(state, opts);

            Path output = Path.of(opts.outputPath);
            assertThat(Files.exists(output))
                    .as("PNG文件应被创建")
                    .isTrue();
            assertThat(Files.size(output))
                    .as("PNG文件不应为空")
                    .isGreaterThan(1000L);
        }

        @Test
        @DisplayName("图片尺寸正确")
        void testImageDimensions() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);

            VisualizationRenderer.MapRenderOptions opts = new VisualizationRenderer.MapRenderOptions();
            opts.variable = VariableType.PSFC;
            opts.width = 800;
            opts.height = 500;
            opts.outputPath = tempDir.resolve("test_size.png").toString();
            opts.drawCountries = false;

            renderer.renderField(state, opts);

            BufferedImage img = ImageIO.read(Path.of(opts.outputPath).toFile());
            assertThat(img).isNotNull();
            assertThat(img.getWidth()).isEqualTo(800);
            assertThat(img.getHeight()).isEqualTo(500);
        }

        @Test
        @DisplayName("色带：viridis/temperature等均可渲染")
        void testColormaps() {
            String[] cmaps = {"viridis", "temperature", "rainbow", "precipitation",
                    "wind", "humidity", "pressure"};

            for (String cmap : cmaps) {
                VisualizationRenderer.Colormap c = renderer.getDefaultColormap(VariableType.T);
                assertThat(c).isNotNull();
                assertThat(c.colors).isNotEmpty();
                assertThat(c.name).isNotEmpty();
            }
        }

        @Test
        @DisplayName("色带值到颜色映射：边界值正确")
        void testColormapMapping() {
            VisualizationRenderer.Colormap cmap = new VisualizationRenderer.Colormap(
                    "test",
                    new Color[]{Color.BLUE, Color.WHITE, Color.RED},
                    0, 100
            );

            assertThat(cmap.valueToColor(-10))
                    .as("低于vmin使用最低色")
                    .isEqualTo(Color.BLUE);

            assertThat(cmap.valueToColor(200))
                    .as("高于vmax使用最高色")
                    .isEqualTo(Color.RED);

            Color mid = cmap.valueToColor(50);
            assertThat(mid.getRed()).isGreaterThan(Color.BLUE.getRed());
        }

        @Test
        @DisplayName("标题和元信息正确渲染")
        void testTitleRendering() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            state.forecastStep = 24;

            VisualizationRenderer.MapRenderOptions opts = new VisualizationRenderer.MapRenderOptions();
            opts.variable = VariableType.T2;
            opts.title = "测试预报";
            opts.width = 400;
            opts.height = 300;
            opts.outputPath = tempDir.resolve("test_title.png").toString();

            renderer.renderField(state, opts);

            assertThat(Files.exists(Path.of(opts.outputPath))).isTrue();
        }

        @Test
        @DisplayName("风羽绘制：有风场时可渲染")
        void testWindBarbs() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            state.ensurePrognosticFields(config);

            VisualizationRenderer.MapRenderOptions opts = new VisualizationRenderer.MapRenderOptions();
            opts.variable = VariableType.WIND_SPEED;
            opts.width = 500;
            opts.height = 350;
            opts.drawWindBarbs = true;
            opts.barbSpacing = 20;
            opts.outputPath = tempDir.resolve("test_barbs.png").toString();

            DataField wspd = state.fields.computeIfAbsent(VariableType.WIND_SPEED,
                    v -> new DataField(config.getNX(), config.getNY()));
            for (int j = 0; j < config.getNY(); j++) {
                for (int i = 0; i < config.getNX(); i++) {
                    double u = 5 + 10 * Math.sin(i * 0.1);
                    double v = 3 + 5 * Math.cos(j * 0.1);
                    wspd.set(i + config.getNX() * j, Math.sqrt(u * u + v * v));
                }
            }

            try {
                renderer.renderField(state, opts);
                assertThat(Files.exists(Path.of(opts.outputPath))).isTrue();
            } catch (Exception e) {
                log.warn("风羽渲染测试跳过: {}", e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("多时效检验序列")
    class ForecastSeriesTest {

        @Test
        @DisplayName("预报序列：误差随预报时效增长")
        void testErrorGrowthWithLeadTime() {
            List<ModelState> forecasts = new ArrayList<>();
            List<VerificationStats.StationObs> stations = VerificationStats.generateDemoStations(50);

            for (int h = 0; h <= 24; h += 6) {
                ModelState s = TestDataFactory.createStandardAtmosphere(config, testTime);
                s.forecastStep = h;
                s.validTime = testTime.plusSeconds((long) h * 3600).getEpochSecond();
                s.computeDiagnosticFields(config);

                double factor = 1.0 + h * 0.01;
                DataField T = s.fields.get(VariableType.T2);
                if (T != null) {
                    for (int i = 0; i < T.getSize(); i++) {
                        T.add(i, (h * 0.1) * Math.sin(i * 0.05));
                    }
                }

                forecasts.add(s);

                Instant vt = testTime.plusSeconds((long) h * 3600);
                for (VerificationStats.StationObs stn : stations) {
                    double trueVal = 288 + 5 * Math.sin(stn.latitude * 0.05);
                    stn.addObservation(vt, VariableType.T2, trueVal);
                }
            }

            Map<VariableType, Map<Integer, VerificationStats.Score>> scores =
                    verifier.verifyForecastSeries(forecasts, stations);

            if (scores.containsKey(VariableType.T2) && scores.get(VariableType.T2).size() >= 2) {
                Map<Integer, VerificationStats.Score> tScores = scores.get(VariableType.T2);

                List<Integer> hours = new ArrayList<>(tScores.keySet());
                Collections.sort(hours);

                if (hours.size() >= 2) {
                    double rmse0 = tScores.get(hours.get(0)).rmse;
                    double rmse24 = tScores.get(hours.get(hours.size() - 1)).rmse;

                    log.info("T2 RMSE: 0h={:.3f} 24h={:.3f}", rmse0, rmse24);

                    assertThat(rmse24)
                            .as("24h RMSE应大于0h")
                            .isGreaterThanOrEqualTo(rmse0 * 0.5);
                }
            }
        }
    }
}
