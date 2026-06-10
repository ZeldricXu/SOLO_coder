package com.exam.service.index;

import com.exam.common.Constants;
import com.exam.entity.Question;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionInvertedIndex {

    private final RedisTemplate<String, Object> redisTemplate;

    private final Map<Long, Map<Integer, Map<Integer, List<Long>>>> subjectTypeDifficultyIndex = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, List<Long>>> subjectKnowledgePointIndex = new ConcurrentHashMap<>();
    private final Map<Long, List<Question>> subjectQuestionCache = new ConcurrentHashMap<>();

    private static final String INDEX_CACHE_KEY = "exam:index:question:version";

    @PostConstruct
    public void init() {
        log.info("题目倒排索引初始化完成");
    }

    public List<Question> getQuestionsByTypeAndDifficulty(Long subjectId, Integer questionType, Integer difficulty) {
        List<Question> all = subjectQuestionCache.getOrDefault(subjectId, Collections.emptyList());
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        return all.stream()
                .filter(q -> questionType == null || Objects.equals(q.getQuestionType(), questionType))
                .filter(q -> difficulty == null || Objects.equals(q.getDifficulty(), difficulty))
                .collect(Collectors.toList());
    }

    public List<Question> getQuestionsByKnowledgePoint(Long subjectId, String knowledgePoint) {
        List<Question> all = subjectQuestionCache.getOrDefault(subjectId, Collections.emptyList());
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        return all.stream()
                .filter(q -> {
                    String kps = q.getKnowledgePoints();
                    if (kps == null) return false;
                    return Arrays.stream(kps.split(","))
                            .map(String::trim)
                            .anyMatch(kp -> kp.equals(knowledgePoint));
                })
                .collect(Collectors.toList());
    }

    public void buildIndex(Long subjectId, List<Question> questions) {
        Map<Integer, Map<Integer, List<Long>>> typeDifficultyMap = new HashMap<>();
        Map<String, List<Long>> kpMap = new HashMap<>();

        for (Question q : questions) {
            Integer type = q.getQuestionType();
            Integer diff = q.getDifficulty();

            typeDifficultyMap.computeIfAbsent(type, k -> new HashMap<>())
                    .computeIfAbsent(diff, k -> new ArrayList<>())
                    .add(q.getId());

            String kps = q.getKnowledgePoints();
            if (kps != null && !kps.isEmpty()) {
                Arrays.stream(kps.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(kp -> kpMap.computeIfAbsent(kp, k -> new ArrayList<>())
                                .add(q.getId()));
            }
        }

        subjectTypeDifficultyIndex.put(subjectId, typeDifficultyMap);
        subjectKnowledgePointIndex.put(subjectId, kpMap);
        subjectQuestionCache.put(subjectId, new ArrayList<>(questions));

        log.info("科目{}题目索引构建完成，共{}道题", subjectId, questions.size());
    }

    public void invalidateIndex(Long subjectId) {
        subjectTypeDifficultyIndex.remove(subjectId);
        subjectKnowledgePointIndex.remove(subjectId);
        subjectQuestionCache.remove(subjectId);
        log.info("科目{}题目索引已失效", subjectId);
    }

    public boolean hasIndex(Long subjectId) {
        return subjectQuestionCache.containsKey(subjectId)
                && !subjectQuestionCache.get(subjectId).isEmpty();
    }

    public int getQuestionCount(Long subjectId) {
        List<Question> questions = subjectQuestionCache.getOrDefault(subjectId, Collections.emptyList());
        return questions.size();
    }

    public List<Question> getAllQuestions(Long subjectId) {
        List<Question> questions = subjectQuestionCache.get(subjectId);
        return questions != null ? new ArrayList<>(questions) : new ArrayList<>();
    }

    @Scheduled(fixedRate = 300000)
    public void refreshIndexVersion() {
        try {
            String version = String.valueOf(System.currentTimeMillis());
            redisTemplate.opsForValue().set(INDEX_CACHE_KEY, version);
        } catch (Exception e) {
            log.warn("更新索引版本失败", e);
        }
    }
}
