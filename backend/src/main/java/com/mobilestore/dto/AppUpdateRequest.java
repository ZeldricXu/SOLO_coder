package com.mobilestore.dto;

import lombok.Data;

@Data
public class AppUpdateRequest {

    private String name;
    private String icon;
    private String description;
    private String category;
    private String platform;
    private String status;
}
