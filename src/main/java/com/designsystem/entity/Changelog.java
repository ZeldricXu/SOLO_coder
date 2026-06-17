package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_changelog")
public class Changelog extends BaseEntity {
    private Long componentId;
    private String version;
    private String commitType;
    private String commitScope;
    private String commitSubject;
    private String commitBody;
    private String breakingChange;
    private String commitHash;
    private String author;
    private String authorEmail;
    private LocalDateTime committedAt;
    private Integer includedInRelease;
}
