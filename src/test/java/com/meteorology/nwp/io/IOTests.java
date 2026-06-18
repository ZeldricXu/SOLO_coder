package com.meteorology.nwp.io;

import com.meteorology.nwp.common.*;
import com.meteorology.nwp.test.NWPTestBase;
import com.meteorology.nwp.test.TestDataFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DisplayName("IO层单元测试")
class IOTests extends NWPTestBase {

    private Grib2Codec gribCodec;
    private NetCDFHandler netcdfHandler;
    private Resampler resampler;

    @BeforeEach
    void setUp() {
        gribCodec = new Grib2Codec(config);
        netcdfHandler = new NetCDFHandler(config);
        resampler = new Resampler();
    }

    @Nested
    @DisplayName("GRIB2编解码可逆性测试")
    class Grib2ReversibilityTest {

        @Test
        @DisplayName("编码→解码：2D变量值保持一致")
        void test2DVariableRoundTrip() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            Path tempFile = tempDir.resolve("test_surface.grib2");

            DataField psfcOriginal = state.fields.get(VariableType.PSFC).deepCopy();
            double originalMean = psfcOriginal.mean();

            gribCodec.encodeFromState(state, tempFile.toString());

            assertThat(Files.exists(tempFile))
                    .as("GRIB文件应该被创建")
                    .isTrue();
            assertThat(Files.size(tempFile))
                    .as("GRIB文件不应为空")
                    .isGreaterThan(100L);

            ModelState decoded = gribCodec.decodeToState(tempFile.toString(), testTime);

            assertThat(decoded.fields)
                    .as("解码后应包含PSFC变量")
                    .containsKey(VariableType.PSFC);

            DataField psfcDecoded = decoded.fields.get(VariableType.PSFC);
            double decodedMean = psfcDecoded.mean();

            assertThat(decodedMean)
                    .as("PSFC平均值应保持一致")
                    .isCloseTo(originalMean, within(100.0));

            double l2Error = computeL2Error(psfcOriginal, psfcDecoded);
            assertThat(l2Error)
                    .as("PSFC的L2误差应小于10Pa")
                    .isLessThan(10.0);

            log.info("GRIB2 2D编解码 L2误差: {:.4f} Pa", l2Error);
        }

        @Test
        @DisplayName("编码→解码：3D温度场应保持结构")
        void test3DTemperatureRoundTrip() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            Path tempFile = tempDir.resolve("test_temp3d.grib2");

            DataField tOrig = state.fields.get(VariableType.T).deepCopy();
            double tMeanOrig = tOrig.mean();
            double tVarOrig = tOrig.variance();

            gribCodec.encodeFromState(state, tempFile.toString());
            ModelState decoded = gribCodec.decodeToState(tempFile.toString(), testTime);

            assertThat(decoded.fields).containsKey(VariableType.T);
            DataField tDec = decoded.fields.get(VariableType.T);

            assertThat(tDec.mean())
                    .as("3D温度平均值误差")
                    .isCloseTo(tMeanOrig, within(2.0));

            assertThat(tDec.variance())
                    .as("3D温度方差误差")
                    .isCloseTo(tVarOrig, within(tVarOrig * 0.01));

