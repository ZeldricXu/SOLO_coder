package com.eventticket.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "verifications")
public class Verification {
    @Id
    @Column(name = "verify_id", length = 50)
    private String verifyId;

    @Column(name = "ticket_id", length = 50, nullable = false)
    private String ticketId;

    @Column(name = "verify_time", nullable = false)
    private LocalDateTime verifyTime;

    @Column(name = "verify_result", length = 50, nullable = false)
    private String verifyResult;

    @Column(name = "verify_operator", length = 50)
    private String verifyOperator;
}
