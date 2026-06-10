package com.exam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class QuestionDTO {
    private Long id;

    @NotBlank(message = "题目标题不能为空")
    private String questionTitle;

    private String questionContent;

    @NotNull(message = "题目类型不能为空")
    private Integer questionType;

    @NotNull(message = "难度等级不能为空")
    private Integer difficulty;

    @NotNull(message = "所属科目不能为空")
    private Long subjectId;

    private BigDecimal defaultScore;

    private String answer;

    private String analysis;

    private String versionRemark;

    private Integer status;

    private List<QuestionOptionDTO> options;

    private List<Long> knowledgePointIds;

    private List<Long> tagIds;

    private String programmingLanguage;

    private String testCases;

    private Integer timeLimit;

    private Integer memoryLimit;

    @Data
    public static class QuestionOptionDTO {
        private Long id;
        private String optionLabel;
        private String optionContent;
        private Integer sortOrder;
    }
}
