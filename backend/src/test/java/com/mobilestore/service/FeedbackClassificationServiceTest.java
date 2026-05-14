package com.mobilestore.service;

import com.mobilestore.test.BaseServiceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("反馈自动分类服务测试")
class FeedbackClassificationServiceTest extends BaseServiceTest {

    private final FeedbackClassificationService classificationService = new FeedbackClassificationService();

    @Nested
    @DisplayName("Bug类反馈分类测试")
    class BugClassificationTests {

        @Test
        @DisplayName("闪退反馈应识别为bug_report且高优先级")
        void classify_crashFeedback_shouldBeBugReportHighPriority() {
            String content = "应用一打开就闪退，根本用不了！";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
            assertTrue(result.getMatchedKeywords().size() > 0);
        }

        @Test
        @DisplayName("崩溃反馈应识别为bug_report且高优先级")
        void classify_appCrash_shouldBeBugReport() {
            String content = "使用中突然崩溃，需要重新启动";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
        }

        @Test
        @DisplayName("无法启动反馈应识别为bug_report")
        void classify_cannotStart_shouldBeBugReport() {
            String content = "点击图标后黑屏，无法启动应用";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 2);

            assertEquals("bug_report", result.getFeedbackType());
        }

        @Test
        @DisplayName("报错反馈应识别为bug_report")
        void classify_errorFeedback_shouldBeBugReport() {
            String content = "登录时提示network error，请尽快修复";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 2);

