package com.datastandard.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOperationRequest<T> {

    private String operationType;
    private List<T> items;
    private List<String> ids;
    private Map<String, Object> params;
    private Boolean ignoreError;
}
