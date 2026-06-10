package com.exam.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.entity.*;
import com.exam.mapper.*;
import com.exam.service.ScoreAnalysisService;
import com.exam.vo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreAnalysisServiceImpl implements ScoreAnalysisService {

    private final ExamRecordMapper examRecordMapper;
    private final ExamAnswerMapper examAnswerMapper;
    private final ExamMapper examMapper;
    private final QuestionMapper questionMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final UserMapper userMapper;
    private final KnowledgePointMapper knowledgePointMapper;

    @Override
    public ExamReportVO getExamReport(Long examRecordId) {
        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            throw new BusinessException("考试记录不存在");
        }

        Exam exam = examMapper.selectById(record.getExamId());
        User user = userMapper.selectById(record.getUserId());

        ExamReportVO report = new ExamReportVO();
        report.setExamId(record.getExamId());
        report.setExamName(exam != null ? exam.getExamName() : "");
        report.setExamRecordId(examRecordId);
        report.setUserId(record.getUserId());
        report.setUserName(user != null ? user.getRealName() : "");
        report.setTotalScore(record.getTotalScore());
        report.setObjectiveScore(record.getObjectiveScore());
        report.setSubjectiveScore(record.getSubjectiveScore());
        report.setProgrammingScore(record.getProgrammingScore());
        report.setFinalScore(record.getFinalScore());
        report.setPassScore(exam != null ? BigDecimal.valueOf(exam.getPassScore()) : BigDecimal.ZERO);
        report.setIsPass(record.getIsPass() != null && record.getIsPass() == 1);
        report.setUsedTime(record.getUsedTime());
        report.setDuration(record.getDuration());
        report.setStartTime(record.getStartTime());
        report.setSubmitTime(record.getSubmitTime());
        report.setAbnormalCount(record.getAbnormalCount());
        report.setScreenSwitchCount(record.getScreenSwitchCount());

        List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(examRecordId);
        report.setTotalQuestions(answers.size());

        int correctCount = 0;
        int wrongCount = 0;
        int unansweredCount = 0;

        List<QuestionReportVO> questionReports = new ArrayList<>();
        for (ExamAnswer answer : answers) {
            QuestionReportVO qr = new QuestionReportVO();
            qr.setQuestionId(answer.getQuestionId());
            qr.setQuestionType(answer.getQuestionType());
            qr.setQuestionTypeText(getQuestionTypeText(answer.getQuestionType()));
            qr.setQuestionScore(answer.getQuestionScore());
            qr.setScore(answer.getScore());
            qr.setIsCorrect(answer.getIsCorrect() != null && answer.getIsCorrect() == 1);
            qr.setUserAnswer(answer.getUserAnswer());
            qr.setCorrectAnswer(answer.getCorrectAnswer());
            qr.setQuestionOrder(answer.getQuestionOrder());

            Question question = questionMapper.selectById(answer.getQuestionId());
            if (question != null) {
                qr.setCorrectAnswer(question.getAnswer());
                qr.setAnalysis(question.getAnalysis());
            }

            questionReports.add(qr);

            if (answer.getIsCorrect() != null && answer.getIsCorrect() == 1) {
                correctCount++;
            } else if (answer.getAnswerStatus() != null
                    && answer.getAnswerStatus().equals(Constants.ANSWER_STATUS_NOT_ANSWERED)) {
                unansweredCount++;
            } else {
                wrongCount++;
            }
        }

        report.setQuestionReports(questionReports);
        report.setCorrectCount(correctCount);
        report.setWrongCount(wrongCount);
        report.setUnansweredCount(unansweredCount);

        if (!answers.isEmpty()) {
            BigDecimal accuracy = BigDecimal.valueOf(correctCount)
                    .divide(BigDecimal.valueOf(answers.size()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            report.setAccuracy(accuracy);
        }

        Long totalCandidates = examRecordMapper.selectCount(
                new LambdaQueryWrapper<ExamRecord>().eq(ExamRecord::getExamId, record.getExamId()));
        report.setTotalCandidates(totalCandidates.intValue());

        List<ExamRecord> records = examRecordMapper.selectList(
                new LambdaQueryWrapper<ExamRecord>()
                        .eq(ExamRecord::getExamId, record.getExamId())
                        .orderByDesc(ExamRecord::getTotalScore));

        int rank = 1;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getId().equals(examRecordId)) {
                rank = i + 1;
                break;
            }
        }
        report.setRank(rank);

        List<KnowledgePointScoreVO> knowledgeScores = calculateKnowledgePointScores(answers, record.getExamId());
        report.setKnowledgePointScores(knowledgeScores);

        return report;
    }

    @Override
    public ExamStatisticsVO getExamStatistics(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException("考试不存在");
        }

        List<ExamRecord> records = examRecordMapper.selectList(
                new LambdaQueryWrapper<ExamRecord>()
                        .eq(ExamRecord::getExamId, examId)
                        .eq(ExamRecord::getGradingStatus, Constants.GRADING_STATUS_COMPLETED));

        ExamStatisticsVO statistics = new ExamStatisticsVO();
        statistics.setExamId(examId);
        statistics.setExamName(exam.getExamName());
        statistics.setTotalCandidates(exam.getTotalCandidates());
        statistics.setSubmittedCount(exam.getSubmittedCount());
        statistics.setGradedCount(records.size());

        if (CollUtil.isNotEmpty(records)) {
            List<BigDecimal> scores = records.stream()
                    .map(r -> r.getTotalScore() != null ? r.getTotalScore() : BigDecimal.ZERO)
                    .sorted()
                    .collect(Collectors.toList());

            int passCount = 0;
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal maxScore = BigDecimal.ZERO;
            BigDecimal minScore = new BigDecimal("9999");

            for (BigDecimal score : scores) {
                sum = sum.add(score);
                if (score.compareTo(BigDecimal.valueOf(exam.getPassScore())) >= 0) {
                    passCount++;
                }
                if (score.compareTo(maxScore) > 0) {
                    maxScore = score;
                }
                if (score.compareTo(minScore) < 0) {
                    minScore = score;
                }
            }

            statistics.setPassCount(passCount);
            statistics.setPassRate(BigDecimal.valueOf(passCount)
                    .divide(BigDecimal.valueOf(scores.size()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)));

            statistics.setAvgScore(sum.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP));
            statistics.setMaxScore(maxScore);
            statistics.setMinScore(minScore);

            int mid = scores.size() / 2;
            if (scores.size() % 2 == 0) {
                statistics.setMedianScore(scores.get(mid - 1).add(scores.get(mid))
                        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP));
            } else {
                statistics.setMedianScore(scores.get(mid));
            }

            BigDecimal variance = BigDecimal.ZERO;
            for (BigDecimal score : scores) {
                BigDecimal diff = score.subtract(statistics.getAvgScore());
                variance = variance.add(diff.multiply(diff));
            }
            variance = variance.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP);
            statistics.setStandardDeviation(BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                    .setScale(2, RoundingMode.HALF_UP));
        }

        return statistics;
    }

    @Override
    public KnowledgeRadarVO getKnowledgeRadar(Long examRecordId) {
        ExamRecord record = examRecordMapper.selectById(examRecordId);
        if (record == null) {
            throw new BusinessException("考试记录不存在");
        }

        List<ExamAnswer> answers = examAnswerMapper.selectByExamRecordId(examRecordId);
        List<KnowledgePointScoreVO> knowledgeScores = calculateKnowledgePointScores(answers, record.getExamId());

        KnowledgeRadarVO radar = new KnowledgeRadarVO();
        List<String> labels = new ArrayList<>();
        List<BigDecimal> scores = new ArrayList<>();
        List<BigDecimal> maxScores = new ArrayList<>();
        List<BigDecimal> classAvgScores = new ArrayList<>();

        for (KnowledgePointScoreVO kp : knowledgeScores) {
            labels.add(kp.getKnowledgePointName());
            scores.add(kp.getScore());
            maxScores.add(kp.getTotalScore());
            classAvgScores.add(kp.getTotalScore().multiply(BigDecimal.valueOf(0.7)));
        }

        radar.setLabels(labels);
        radar.setScores(scores);
        radar.setMaxScores(maxScores);
        radar.setClassAvgScores(classAvgScores);

        return radar;
    }

    @Override
    public List<ScoreDistributionVO> getScoreDistribution(Long examId) {
        List<ExamRecord> records = examRecordMapper.selectList(
                new LambdaQueryWrapper<ExamRecord>()
                        .eq(ExamRecord::getExamId, examId)
                        .eq(ExamRecord::getGradingStatus, Constants.GRADING_STATUS_COMPLETED));

        int[] ranges = {0, 60, 70, 80, 90, 100};
        String[] rangeNames = {"0-59", "60-69", "70-79", "80-89", "90-100"};

        int[] counts = new int[5];

        for (ExamRecord record : records) {
            BigDecimal score = record.getTotalScore() != null ? record.getTotalScore() : BigDecimal.ZERO;
            int scoreInt = score.intValue();

            if (scoreInt < 60) {
                counts[0]++;
            } else if (scoreInt < 70) {
                counts[1]++;
            } else if (scoreInt < 80) {
                counts[2]++;
            } else if (scoreInt < 90) {
                counts[3]++;
            } else {
                counts[4]++;
            }
        }

        List<ScoreDistributionVO> result = new ArrayList<>();
        int total = records.size();

        for (int i = 0; i < rangeNames.length; i++) {
            ScoreDistributionVO vo = new ScoreDistributionVO();
            vo.setScoreRange(rangeNames[i]);
            vo.setCount(counts[i]);
            if (total > 0) {
                vo.setPercentage(BigDecimal.valueOf(counts[i])
                        .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)));
            } else {
                vo.setPercentage(BigDecimal.ZERO);
            }
            result.add(vo);
        }

        return result;
    }

    @Override
    public PersonalScoreVO getPersonalScoreSummary(Long userId, Long subjectId) {
        List<ExamRecord> records = examRecordMapper.selectList(
                new LambdaQueryWrapper<ExamRecord>()
                        .eq(ExamRecord::getUserId, userId)
                        .eq(ExamRecord::getGradingStatus, Constants.GRADING_STATUS_COMPLETED)
                        .orderByDesc(ExamRecord::getSubmitTime));

        PersonalScoreVO vo = new PersonalScoreVO();
        vo.setUserId(userId);

        User user = userMapper.selectById(userId);
        if (user != null) {
            vo.setUserName(user.getRealName());
        }

        vo.setTotalExamCount(records.size());

        if (CollUtil.isNotEmpty(records)) {
            int passCount = 0;
            BigDecimal sumScore = BigDecimal.ZERO;
            BigDecimal maxScore = BigDecimal.ZERO;
            BigDecimal minScore = new BigDecimal("9999");

            List<ExamScoreVO> examScores = new ArrayList<>();

            for (ExamRecord record : records) {
                Exam exam = examMapper.selectById(record.getExamId());

                ExamScoreVO es = new ExamScoreVO();
                es.setExamId(record.getExamId());
                es.setExamName(exam != null ? exam.getExamName() : "");
                es.setScore(record.getTotalScore());
                es.setIsPass(record.getIsPass() != null && record.getIsPass() == 1);
                es.setExamTime(record.getSubmitTime());
                examScores.add(es);

                if (record.getIsPass() != null && record.getIsPass() == 1) {
                    passCount++;
                }

                BigDecimal score = record.getTotalScore() != null ? record.getTotalScore() : BigDecimal.ZERO;
                sumScore = sumScore.add(score);

                if (score.compareTo(maxScore) > 0) {
                    maxScore = score;
                }
                if (score.compareTo(minScore) < 0) {
                    minScore = score;
                }
            }

            vo.setPassCount(passCount);
            vo.setPassRate(BigDecimal.valueOf(passCount)
                    .divide(BigDecimal.valueOf(records.size()), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)));
            vo.setAvgScore(sumScore.divide(BigDecimal.valueOf(records.size()), 2, RoundingMode.HALF_UP));
            vo.setHighestScore(maxScore);
            vo.setLowestScore(minScore);
            vo.setExamScores(examScores);
        }

        return vo;
    }

    @Override
    public ClassScoreVO getClassScore(Long classId, Long examId) {
        return null;
    }

    @Override
    public byte[] generateReportPdf(Long examRecordId) {
        return new byte[0];
    }

    private String getQuestionTypeText(Integer type) {
        if (type == null) {
            return "未知";
        }
        switch (type) {
            case 1:
                return "单选题";
            case 2:
                return "多选题";
            case 3:
                return "判断题";
            case 4:
                return "填空题";
            case 5:
                return "简答题";
            case 6:
                return "编程题";
            default:
                return "未知";
        }
    }

    private List<KnowledgePointScoreVO> calculateKnowledgePointScores(List<ExamAnswer> answers, Long examId) {
        Map<Long, KnowledgePointScoreVO> scoreMap = new HashMap<>();

        for (ExamAnswer answer : answers) {
            Question question = questionMapper.selectById(answer.getQuestionId());
            if (question == null || question.getKnowledgePointIds() == null) {
                continue;
            }

            String[] kpIds = question.getKnowledgePointIds().split(",");
            if (kpIds.length == 0) {
                continue;
            }

            BigDecimal scorePerKp = answer.getScore() != null ?
                    answer.getScore().divide(BigDecimal.valueOf(kpIds.length), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal totalPerKp = answer.getQuestionScore() != null ?
                    answer.getQuestionScore().divide(BigDecimal.valueOf(kpIds.length), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

            for (String kpIdStr : kpIds) {
                try {
                    Long kpId = Long.parseLong(kpIdStr.trim());

                    KnowledgePointScoreVO vo = scoreMap.get(kpId);
                    if (vo == null) {
                        vo = new KnowledgePointScoreVO();
                        vo.setKnowledgePointId(kpId);
                        vo.setScore(BigDecimal.ZERO);
                        vo.setTotalScore(BigDecimal.ZERO);
                        vo.setQuestionCount(0);
                        vo.setCorrectCount(0);

                        KnowledgePoint kp = knowledgePointMapper.selectById(kpId);
                        if (kp != null) {
                            vo.setKnowledgePointName(kp.getPointName());
                        }

                        scoreMap.put(kpId, vo);
                    }

                    vo.setScore(vo.getScore().add(scorePerKp));
                    vo.setTotalScore(vo.getTotalScore().add(totalPerKp));
                    vo.setQuestionCount(vo.getQuestionCount() + 1);

                    if (answer.getIsCorrect() != null && answer.getIsCorrect() == 1) {
                        vo.setCorrectCount(vo.getCorrectCount() + 1);
                    }

                    if (vo.getTotalScore().compareTo(BigDecimal.ZERO) > 0) {
                        vo.setAccuracy(vo.getScore()
                                .divide(vo.getTotalScore(), 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100)));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return new ArrayList<>(scoreMap.values());
    }
}
