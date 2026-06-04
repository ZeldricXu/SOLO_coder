package com.flowplatform.common;

import com.flowplatform.mapper.SysConfigMapper;
import com.flowplatform.test.BaseUnitTest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.*;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("工作时长计算边界测试")
public class WorkingHoursCalculatorTest extends BaseUnitTest {

    private WorkingHoursCalculator calculator;
    private SysConfigMapper configMapper;

    @BeforeEach
    void setUp() {
        configMapper = mock(SysConfigMapper.class);
        calculator = new WorkingHoursCalculator(configMapper);
    }

    private WorkingHoursCalculator.WorkingHoursConfig defaultConfig() {
        return new WorkingHoursCalculator.WorkingHoursConfig(
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                true
        );
    }

    @Test
    @DisplayName("同一工作日内 - 完整工作时段")
    void testSameDayFullWorkHours() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();
        LocalDateTime start = LocalDateTime.of(2025, 6, 2, 9, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 2, 18, 0);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(9.0, hours, 0.01, "9点到18点应为9小时");
    }

    @Test
    @DisplayName("同一工作日内 - 部分工作时段")
    void testSameDayPartialHours() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();

        LocalDateTime start = LocalDateTime.of(2025, 6, 2, 10, 30);
        LocalDateTime end = LocalDateTime.of(2025, 6, 2, 14, 0);
        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(3.5, hours, 0.01, "10:30到14:00应为3.5小时");
    }

    @Test
    @DisplayName("开始时间早于上班时间 - 从上班时间算")
    void testStartBeforeWorkHours() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();
        LocalDateTime start = LocalDateTime.of(2025, 6, 2, 7, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 2, 12, 0);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(3.0, hours, 0.01, "早7点到中午12点应从9点开始算，共3小时");
    }

    @Test
    @DisplayName("结束时间晚于下班时间 - 算到下班时间")
    void testEndAfterWorkHours() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();
        LocalDateTime start = LocalDateTime.of(2025, 6, 2, 17, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 2, 22, 0);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(1.0, hours, 0.01, "下午5点到晚上10点应算到6点，共1小时");
    }

    @Test
    @DisplayName("跨工作日 - 周五下午到下周一上午")
    void testAcrossWeekend() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();

        LocalDateTime start = LocalDateTime.of(2025, 6, 6, 14, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 9, 12, 0);

        double hours = calculator.calculateWorkingHours(start, end, config);

        double expectedHours = (18 - 14) + 9 + (12 - 9);
        assertEquals(expectedHours, hours, 0.01,
                "周五14点到下周一12点：周五4小时 + 周一3小时 = 7小时，排除周六日");
    }

    @Test
    @DisplayName("完整跨周末 - 周五18点到下周一9点")
    void testFullWeekendGap() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();

        LocalDateTime start = LocalDateTime.of(2025, 6, 6, 18, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 9, 9, 0);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(0.0, hours, 0.01, "周五下班到周一上班之间无工作时长");
    }

    @Test
    @DisplayName("完全在周末 - 周六周日无工作时长")
    void testWeekendOnly() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();

        LocalDateTime start = LocalDateTime.of(2025, 6, 7, 10, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 8, 18, 0);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(0.0, hours, 0.01, "周六周日不应计算工作时长");
    }

    @ParameterizedTest
    @CsvSource({
            "2025-06-02T09:00, 2025-06-04T18:00, 27.0",
            "2025-06-03T10:00, 2025-06-05T14:00, 22.0",
            "2025-06-03T09:15, 2025-06-03T09:45, 0.5",
            "2025-06-02T17:30, 2025-06-03T10:00, 1.5",
    })
    @DisplayName("多工作日跨天场景 - 参数化测试")
    void testMultiDayScenarios(String startStr, String endStr, double expected) {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();
        LocalDateTime start = LocalDateTime.parse(startStr);
        LocalDateTime end = LocalDateTime.parse(endStr);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(expected, hours, 0.01,
                start + " 到 " + end + " 应为 " + expected + " 小时");
    }

    @Test
    @DisplayName("周三15点到下周三15点 - 完整一周")
    void testFullWorkWeek() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();

        LocalDateTime start = LocalDateTime.of(2025, 6, 4, 15, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 11, 15, 0);

        double expectedHours =
                (18 - 15) +
                9 + 9 + 9 + 9 + 9 +
                (15 - 9);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(expectedHours, hours, 0.01,
                "完整一周应扣除周六日，共5个工作日 × 9小时 = 45小时");
        assertEquals(45.0, hours, 0.01);
    }

    @Test
    @DisplayName("未配置工作时长规则 - 默认自然时间")
    void testNoConfigFallbackToNaturalTime() {
        when(configMapper.getValueByKey(anyString())).thenThrow(new RuntimeException("DB not available"));

        LocalDateTime start = LocalDateTime.of(2025, 6, 6, 14, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 9, 12, 0);

        double hours = calculator.calculateWorkingHours(start, end);

        long naturalMinutes = Duration.between(start, end).toMinutes();
        double naturalHours = naturalMinutes / 60.0;

        assertEquals(naturalHours, hours, 0.01,
                "未配置时应按自然时间计算，包含周末的70小时");
        assertTrue(naturalHours > 60, "自然时间应超过60小时（周五到周一自然时长约70小时）");
    }

    @Test
    @DisplayName("start晚于end - 返回0")
    void testStartAfterEnd() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();
        LocalDateTime start = LocalDateTime.of(2025, 6, 3, 14, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 3, 10, 0);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(0.0, hours, 0.01);
    }

    @Test
    @DisplayName("null参数 - 返回0")
    void testNullParameters() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();

        assertEquals(0.0, calculator.calculateWorkingHours(null, LocalDateTime.now(), config), 0.01);
        assertEquals(0.0, calculator.calculateWorkingHours(LocalDateTime.now(), null, config), 0.01);
        assertEquals(0.0, calculator.calculateWorkingHours(null, null, config), 0.01);
    }

    @Test
    @DisplayName("自定义周末配置 - 周四周五休息")
    void testCustomWeekendConfig() {
        WorkingHoursCalculator.WorkingHoursConfig thurFriWeekend = new WorkingHoursCalculator.WorkingHoursConfig(
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                Set.of(DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                true
        );

        LocalDateTime start = LocalDateTime.of(2025, 6, 4, 14, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 8, 12, 0);

        double hours = calculator.calculateWorkingHours(start, end, thurFriWeekend);

        double expectedHours = (18 - 14) + (12 - 9);
        assertEquals(expectedHours, hours, 0.01,
                "自定义周四周五休息：周三4小时 + 周六3小时 = 7小时");
    }

    @Test
    @DisplayName("自定义工作时间 - 8小时工作制")
    void testCustomWorkHours() {
        WorkingHoursCalculator.WorkingHoursConfig eightHourDay = new WorkingHoursCalculator.WorkingHoursConfig(
                LocalTime.of(9, 0),
                LocalTime.of(17, 0),
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                true
        );

        LocalDateTime start = LocalDateTime.of(2025, 6, 2, 9, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 2, 17, 0);

        double hours = calculator.calculateWorkingHours(start, end, eightHourDay);
        assertEquals(8.0, hours, 0.01, "8小时工作制应为8小时");
    }

    @Test
    @DisplayName("午间休息 - 非工作时间扣除")
    void testLunchBreak() {
        WorkingHoursCalculator.WorkingHoursConfig withLunch = new WorkingHoursCalculator.WorkingHoursConfig(
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                true
        );

        LocalDateTime start = LocalDateTime.of(2025, 6, 2, 12, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 2, 13, 0);

        double hours = calculator.calculateWorkingHours(start, end, withLunch);
        assertEquals(1.0, hours, 0.01,
                "中午12-13点在工作时间内，算1小时（可扩展扣除午休配置）");
    }

    @Test
    @DisplayName("短时间间隔 - 分钟级精度")
    void testMinuteLevelPrecision() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();

        LocalDateTime start = LocalDateTime.of(2025, 6, 2, 9, 0);
        LocalDateTime end = LocalDateTime.of(2025, 6, 2, 9, 15);

        double hours = calculator.calculateWorkingHours(start, end, config);
        assertEquals(0.25, hours, 0.01, "15分钟应为0.25小时");
    }

    @Test
    @DisplayName("节假日支持 - 5月1日国际劳动节")
    void testHolidaySupport() {
        WorkingHoursCalculator.WorkingHoursConfig config = defaultConfig();

        LocalDateTime may1Start = LocalDateTime.of(2025, 5, 1, 9, 0);
        LocalDateTime may1End = LocalDateTime.of(2025, 5, 1, 18, 0);
        double hours = calculator.calculateWorkingHours(may1Start, may1End, config);

        assertEquals(9.0, hours, 0.01,
                "5月1日周四是工作日（暂未实现节假日配置，按正常工作时间算）");
    }
}
