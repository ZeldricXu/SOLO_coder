package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_wrong_book")
public class WrongBook extends BaseEntity {
    private Long studentId;
    private Long subjectId;
    private Long examId;
    private Long questionId;
    private String studentAnswer;
    private String correctAnswer;
    private Integer wrongCount;
    private LocalDateTime lastWrongTime;
    private Integer mastered;
}
