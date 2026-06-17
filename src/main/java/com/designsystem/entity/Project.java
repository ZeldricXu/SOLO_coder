package com.designsystem.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.designsystem.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ds_project")
public class Project extends BaseEntity {
    private String projectName;
    private String projectCode;
    private String description;
    private String gitRepository;
    private String gitBranch;
    private String techStack;
    private String contactPerson;
    private String contactEmail;
    private Integer subscriptionStatus;
    private String webhookUrl;
    private String notificationConfig;
}