            assertEquals("bug_report", result.getFeedbackType());
        }

        @Test
        @DisplayName("数据丢失反馈应识别为高优先级bug")
        void classify_dataLoss_shouldBeHighPriorityBug() {
            String content = "更新后所有数据都丢失了，希望能恢复";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
            assertTrue(result.getMatchedKeywords().contains("数据丢失"));
        }

        @Test
        @DisplayName("支付失败反馈应识别为高优先级bug")
        void classify_paymentFail_shouldBeHighPriorityBug() {
            String content = "支付失败但钱被扣了，怎么退款？";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
        }
    }

    @Nested
    @DisplayName("功能建议类反馈分类测试")
    class FeatureRequestClassificationTests {

        @Test
        @DisplayName("新增功能建议应识别为feature_request")
        void classify_addFeature_shouldBeFeatureRequest() {
            String content = "希望能新增深色模式功能，晚上使用更护眼";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 4);

            assertEquals("feature_request", result.getFeedbackType());
            assertEquals("medium", result.getPriority());
        }

        @Test
        @DisplayName("改进建议应识别为feature_request")
        void classify_improvement_shouldBeFeatureRequest() {
            String content = "界面可以改进一下，操作不够流畅";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 3);

            assertEquals("feature_request", result.getFeedbackType());
        }

        @Test
        @DisplayName("功能增强建议应识别为feature_request")
        void classify_enhancement_shouldBeFeatureRequest() {
            String content = "建议增加分享到微信的功能";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 4);

            assertEquals("feature_request", result.getFeedbackType());
        }

        @Test
        @DisplayName("建议类反馈优先级应为中")
        void classify_suggestion_shouldBeMediumPriority() {
            String content = "建议在首页添加快捷入口";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 4);

            assertEquals("medium", result.getPriority());
        }
    }

    @Nested
    @DisplayName("投诉类反馈分类测试")
    class ComplaintClassificationTests {

        @Test
        @DisplayName("差评投诉应识别为complaint")
        void classify_badReview_shouldBeComplaint() {
            String content = "太差了！广告太多，根本没法用";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("complaint", result.getFeedbackType());
            assertEquals("high", result.getPriority());
        }

        @Test
        @DisplayName("客服投诉应识别为complaint")
        void classify_complaintAboutService_shouldBeComplaint() {
            String content = "客服态度很差，问题迟迟不给解决";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("complaint", result.getFeedbackType());
        }

        @Test
        @DisplayName("2星及以下投诉应标记为高优先级")
        void classify_lowRatingComplaint_shouldBeHighPriority() {
            String content = "体验非常糟糕";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("high", result.getPriority());
        }
    }

    @Nested
    @DisplayName("咨询类反馈分类测试")
    class QuestionClassificationTests {

        @Test
        @DisplayName("使用咨询应识别为question")
        void classify_usageQuestion_shouldBeQuestion() {
            String content = "请问怎么更换主题？找了半天没找到";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 3);

            assertEquals("question", result.getFeedbackType());
        }

        @Test
        @DisplayName("功能咨询应识别为question")
        void classify_featureQuestion_shouldBeQuestion() {
            String content = "这个功能有什么作用？不太明白";

            FeedbackClassificationService.ClassificationResult result =
                classificationService.classify(null, content, 3);

            assertEquals("question", result.getFeedbackType());
        }

        @Test
        @DisplayName("咨询类优先级应为低")
        void classify_question_shouldBeLowPriority() {
            String content = "请问数据可以导出吗？";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 3);

            assertEquals("question", result.getFeedbackType());
            assertEquals("low", result.getPriority());
        }
    }

    @Nested
    @DisplayName("处理人自动分配测试")
    class AssigneeAssignmentTests {

        @Test
        @DisplayName("Bug类反馈应分配给技术支持")
        void assignBug_shouldBeTechSupport() {
            String content = "应用点击就闪退";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
            assertTrue(result.getAssignee().startsWith("tech"));
        }

        @Test
        @DisplayName("功能建议应分配给产品经理")
        void assignFeatureRequest_shouldBeProduct() {
            String content = "希望能增加新功能";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 4);

            assertEquals("feature_request", result.getFeedbackType());
            assertEquals("medium", result.getPriority());
            assertTrue(result.getAssignee().startsWith("product"));
        }

        @Test
        @DisplayName("投诉应分配给客服")
        void assignComplaint_shouldBeSupport() {
            String content = "体验很差，希望退款";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("complaint", result.getFeedbackType());
            assertEquals("high", result.getPriority());
            assertTrue(result.getAssignee().startsWith("support"));
        }

        @Test
        @DisplayName("咨询应分配给客服")
        void assignQuestion_shouldBeSupport() {
            String content = "请问怎么使用这个功能？";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 3);

            assertEquals("question", result.getFeedbackType());
            assertEquals("low", result.getPriority());
            assertTrue(result.getAssignee().startsWith("support"));
        }
    }

    @Nested
    @DisplayName("混合关键词测试")
    class MixedKeywordTests {

        @Test
        @DisplayName("混合Bug和建议应优先识别Bug")
        void classify_mixedBugAndFeature_shouldPrioritizeBug() {
            String content = "应用经常闪退，另外希望增加深色模式";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 2);

            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
            assertTrue(result.getMatchedKeywords().contains("闪退"));
        }

        @Test
        @DisplayName("多个Bug关键词应全部匹配")
        void classify_multipleBugKeywords_shouldMatchAll() {
            String content = "应用闪退、崩溃、数据都丢失了";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            List<String> keywords = result.getMatchedKeywords();
            assertTrue(keywords.contains("闪退"));
            assertTrue(keywords.contains("崩溃"));
            assertTrue(keywords.contains("数据丢失"));
        }
    }

    @Nested
    @DisplayName("指定反馈类型测试")
    class SpecifiedTypeTests {

        @Test
        @DisplayName("用户指定类型时应优先使用")
        void classify_specifiedType_shouldUseIt() {
            String content = "希望能增加新功能";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify("bug_report", content, 4);

            assertEquals("bug_report", result.getFeedbackType());
        }

        @Test
        @DisplayName("未指定类型时应自动推断")
        void classify_nullType_shouldInfer() {
            String content = "应用经常崩溃";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("bug_report", result.getFeedbackType());
        }

        @Test
        @DisplayName("空类型时应自动推断")
        void classify_emptyType_shouldInfer() {
            String content = "建议增加分享功能";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify("", content, 4);

            assertEquals("feature_request", result.getFeedbackType());
        }
    }

    @Nested
    @DisplayName("优先级计算测试")
    class PriorityCalculationTests {

        @Test
        @DisplayName("严重Bug关键词应强制高优先级")
        void determinePriority_seriousBug_shouldBeHigh() {
            String content = "支付失败";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 3);

            assertEquals("high", result.getPriority());
        }

        @Test
        @DisplayName("低评分Bug应提升优先级")
        void determinePriority_lowRatingBug_shouldBeHigh() {
            String content = "登录报错";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 1);

            assertEquals("high", result.getPriority());
        }

        @Test
        @DisplayName("普通建议应为中优先级")
        void determinePriority_normalSuggestion_shouldBeMedium() {
            String content = "建议优化界面";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 4);

            assertEquals("medium", result.getPriority());
        }

        @Test
        @DisplayName("普通咨询应为低优先级")
        void determinePriority_normalQuestion_shouldBeLow() {
            String content = "请问怎么使用？";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 3);

            assertEquals("low", result.getPriority());
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    class EdgeCaseTests {

        @Test
        @DisplayName("空内容应返回other类型")
        void classify_emptyContent_shouldBeOther() {
            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, "", 3);

            assertEquals("other", result.getFeedbackType());
            assertEquals("low", result.getPriority());
        }

        @Test
        @DisplayName("无效评分应使用默认值")
        void classify_invalidRating_shouldUseDefault() {
            String content = "应用闪退";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, null);

            assertEquals("bug_report", result.getFeedbackType());
            assertEquals("high", result.getPriority());
        }

        @Test
        @DisplayName("无法识别的内容应返回other类型")
        void classify_unknownContent_shouldBeOther() {
            String content = "今天天气很好";

            FeedbackClassificationService.ClassificationResult result =
                classificationService.classify(null, content, 3);

            assertEquals("other", result.getFeedbackType());
            assertEquals("low", result.getPriority());
        }

        @Test
        @DisplayName("中文关键词大小写不敏感")
        void classify_chineseKeywords_caseInsensitive() {
            String content = "程序崩了，error异常";

            FeedbackClassificationService.ClassificationResult result = 
                classificationService.classify(null, content, 2);

            assertEquals("bug_report", result.getFeedbackType());
        }
    }

    @Nested
    @DisplayName("分类规则获取测试")
    class ClassificationRulesTests {

        @Test
        @DisplayName("获取分类规则应包含所有关键词")
        void getClassificationRules_shouldContainAllKeywords() {
            FeedbackClassificationService.ClassificationRules rules = 
                classificationService.getClassificationRules();

            assertNotNull(rules);
            assertTrue(rules.getBugKeywords().size() > 0);
            assertTrue(rules.getFeatureKeywords().size() > 0);
            assertTrue(rules.getComplaintKeywords().size() > 0);
            assertTrue(rules.getQuestionKeywords().size() > 0);
        }

        @Test
        @DisplayName("严重Bug关键词应包含支付失败等")
        void getSeriousBugKeywords_shouldContainCriticalOnes() {
            FeedbackClassificationService.ClassificationRules rules = 
                classificationService.getClassificationRules();

            List<String> serious = rules.getSeriousBugKeywords();
            assertTrue(serious.contains("支付失败"));
            assertTrue(serious.contains("数据丢失"));
        }

        @Test
        @DisplayName("优先级规则应正确返回")
        void getPriorityRules_shouldBeCorrect() {
            FeedbackClassificationService.ClassificationRules rules = 
                classificationService.getClassificationRules();

            assertNotNull(rules.getPriorityRules());
            assertTrue(rules.getPriorityRules().containsKey("high"));
            assertTrue(rules.getPriorityRules().containsKey("medium"));
        }
    }
}
