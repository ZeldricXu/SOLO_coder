package com.exam.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_knowledge_point")
public class KnowledgePoint extends BaseEntity {
    private String pointName;
    private String pointCode;
    private Long subjectId;
    private Long parentId;
    private Integer sortOrder;
    private String description;

    @TableField(exist = false)
    private List<KnowledgePoint> children;
}
