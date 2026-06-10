package com.exam.service.constraint;

import com.exam.entity.Question;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("约束求解器单元测试")
class ConstraintSolverTest {

    private SelectionContext context;

    @BeforeEach
    void setUp() {
        context = new SelectionContext(1L, 10);
    }

    @Nested
    @DisplayName("难度分布约束")
    class DifficultyConstraintTests {

        private DifficultyConstraint constraint;

        @BeforeEach
        void setUp() {
            constraint = new DifficultyConstraint(
                    new BigDecimal("0.3"),
                    new BigDecimal("0.5"),
                    new BigDecimal("0.2"),
                    1
            );
        }

        @Test
        @DisplayName("达到目标数量前允许对应难度题目")
        void shouldAllowQuestionBeforeLimit() {
            Question easy = new Question();
            easy.setId(1L);
            easy.setDifficulty(1);

            assertThat(constraint.check(easy, context)).isTrue();

            constraint.onSelected(easy, context);
            assertThat(context.getDifficultyCount().get(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("超过目标数量后拒绝对应难度题目")
        void shouldRejectWhenLimitReached() {
            for (int i = 0; i < 3; i++) {
                Question q = new Question();
                q.setId((long) i);
                q.setDifficulty(1);
                constraint.onSelected(q, context);
            }

            Question extra = new Question();
            extra.setId(100L);
            extra.setDifficulty(1);

            assertThat(constraint.check(extra, context)).isFalse();
        }

        @Test
        @DisplayName("不同难度独立计数")
        void shouldCountByDifficultySeparately() {
            Question easyQ = new Question();
            easyQ.setId(1L);
            easyQ.setDifficulty(1);
            constraint.onSelected(easyQ, context);

            Question mediumQ = new Question();
            mediumQ.setId(2L);
            mediumQ.setDifficulty(2);

            assertThat(constraint.check(mediumQ, context)).isTrue();
            assertThat(context.getDifficultyCount().get(1)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("知识点数量限制约束")
    class KnowledgePointLimitTests {

        private KnowledgePointLimitConstraint constraint;

        @BeforeEach
        void setUp() {
            constraint = new KnowledgePointLimitConstraint(3);
        }

        @Test
        @DisplayName("知识点未超限允许通过")
        void shouldAllowWhenUnderLimit() {
            Question q = new Question();
            q.setId(1L);
            q.setKnowledgePoints("kp1,kp2");

            assertThat(constraint.check(q, context)).isTrue();

            constraint.onSelected(q, context);
            assertThat(context.getKnowledgePointCount().get("kp1")).isEqualTo(1);
            assertThat(context.getKnowledgePointCount().get("kp2")).isEqualTo(1);
        }

        @Test
        @DisplayName("知识点超限时拒绝")
        void shouldRejectWhenLimitExceeded() {
            for (int i = 0; i < 3; i++) {
                Question q = new Question();
                q.setId((long) i);
                q.setKnowledgePoints("kp1");
                constraint.onSelected(q, context);
            }

            Question q4 = new Question();
            q4.setId(100L);
            q4.setKnowledgePoints("kp1");

            assertThat(constraint.check(q4, context)).isFalse();
        }

        @Test
        @DisplayName("多知识点题目任一超限都拒绝")
        void shouldRejectIfAnyKnowledgePointExceeded() {
            for (int i = 0; i < 3; i++) {
                Question q = new Question();
                q.setId((long) i);
                q.setKnowledgePoints("kp1");
                constraint.onSelected(q, context);
            }

            Question q = new Question();
            q.setId(100L);
            q.setKnowledgePoints("kp1,kp5");

            assertThat(constraint.check(q, context)).isFalse();
        }
    }

    @Nested
    @DisplayName("排除题目约束")
    class ExcludedQuestionTests {

        private ExcludedQuestionConstraint constraint;

        @BeforeEach
        void setUp() {
            Set<Long> excluded = new HashSet<>(Arrays.asList(1L, 2L, 3L));
            constraint = new ExcludedQuestionConstraint(excluded);
        }

        @Test
        @DisplayName("排除列表中的题目被拒绝")
        void shouldRejectExcludedQuestions() {
            Question q = new Question();
            q.setId(2L);

            assertThat(constraint.check(q, context)).isFalse();
        }

        @Test
        @DisplayName("非排除列表中的题目允许")
        void shouldAllowNonExcludedQuestions() {
            Question q = new Question();
            q.setId(100L);

            assertThat(constraint.check(q, context)).isTrue();
        }

        @Test
        @DisplayName("选过的题目自动加入排除列表")
        void shouldAddSelectedToExcluded() {
            Question q = new Question();
            q.setId(50L);

            assertThat(constraint.check(q, context)).isTrue();
            constraint.onSelected(q, context);

            assertThat(context.getExcludedQuestionIds()).contains(50L);
        }
    }

    @Nested
    @DisplayName("约束链")
    class ConstraintChainTests {

        private ConstraintChain chain;

        @BeforeEach
        void setUp() {
            chain = new ConstraintChain();
            chain.addSolver(new DifficultyConstraint(
                    new BigDecimal("0.3"),
                    new BigDecimal("0.5"),
                    new BigDecimal("0.2"),
                    1
            ));
            chain.addSolver(new ExcludedQuestionConstraint(new HashSet<>(Arrays.asList(999L))));
        }

        @Test
        @DisplayName("所有约束通过才允许")
        void shouldPassOnlyWhenAllConstraintsPass() {
            Question q = new Question();
            q.setId(1L);
            q.setDifficulty(1);

            assertThat(chain.checkAll(q, context)).isTrue();
        }

        @Test
        @DisplayName("任一约束不通过则拒绝")
        void shouldRejectIfAnyConstraintFails() {
            Question excluded = new Question();
            excluded.setId(999L);
            excluded.setDifficulty(1);

            assertThat(chain.checkAll(excluded, context)).isFalse();
        }

        @Test
        @DisplayName("选中后所有约束收到通知")
        void shouldNotifyAllConstraintsOnSelect() {
            Question q = new Question();
            q.setId(1L);
            q.setDifficulty(1);
            q.setKnowledgePoints("kp1");

            chain.notifySelected(q, context);

            assertThat(context.getDifficultyCount()).containsKey(1);
            assertThat(context.getExcludedQuestionIds()).contains(1L);
        }

        @Test
        @DisplayName("约束按优先级降序排列")
        void shouldSortByPriorityDescending() {
            List<ConstraintSolver> solvers = chain.getSolvers();
            assertThat(solvers.get(0).getPriority())
                    .isGreaterThanOrEqualTo(solvers.get(1).getPriority());
        }
    }
}
