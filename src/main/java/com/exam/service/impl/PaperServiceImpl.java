package com.exam.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.common.ResultCode;
import com.exam.dto.PaperGenerateDTO;
import com.exam.entity.Paper;
import com.exam.entity.PaperQuestion;
import com.exam.entity.Question;
import com.exam.mapper.PaperMapper;
import com.exam.mapper.PaperQuestionMapper;
import com.exam.mapper.QuestionMapper;
import com.exam.service.PaperService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperServiceImpl implements PaperService {

    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;

    @Override
    public IPage<Paper> getPaperPage(int pageNum, int pageSize, Long subjectId, String keyword) {
        LambdaQueryWrapper<Paper> wrapper = new LambdaQueryWrapper<>();
        if (subjectId != null) {
            wrapper.eq(Paper::getSubjectId, subjectId);
        }
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.like(Paper::getPaperName, keyword);
        }
        wrapper.orderByDesc(Paper::getCreateTime);

        Page<Paper> page = new Page<>(pageNum, pageSize);
        return paperMapper.selectPage(page, wrapper);
    }

    @Override
    public Paper getPaperById(Long id) {
        Paper paper = paperMapper.selectById(id);
        if (paper == null) {
            throw new BusinessException(ResultCode.PAPER_NOT_FOUND);
        }
        return paper;
    }

    @Override
    public List<PaperQuestion> getPaperQuestions(Long paperId) {
        return paperQuestionMapper.selectByPaperId(paperId);
    }

    @Override
    @Transactional
    public Paper generatePaper(PaperGenerateDTO generateDTO) {
        if (generateDTO.getPaperMode().equals(Constants.PAPER_MODE_RANDOM)) {
            return generateRandomPaper(generateDTO);
        } else {
            throw new BusinessException("固定卷请使用createFixedPaper方法");
        }
    }

    @Override
    @Transactional
    public Paper generateRandomPaper(PaperGenerateDTO generateDTO) {
        validateGenerateDTO(generateDTO);

        List<PaperGenerateDTO.QuestionTypeConfig> typeConfigs = generateDTO.getQuestionTypeConfigs();
        if (CollUtil.isEmpty(typeConfigs)) {
            throw new BusinessException("请配置题型");
        }

        Paper paper = new Paper();
        paper.setPaperName(generateDTO.getPaperName());
        paper.setSubjectId(generateDTO.getSubjectId());
        paper.setPaperMode(Constants.PAPER_MODE_RANDOM);
        paper.setDuration(generateDTO.getDuration());
        paper.setTotalScore(generateDTO.getTotalScore());
        paper.setPassScore(generateDTO.getPassScore());
        paper.setPaperVersion(1);
        paper.setStatus(1);
        paper.setTotalQuestions(generateDTO.getTotalQuestions());
        paperMapper.insert(paper);

        int sortOrder = 1;
        BigDecimal totalScore = BigDecimal.ZERO;
        int totalQuestions = 0;
        List<Long> questionIds = new ArrayList<>();

        for (PaperGenerateDTO.QuestionTypeConfig typeConfig : typeConfigs) {
            List<Question> selectedQuestions = selectQuestionsByType(
                    generateDTO.getSubjectId(),
                    typeConfig.getQuestionType(),
                    typeConfig.getQuestionCount(),
                    generateDTO.getDifficultyConfig(),
                    generateDTO.getKnowledgePointConfig()
            );

            if (selectedQuestions.size() < typeConfig.getQuestionCount()) {
                throw new BusinessException("题型" + typeConfig.getQuestionType() + "可用题目不足，需要"
                        + typeConfig.getQuestionCount() + "道，实际只有" + selectedQuestions.size() + "道");
            }

            for (Question question : selectedQuestions) {
                PaperQuestion pq = new PaperQuestion();
                pq.setPaperId(paper.getId());
                pq.setQuestionId(question.getId());
                pq.setQuestionType(question.getQuestionType());
                pq.setQuestionOrder(sortOrder);
                pq.setScore(typeConfig.getScorePerQuestion());
                pq.setSortOrder(sortOrder);
                paperQuestionMapper.insert(pq);

                questionIds.add(question.getId());
                totalScore = totalScore.add(typeConfig.getScorePerQuestion());
                sortOrder++;
                totalQuestions++;
            }
        }

        paper.setTotalScore(totalScore.intValue());
        paper.setTotalQuestions(totalQuestions);

        BigDecimal difficultyAvg = calculateDifficultyAvg(questionIds);
        paper.setDifficultyAvg(difficultyAvg);

        paper.setQuestionIds(questionIds.stream().map(String::valueOf).collect(Collectors.joining(",")));

        paperMapper.updateById(paper);

        log.info("随机卷生成成功: paperId={}, totalQuestions={}", paper.getId(), totalQuestions);

        return paper;
    }

    @Override
    @Transactional
    public Map<String, Paper> generateABPaper(PaperGenerateDTO generateDTO) {
        Paper paperA = generateRandomPaper(generateDTO);
        paperA.setPaperName(generateDTO.getPaperName() + "-A卷");
        paperMapper.updateById(paperA);

        Paper paperB = generateRandomPaper(generateDTO);
        paperB.setPaperName(generateDTO.getPaperName() + "-B卷");
        paperMapper.updateById(paperB);

        Map<String, Paper> result = new HashMap<>();
        result.put("A", paperA);
        result.put("B", paperB);

        log.info("AB卷生成成功: paperA={}, paperB={}", paperA.getId(), paperB.getId());

        return result;
    }

    @Override
    @Transactional
    public Paper createFixedPaper(Paper paper, List<Long> questionIds) {
        paper.setPaperMode(Constants.PAPER_MODE_FIXED);
        paper.setPaperVersion(1);
        paper.setStatus(1);
        if (paper.getTotalQuestions() == null) {
            paper.setTotalQuestions(questionIds.size());
        }
        paperMapper.insert(paper);

        List<Question> questions = questionMapper.selectBatchIds(questionIds);
        int sortOrder = 1;
        BigDecimal totalScore = BigDecimal.ZERO;

        for (Long questionId : questionIds) {
            Question question = questions.stream()
                    .filter(q -> q.getId().equals(questionId))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("题目不存在: " + questionId));

            PaperQuestion pq = new PaperQuestion();
            pq.setPaperId(paper.getId());
            pq.setQuestionId(questionId);
            pq.setQuestionType(question.getQuestionType());
            pq.setQuestionOrder(sortOrder);
            pq.setScore(question.getDefaultScore());
            pq.setSortOrder(sortOrder);
            paperQuestionMapper.insert(pq);

            totalScore = totalScore.add(question.getDefaultScore() != null ? question.getDefaultScore() : BigDecimal.ZERO);
            sortOrder++;
        }

        if (paper.getTotalScore() == null) {
            paper.setTotalScore(totalScore.intValue());
        }

        BigDecimal difficultyAvg = calculateDifficultyAvg(questionIds);
        paper.setDifficultyAvg(difficultyAvg);
        paper.setQuestionIds(questionIds.stream().map(String::valueOf).collect(Collectors.joining(",")));

        paperMapper.updateById(paper);

        log.info("固定卷创建成功: paperId={}", paper.getId());

        return paper;
    }

    @Override
    @Transactional
    public void deletePaper(Long id) {
        Paper paper = paperMapper.selectById(id);
        if (paper == null) {
            throw new BusinessException(ResultCode.PAPER_NOT_FOUND);
        }

        paperMapper.deleteById(id);

        paperQuestionMapper.delete(
                new LambdaQueryWrapper<PaperQuestion>().eq(PaperQuestion::getPaperId, id));

        log.info("试卷删除成功: paperId={}", id);
    }

    @Override
    @Transactional
    public Paper updatePaper(Paper paper) {
        Paper existing = paperMapper.selectById(paper.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.PAPER_NOT_FOUND);
        }

        if (paper.getPaperVersion() != null) {
            paper.setPaperVersion(existing.getPaperVersion() + 1);
        }

        paperMapper.updateById(paper);

        log.info("试卷更新成功: paperId={}", paper.getId());

        return getPaperById(paper.getId());
    }

    @Override
    @Transactional
    public void copyPaper(Long sourcePaperId, String newPaperName) {
        Paper sourcePaper = getPaperById(sourcePaperId);
        List<PaperQuestion> sourceQuestions = getPaperQuestions(sourcePaperId);

        Paper newPaper = new Paper();
        BeanUtils.copyProperties(sourcePaper, newPaper);
        newPaper.setId(null);
        newPaper.setPaperName(newPaperName);
        newPaper.setPaperCode(null);
        newPaper.setPaperVersion(1);
        paperMapper.insert(newPaper);

        List<Long> questionIds = new ArrayList<>();
        for (PaperQuestion sq : sourceQuestions) {
            PaperQuestion nq = new PaperQuestion();
            BeanUtils.copyProperties(sq, nq);
            nq.setId(null);
            nq.setPaperId(newPaper.getId());
            paperQuestionMapper.insert(nq);
            questionIds.add(sq.getQuestionId());
        }

        newPaper.setQuestionIds(questionIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
        paperMapper.updateById(newPaper);

        log.info("试卷复制成功: sourcePaperId={}, newPaperId={}", sourcePaperId, newPaper.getId());
    }

    private void validateGenerateDTO(PaperGenerateDTO generateDTO) {
        if (generateDTO.getSubjectId() == null) {
            throw new BusinessException("请选择科目");
        }
        if (StrUtil.isBlank(generateDTO.getPaperName())) {
            throw new BusinessException("请输入试卷名称");
        }
    }

    private List<Question> selectQuestionsByType(Long subjectId, Integer questionType,
                                                 Integer count, Map<Integer, BigDecimal> difficultyConfig,
                                                 Map<Long, BigDecimal> knowledgePointConfig) {
        List<Question> result = new ArrayList<>();
        Set<Long> selectedIds = new HashSet<>();

        if (CollUtil.isNotEmpty(difficultyConfig)) {
            for (Map.Entry<Integer, BigDecimal> entry : difficultyConfig.entrySet()) {
                int difficulty = entry.getKey();
                int diffCount = (int) Math.round(count * entry.getValue().doubleValue());

                List<Question> questions = questionMapper.selectRandomQuestions(
                        subjectId, questionType, difficulty, diffCount);

                for (Question q : questions) {
                    if (!selectedIds.contains(q.getId())) {
                        result.add(q);
                        selectedIds.add(q.getId());
                    }
                }
            }
        }

        if (result.size() < count) {
            int remaining = count - result.size();
            List<Question> questions = questionMapper.selectRandomQuestions(
                    subjectId, questionType, null, remaining);

            for (Question q : questions) {
                if (!selectedIds.contains(q.getId())) {
                    result.add(q);
                    selectedIds.add(q.getId());
                }
            }
        }

        if (result.size() > count) {
            result = result.subList(0, count);
        }

        Collections.shuffle(result);

        return result;
    }

    private BigDecimal calculateDifficultyAvg(List<Long> questionIds) {
        if (CollUtil.isEmpty(questionIds)) {
            return BigDecimal.ZERO;
        }

        List<Question> questions = questionMapper.selectBatchIds(questionIds);
        if (CollUtil.isEmpty(questions)) {
            return BigDecimal.ZERO;
        }

        int totalDifficulty = 0;
        for (Question q : questions) {
            if (q.getDifficulty() != null) {
                totalDifficulty += q.getDifficulty();
            }
        }

        return BigDecimal.valueOf(totalDifficulty)
                .divide(BigDecimal.valueOf(questions.size()), 2, RoundingMode.HALF_UP);
    }
}
