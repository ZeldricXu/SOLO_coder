package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "change_records")
public class ChangeRecord {
    @Id
    @Column(name = "change_id", length = 50)
    private String changeId;

    @Column(name = "ticket_id", length = 50, nullable = false)
    private String ticketId;

    @Column(name = "change_type", length = 50, nullable = false)
    private String changeType;

    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "change_amount")
    private Integer changeAmount;

    @Column(name = "change_status", length = 50, nullable = false)
    private String changeStatus;

    @Column(name = "change_time", nullable = false)
    private LocalDateTime changeTime;
}
