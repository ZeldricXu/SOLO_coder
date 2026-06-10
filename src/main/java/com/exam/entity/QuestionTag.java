package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_question_tag")
public class QuestionTag extends BaseEntity {
    private String tagName;
    private String tagColor;
    private Long subjectId;
}
