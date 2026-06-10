package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_question_option")
public class QuestionOption extends BaseEntity {
    private Long questionId;
    private String optionLabel;
    private String optionContent;
    private Integer sortOrder;
}
