package com.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ContactRequest {
    @NotBlank(message = "客户ID不能为空")
    private String customerId;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String contactPosition;
    private Boolean isPrimary;
}
