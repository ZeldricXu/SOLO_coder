package com.finance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "account_types")
public class AccountType {

    @Id
    @Column(name = "type_id", nullable = false, length = 50)
    private String typeId;

    @Column(name = "type_code", nullable = false, length = 50, unique = true)
    private String typeCode;

    @Column(name = "type_name", nullable = false, length = 100)
    private String typeName;

    @Column(name = "type_description", length = 500)
    private String typeDescription;

    @Column(name = "type_status", nullable = false, length = 20)
    private String typeStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
