package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierMessage implements Serializable {
    private String messageId;
    private String supplierId;
    private String sender;
    private String receiver;
    private String messageType;
    private String messageContent;
    private String relatedOrderId;
    private String status;
    private LocalDateTime sentAt;
    private LocalDateTime readAt;
}
