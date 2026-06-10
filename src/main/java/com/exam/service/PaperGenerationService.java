package com.exam.service;

import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.common.ResultCode;
import com.exam.entity.*;
import com.exam.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperGenerationService {

    private final QuestionMapper questionMapper;
    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final PaperTemplateMapper paperTemplateMapper;
    private final RedissonClient redissonClient;

    private static final String PAPER_GENERATION_LOCK_PREFIX = "exam:paper:generation:lock:";
    private static final int LOCK_WAIT_TIME = 10;
    private static final int LOCK_LEASE_TIME = 120;

    private final Set<String> recentlyUsedQuestionIds = Collections.synchronizedSet(new LinkedHashSet<String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 1000;
        }
    }.keySet());

    @Transactional
    public Paper generatePaper(PaperTemplate template, String paperName, Long userId) {
        String lockKey = PAPER_GENERATION_LOCK_PREFIX + template.getSubjectId() + ":" + Thread.currentThread().getId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS)) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            Paper paper = createPaperBasic(template, paperName, userId);

            List<PaperQuestion> paperQuestions = generateQuestions(template, paper);
            if (paperQuestions.isEmpty()) {
                throw new BusinessException(ResultCode.PAPER_GENERATE_ERROR);
            }

            paper.setQuestionCount(paperQuestions.size());
            paperMapper.insert(paper);

            for (PaperQuestion pq : paperQuestions) {
                pq.setPaperId(paper.getId());
                paperQuestionMapper.insert(pq);
            }

            recordUsedQuestions(paperQuestions);
            return paper;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("组卷被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Paper createPaperBasic(PaperTemplate template, String paperName, Long userId) {
        Paper paper = new Paper();
        paper.setPaperName(paperName);
        paper.setSubjectId(template.getSubjectId());
        paper.setTemplateId(template.getId());
        paper.setPaperMode(template.getPaperMode());
        paper.setPaperVersion(UUID.randomUUID().toString().substring(0, 8));
        paper.setAbType(1);
        paper.setTotalScore(template.getTotalScore());
        paper.setTotalMinutes(template.getTotalMinutes());
        paper.setStatus(0);
        paper.setCreateBy(userId);
        return paper;
    }

    public List<PaperQuestion> generateQuestions(PaperTemplate template, Paper paper) {
        List<PaperQuestion> result = new ArrayList<>();
        int order = 1;

        GenerationResult singleResult = generateByType(template, Constants.QUESTION_TYPE_SINGLE,
                template.getSingleCount(), template.getSingleScore(), order);
        result.addAll(singleResult.questions);
        order += singleResult.questions.size();

        GenerationResult multipleResult = generateByType(template, Constants.QUESTION_TYPE_MULTIPLE,
                template.getMultipleCount(), template.getMultipleScore(), order);
        result.addAll(multipleResult.questions);
        order += multipleResult.questions.size();

        GenerationResult judgeResult = generateByType(template, Constants.QUESTION_TYPE_JUDGE,
                template.getJudgeCount(), template.getJudgeScore(), order);
        result.addAll(judgeResult.questions);
        order += judgeResult.questions.size();

        GenerationResult fillResult = generateByType(template, Constants.QUESTION_TYPE_FILL,
                template.getFillCount(), template.getFillScore(), order);
        result.addAll(fillResult.questions);
        order += fillResult.questions.size();

        GenerationResult shortResult = generateByType(template, Constants.QUESTION_TYPE_SHORT,
                template.getShortCount(), template.getShortScore(), order);
        result.addAll(shortResult.questions);
        order += shortResult.questions.size();

        GenerationResult programResult = generateByType(template, Constants.QUESTION_TYPE_PROGRAM,
                template.getProgramCount(), template.getProgramScore(), order);
        result.addAll(programResult.questions);

        List<String> warnings = new ArrayList<>();
        warnings.addAll(singleResult.warnings);
        warnings.addAll(multipleResult.warnings);
        warnings.addAll(judgeResult.warnings);
        warnings.addAll(fillResult.warnings);
        warnings.addAll(shortResult.warnings);
        warnings.addAll(programResult.warnings);

        if (!warnings.isEmpty()) {
            log.warn("组卷降级提示: {}", String.join("; ", warnings));
        }

        return result;
    }

    private GenerationResult generateByType(PaperTemplate template, Integer questionType,
                                            Integer targetCount, BigDecimal score, int startOrder) {
        GenerationResult result = new GenerationResult();
        if (targetCount == null || targetCount <= 0) {
            return result;
        }

        BigDecimal easyRatio = template.getEasyRatio() != null ? template.getEasyRatio() : new BigDecimal("0.3");
        BigDecimal mediumRatio = template.getMediumRatio() != null ? template.getMediumRatio() : new BigDecimal("0.5");
        BigDecimal hardRatio = template.getHardRatio() != null ? template.getHardRatio() : new BigDecimal("0.2");

        int easyCount = new BigDecimal(targetCount).multiply(easyRatio).setScale(0, RoundingMode.HALF_UP).intValue();
        int mediumCount = new BigDecimal(targetCount).multiply(mediumRatio).setScale(0, RoundingMode.HALF_UP).intValue();
        int hardCount = targetCount - easyCount - mediumCount;

        int currentOrder = startOrder;

        List<Question> easyQuestions = selectQuestionsWithFallback(
                template.getSubjectId(), questionType, Constants.DIFFICULTY_EASY, easyCount);
        for (Question q : easyQuestions) {
            result.questions.add(toPaperQuestion(q, score, currentOrder++));
        }
        if (easyQuestions.size() < easyCount) {
            result.warnings.add(String.format("题型%d简单题不足，需要%d实际%d",
                    questionType, easyCount, easyQuestions.size()));
        }

        int remaining = targetCount - easyQuestions.size();
        int mediumTarget = Math.min(mediumCount, remaining);
        List<Question> mediumQuestions = selectQuestionsWithFallback(
                template.getSubjectId(), questionType, Constants.DIFFICULTY_MEDIUM, mediumTarget);
        for (Question q : mediumQuestions) {
            result.questions.add(toPaperQuestion(q, score, currentOrder++));
        }
        if (mediumQuestions.size() < mediumTarget) {
            result.warnings.add(String.format("题型%d中等题不足，需要%d实际%d",
                    questionType, mediumTarget, mediumQuestions.size()));
        }

        remaining = targetCount - easyQuestions.size() - mediumQuestions.size();
        if (remaining > 0) {
            List<Question> hardQuestions = selectQuestionsWithFallback(
                    template.getSubjectId(), questionType, Constants.DIFFICULTY_HARD, remaining);
            for (Question q : hardQuestions) {
                result.questions.add(toPaperQuestion(q, score, currentOrder++));
            }
            if (hardQuestions.size() < remaining) {
                result.warnings.add(String.format("题型%d困难题不足，需要%d实际%d",
                        questionType, remaining, hardQuestions.size()));
            }
        }

        if (result.questions.size() < targetCount) {
            int stillNeed = targetCount - result.questions.size();
            result.warnings.add(String.format("题型%d知识点覆盖不全，题库总量不足%d，降级按题型随机抽取%d题补充",
                    questionType, targetCount, result.questions.size()));

            List<Question> fallbackQuestions = questionMapper.selectRandomQuestionsByType(
                    template.getSubjectId(), questionType, stillNeed + 50);
            Set<Long> existingIds = result.questions.stream()
                    .map(PaperQuestion::getQuestionId)
                    .collect(Collectors.toSet());

            for (Question q : fallbackQuestions) {
                if (existingIds.contains(q.getId())) continue;
                if (isRecentlyUsed(q.getId())) continue;
                result.questions.add(toPaperQuestion(q, score, currentOrder++));
                if (result.questions.size() >= targetCount) break;
            }
        }

        Collections.shuffle(result.questions);
        for (int i = 0; i < result.questions.size(); i++) {
            result.questions.get(i).setQuestionOrder(startOrder + i);
        }

        return result;
    }

    private List<Question> selectQuestionsWithFallback(Long subjectId, Integer questionType,
                                                       Integer difficulty, int count) {
        if (count <= 0) return new ArrayList<>();

        int available = questionMapper.countByTypeAndDifficulty(subjectId, questionType, difficulty);
        int targetCount = Math.min(count, available);
        if (targetCount <= 0) return new ArrayList<>();

        List<Question> selected = new ArrayList<>();
        Set<Long> usedInSession = new HashSet<>();

        List<Question> candidates = questionMapper.selectRandomQuestions(
                subjectId, questionType, difficulty, targetCount * 3);

        for (Question q : candidates) {
            if (selected.size() >= targetCount) break;
            if (usedInSession.contains(q.getId())) continue;
            if (isRecentlyUsed(q.getId())) continue;
            selected.add(q);
            usedInSession.add(q.getId());
        }

        if (selected.size() < targetCount) {
            for (Question q : candidates) {
                if (selected.size() >= targetCount) break;
                if (usedInSession.contains(q.getId())) continue;
                selected.add(q);
                usedInSession.add(q.getId());
            }
        }

        return selected;
    }

    private PaperQuestion toPaperQuestion(Question q, BigDecimal score, int order) {
        PaperQuestion pq = new PaperQuestion();
        pq.setQuestionId(q.getId());
        pq.setQuestionOrder(order);
        pq.setQuestionType(q.getQuestionType());
        pq.setQuestionScore(score != null ? score : q.getScore());
        pq.setDifficulty(q.getDifficulty());
        pq.setKnowledgePoints(q.getKnowledgePoints());
        return pq;
    }

    private boolean isRecentlyUsed(Long questionId) {
        return recentlyUsedQuestionIds.contains(String.valueOf(questionId));
    }

    private void recordUsedQuestions(List<PaperQuestion> questions) {
        for (PaperQuestion pq : questions) {
            recentlyUsedQuestionIds.add(String.valueOf(pq.getQuestionId()));
        }
    }

    public boolean validateNoDuplicates(List<Paper> papers) {
        if (papers == null || papers.size() < 2) return true;

        Set<Long> allQuestionIds = new HashSet<>();
        for (Paper paper : papers) {
            List<PaperQuestion> questions = paperQuestionMapper.selectByPaperId(paper.getId());
            for (PaperQuestion pq : questions) {
                if (allQuestionIds.contains(pq.getQuestionId())) {
                    return false;
                }
                allQuestionIds.add(pq.getQuestionId());
            }
        }
        return true;
    }

    @Transactional
    public Paper[] generateABPaper(PaperTemplate template, String baseName, Long userId) {
        Paper paperA = generatePaper(template, baseName + "-A卷", userId);
        paperA.setAbType(1);
        paperMapper.updateById(paperA);

        Paper paperB = generatePaper(template, baseName + "-B卷", userId);
        paperB.setAbType(2);
        paperMapper.updateById(paperB);

        return new Paper[]{paperA, paperB};
    }

    private static class GenerationResult {
        List<PaperQuestion> questions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
    }
}