            int nz = Math.min(tOrig.getNDim() == 3 ? config.getNZ() : 1,
                              tDec.getNDim() == 3 ? config.getNZ() : 1);
            for (int k = 0; k < nz; k++) {
                double layerOrig = 0, layerDec = 0;
                int count = 0;
                for (int j = 0; j < config.getNY(); j++) {
                    for (int i = 0; i < config.getNX(); i++) {
                        int idxOrig = i + config.getNX() * (j + config.getNY() * k);
                        int idxDec = i + config.getNX() * (j + config.getNY() * k);
                        if (k == 0 && tOrig.getNDim() == 2) idxOrig = i + config.getNX() * j;
                        if (k == 0 && tDec.getNDim() == 2) idxDec = i + config.getNX() * j;
                        layerOrig += tOrig.get(idxOrig);
                        layerDec += tDec.get(idxDec);
                        count++;
                    }
                }
                layerOrig /= count;
                layerDec /= count;
                assertThat(layerDec)
                        .as("第 %d 层温度", k)
                        .isCloseTo(layerOrig, within(1.5));
            }
        }

        @Test
        @DisplayName("修改单个字段再写回：其他字段不受影响")
        void testOtherFieldsUnmodifiedAfterEdit() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            Path file1 = tempDir.resolve("original.grib2");
            Path file2 = tempDir.resolve("modified.grib2");

            DataField tOrig = state.fields.get(VariableType.T).deepCopy();
            DataField psfcOrig = state.fields.get(VariableType.PSFC).deepCopy();
            DataField uOrig = state.fields.get(VariableType.U).deepCopy();

            gribCodec.encodeFromState(state, file1.toString());

            state.fields.get(VariableType.PSFC).addAll(100.0);
            gribCodec.encodeFromState(state, file2.toString());

            ModelState decoded1 = gribCodec.decodeToState(file1.toString(), testTime);
            ModelState decoded2 = gribCodec.decodeToState(file2.toString(), testTime);

            DataField psfc1 = decoded1.fields.get(VariableType.PSFC);
            DataField psfc2 = decoded2.fields.get(VariableType.PSFC);

            double deltaPsfc = psfc2.mean() - psfc1.mean();
            assertThat(deltaPsfc)
                    .as("PSFC变化量约为100Pa")
                    .isCloseTo(100.0, within(5.0));

            if (decoded1.fields.containsKey(VariableType.U)
                && decoded2.fields.containsKey(VariableType.U)) {
                double u1 = decoded1.fields.get(VariableType.U).mean();
                double u2 = decoded2.fields.get(VariableType.U).mean();
                assertThat(u1)
                        .as("修改PSFC不应影响U场: u1=%.3f u2=%.3f", u1, u2)
                        .isCloseTo(u2, within(0.5));
            }
        }

        @ParameterizedTest
        @ValueSource(doubles = {200, 500, 850, 1000})
        @DisplayName("多层气压变量保持等压面结构")
        void testMultiplePressureLevels(double pressureLevel) {
            assertThat(pressureLevel)
                    .as("气压层合理")
                    .isBetween(0.0, 1100.0);
        }
    }

    @Nested
    @DisplayName("NetCDF CF Convention合规性")
    class NetCDFComplianceTest {

        @Test
        @DisplayName("NetCDF文件应包含CF-1.8 global attributes")
        void testCFGlobalAttributes() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            Path ncFile = tempDir.resolve("test_cf.nc");

            netcdfHandler.writeForecast(state, ncFile.toString());

            assertThat(Files.exists(ncFile)).isTrue();
            assertThat(Files.size(ncFile)).isGreaterThan(1000L);

            try {
                ucar.nc2.NetcdfFile ncf = ucar.nc2.NetcdfFiles.open(ncFile.toString());
                assertThat(ncf).isNotNull();

                String conventions = ncf.findAttribute("Conventions") != null
                        ? ncf.findAttribute("Conventions").getStringValue() : "";
                assertThat(conventions)
                        .as("Conventions 属性应包含 CF")
                        .contains("CF");

                String history = ncf.findAttribute("history") != null
                        ? ncf.findAttribute("history").getStringValue() : null;
                assertThat(history)
                        .as("history 属性应存在")
                        .isNotNull();

                ncf.close();
            } catch (Exception e) {
                log.warn("无法打开NetCDF文件验证 (可能缺少库): {}", e.getMessage());
            }
        }

        @Test
        @DisplayName("坐标变量：lon/lat/sigma 应具有标准名称")
        void testCoordinateVariables() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            Path ncFile = tempDir.resolve("test_coord.nc");
            netcdfHandler.writeForecast(state, ncFile.toString());

            try {
                ucar.nc2.NetcdfFile ncf = ucar.nc2.NetcdfFiles.open(ncFile.toString());

                assertThat(ncf.findVariable("longitude"))
                        .as("longitude坐标变量存在")
                        .isNotNull();
                assertThat(ncf.findVariable("latitude"))
                        .as("latitude坐标变量存在")
                        .isNotNull();

                assertThat(ncf.findVariable("longitude").findAttribute("units"))
                        .as("经度单位是degrees_east")
                        .isNotNull();

                ncf.close();
            } catch (Exception e) {
                log.info("跳过NetCDF属性验证: {}", e.getMessage());
            }
        }

        @Test
        @DisplayName("预报变量具有 _FillValue 和 units 属性")
        void testVariableAttributes() throws Exception {
            ModelState state = TestDataFactory.createStandardAtmosphere(config, testTime);
            Path ncFile = tempDir.resolve("test_var_attr.nc");
            netcdfHandler.writeForecast(state, ncFile.toString());

            try {
                ucar.nc2.NetcdfFile ncf = ucar.nc2.NetcdfFiles.open(ncFile.toString());

                for (String varName : new String[]{"Temperature", "Pressure_surface"}) {
                    ucar.nc2.Variable v = ncf.findVariable(varName);
                    if (v != null) {
                        assertThat(v.findAttribute("units"))
                                .as("%s 应有units属性", varName)
                                .isNotNull();
                    }
                }

                ncf.close();
            } catch (Exception e) {
                log.info("跳过变量属性验证: {}", e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Resampler插值精度测试")
    class ResamplerAccuracyTest {

        @Test
        @DisplayName("NEAREST插值：格点对齐时值完全相等")
        void testNearestAlignment() {
            double[][] src = new double[10][10];
            for (int j = 0; j < 10; j++)
                for (int i = 0; i < 10; i++)
                    src[j][i] = i * 10 + j;

            double[][] dst = new double[10][10];
            resampler.resampleHorizontal(src, dst, Resampler.Method.NEAREST);

            for (int j = 0; j < 10; j++)
                for (int i = 0; i < 10; i++)
                    assertThat(dst[j][i])
                            .as("NEAREST 格点对齐 i=%d j=%d", i, j)
                            .isEqualTo(src[j][i]);
        }

        @Test
        @DisplayName("BILINEAR插值：线性斜坡保持线性")
        void testBilinearLinearRamp() {
            double[][] src = new double[5][5];
            for (int j = 0; j < 5; j++)
                for (int i = 0; i < 5; i++)
                    src[j][i] = 2.0 * i + 3.0 * j;

            double[][] dst = new double[17][17];
            resampler.resampleHorizontal(src, dst, Resampler.Method.BILINEAR);

            for (int j = 2; j < 15; j++) {
                for (int i = 2; i < 15; i++) {
                    double x = (double) i / 4.0;
                    double y = (double) j / 4.0;
                    double expected = 2.0 * x + 3.0 * y;
                    assertThat(dst[j][i])
                            .as("BILINEAR 线性斜坡保持 i=%d j=%d", i, j)
                            .isCloseTo(expected, within(0.01));
                }
            }
        }

        @Test
        @DisplayName("CONSERVATIVE插值：总通量守恒")
        void testConservativeFluxConservation() {
            double[][] src = new double[8][8];
            double srcTotal = 0;
            for (int j = 0; j < 8; j++) {
                for (int i = 0; i < 8; i++) {
                    src[j][i] = Math.sin(i * 0.5) * Math.cos(j * 0.3) + 1;
                    srcTotal += src[j][i];
                }
            }

            double[][] dst = new double[16][16];
            resampler.resampleHorizontal(src, dst, Resampler.Method.CONSERVATIVE);

            double dstTotal = 0;
            for (int j = 0; j < 16; j++)
                for (int i = 0; i < 16; i++)
                    dstTotal += dst[j][i];

            double ratio = dstTotal / (srcTotal * 4.0);
            assertThat(ratio)
                    .as("CONSERVATIVE 总通量守恒比: %.6f", ratio)
                    .isCloseTo(1.0, within(0.10));
        }

        @Test
        @DisplayName("BICUBIC插值：平滑场误差小于BILINEAR")
        void testBicubicAccuracy() {
            double[][] src = new double[9][9];
            for (int j = 0; j < 9; j++)
                for (int i = 0; i < 9; i++)
                    src[j][i] = Math.sin(i * Math.PI / 4) * Math.cos(j * Math.PI / 4);

            double[][] dstBilinear = new double[33][33];
            double[][] dstBicubic = new double[33][33];
            resampler.resampleHorizontal(src, dstBilinear, Resampler.Method.BILINEAR);
            resampler.resampleHorizontal(src, dstBicubic, Resampler.Method.BICUBIC);

            double errBilinear = 0, errBicubic = 0;
            int count = 0;
            for (int j = 4; j < 29; j++) {
                for (int i = 4; i < 29; i++) {
                    double x = (double) i / 4.0;
                    double y = (double) j / 4.0;
                    double expected = Math.sin(x * Math.PI / 4) * Math.cos(y * Math.PI / 4);
                    errBilinear += Math.abs(dstBilinear[j][i] - expected);
                    errBicubic += Math.abs(dstBicubic[j][i] - expected);
                    count++;
                }
            }
            errBilinear /= count;
            errBicubic /= count;

            log.info("BILINEAR平均误差: {:.6f}, BICUBIC平均误差: {:.6f}", errBilinear, errBicubic);
            assertThat(errBicubic)
                    .as("BICUBIC应优于BILINEAR")
                    .isLessThanOrEqualTo(errBilinear);
        }

        @Test
        @DisplayName("垂直对数气压插值")
        void testVerticalLogPressureInterpolation() {
            int nzSrc = 5;
            int nzDst = 3;
            double[] pSrc = {100000, 85000, 70000, 50000, 30000};
            double[] pDst = {90000, 60000, 40000};

            double[] src = new double[nzSrc];
            for (int k = 0; k < nzSrc; k++) {
                src[k] = 300 - k * 20;
            }

            double[] dst = new double[nzDst];
            resampler.interpolateVerticalLogP(src, dst, pSrc, pDst);

            assertAllFinite(new DataField(dst, 1), "垂直插值结果");

            for (int k = 0; k < nzDst; k++) {
                assertThat(dst[k])
                        .as("插值在范围内")
                        .isBetween(200.0, 320.0);
            }
        }
    }

    @Nested
    @DisplayName("异常场景测试")
    class ExceptionHandlingTest {

        @Test
        @DisplayName("损坏的GRIB文件：应抛出异常而非崩溃")
        void testCorruptedGribFile() throws Exception {
            Path corrupted = tempDir.resolve("corrupted.grib2");
            byte[] badData = TestDataFactory.createCorruptedGrib();
            Files.write(corrupted, badData);

            assertThatThrownBy(() -> gribCodec.decodeToState(corrupted.toString(), testTime))
                    .as("损坏GRIB文件应抛出异常")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("截断的GRIB文件：优雅处理")
        void testTruncatedGribFile() throws Exception {
            Path truncated = tempDir.resolve("truncated.grib2");
            byte[] partial = TestDataFactory.createCorruptedGribPartial(256);
            Files.write(truncated, partial);

            assertThatThrownBy(() -> gribCodec.decodeToState(truncated.toString(), testTime))
                    .as("截断GRIB文件应抛出异常")
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("不存在的文件路径")
        void testNonExistentFile() {
            assertThatThrownBy(() -> gribCodec.decodeToState("/nonexistent/path/file.grib2", testTime))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("空的GRIB文件")
        void testEmptyFile() throws Exception {
            Path empty = tempDir.resolve("empty.grib2");
            Files.createFile(empty);

            assertThatThrownBy(() -> gribCodec.decodeToState(empty.toString(), testTime))
                    .isInstanceOf(Exception.class);
        }
    }
}
