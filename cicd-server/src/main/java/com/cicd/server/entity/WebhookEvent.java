package com.cicd.server.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "webhook_events")
public class WebhookEvent extends BaseEntity {

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "git_provider", nullable = false, length = 50)
    private String gitProvider;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "pipeline_id")
    private Long pipelineId;

    @Column(name = "branch_name", length = 200)
    private String branchName;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "tag_name", length = 200)
    private String tagName;

    @Column(name = "repo_url", length = 500)
    private String repoUrl;

    @Column(name = "sender", length = 200)
    private String sender;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "headers_json", columnDefinition = "TEXT")
    private String headersJson;

    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    @Column(name = "triggered_execution_id")
    private Long triggeredExecutionId;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}
