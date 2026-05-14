package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "publish_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublishRecord {

    @Id
    @Column(name = "publish_id", nullable = false, length = 50)
    private String publishId;

    @Column(name = "survey_id", nullable = false, length = 50)
    private String surveyId;

    @Column(name = "publish_channel", nullable = false, length = 30)
    private String publishChannel;

    @Column(name = "publish_range", nullable = false, length = 30)
    private String publishRange;

    @Column(name = "publish_status", nullable = false, length = 30)
    private String publishStatus;

    @Column(name = "publish_time", nullable = false)
    private LocalDateTime publishTime;

    @Column(name = "publish_count", nullable = false)
    private Integer publishCount = 0;

    @Column(name = "publish_link", length = 500)
    private String publishLink;

    @Column(name = "confirm_status", length = 30)
    private String confirmStatus;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    @Column(name = "max_retry_count", nullable = false)
    private Integer maxRetryCount = 3;

    @Column(name = "last_retry_time")
    private LocalDateTime lastRetryTime;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "target_emails", length = 2000)
    private String targetEmails;

    @Column(name = "target_user_ids", length = 2000)
    private String targetUserIds;
}
