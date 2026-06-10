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
    private Integer version;
    private Long subjectId;
    private Integer questionType;
    private Integer difficulty;
    private String questionContent;
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
    private String changeLog;
}
