package com.exam.service.sampler;

import com.exam.entity.Question;
import com.exam.fixture.QuestionFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("带权重蓄水池抽样算法测试")
class WeightedReservoirSamplerTest {

    private WeightedReservoirSampler sampler;

    @BeforeEach
    void setUp() {
        sampler = new WeightedReservoirSampler(new Random(42));
    }

    @Test
    @DisplayName("样本数为0返回空列表")
    void shouldReturnEmptyWhenKIsZero() {
        List<Question> pool = QuestionFixture.createQuestionPool(5);
        List<Question> result = sampler.sample(pool, 0, q -> 1.0);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("池为空返回空列表")
    void shouldReturnEmptyWhenPoolIsEmpty() {
        List<Question> result = sampler.sample(Collections.emptyList(), 10, q -> 1.0);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("k大于池大小返回所有元素")
    void shouldReturnAllWhenKExceedsPoolSize() {
        List<Question> pool = QuestionFixture.createQuestionPool(5);
        List<Question> result = sampler.sample(pool, 10, q -> 1.0);
        assertThat(result).hasSize(5);
    }

    @Test
    @DisplayName("等权重抽样返回正确数量")
    void shouldReturnCorrectCountWithEqualWeights() {
        List<Question> pool = QuestionFixture.createQuestionPool(100);
        List<Question> result = sampler.sample(pool, 20, q -> 1.0);
        assertThat(result).hasSize(20);
    }

    @Test
    @DisplayName("结果中没有重复元素")
    void shouldHaveNoDuplicates() {
        List<Question> pool = QuestionFixture.createQuestionPool(100);
        List<Question> result = sampler.sample(pool, 30, q -> 1.0);

        Set<Long> ids = new HashSet<>();
        for (Question q : result) {
            ids.add(q.getId());
        }
        assertThat(ids).hasSize(result.size());
    }

    @Test
    @DisplayName("高权重题目被抽中概率更高")
    void shouldHigherWeightHaveHigherProbability() {
        List<Question> pool = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Question q = new Question();
            q.setId((long) i);
            q.setDifficulty(1);
            pool.add(q);
        }
        for (int i = 50; i < 100; i++) {
            Question q = new Question();
            q.setId((long) i);
            q.setDifficulty(3);
            pool.add(q);
        }

        WeightedReservoirSampler.WeightFunction weightFunc = q -> {
            if (q.getDifficulty() == 1) return 2.0;
            return 1.0;
        };

        int easyCount = 0;
        int runs = 100;
        for (int i = 0; i < runs; i++) {
            WeightedReservoirSampler s = new WeightedReservoirSampler(new Random(i));
            List<Question> sample = s.sample(pool, 20, weightFunc);
            easyCount += (int) sample.stream().filter(q -> q.getDifficulty() == 1).count();
        }

        double avgEasy = (double) easyCount / runs;
        assertThat(avgEasy).isGreaterThan(10.0);
    }

    @Test
    @DisplayName("所有结果都来自池中")
    void shouldAllResultsFromPool() {
        List<Question> pool = QuestionFixture.createQuestionPool(50);
        Set<Long> poolIds = new HashSet<>();
        for (Question q : pool) {
            poolIds.add(q.getId());
        }

        List<Question> result = sampler.sample(pool, 15, q -> 1.0);
        for (Question q : result) {
            assertThat(poolIds).contains(q.getId());
        }
    }
}
