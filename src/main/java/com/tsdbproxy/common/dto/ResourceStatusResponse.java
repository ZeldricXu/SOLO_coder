package com.tsdbproxy.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceStatusResponse {

    private String id;
    private String status;
    private Double progress;
}
