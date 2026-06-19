package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_doc_parse_record")
public class DocParseRecord extends BaseEntity {

    private Long componentVersionId;

    private String filePath;

    private String fileName;

    private String fileHash;

    private Long fileSize;

    private String framework;

    private String parseStatus;

    private String parseMessage;

    private Integer propCount;

    private Integer docCount;

    private LocalDateTime lastParsedAt;

    private String lastCommitHash;
}
