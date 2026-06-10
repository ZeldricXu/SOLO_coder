package com.exam.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.common.BusinessException;
import com.exam.common.Constants;
import com.exam.common.ResultCode;
import com.exam.dto.QuestionDTO;
import com.exam.dto.QuestionQueryDTO;
import com.exam.entity.Question;
import com.exam.entity.QuestionOption;
import com.exam.entity.QuestionVersion;
import com.exam.mapper.QuestionMapper;
import com.exam.mapper.QuestionOptionMapper;
import com.exam.mapper.QuestionVersionMapper;
import com.exam.service.QuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {

    private final QuestionMapper questionMapper;
    private final QuestionOptionMapper questionOptionMapper;
    private final QuestionVersionMapper questionVersionMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public IPage<Question> getQuestionPage(QuestionQueryDTO queryDTO) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<>();

        if (queryDTO.getSubjectId() != null) {
            wrapper.eq(Question::getSubjectId, queryDTO.getSubjectId());
        }
        if (queryDTO.getQuestionType() != null) {
            wrapper.eq(Question::getQuestionType, queryDTO.getQuestionType());
        }
        if (queryDTO.getDifficulty() != null) {
            wrapper.eq(Question::getDifficulty, queryDTO.getDifficulty());
        }
        if (StrUtil.isNotBlank(queryDTO.getKeyword())) {
            wrapper.like(Question::getQuestionTitle, queryDTO.getKeyword());
        }
        if (queryDTO.getStatus() != null) {
            wrapper.eq(Question::getStatus, queryDTO.getStatus());
        }

        wrapper.orderByDesc(Question::getCreateTime);

        Page<Question> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        return questionMapper.selectPage(page, wrapper);
    }

    @Override
    public Question getQuestionById(Long id) {
        String cacheKey = Constants.REDIS_QUESTION_CACHE_PREFIX + id;
        Question cached = (Question) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Question question = questionMapper.selectById(id);
        if (question == null) {
            throw new BusinessException(ResultCode.QUESTION_NOT_FOUND);
        }

        List<QuestionOption> options = questionOptionMapper.selectList(
                new LambdaQueryWrapper<QuestionOption>().eq(QuestionOption::getQuestionId, id)
                        .orderByAsc(QuestionOption::getSortOrder));
        question.setOptions(options);

        if (StrUtil.isNotBlank(question.getKnowledgePointIds())) {
            List<Long> knowledgePointIdList = Arrays.stream(question.getKnowledgePointIds().split(","))
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
            question.setKnowledgePointIdList(knowledgePointIdList);
        }

        if (StrUtil.isNotBlank(question.getTagIds())) {
            List<Long> tagIdList = Arrays.stream(question.getTagIds().split(","))
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
            question.setTagIdList(tagIdList);
        }

        redisTemplate.opsForValue().set(cacheKey, question, 1, TimeUnit.HOURS);

        return question;
    }

    @Override
    @Transactional
    public Question createQuestion(QuestionDTO questionDTO) {
        Question question = new Question();
        BeanUtils.copyProperties(questionDTO, question);
        question.setVersion(1);
        if (question.getStatus() == null) {
            question.setStatus(1);
        }

        if (CollUtil.isNotEmpty(questionDTO.getKnowledgePointIds())) {
            question.setKnowledgePointIds(
                    questionDTO.getKnowledgePointIds().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(","))
            );
        }

        if (CollUtil.isNotEmpty(questionDTO.getTagIds())) {
            question.setTagIds(
                    questionDTO.getTagIds().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(","))
            );
        }

        questionMapper.insert(question);

        saveQuestionOptions(question.getId(), questionDTO.getOptions());

        saveQuestionVersion(question, "创建题目");

        log.info("题目创建成功: {}", question.getId());

        return question;
    }

    @Override
    @Transactional
    public Question updateQuestion(QuestionDTO questionDTO) {
        Question oldQuestion = questionMapper.selectById(questionDTO.getId());
        if (oldQuestion == null) {
            throw new BusinessException(ResultCode.QUESTION_NOT_FOUND);
        }

        Question question = new Question();
        BeanUtils.copyProperties(questionDTO, question);
        question.setVersion(oldQuestion.getVersion() + 1);
        question.setCreateTime(null);
        question.setUpdateTime(null);

        if (CollUtil.isNotEmpty(questionDTO.getKnowledgePointIds())) {
            question.setKnowledgePointIds(
                    questionDTO.getKnowledgePointIds().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(","))
            );
        }

        if (CollUtil.isNotEmpty(questionDTO.getTagIds())) {
            question.setTagIds(
                    questionDTO.getTagIds().stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(","))
            );
        }

        questionMapper.updateById(question);

        questionOptionMapper.delete(
                new LambdaQueryWrapper<QuestionOption>().eq(QuestionOption::getQuestionId, question.getId()));

        saveQuestionOptions(question.getId(), questionDTO.getOptions());

        saveQuestionVersion(question, questionDTO.getVersionRemark());

        String cacheKey = Constants.REDIS_QUESTION_CACHE_PREFIX + question.getId();
        redisTemplate.delete(cacheKey);

        log.info("题目更新成功: {}", question.getId());

        return getQuestionById(question.getId());
    }

    @Override
    @Transactional
    public void deleteQuestion(Long id) {
        Question question = questionMapper.selectById(id);
        if (question == null) {
            throw new BusinessException(ResultCode.QUESTION_NOT_FOUND);
        }

        questionMapper.deleteById(id);

        questionOptionMapper.delete(
                new LambdaQueryWrapper<QuestionOption>().eq(QuestionOption::getQuestionId, id));

        String cacheKey = Constants.REDIS_QUESTION_CACHE_PREFIX + id;
        redisTemplate.delete(cacheKey);

        log.info("题目删除成功: {}", id);
    }

    @Override
    @Transactional
    public void batchDeleteQuestions(List<Long> ids) {
        for (Long id : ids) {
            deleteQuestion(id);
        }
    }

    @Override
    public List<QuestionVersion> getQuestionVersions(Long questionId) {
        return questionVersionMapper.selectList(
                new LambdaQueryWrapper<QuestionVersion>()
                        .eq(QuestionVersion::getQuestionId, questionId)
                        .orderByDesc(QuestionVersion::getVersion));
    }

    @Override
    public Question getQuestionByVersion(Long questionId, Integer version) {
        QuestionVersion versionRecord = questionVersionMapper.selectOne(
                new LambdaQueryWrapper<QuestionVersion>()
                        .eq(QuestionVersion::getQuestionId, questionId)
                        .eq(QuestionVersion::getVersion, version));

        if (versionRecord == null) {
            throw new BusinessException("版本不存在");
        }

        Question question = new Question();
        BeanUtils.copyProperties(versionRecord, question);
        question.setId(questionId);

        return question;
    }

    @Override
    @Transactional
    public void rollbackVersion(Long questionId, Integer version) {
        QuestionVersion versionRecord = questionVersionMapper.selectOne(
                new LambdaQueryWrapper<QuestionVersion>()
                        .eq(QuestionVersion::getQuestionId, questionId)
                        .eq(QuestionVersion::getVersion, version));

        if (versionRecord == null) {
            throw new BusinessException("版本不存在");
        }

        Question question = new Question();
        BeanUtils.copyProperties(versionRecord, question);
        question.setId(questionId);
        question.setVersion(null);

        Question oldQuestion = questionMapper.selectById(questionId);
        if (oldQuestion != null) {
            question.setVersion(oldQuestion.getVersion() + 1);
        }

        questionMapper.updateById(question);
        saveQuestionVersion(question, "回滚到版本" + version);

        String cacheKey = Constants.REDIS_QUESTION_CACHE_PREFIX + questionId;
        redisTemplate.delete(cacheKey);

        log.info("题目回滚成功: questionId={}, version={}", questionId, version);
    }

    @Override
    @Transactional
    public void importQuestions(List<QuestionDTO> questionDTOList) {
        int successCount = 0;
        int failCount = 0;

        for (QuestionDTO dto : questionDTOList) {
            try {
                createQuestion(dto);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("导入题目失败: {}", e.getMessage());
            }
        }

        log.info("题目导入完成: 成功{}, 失败{}", successCount, failCount);
    }

    @Override
    public List<QuestionDTO> exportQuestions(List<Long> ids) {
        List<Question> questions = questionMapper.selectBatchIds(ids);
        List<QuestionDTO> result = new ArrayList<>();

        for (Question question : questions) {
            QuestionDTO dto = new QuestionDTO();
            BeanUtils.copyProperties(question, dto);

            List<QuestionOption> options = questionOptionMapper.selectList(
                    new LambdaQueryWrapper<QuestionOption>().eq(QuestionOption::getQuestionId, question.getId()));

            List<QuestionDTO.QuestionOptionDTO> optionDTOs = options.stream().map(opt -> {
                QuestionDTO.QuestionOptionDTO optionDTO = new QuestionDTO.QuestionOptionDTO();
                optionDTO.setId(opt.getId());
                optionDTO.setOptionLabel(opt.getOptionLabel());
                optionDTO.setOptionContent(opt.getOptionContent());
                optionDTO.setSortOrder(opt.getSortOrder());
                return optionDTO;
            }).collect(Collectors.toList());

            dto.setOptions(optionDTOs);
            result.add(dto);
        }

        return result;
    }

    private void saveQuestionOptions(Long questionId, List<QuestionDTO.QuestionOptionDTO> options) {
        if (CollUtil.isEmpty(options)) {
            return;
        }

        for (int i = 0; i < options.size(); i++) {
            QuestionDTO.QuestionOptionDTO opt = options.get(i);
            QuestionOption option = new QuestionOption();
            option.setQuestionId(questionId);
            option.setOptionLabel(opt.getOptionLabel());
            option.setOptionContent(opt.getOptionContent());
            option.setSortOrder(opt.getSortOrder() != null ? opt.getSortOrder() : i + 1);
            questionOptionMapper.insert(option);
        }
    }

    private void saveQuestionVersion(Question question, String remark) {
        QuestionVersion version = new QuestionVersion();
        BeanUtils.copyProperties(question, version);
        version.setQuestionId(question.getId());
        version.setId(null);
        version.setVersionRemark(remark);
        questionVersionMapper.insert(version);
    }
}
