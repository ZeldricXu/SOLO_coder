package com.exam.service;

import com.exam.entity.ExamAnswer;
import com.exam.fixture.ExamAnswerFixture;
import com.exam.mapper.QuestionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("客观题判分测试")
class GradingServiceTest {

    @Mock
    private QuestionMapper questionMapper;

    @InjectMocks
    private GradingService gradingService;

    @Nested
    @DisplayName("单选题判分")
    class SingleChoiceGradingTest {

        @Test
        @DisplayName("答案完全正确得满分")
        void shouldGiveFullScoreWhenCorrect() {
            ExamAnswer answer = ExamAnswerFixture.singleChoiceCorrect();

            BigDecimal score = gradingService.gradeSingleChoice(answer);

            assertThat(score).isEqualByComparingTo(new BigDecimal("2"));
        }

        @Test
        @DisplayName("答案错误得0分")
        void shouldGiveZeroWhenWrong() {
            ExamAnswer answer = ExamAnswerFixture.singleChoiceWrong();

            BigDecimal score = gradingService.gradeSingleChoice(answer);

            assertThat(score).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("答案不区分大小写")
        void shouldBeCaseInsensitive() {
            ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(1, "b", "B", new BigDecimal("2"));

            BigDecimal score = gradingService.gradeSingleChoice(answer);

            assertThat(score).isEqualByComparingTo(new BigDecimal("2"));
        }

        @Test
        @DisplayName("答案为null时得0分")
        void shouldGiveZeroWhenAnswerNull() {
            ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(1, null, "B", new BigDecimal("2"));

            BigDecimal score = gradingService.gradeSingleChoice(answer);

            assertThat(score).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("首尾空格自动去除")
        void shouldTrimWhitespace() {
            ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(1, "  B  ", "B", new BigDecimal("2"));

            BigDecimal score = gradingService.gradeSingleChoice(answer);

            assertThat(score).isEqualByComparingTo(new BigDecimal("2"));
        }
    }

    @Nested
    @DisplayName("多选题判分")
    class MultipleChoiceGradingTest {

        @Test
        @DisplayName("全部选对得满分")
        void shouldGiveFullScoreWhenAllCorrect() {
            ExamAnswer answer = ExamAnswerFixture.multipleChoiceFull();

            BigDecimal score = gradingService.gradeMultipleChoice(answer);

            assertThat(score).isEqualByComparingTo(new BigDecimal("4"));
        }

        @Test
        @DisplayName("选了错误选项得0分")
        void shouldGiveZeroWhenWrongOptionSelected() {
            ExamAnswer answer = ExamAnswerFixture.multipleChoiceWrong();

            BigDecimal score = gradingService.gradeMultipleChoice(answer);

            assertThat(score).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("漏选但选的都对时得半分")
        void shouldGiveHalfScoreWhenPartialCorrect() {
            ExamAnswer answer = ExamAnswerFixture.multipleChoicePartial();

            BigDecimal score = gradingService.gradeMultipleChoice(answer);

            BigDecimal expected = new BigDecimal("4")
                    .multiply(new BigDecimal("2"))
                    .divide(new BigDecimal("3"), 4, RoundingMode.HALF_UP)
                    .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
            assertThat(score).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("空答案得0分")
        void shouldGiveZeroWhenEmptyAnswer() {
            ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(2, "", "A,B,D", new BigDecimal("4"));

            BigDecimal score = gradingService.gradeMultipleChoice(answer);

            assertThat(score).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("支持多种分隔符（逗号、分号、顿号、空格）")
        void shouldSupportVariousDelimiters() {
            ExamAnswer commaSep = ExamAnswerFixture.examAnswerForGrading(2, "A,B,D", "A,B,D", new BigDecimal("4"));
            ExamAnswer semicolonSep = ExamAnswerFixture.examAnswerForGrading(2, "A;B;D", "A,B,D", new BigDecimal("4"));
            ExamAnswer spaceSep = ExamAnswerFixture.examAnswerForGrading(2, "A B D", "A,B,D", new BigDecimal("4"));
            ExamAnswer mixedSep = ExamAnswerFixture.examAnswerForGrading(2, "A，B；D", "A,B,D", new BigDecimal("4"));

            assertThat(gradingService.gradeMultipleChoice(commaSep)).isEqualByComparingTo("4");
            assertThat(gradingService.gradeMultipleChoice(semicolonSep)).isEqualByComparingTo("4");
            assertThat(gradingService.gradeMultipleChoice(spaceSep)).isEqualByComparingTo("4");
            assertThat(gradingService.gradeMultipleChoice(mixedSep)).isEqualByComparingTo("4");
        }
    }

    @Nested
    @DisplayName("判断题判分")
    class JudgeGradingTest {

        @Test
        @DisplayName("正确答案匹配得满分")
        void shouldGiveFullScoreWhenMatched() {
            ExamAnswer answer = ExamAnswerFixture.judgeCorrect();

            BigDecimal score = gradingService.gradeJudge(answer);

            assertThat(score).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("支持多种真值表述（true/T/1/对/正确/yes/y）")
        void shouldSupportVariousTrueRepresentations() {
            String[] trueAnswers = {"true", "True", "TRUE", "T", "t", "1", "对", "正确", "yes", "Y", "y"};
            for (String ans : trueAnswers) {
                ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(3, ans, "正确", new BigDecimal("1"));
                BigDecimal score = gradingService.gradeJudge(answer);
                assertThat(score)
                        .as("答案 '%s' 应被判为正确", ans)
                        .isEqualByComparingTo("1");
            }
        }

        @Test
        @DisplayName("支持多种假值表述（false/F/0/错/错误/no/n）")
        void shouldSupportVariousFalseRepresentations() {
            String[] falseAnswers = {"false", "False", "FALSE", "F", "f", "0", "错", "错误", "no", "N", "n"};
            for (String ans : falseAnswers) {
                ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(3, ans, "错误", new BigDecimal("1"));
                BigDecimal score = gradingService.gradeJudge(answer);
                assertThat(score)
                        .as("答案 '%s' 应被判为正确", ans)
                        .isEqualByComparingTo("1");
            }
        }

        @Test
        @DisplayName("判断错误得0分")
        void shouldGiveZeroWhenJudgeWrong() {
            ExamAnswer answer = ExamAnswerFixture.judgeWrong();

            BigDecimal score = gradingService.gradeJudge(answer);

            assertThat(score).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("首尾空格自动忽略")
        void shouldTrimWhitespace() {
            ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(3, "  正确  ", "正确", new BigDecimal("1"));

            BigDecimal score = gradingService.gradeJudge(answer);

            assertThat(score).isEqualByComparingTo("1");
        }
    }

    @Nested
    @DisplayName("填空题判分")
    class FillBlankGradingTest {

        @Test
        @DisplayName("多空全部答对得满分")
        void shouldGiveFullScoreWhenAllBlanksCorrect() {
            ExamAnswer answer = ExamAnswerFixture.fillBlankCorrect();

            BigDecimal score = gradingService.gradeFillBlank(answer);

            assertThat(score).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("多空部分答对按比例得分")
        void shouldGiveProportionalScoreForPartialCorrect() {
            ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(4, "final||wrong", "final||interface", new BigDecimal("4"));

            BigDecimal score = gradingService.gradeFillBlank(answer);

            BigDecimal expected = new BigDecimal("4")
                    .divide(new BigDecimal("2"), 4, RoundingMode.HALF_UP);
            assertThat(score).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("忽略首尾空格和连续空格")
        void shouldIgnoreWhitespace() {
            ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(
                    4, "  final   answer  ||   java   interface  ",
                    "final answer||java interface", new BigDecimal("4"));

            BigDecimal score = gradingService.gradeFillBlank(answer);

            assertThat(score).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("支持多答案匹配（用|分隔，答任意一个算对）")
        void shouldSupportMultipleAcceptableAnswers() {
            ExamAnswer answer = ExamAnswerFixture.fillBlankWithSpaces();

            BigDecimal score = gradingService.gradeFillBlank(answer);

            assertThat(score).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("自动转换中文标点为英文标点")
        void shouldNormalizeChinesePunctuation() {
            ExamAnswer answer = ExamAnswerFixture.examAnswerForGrading(
                    4, "你好，世界。", "你好,世界.", new BigDecimal("2"));

            BigDecimal score = gradingService.gradeFillBlank(answer);

            assertThat(score).isEqualByComparingTo("2");
        }
    }

    @Nested
    @DisplayName("主观题双盲评阅合并")
    class SubjectiveGradingMergeTest {

        @Test
        @DisplayName("两位阅卷老师分数差在阈值内（≤20%），取平均值")
        void shouldTakeAverageWhenDifferenceWithinThreshold() {
            ExamAnswer answer = ExamAnswerFixture.subjectiveForMerge(
                    new BigDecimal("8"), new BigDecimal("9"), new BigDecimal("10"));

            BigDecimal merged = gradingService.mergeSubjectiveGrades(answer);

            assertThat(merged).isEqualByComparingTo(new BigDecimal("8.50"));
        }

        @Test
        @DisplayName("两位阅卷老师分数差超出阈值（>20%），返回null需仲裁")
        void shouldReturnNullWhenDifferenceExceedsThreshold() {
            ExamAnswer answer = ExamAnswerFixture.subjectiveForMerge(
                    new BigDecimal("6"), new BigDecimal("9"), new BigDecimal("10"));

            BigDecimal merged = gradingService.mergeSubjectiveGrades(answer);

            assertThat(merged).isNull();
        }

        @Test
        @DisplayName("只有一位老师评分时直接采用该分数")
        void shouldUseAvailableScoreWhenOnlyOneGrader() {
            ExamAnswer onlyFirst = ExamAnswerFixture.subjectiveForMerge(
                    new BigDecimal("8.5"), null, new BigDecimal("10"));
            ExamAnswer onlySecond = ExamAnswerFixture.subjectiveForMerge(
                    null, new BigDecimal("7.5"), new BigDecimal("10"));

            assertThat(gradingService.mergeSubjectiveGrades(onlyFirst)).isEqualByComparingTo("8.5");
            assertThat(gradingService.mergeSubjectiveGrades(onlySecond)).isEqualByComparingTo("7.5");
        }

        @Test
        @DisplayName("两位老师都未评分返回0")
        void shouldReturnZeroWhenNoGraderScore() {
            ExamAnswer answer = ExamAnswerFixture.subjectiveForMerge(null, null, new BigDecimal("10"));

            BigDecimal merged = gradingService.mergeSubjectiveGrades(answer);

            assertThat(merged).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
