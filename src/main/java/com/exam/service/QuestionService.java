package com.exam.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.dto.QuestionDTO;
import com.exam.dto.QuestionQueryDTO;
import com.exam.entity.Question;
import com.exam.entity.QuestionVersion;

import java.util.List;

public interface QuestionService {
    IPage<Question> getQuestionPage(QuestionQueryDTO queryDTO);
    Question getQuestionById(Long id);
    Question createQuestion(QuestionDTO questionDTO);
    Question updateQuestion(QuestionDTO questionDTO);
    void deleteQuestion(Long id);
    void batchDeleteQuestions(List<Long> ids);
    List<QuestionVersion> getQuestionVersions(Long questionId);
    Question getQuestionByVersion(Long questionId, Integer version);
    void rollbackVersion(Long questionId, Integer version);
    void importQuestions(List<QuestionDTO> questionDTOList);
    List<QuestionDTO> exportQuestions(List<Long> ids);
}
