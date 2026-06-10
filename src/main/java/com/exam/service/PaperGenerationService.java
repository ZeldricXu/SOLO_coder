package com.exam.service;

import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.common.ResultCode;
import com.exam.entity.*;
import com.exam.mapper.*;
import com.exam.service.constraint.*;
import com.exam.service.index.QuestionInvertedIndex;
import com.exam.service.sampler.WeightedReservoirSampler;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PaperGenerationService {

    private final QuestionMapper questionMapper;
    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final PaperTemplateMapper paperTemplateMapper;
    private final RedissonClient redissonClient;
    private final QuestionInvertedIndex questionInvertedIndex;
    private final WeightedReservoirSampler weightedSampler;

    public PaperGenerationService(QuestionMapper questionMapper, PaperMapper paperMapper,
                                  PaperQuestionMapper paperQuestionMapper, PaperTemplateMapper paperTemplateMapper,
                                  RedissonClient redissonClient) {
        this(questionMapper, paperMapper, paperQuestionMapper, paperTemplateMapper, redissonClient,
                new QuestionInvertedIndex(null), new WeightedReservoirSampler());
    }

    public PaperGenerationService(QuestionMapper questionMapper, PaperMapper paperMapper,
                                  PaperQuestionMapper paperQuestionMapper, PaperTemplateMapper paperTemplateMapper,
                                  RedissonClient redissonClient, QuestionInvertedIndex questionInvertedIndex,
                                  WeightedReservoirSampler weightedSampler) {
        this.questionMapper = questionMapper;
        this.paperMapper = paperMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.paperTemplateMapper = paperTemplateMapper;
        this.redissonClient = redissonClient;
        this.questionInvertedIndex = questionInvertedIndex != null ? questionInvertedIndex : new QuestionInvertedIndex(null);
        this.weightedSampler = weightedSampler != null ? weightedSampler : new WeightedReservoirSampler();
    }

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
        List<String> allWarnings = new ArrayList<>();
        int order = 1;

        for (QuestionTypeConfig cfg : buildTypeConfigs(template)) {
            GenerationResult r = generateByTypeWithConstraint(
                    template, cfg.type, cfg.count, cfg.score, order);
            result.addAll(r.questions);
            order += r.questions.size();
            allWarnings.addAll(r.warnings);
        }

        if (!allWarnings.isEmpty()) {
            log.warn("组卷降级提示: {}", String.join("; ", allWarnings));
        }

        return result;
    }

    private List<QuestionTypeConfig> buildTypeConfigs(PaperTemplate template) {
        List<QuestionTypeConfig> configs = new ArrayList<>();
        configs.add(new QuestionTypeConfig(Constants.QUESTION_TYPE_SINGLE,
                template.getSingleCount(), template.getSingleScore()));
        configs.add(new QuestionTypeConfig(Constants.QUESTION_TYPE_MULTIPLE,
                template.getMultipleCount(), template.getMultipleScore()));
        configs.add(new QuestionTypeConfig(Constants.QUESTION_TYPE_JUDGE,
                template.getJudgeCount(), template.getJudgeScore()));
        configs.add(new QuestionTypeConfig(Constants.QUESTION_TYPE_FILL,
                template.getFillCount(), template.getFillScore()));
        configs.add(new QuestionTypeConfig(Constants.QUESTION_TYPE_SHORT,
                template.getShortCount(), template.getShortScore()));
        configs.add(new QuestionTypeConfig(Constants.QUESTION_TYPE_PROGRAM,
                template.getProgramCount(), template.getProgramScore()));
        return configs;
    }

    private GenerationResult generateByTypeWithConstraint(PaperTemplate template,
                                                          Integer questionType,
                                                          Integer targetCount,
                                                          BigDecimal score,
                                                          int startOrder) {
        GenerationResult result = new GenerationResult();
        if (targetCount == null || targetCount <= 0) {
            return result;
        }

        Long subjectId = template.getSubjectId();
        boolean useIndex = questionInvertedIndex.hasIndex(subjectId);

        List<Question> candidates;
        if (useIndex) {
            candidates = questionInvertedIndex.getQuestionsByTypeAndDifficulty(
                    subjectId, questionType, null);
        } else {
            candidates = questionMapper.selectRandomQuestionsByType(
                    subjectId, questionType, targetCount * 10);
        }

        if (candidates.isEmpty()) {
            result.warnings.add(String.format("题型%d题库为空", questionType));
            return result;
        }

        ConstraintChain chain = buildConstraintChain(template, questionType);
        SelectionContext context = new SelectionContext(subjectId, targetCount);

        context.addExcludedQuestionIds(getRecentlyUsedIds());
        context.setAttribute("questionType", questionType);

        List<Question> selected = selectWithConstraint(candidates, targetCount, chain, context);

        if (selected.size() < targetCount) {
            int stillNeed = targetCount - selected.size();
            result.warnings.add(String.format(
                    "题型%d约束求解后不足，需要%d实际%d，降级随机补充%d题",
                    questionType, targetCount, selected.size(), stillNeed));

            Set<Long> existingIds = selected.stream()
                    .map(Question::getId)
                    .collect(Collectors.toSet());

            for (Question q : candidates) {
                if (existingIds.contains(q.getId())) continue;
                if (context.getExcludedQuestionIds().contains(q.getId())) continue;
                selected.add(q);
                existingIds.add(q.getId());
                context.addSelected(q);
                chain.notifySelected(q, context);
                if (selected.size() >= targetCount) break;
            }
        }

        Collections.shuffle(selected);
        for (int i = 0; i < selected.size(); i++) {
            result.questions.add(toPaperQuestion(selected.get(i), score, startOrder + i));
        }

        return result;
    }

    private ConstraintChain buildConstraintChain(PaperTemplate template, Integer questionType) {
        ConstraintChain chain = new ConstraintChain();

        chain.addSolver(new ExcludedQuestionConstraint(Collections.emptySet()));

        chain.addSolver(new DifficultyConstraint(
                template.getEasyRatio(),
                template.getMediumRatio(),
                template.getHardRatio(),
                questionType
        ));

        return chain;
    }

    private List<Question> selectWithConstraint(List<Question> candidates, int targetCount,
                                                ConstraintChain chain, SelectionContext context) {
        List<Question> pool = new ArrayList<>();
        for (Question q : candidates) {
            if (chain.checkAll(q, context)) {
                pool.add(q);
            }
        }

        WeightedReservoirSampler.WeightFunction weightFunc = q -> {
            double base = 1.0;
            if (q.getDifficulty() != null) {
                base += 0.1 * (3 - q.getDifficulty());
            }
            return Math.max(0.1, base);
        };

        List<Question> sampled = weightedSampler.sample(pool, targetCount, weightFunc);

        for (Question q : sampled) {
            context.addSelected(q);
            chain.notifySelected(q, context);
        }

        return sampled;
    }

    private Set<Long> getRecentlyUsedIds() {
        Set<Long> ids = new HashSet<>();
        synchronized (recentlyUsedQuestionIds) {
            for (String idStr : recentlyUsedQuestionIds) {
                try {
                    ids.add(Long.parseLong(idStr));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ids;
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

    public void addCustomConstraint(Long subjectId, ConstraintSolver constraint) {
        log.info("为科目{}添加约束: {}", subjectId, constraint.getName());
    }

    public void refreshQuestionIndex(Long subjectId, List<Question> questions) {
        questionInvertedIndex.buildIndex(subjectId, questions);
    }

    private static class GenerationResult {
        List<PaperQuestion> questions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
    }

    private static class QuestionTypeConfig {
        final Integer type;
        final Integer count;
        final BigDecimal score;

        QuestionTypeConfig(Integer type, Integer count, BigDecimal score) {
            this.type = type;
            this.count = count;
            this.score = score;
        }
    }
}
