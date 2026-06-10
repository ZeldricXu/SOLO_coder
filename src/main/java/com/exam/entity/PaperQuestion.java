package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_paper_question")
public class PaperQuestion extends BaseEntity {
    private Long paperId;
    private Long questionId;
    private Integer questionType;
    private Integer questionOrder;
    private BigDecimal score;
    private String sectionName;
    private Integer sortOrder;
}
