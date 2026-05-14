package com.fooddelivery.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notifications")
public class Notify {
    @Id
    @Column(name = "notify_id")
    private String notifyId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "notify_type")
    private String notifyType;

    @Column(name = "notify_status")
    private String notifyStatus;

    @Column(name = "notify_message")
    private String notifyMessage;

    @Column(name = "is_read")
    private Boolean isRead;

    @Column(name = "notify_time")
    private LocalDateTime notifyTime;

    @PrePersist
    protected void onCreate() {
        notifyTime = LocalDateTime.now();
        if (isRead == null) {
            isRead = false;
        }
    }
}
