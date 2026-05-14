package com.invoice.mgmt.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceArchive {
    private String archiveId;
    private String invoiceId;
    private String archiveType;
    private String storagePath;
    private String fileName;
    private Long fileSize;
    private String md5;
    private String archivedBy;
    private Instant archivedAt;
    private Instant createdAt;
}
