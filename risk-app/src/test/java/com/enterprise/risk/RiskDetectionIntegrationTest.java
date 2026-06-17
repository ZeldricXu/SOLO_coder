package com.enterprise.risk;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.common.alert.AlertSeverity;
import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.common.rule.RuleDefinition;
import com.enterprise.risk.common.rule.RuleEvaluationResult;
import com.enterprise.risk.common.rule.RuleType;
import com.enterprise.risk.engine.config.RiskEngineConfig;
import com.enterprise.risk.engine.engine.RuleEngine;
import com.enterprise.risk.engine.parser.RuleExpressionCompiler;
import com.enterprise.risk.engine.parser.RuleExpressionEvaluator;
import com.enterprise.risk.common.utils.EventUtils;
import com.enterprise.risk.common.utils.FingerprintGenerator;
import com.enterprise.risk.common.utils.TimeWindowUtils;
import com.enterprise.risk.gateway.validator.EventValidator;
import com.enterprise.risk.alert.AlertFingerprintGenerator;
import com.enterprise.risk.alert.AlertAggregator;
import com.enterprise.risk.alert.AlertPipeline;
import com.enterprise.risk.model.ScoreFusionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RiskDetectionIntegrationTest {

    private RuleExpressionCompiler compiler;
    private RuleExpressionEvaluator evaluator;
    private EventValidator eventValidator;
    private AlertFingerprintGenerator fingerprintGenerator;
    private AlertAggregator alertAggregator;
    private ScoreFusionService scoreFusionService;

    @BeforeEach
    void setUp() {
        RiskEngineConfig config = new RiskEngineConfig();
        compiler = config.ruleExpressionCompiler();
        evaluator = config.ruleExpressionEvaluator();
        eventValidator = new EventValidator();
        fingerprintGenerator = new AlertFingerprintGenerator();
        alertAggregator = new AlertAggregator();
        scoreFusionService = new ScoreFusionService();
    }

    @Nested
    @DisplayName("事件接入层测试")
    class EventGatewayTests {

        @Test
        @DisplayName("事件必填字段校验")
        void testEventValidation_requiredFields() {
            RiskEvent validEvent = createValidPaymentEvent();
            var result = eventValidator.validate(validEvent);
            assertTrue(result.isValid(), "合法事件应通过校验");
            assertTrue(result.getErrors().isEmpty(), "不应有校验错误");

            RiskEvent invalidEvent = RiskEvent.builder()
                    .eventType("payment.create")
                    .build();
            var invalidResult = eventValidator.validate(invalidEvent);
            assertFalse(invalidResult.isValid(), "缺少必填字段应校验失败");
            assertFalse(invalidResult.getErrors().isEmpty(), "应有校验错误");
        }

        @Test
        @DisplayName("业务线与事件类型合法性校验")
        void testEventValidation_businessLineAndType() {
            RiskEvent event1 = createValidPaymentEvent();
            event1.setBusinessLine("unknown_line");
            var r1 = eventValidator.validate(event1);
            assertFalse(r1.isValid(), "非法业务线应失败");

            RiskEvent event2 = createValidPaymentEvent();
            event2.setEventType("unknown.event");
            var r2 = eventValidator.validate(event2);
            assertFalse(r2.isValid(), "非法事件类型应失败");
        }

        @Test
        @DisplayName("IP格式校验")
        void testEventValidation_ipFormat() {
            RiskEvent event = createValidPaymentEvent();
            event.setIp("invalid-ip");
            var result = eventValidator.validate(event);
            assertFalse(result.isValid(), "非法IP格式应失败");

            event.setIp("192.168.1.1");
            result = eventValidator.validate(event);
            assertTrue(result.isValid(), "合法IP应通过");
        }

        @Test
        @DisplayName("事件时间范围校验")
        void testEventValidation_timestampRange() {
            RiskEvent event = createValidPaymentEvent();
            event.setTimestamp(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2));
            var result = eventValidator.validate(event);
            assertFalse(result.isValid(), "超过时效的事件应失败");
        }
    }

    @Nested
    @DisplayName("规则引擎DSL表达式测试")
    class RuleEngineDslTests {

        @Test
        @DisplayName("基础比较运算：大于、小于、等于")
        void testExpression_basicComparison() {
            RiskEvent event = createValidPaymentEvent();
            event.setAttribute("amount", 80000.0);
            event.setAttribute("country", "BR");

            boolean r1 = evaluateExpression(event, "event.amount > 50000");
            assertTrue(r1, "80000 > 50000 应为true");

            boolean r2 = evaluateExpression(event, "event.amount < 30000");
            assertFalse(r2, "80000 < 30000 应为false");

            boolean r3 = evaluateExpression(event, "event.country == 'BR'");
            assertTrue(r3, "国家等于巴西应为true");
        }

        @Test
        @DisplayName("逻辑运算：AND && OR || NOT !")
        void testExpression_logicalOperators() {
            RiskEvent event = createValidPaymentEvent();
            event.setAttribute("amount", 80000.0);
            event.setAttribute("country", "BR");
            event.setAttribute("deviceRiskLevel", 5);

            boolean andResult = evaluateExpression(event,
                    "event.amount > 50000 && event.country NOT IN ['CN','US','JP']");
            assertTrue(andResult, "AND组合应匹配");

            boolean orResult = evaluateExpression(event,
                    "event.amount < 1000 || event.deviceRiskLevel >= 4");
            assertTrue(orResult, "OR组合应匹配");

            boolean notResult = evaluateExpression(event, "!(event.amount < 1000)");
            assertTrue(notResult, "NOT运算应正确");
        }

        @Test
        @DisplayName("IN列表运算")
        void testExpression_inOperator() {
            RiskEvent event = createValidPaymentEvent();
            event.setAttribute("country", "CN");

            boolean r1 = evaluateExpression(event, "event.country IN ['CN','US','JP']");
            assertTrue(r1, "CN在白名单中");

            boolean r2 = evaluateExpression(event, "event.country NOT IN ['BR','RU']");
            assertTrue(r2, "CN不在黑名单中");

            boolean r3 = evaluateExpression(event, "event.country IN ['BR','RU']");
            assertFalse(r3, "CN不在列表中");
        }

        @Test
        @DisplayName("BETWEEN范围运算")
        void testExpression_betweenOperator() {
            RiskEvent event = createValidPaymentEvent();
            event.setAttribute("amount", 25000.0);

            boolean r1 = evaluateExpression(event, "event.amount BETWEEN 10000 AND 50000");
            assertTrue(r1, "25000在范围内");

            boolean r2 = evaluateExpression(event, "event.amount BETWEEN 50000 AND 100000");
            assertFalse(r2, "25000不在范围内");
        }

        @Test
        @DisplayName("LIKE模糊匹配")
        void testExpression_likeOperator() {
            RiskEvent event = createValidPaymentEvent();
            event.setAttribute("orderId", "ORD-TXN-20240101-ABC");

            boolean r1 = evaluateExpression(event, "event.orderId LIKE 'ORD-TXN%'");
            assertTrue(r1, "前缀匹配");

            boolean r2 = evaluateExpression(event, "event.orderId LIKE '%-ABC'");
            assertTrue(r2, "后缀匹配");

            boolean r3 = evaluateExpression(event, "event.orderId LIKE '%20240101%'");
            assertTrue(r3, "包含匹配");
        }

        @Test
        @DisplayName("NULL判断")
        void testExpression_nullCheck() {
            RiskEvent event = createValidPaymentEvent();

            boolean r1 = evaluateExpression(event, "event.nonexistent IS NULL");
            assertTrue(r1, "不存在字段应为NULL");

            boolean r2 = evaluateExpression(event, "event.amount IS NOT NULL");
            assertFalse(r2, "未设置amount应为NULL");

            event.setAttribute("amount", 1000.0);
            boolean r3 = evaluateExpression(event, "event.amount IS NOT NULL");
            assertTrue(r3, "已设置字段不应为NULL");
        }

        @Test
        @DisplayName("内置函数调用")
        void testExpression_builtinFunctions() {
            RiskEvent event = createValidPaymentEvent();
            event.setAttribute("amount", -5000.0);
            event.setAttribute("userName", "John_Doe");
            event.setAttribute("email", "john@example.com");

            boolean r1 = evaluateExpression(event, "abs(event.amount) > 1000");
            assertTrue(r1, "abs(-5000)=5000 > 1000");

            boolean r2 = evaluateExpression(event, "startsWith(event.userName, 'John')");
            assertTrue(r2, "startsWith匹配");

            boolean r3 = evaluateExpression(event, "contains(event.email, '@')");
            assertTrue(r3, "contains匹配");

            boolean r4 = evaluateExpression(event, "length(event.userName) >= 5");
            assertTrue(r4, "length计算正确");

            boolean r5 = evaluateExpression(event, "toLower(event.userName) == 'john_doe'");
            assertTrue(r5, "toLower转换正确");
        }

        @Test
        @DisplayName("复杂嵌套表达式与短路")
        void testExpression_complexNested() {
            RiskEvent event = createValidPaymentEvent();
            event.setAttribute("amount", 80000.0);
            event.setAttribute("country", "BR");
            event.setAttribute("deviceRiskLevel", 5);
            event.setAttribute("isFirstTransaction", true);

            String dsl = "(event.amount > 50000 && event.country NOT IN ['CN','US']) " +
                    "|| (event.deviceRiskLevel >= 4 && event.isFirstTransaction == true)";
            boolean result = evaluateExpression(event, dsl);
            assertTrue(result, "复杂嵌套表达式应匹配");
        }

        private boolean evaluateExpression(RiskEvent event, String dsl) {
            var compiled = compiler.compile(dsl);
            return (Boolean) evaluator.evaluate(compiled, event, new HashMap<>());
        }
    }

    @Nested
    @DisplayName("规则引擎核心流程测试")
    class RuleEngineTests {

        @Test
        @DisplayName("规则定义的表达式规则执行")
        void testRuleEngine_expressionRule() {
            RuleDefinition rule = createExpressionRule();
            RiskEvent matchingEvent = createValidPaymentEvent();
            matchingEvent.setAttribute("amount", 80000.0);
            matchingEvent.setAttribute("country", "BR");

            var compiledExpr = compiler.compile(rule.getDslExpression());
            boolean matched = (Boolean) evaluator.evaluate(compiledExpr, matchingEvent, new HashMap<>());
            assertTrue(matched, "匹配事件应命中规则");

            RiskEvent nonMatchingEvent = createValidPaymentEvent();
            nonMatchingEvent.setAttribute("amount", 1000.0);
            nonMatchingEvent.setAttribute("country", "CN");
            boolean notMatched = (Boolean) evaluator.evaluate(compiledExpr, nonMatchingEvent, new HashMap<>());
            assertFalse(notMatched, "非匹配事件不应命中");
        }

        @Test
        @DisplayName("规则优先级排序")
        void testRuleDefinition_priorityOrdering() {
            List<RuleDefinition> rules = Arrays.asList(
                    createRuleWithPriority("R-LOW", 100),
                    createRuleWithPriority("R-HIGH", 10),
                    createRuleWithPriority("R-MID", 50)
            );

            rules.sort(Comparator.comparingInt(RuleDefinition::getPriority));

            assertEquals("R-HIGH", rules.get(0).getRuleId(), "优先级数字越小越靠前");
            assertEquals("R-MID", rules.get(1).getRuleId());
            assertEquals("R-LOW", rules.get(2).getRuleId());
        }

        @Test
        @DisplayName("规则业务线过滤")
        void testRuleDefinition_businessLineFilter() {
            RuleDefinition payRule = createExpressionRule();
            payRule.setBusinessLine("payment");
            payRule.setEventTypes(Arrays.asList("payment.create", "payment.confirm"));

            RiskEvent payEvent = createValidPaymentEvent();
            assertTrue(matchesBusinessLine(payRule, payEvent), "支付业务匹配");
            assertTrue(matchesEventType(payRule, payEvent), "payment.create在事件列表中");

            RiskEvent loginEvent = createValidLoginEvent();
            assertFalse(matchesBusinessLine(payRule, loginEvent), "登录业务不匹配支付规则");
        }

        @Test
        @DisplayName("RuleEvaluationResult构建")
        void testRuleEvaluationResult_builder() {
            RuleEvaluationResult result = RuleEvaluationResult.matched("R001", "测试规则");
            result.setRuleScore(0.9);
            result.setModelScore(0.7);
            result.getMatchedReasons().add("金额超过阈值");

            assertTrue(result.isMatched());
            assertEquals("R001", result.getRuleId());
            assertEquals(0.9, result.getRuleScore(), 0.001);
            assertFalse(result.getMatchedReasons().isEmpty());
        }

        private boolean matchesBusinessLine(RuleDefinition rule, RiskEvent event) {
            return rule.getBusinessLine().equals(event.getBusinessLine());
        }

        private boolean matchesEventType(RuleDefinition rule, RiskEvent event) {
            return rule.getEventTypes() != null && rule.getEventTypes().contains(event.getEventType());
        }

        private RuleDefinition createRuleWithPriority(String id, int priority) {
            return RuleDefinition.builder()
                    .ruleId(id)
                    .ruleName("规则-" + id)
                    .priority(priority)
                    .build();
        }
    }

    @Nested
    @DisplayName("模型分数融合测试")
    class ModelFusionTests {

        @Test
        @DisplayName("规则分与模型分加权融合")
        void testScoreFusion_weightedAverage() {
            double ruleScore = 0.9;
            double modelScore = 0.7;
            double ruleWeight = 0.6;
            double modelWeight = 0.4;

            RuleDefinition rule = createExpressionRule();
            rule.setModelWeight(modelWeight);

            RuleEvaluationResult result = RuleEvaluationResult.matched("R001", "测试");
            result.setRuleScore(ruleScore);
            result.setModelScore(modelScore);

            double fused = scoreFusionService.fuse(result, rule, modelWeight);
            double expected = ruleWeight * ruleScore + modelWeight * modelScore;
            assertEquals(expected, fused, 0.01, "加权融合结果正确");
        }

        @Test
        @DisplayName("默认权重融合（无配置时）")
        void testScoreFusion_defaultWeights() {
            double ruleScore = 0.8;
            double modelScore = 0.6;

            RuleEvaluationResult result = RuleEvaluationResult.matched("R001", "测试");
            result.setRuleScore(ruleScore);
            result.setModelScore(modelScore);

            double fused = scoreFusionService.fuse(result, createExpressionRule(), 0.5);
            assertTrue(fused > 0, "融合分数应大于0");
        }
    }

    @Nested
    @DisplayName("告警降噪与聚合测试")
    class AlertNoiseReductionTests {

        @Test
        @DisplayName("告警指纹生成：相同实体同规则")
        void testFingerprintGenerator_sameEntitySameRule() {
            RuleEvaluationResult r1 = createMockResult("R001", "user-123", "user", "payment");
            RuleEvaluationResult r2 = createMockResult("R001", "user-123", "user", "payment");

            String fp1 = fingerprintGenerator.generate(r1);
            String fp2 = fingerprintGenerator.generate(r2);

            assertEquals(fp1, fp2, "相同条件应生成相同指纹");
            assertNotNull(fp1, "指纹不应为空");
            assertTrue(fp1.length() > 0, "指纹应有长度");
        }

        @Test
        @DisplayName("告警指纹生成：不同实体不同指纹")
        void testFingerprintGenerator_differentEntityDifferentFingerprint() {
            RuleEvaluationResult r1 = createMockResult("R001", "user-123", "user", "payment");
            RuleEvaluationResult r2 = createMockResult("R001", "user-456", "user", "payment");

            String fp1 = fingerprintGenerator.generate(r1);
            String fp2 = fingerprintGenerator.generate(r2);

            assertNotEquals(fp1, fp2, "不同实体应生成不同指纹");
        }

        @Test
        @DisplayName("告警指纹生成：不同规则不同指纹")
        void testFingerprintGenerator_differentRuleDifferentFingerprint() {
            RuleEvaluationResult r1 = createMockResult("R001", "user-123", "user", "payment");
            RuleEvaluationResult r2 = createMockResult("R002", "user-123", "user", "payment");

            String fp1 = fingerprintGenerator.generate(r1);
            String fp2 = fingerprintGenerator.generate(r2);

            assertNotEquals(fp1, fp2, "不同规则应生成不同指纹");
        }

        @Test
        @DisplayName("告警聚合：同类告警合并")
        void testAlertAggregator_mergeSameFingerprint() {
            RuleEvaluationResult r1 = createMockResult("R001", "user-123", "user", "payment");
            RuleEvaluationResult r2 = createMockResult("R001", "user-123", "user", "payment");
            r2.getMatchedEvents().add(createValidPaymentEvent());
            r2.getMatchedEvents().get(0).setEventId("event-2");

            AlertEvent alert1 = alertAggregator.createInitialAlert(r1, "fp-001");
            AlertEvent merged = alertAggregator.mergeAlert(alert1, r2);

            assertEquals(2, merged.getEventCount(), "事件数应合并");
            assertEquals(2, merged.getRuleHitCount(), "命中数应合并");
            assertNotNull(merged.getFirstEventTime(), "首事件时间应存在");
            assertNotNull(merged.getLastEventTime(), "末事件时间应存在");
        }

        @Test
        @DisplayName("告警升级：连续命中升级严重级别")
        void testAlertEvent_escalation() {
            AlertEvent alert = AlertEvent.builder()
                    .alertId("A001")
                    .severity(AlertSeverity.WARNING)
                    .ruleHitCount(1)
                    .build();

            assertFalse(alert.shouldEscalate(5), "1次未达阈值不应升级");

            alert.setRuleHitCount(5);
            assertTrue(alert.shouldEscalate(5), "达到阈值应升级");

            AlertSeverity newSeverity = alert.escalate();
            assertEquals(AlertSeverity.MEDIUM, newSeverity, "应从WARNING升级到MEDIUM");
        }

        @Test
        @DisplayName("告警严重级别排序")
        void testAlertSeverity_levelOrdering() {
            assertTrue(AlertSeverity.CRITICAL.isHigherThan(AlertSeverity.HIGH));
            assertTrue(AlertSeverity.HIGH.isHigherThan(AlertSeverity.MEDIUM));
            assertTrue(AlertSeverity.MEDIUM.isHigherThan(AlertSeverity.WARNING));
            assertTrue(AlertSeverity.WARNING.isHigherThan(AlertSeverity.INFO));
            assertFalse(AlertSeverity.LOW.isHigherThan(AlertSeverity.HIGH));
        }

        @Test
        @DisplayName("告警事件时间更新")
        void testAlertEvent_eventTimeUpdate() {
            AlertEvent alert = AlertEvent.builder().build();
            long t1 = System.currentTimeMillis() - 60000;
            long t2 = System.currentTimeMillis();
            long t3 = System.currentTimeMillis() - 30000;

            alert.updateEventTime(t2);
            assertEquals(t2, alert.getFirstEventTime());
            assertEquals(t2, alert.getLastEventTime());

            alert.updateEventTime(t1);
            assertEquals(t1, alert.getFirstEventTime(), "更早时间应更新首事件");
            assertEquals(t2, alert.getLastEventTime());

            alert.updateEventTime(t3);
            assertEquals(t1, alert.getFirstEventTime(), "中间时间不应更新两端");
            assertEquals(t2, alert.getLastEventTime());
        }

        private RuleEvaluationResult createMockResult(String ruleId, String entityId, String entityType, String businessLine) {
            RiskEvent event = RiskEvent.builder()
                    .eventId("event-" + UUID.randomUUID())
                    .entityId(entityId)
                    .entityType(entityType)
                    .businessLine(businessLine)
                    .eventType("test.event")
                    .timestamp(System.currentTimeMillis())
                    .build();

            return RuleEvaluationResult.builder()
                    .ruleId(ruleId)
                    .ruleName("规则-" + ruleId)
                    .matched(true)
                    .matchedEvents(Collections.singletonList(event))
                    .build();
        }
    }

    @Nested
    @DisplayName("工具类测试")
    class UtilsTests {

        @Test
        @DisplayName("时间窗口计算：滚动窗口")
        void testTimeWindow_tumblingWindow() {
            long now = Instant.parse("2024-01-15T10:30:45Z").toEpochMilli();
            long windowSize = TimeUnit.MINUTES.toMillis(5);

            long[] window = TimeWindowUtils.getTumblingWindow(now, windowSize);
            long expectedStart = Instant.parse("2024-01-15T10:30:00Z").toEpochMilli();
            long expectedEnd = Instant.parse("2024-01-15T10:35:00Z").toEpochMilli();

            assertEquals(expectedStart, window[0], "窗口起始正确");
            assertEquals(expectedEnd, window[1], "窗口结束正确");
        }

        @Test
        @DisplayName("时间窗口计算：滑动窗口")
        void testTimeWindow_slidingWindow() {
            long now = Instant.parse("2024-01-15T10:30:45Z").toEpochMilli();
            long windowSize = TimeUnit.MINUTES.toMillis(5);

            long[] window = TimeWindowUtils.getSlidingWindow(now, windowSize);
            long expectedStart = now - windowSize;

            assertEquals(expectedStart, window[0], "滑动窗口起始正确");
            assertEquals(now, window[1], "滑动窗口结束为当前时间");
        }

        @Test
        @DisplayName("IP地址校验与解析")
        void testEventUtils_ipValidation() {
            assertTrue(EventUtils.isValidIp("192.168.1.1"), "合法IPv4");
            assertTrue(EventUtils.isValidIp("255.255.255.255"), "边界IPv4");
            assertTrue(EventUtils.isValidIp("2001:db8::1"), "合法IPv6");
            assertFalse(EventUtils.isValidIp("256.1.1.1"), "非法IP");
            assertFalse(EventUtils.isValidIp("abc"), "非IP字符串");
            assertFalse(EventUtils.isValidIp(null), "null应返回false");
        }

        @Test
        @DisplayName("字段提取：嵌套点路径")
        void testEventUtils_fieldExtraction() {
            RiskEvent event = createValidPaymentEvent();
            event.setAttribute("amount", 12345.67);
            Map<String, Object> device = new HashMap<>();
            device.put("riskLevel", 5);
            device.put("model", "iPhone15");
            event.setAttribute("device", device);

            assertEquals(12345.67, EventUtils.extractField(event, "event.amount"));
            assertEquals(5, EventUtils.extractField(event, "event.device.riskLevel"));
            assertEquals("payment", EventUtils.extractField(event, "event.businessLine"));
            assertNull(EventUtils.extractField(event, "event.nonexistent.path"));
        }

        @Test
        @DisplayName("类型转换")
        void testEventUtils_typeConversion() {
            assertEquals(100L, EventUtils.toLong("100"));
            assertEquals(100L, EventUtils.toLong(100));
            assertEquals(100L, EventUtils.toLong(99.9));

            assertEquals(3.14, EventUtils.toDouble("3.14"), 0.001);
            assertEquals(3.0, EventUtils.toDouble(3), 0.001);

            assertTrue(EventUtils.toBoolean("true"));
            assertTrue(EventUtils.toBoolean(1));
            assertFalse(EventUtils.toBoolean("false"));
            assertFalse(EventUtils.toBoolean(0));
        }

        @Test
        @DisplayName("指纹生成：SHA256一致性")
        void testFingerprintGenerator_sha256() {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ruleId", "R001");
            fields.put("entityId", "user-123");
            fields.put("businessLine", "payment");

            String fp1 = FingerprintGenerator.generateSha256(fields);
            String fp2 = FingerprintGenerator.generateSha256(fields);

            assertEquals(fp1, fp2, "相同输入SHA256应一致");
            assertEquals(64, fp1.length(), "SHA256应为64字符hex");
        }
    }

    // ============ Helper Methods ============

    private RiskEvent createValidPaymentEvent() {
        return RiskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("payment.create")
                .businessLine("payment")
                .entityId("order-" + UUID.randomUUID())
                .entityType("ORDER")
                .timestamp(System.currentTimeMillis())
                .source("mobile-app")
                .sessionId("session-" + UUID.randomUUID())
                .ip("192.168.1.100")
                .userId("user-" + UUID.randomUUID())
                .build();
    }

    private RiskEvent createValidLoginEvent() {
        return RiskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("login.success")
                .businessLine("login")
                .entityId("user-" + UUID.randomUUID())
                .entityType("USER")
                .timestamp(System.currentTimeMillis())
                .source("web")
                .ip("10.0.0.50")
                .userId("user-" + UUID.randomUUID())
                .build();
    }

    private RuleDefinition createExpressionRule() {
        return RuleDefinition.builder()
                .ruleId("RULE-PAY-001")
                .ruleName("大额跨境支付异常")
                .ruleType(RuleType.EXPRESSION)
                .businessLine("payment")
                .eventTypes(Arrays.asList("payment.create", "payment.confirm"))
                .priority(10)
                .shortCircuit(true)
                .enabled(true)
                .severity(AlertSeverity.HIGH)
                .dslExpression("event.amount > 50000 && event.country NOT IN ['CN','US','JP','GB','DE']")
                .modelWeight(0.6)
                .threshold(0.7)
                .actions(Arrays.asList("ACT_FREEZE_ACCOUNT", "ACT_WEBHOOK_RISK"))
                .description("单笔金额超过5万且非白名单国家的支付交易")
                .build();
    }
}
