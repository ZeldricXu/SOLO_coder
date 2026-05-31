package com.example.configmanager.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfigQueryDTO {

    private String namespace;

    private Boolean enabled;

    private Integer pageNum;

    private Integer pageSize;
}
