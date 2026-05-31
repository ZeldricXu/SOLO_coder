package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_prompt_version")
public class PromptVersionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String versionId;
    private String promptId;
    private String content;
    private Integer versionNumber;
    private String author;
    private String changeLog;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
