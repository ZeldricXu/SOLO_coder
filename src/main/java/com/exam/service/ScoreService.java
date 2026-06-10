package com.exam.service;

import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.entity.*;
import com.exam.mapper.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreService {

    private final ExamScoreMapper examScoreMapper;
    private final ExamSessionMapper examSessionMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final WrongBookMapper wrongBookMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String PUBLISH_LOCK_PREFIX = "exam:score:publish:lock:";
    private static final int PUBLISH_LOCK_LEASE = 300;

    @Transactional
    public ExamScore publishScore(Long examId, Long studentId, Long operatorId) {
        String lockKey = PUBLISH_LOCK_PREFIX + examId + ":" + studentId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            lock.lock(PUBLISH_LOCK_LEASE, TimeUnit.SECONDS);

            ExamScore existing = examScoreMapper.selectByExamAndStudent(examId, studentId);
            if (existing != null && existing.getPublished() != null && existing.getPublished() == 1) {
                log.info("成绩已发布，跳过幂等处理，examId={}, studentId={}", examId, studentId);
                return existing;
            }

            ExamSession session = examSessionMapper.selectByExamAndStudent(examId, studentId);
            if (session == null) {
                throw new BusinessException("考试会话不存在");
            }
            if (session.getGradingStatus() == null || session.getGradingStatus() < Constants.GRADING_STATUS_COMPLETED) {
                throw new BusinessException("评分未完成，暂不能发布成绩");
            }

            List<ExamAnswer> answers = examAnswerMapper.selectBySessionId(session.getId());

            BigDecimal objectiveScore = BigDecimal.ZERO;
            BigDecimal subjectiveScore = BigDecimal.ZERO;
            BigDecimal programScore = BigDecimal.ZERO;
            BigDecimal totalScore = BigDecimal.ZERO;

            List<Long> wrongQuestionIds = new ArrayList<>();
            Map<String, int[]> kpStats = new HashMap<>();

            for (ExamAnswer answer : answers) {
                PaperQuestion pq = paperQuestionMapper.selectByPaperAndQuestion(
                        session.getPaperId(), answer.getQuestionId());
                BigDecimal qScore = answer.getFinalScore() != null ? answer.getFinalScore()
                        : answer.getStudentScore() != null ? answer.getStudentScore() : BigDecimal.ZERO;
                totalScore = totalScore.add(qScore);

                if (answer.getQuestionType() != null) {
                    if (answer.getQuestionType() <= 4) {
                        objectiveScore = objectiveScore.add(qScore);
                    } else if (answer.getQuestionType() == 5) {
                        subjectiveScore = subjectiveScore.add(qScore);
                    } else if (answer.getQuestionType() == 6) {
                        programScore = programScore.add(qScore);
                    }
                }

                if (pq != null && pq.getQuestionScore() != null
                        && qScore.compareTo(pq.getQuestionScore()) < 0) {
                    wrongQuestionIds.add(answer.getQuestionId());
                }

                if (pq != null && pq.getKnowledgePoints() != null) {
                    String[] kps = pq.getKnowledgePoints().split(",");
                    boolean isCorrect = pq.getQuestionScore() != null
                            && qScore.compareTo(pq.getQuestionScore()) >= 0;
                    for (String kp : kps) {
                        kpStats.computeIfAbsent(kp.trim(), k -> new int[2]);
                        kpStats.get(kp.trim())[0]++;
                        if (isCorrect) {
                            kpStats.get(kp.trim())[1]++;
                        }
                    }
                }
            }

            Map<String, Double> mastery = new HashMap<>();
            for (Map.Entry<String, int[]> entry : kpStats.entrySet()) {
                int[] v = entry.getValue();
                mastery.put(entry.getKey(), v[0] > 0 ? (double) v[1] / v[0] : 0.0);
            }

            ExamScore score = existing != null ? existing : new ExamScore();
            score.setExamId(examId);
            score.setSessionId(session.getId());
            score.setPaperId(session.getPaperId());
            score.setStudentId(studentId);
            score.setSubjectId(session.getPaperId() != null ?
                    questionMapper.selectById(paperQuestionMapper.selectByPaperId(session.getPaperId())
                            .get(0).getQuestionId()).getSubjectId() : null);
            score.setTotalScore(totalScore);
            score.setObjectiveScore(objectiveScore);
            score.setSubjectiveScore(subjectiveScore);
            score.setProgramScore(programScore);

            List<ExamScore> allScores = examScoreMapper.selectByExamId(examId);
            int rank = 1;
            for (ExamScore s : allScores) {
                if (s.getTotalScore() != null && s.getTotalScore().compareTo(totalScore) > 0) {
                    rank++;
                }
            }
            score.setRank(rank);
            score.setPercentile(allScores.size() > 1 ?
                    new BigDecimal(allScores.size() - rank)
                            .divide(new BigDecimal(allScores.size() - 1), 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100")) : new BigDecimal("100"));

            try {
                score.setKnowledgeMastery(objectMapper.writeValueAsString(mastery));
            } catch (Exception e) {
                log.error("序列化知识点掌握度失败", e);
            }
            score.setWrongQuestions(wrongQuestionIds.stream()
                    .map(String::valueOf).collect(Collectors.joining(",")));
            score.setPublishTime(LocalDateTime.now());
            score.setPublished(1);

            if (existing == null) {
                examScoreMapper.insert(score);
            } else {
                examScoreMapper.updateById(score);
            }

            syncWrongBook(studentId, examId, session.getPaperId(), answers);

            log.info("成绩发布成功，examId={}, studentId={}, score={}", examId, studentId, totalScore);
            return score;

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void syncWrongBook(Long studentId, Long examId, Long paperId, List<ExamAnswer> answers) {
        for (ExamAnswer answer : answers) {
            if (answer.getQuestionType() != null && answer.getQuestionType() > 4) continue;

            PaperQuestion pq = paperQuestionMapper.selectByPaperAndQuestion(paperId, answer.getQuestionId());
            if (pq == null || pq.getQuestionScore() == null) continue;

            BigDecimal studentScore = answer.getFinalScore() != null ? answer.getFinalScore()
                    : answer.getStudentScore() != null ? answer.getStudentScore() : BigDecimal.ZERO;

            if (studentScore.compareTo(pq.getQuestionScore()) < 0) {
                WrongBook wb = wrongBookMapper.selectByStudentAndQuestion(studentId, answer.getQuestionId());
                if (wb == null) {
                    wb = new WrongBook();
                    wb.setStudentId(studentId);
                    wb.setSubjectId(pq.getQuestionId());
                    wb.setExamId(examId);
                    wb.setQuestionId(answer.getQuestionId());
                    wb.setWrongCount(1);
                    wb.setMastered(0);
                } else {
                    wb.setWrongCount((wb.getWrongCount() == null ? 0 : wb.getWrongCount()) + 1);
                }
                wb.setStudentAnswer(answer.getStudentAnswer());
                wb.setCorrectAnswer(answer.getCorrectAnswer());
                wb.setLastWrongTime(LocalDateTime.now());

                if (wb.getId() == null) {
                    wrongBookMapper.insert(wb);
                } else {
                    wrongBookMapper.updateById(wb);
                }
            }
        }
    }

    public Map<String, Object> generateReport(Long examId, Long studentId) {
        ExamScore score = examScoreMapper.selectByExamAndStudent(examId, studentId);
        if (score == null) {
            throw new BusinessException("成绩不存在");
        }

        Map<String, Object> report = new HashMap<>();
        report.put("score", score);

        Map<String, Double> mastery = new HashMap<>();
        try {
            if (score.getKnowledgeMastery() != null) {
                mastery = objectMapper.readValue(score.getKnowledgeMastery(),
                        new TypeReference<Map<String, Double>>() {});
            }
        } catch (Exception e) {
            log.error("解析知识点掌握度失败", e);
        }
        report.put("knowledgeMastery", mastery);

        List<ExamAnswer> wrongAnswers = new ArrayList<>();
        if (score.getWrongQuestions() != null && !score.getWrongQuestions().isEmpty()) {
            ExamSession session = examSessionMapper.selectById(score.getSessionId());
            if (session != null) {
                List<ExamAnswer> all = examAnswerMapper.selectBySessionId(session.getId());
                Set<String> wrongSet = new HashSet<>(Arrays.asList(score.getWrongQuestions().split(",")));
                wrongAnswers = all.stream()
                        .filter(a -> wrongSet.contains(String.valueOf(a.getQuestionId())))
                        .collect(Collectors.toList());
            }
        }
        report.put("wrongQuestions", wrongAnswers);

        List<ExamScore> allScores = examScoreMapper.selectByExamId(examId);
        Map<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("0-59", 0);
        distribution.put("60-69", 0);
        distribution.put("70-79", 0);
        distribution.put("80-89", 0);
        distribution.put("90-100", 0);
        for (ExamScore s : allScores) {
            if (s.getTotalScore() == null) continue;
            int v = s.getTotalScore().intValue();
            if (v < 60) distribution.merge("0-59", 1, Integer::sum);
            else if (v < 70) distribution.merge("60-69", 1, Integer::sum);
            else if (v < 80) distribution.merge("70-79", 1, Integer::sum);
            else if (v < 90) distribution.merge("80-89", 1, Integer::sum);
            else distribution.merge("90-100", 1, Integer::sum);
        }
        report.put("scoreDistribution", distribution);

        OptionalDouble avg = allScores.stream()
                .map(ExamScore::getTotalScore)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .average();
        report.put("classAverage", avg.isPresent() ? BigDecimal.valueOf(avg.getAsDouble())
                .setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

        return report;
    }

    public boolean isScorePublished(Long examId, Long studentId) {
        ExamScore score = examScoreMapper.selectByExamAndStudent(examId, studentId);
        return score != null && score.getPublished() != null && score.getPublished() == 1;
    }
}
