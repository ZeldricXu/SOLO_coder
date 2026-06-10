package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_wrong_book")
public class WrongBook extends BaseEntity {
    private Long userId;
    private Long questionId;
    private Integer questionType;
    private Long subjectId;
    private Long examId;
    private String userAnswer;
    private String correctAnswer;
    private Integer wrongCount;
    private Integer masteryLevel;
    private LocalDateTime lastWrongTime;
    private String knowledgePointIds;
}
