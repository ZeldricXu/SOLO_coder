package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_class")
public class ExamClass extends BaseEntity {
    private String className;
    private String classCode;
    private Long subjectId;
    private Long teacherId;
    private String description;
    private Integer studentCount;
    private Integer status;

    @TableField(exist = false)
    private List<Long> studentIds;
}
