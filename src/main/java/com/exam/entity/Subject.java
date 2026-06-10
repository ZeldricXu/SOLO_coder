package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_subject")
public class Subject extends BaseEntity {
    private String subjectName;
    private String subjectCode;
    private Long parentId;
    private Integer sortOrder;
    private String description;
    private Integer status;

    @TableField(exist = false)
    private List<Subject> children;
}
