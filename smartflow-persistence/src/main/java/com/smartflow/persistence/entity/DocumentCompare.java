package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_document_compare")
public class DocumentCompare extends BaseEntity {

    private Long leftDocumentId;
    private String leftTitle;
    private String leftVersion;
    private Long rightDocumentId;
    private String rightTitle;
    private String rightVersion;
    private String diffResult;
    private String changeSummary;
    private String keyClauses;
    private Integer similarity;
    private LocalDateTime comparedAt;
    private String compareOptions;
}
