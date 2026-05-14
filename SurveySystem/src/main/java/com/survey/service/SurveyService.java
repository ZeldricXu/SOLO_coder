package com.survey.service;

import com.survey.common.SurveyConstants;
import com.survey.dto.SurveyCreateRequest;
import com.survey.entity.Question;
import com.survey.entity.Survey;
import com.survey.exception.SurveyException;
import com.survey.repository.QuestionRepository;
import com.survey.repository.SurveyRepository;
import com.survey.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyService {

    private final SurveyRepository surveyRepository;
    private final QuestionRepository questionRepository;
    private final SurveyTypeService surveyTypeService;
    private final SurveyTemplateService templateService;
    private final HistoryService historyService;

    @Transactional
    public Survey createSurvey(SurveyCreateRequest request) {
        log.info("创建问卷: {}", request.getSurveyName());

        if (!surveyTypeService.typeExists(request.getSurveyType())) {
            throw new SurveyException(400, "问卷类型不存在: " + request.getSurveyType());
        }

        if (request.getTemplateId() != null && !templateService.templateExists(request.getTemplateId())) {
            throw new SurveyException(400, "模板不存在: " + request.getTemplateId());
        }

        Survey survey = new Survey();
        survey.setSurveyId(IdGenerator.generateSurveyId());
        survey.setSurveyName(request.getSurveyName());
        survey.setSurveyType(request.getSurveyType());
        survey.setSurveyDescription(request.getSurveyDescription());
        survey.setSurveyStatus(SurveyConstants.SURVEY_STATUS_DRAFT);
        survey.setSurveyDeadline(request.getSurveyDeadline());
        survey.setTemplateId(request.getTemplateId());
        survey.setNeedReview(request.getNeedReview() != null ? request.getNeedReview() : false);
        survey.setCreatedAt(LocalDateTime.now());

        Survey savedSurvey = surveyRepository.save(survey);

        List<Question> questions = createQuestions(savedSurvey, request.getSurveyQuestions());
        savedSurvey.setSurveyQuestions(questions);

        historyService.recordSurveyHistory(savedSurvey.getSurveyId(), "CREATE_SURVEY",
                "创建问卷: " + request.getSurveyName(), null);
        log.info("问卷创建成功: {}", savedSurvey.getSurveyId());
        return savedSurvey;
    }

    private List<Question> createQuestions(Survey survey, List<SurveyCreateRequest.QuestionItem> questionItems) {
        List<Question> questions = new ArrayList<>();
        int order = 1;
        for (SurveyCreateRequest.QuestionItem item : questionItems) {
            Question question = new Question();
            question.setQuestionId(IdGenerator.generateQuestionId());
            question.setSurvey(survey);
            question.setQuestionType(item.getQuestionType());
            question.setQuestionContent(item.getQuestionContent());
            question.setOptions(item.getOptions() != null ? item.getOptions() : new ArrayList<>());
            question.setRequired(item.getRequired() != null ? item.getRequired() : true);
            question.setQuestionOrder(order++);
            questions.add(question);
        }
        return questionRepository.saveAll(questions);
    }

    @Transactional
    public Survey updateSurvey(String surveyId, SurveyCreateRequest request) {
        log.info("更新问卷: {}", surveyId);

        Survey survey = surveyRepository.findBySurveyId(surveyId)
                .orElseThrow(() -> SurveyException.surveyNotFound(surveyId));

        if (!SurveyConstants.SURVEY_STATUS_DRAFT.equals(survey.getSurveyStatus())) {
            throw new SurveyException(400, "只能修改草稿状态的问卷");
        }

        survey.setSurveyName(request.getSurveyName());
        survey.setSurveyType(request.getSurveyType());
        survey.setSurveyDescription(request.getSurveyDescription());
        survey.setSurveyDeadline(request.getSurveyDeadline());
        survey.setTemplateId(request.getTemplateId());
        survey.setNeedReview(request.getNeedReview() != null ? request.getNeedReview() : false);
        survey.setUpdatedAt(LocalDateTime.now());

        questionRepository.deleteAll(survey.getSurveyQuestions());
        List<Question> newQuestions = createQuestions(survey, request.getSurveyQuestions());
        survey.setSurveyQuestions(newQuestions);

        Survey saved = surveyRepository.save(survey);
        historyService.recordSurveyHistory(surveyId, "UPDATE_SURVEY",
                "更新问卷: " + request.getSurveyName(), null);
        return saved;
    }

    @Transactional
    public void deleteSurvey(String surveyId) {
        log.info("删除问卷: {}", surveyId);

        Survey survey = surveyRepository.findBySurveyId(surveyId)
                .orElseThrow(() -> SurveyException.surveyNotFound(surveyId));

        if (SurveyConstants.SURVEY_STATUS_PUBLISHED.equals(survey.getSurveyStatus())) {
            throw new SurveyException(400, "已发布的问卷不能删除");
        }

        surveyRepository.delete(survey);
        historyService.recordSurveyHistory(surveyId, "DELETE_SURVEY",
                "删除问卷: " + survey.getSurveyName(), null);
    }

    @Transactional
    public Survey submitForReview(String surveyId) {
        log.info("提交问卷待发布: {}", surveyId);

        Survey survey = surveyRepository.findBySurveyId(surveyId)
                .orElseThrow(() -> SurveyException.surveyNotFound(surveyId));

        if (!SurveyConstants.SURVEY_STATUS_DRAFT.equals(survey.getSurveyStatus())) {
            throw new SurveyException(400, "只有草稿状态的问卷可以提交");
        }

        survey.setSurveyStatus(SurveyConstants.SURVEY_STATUS_PENDING);
        survey.setUpdatedAt(LocalDateTime.now());

        Survey saved = surveyRepository.save(survey);
        historyService.recordSurveyHistory(surveyId, "SUBMIT_FOR_PUBLISH",
                "提交问卷待发布: " + survey.getSurveyName(), null);
        return saved;
    }

    @Transactional
    public void updateSurveyStatus(String surveyId, String status) {
        Survey survey = surveyRepository.findBySurveyId(surveyId)
                .orElseThrow(() -> SurveyException.surveyNotFound(surveyId));

        survey.setSurveyStatus(status);
        survey.setUpdatedAt(LocalDateTime.now());
        if (SurveyConstants.SURVEY_STATUS_PUBLISHED.equals(status)) {
            survey.setPublishedAt(LocalDateTime.now());
        }
        surveyRepository.save(survey);
    }

    public Survey getSurvey(String surveyId) {
        return surveyRepository.findBySurveyId(surveyId)
                .orElseThrow(() -> SurveyException.surveyNotFound(surveyId));
    }

    public Optional<Survey> findSurvey(String surveyId) {
        return surveyRepository.findBySurveyId(surveyId);
    }

    public List<Survey> getAllSurveys() {
        return surveyRepository.findAll();
    }

    public List<Survey> getSurveysByStatus(String status) {
        return surveyRepository.findBySurveyStatus(status);
    }

    public List<Survey> getSurveysByType(String type) {
        return surveyRepository.findBySurveyType(type);
    }

    public List<Question> getSurveyQuestions(String surveyId) {
        return questionRepository.findBySurvey_SurveyIdOrderByQuestionOrderAsc(surveyId);
    }

    public boolean isValidForPublish(String surveyId) {
        Survey survey = getSurvey(surveyId);
        return SurveyConstants.SURVEY_STATUS_DRAFT.equals(survey.getSurveyStatus())
                || SurveyConstants.SURVEY_STATUS_PENDING.equals(survey.getSurveyStatus());
    }

    public boolean isSurveyActive(String surveyId) {
        Survey survey = getSurvey(surveyId);
        if (SurveyConstants.SURVEY_STATUS_CLOSED.equals(survey.getSurveyStatus())) {
            return false;
        }
        if (survey.getSurveyDeadline() != null && LocalDateTime.now().isAfter(survey.getSurveyDeadline())) {
            return false;
        }
        return SurveyConstants.SURVEY_STATUS_PUBLISHED.equals(survey.getSurveyStatus());
    }
}
