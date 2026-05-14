package com.example.mailservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailSendRequest {
    private List<String> recipients;
    private List<String> cc;
    private List<String> bcc;
    private String subject;
    private String content;
    private String contentType;
    private String category;
    private List<AttachmentInfo> attachments;
    private String templateId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AttachmentInfo {
        private String fileName;
        private String contentType;
        private byte[] content;
    }
}
