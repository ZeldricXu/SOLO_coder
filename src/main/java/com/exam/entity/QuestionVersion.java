package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_question_version")
public class QuestionVersion extends BaseEntity {
    private Long questionId;
    private String questionTitle;
    private String questionContent;
    private Integer questionType;
    private Integer difficulty;
    private Long subjectId;
    private BigDecimal defaultScore;
    private String answer;
    private String analysis;
    private Integer version;
    private String versionRemark;
    private String optionsSnapshot;
    private String programmingLanguage;
    private String testCases;
}
