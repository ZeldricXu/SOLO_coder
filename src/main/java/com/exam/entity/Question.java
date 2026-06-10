package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_question")
public class Question extends BaseEntity {
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
    private Integer status;
    private String tagIds;
    private String knowledgePointIds;
    private String programmingLanguage;
    private String testCases;
    private Integer timeLimit;
    private Integer memoryLimit;

    @TableField(exist = false)
    private List<QuestionOption> options;

    @TableField(exist = false)
    private List<Long> knowledgePointIdList;

    @TableField(exist = false)
    private List<Long> tagIdList;
}
