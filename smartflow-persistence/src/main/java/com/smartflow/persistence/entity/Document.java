package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_document")
public class Document extends BaseEntity {

    private String documentNo;
    private String title;
    private String version;
    private String category;
    private String content;
    private String fileType;
    private String fileUrl;
    private Long fileSize;
    private String md5;
    private Integer status;
    private String tags;
    private String remark;
}
