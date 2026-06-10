package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_subject")
public class Subject extends BaseEntity {
    private String subjectName;
    private String subjectCode;
    private String description;
    private Long parentId;
    private Integer sortOrder;
    private Integer status;
}
