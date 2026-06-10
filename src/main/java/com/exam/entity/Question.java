package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_question")
public class Question extends BaseEntity {
    private Long subjectId;
    private Integer questionType;
    private Integer difficulty;
    private String questionContent;
    private String questionImage;
    private String optionA;
    private String optionB;
    private String optionC;
    private String optionD;
    private String optionE;
    private String optionF;
    private String correctAnswer;
    private String analysis;
    private BigDecimal score;
    private String knowledgePoints;
    private Integer version;
    private String referenceAnswer;
    private String programmingLanguage;
    private String testCases;
    private String codeTemplate;
    private Integer timeLimit;
    private Integer memoryLimit;

    @TableField(exist = false)
    private String[] knowledgePointList;
}
