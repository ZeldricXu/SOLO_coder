package com.cicd.server.entity;

import com.cicd.common.enums.NotificationChannel;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "notification_templates")
public class NotificationTemplate extends BaseEntity {

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationChannel channel;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String template;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = true;

    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled = true;

    @Column(length = 500)
    private String description;
}
