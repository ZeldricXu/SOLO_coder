package com.crm.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CustomerRequest {
    @NotBlank(message = "客户名称不能为空")
    private String customerName;
    private String customerType;
    private String customerSource;
    private String customerContact;
    private String customerAddress;
}
