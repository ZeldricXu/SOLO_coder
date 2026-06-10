package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_tag")
public class Tag extends BaseEntity {
    private String tagName;
    private String tagType;
    private Long subjectId;
    private Integer sortOrder;
}
