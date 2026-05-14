package com.example.mailservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MailSearchRequest {
    private String keyword;
    private String category;
    private String sender;
    private String recipient;
    private String mailType;
    private String mailStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer page;
    private Integer size;
}
