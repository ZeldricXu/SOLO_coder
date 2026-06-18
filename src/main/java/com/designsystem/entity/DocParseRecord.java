package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_doc_parse_record")
public class DocParseRecord extends BaseEntity {
    private Long componentId;
    private Long versionId;
    private String filePath;
    private String fileHash;
    private Long fileSize;
    private Integer parseStatus;
    private String parseError;
    private String lastParsedCommit;
    private java.time.LocalDateTime lastParsedAt;
}
