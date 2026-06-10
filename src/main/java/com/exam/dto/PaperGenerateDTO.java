package com.exam.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class PaperGenerateDTO {
    private String paperName;
    private Long subjectId;
    private Integer paperMode;
    private Integer duration;
    private Integer totalScore;
    private Integer passScore;

    private List<QuestionTypeConfig> questionTypeConfigs;

    private Map<Integer, BigDecimal> difficultyConfig;

    private Map<Long, BigDecimal> knowledgePointConfig;

    private Integer totalQuestions;

    private Boolean abPaper;

    @Data
    public static class QuestionTypeConfig {
        private Integer questionType;
        private Integer questionCount;
        private BigDecimal scorePerQuestion;
    }
}
