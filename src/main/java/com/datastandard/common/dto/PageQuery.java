package com.datastandard.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageQuery {

    @Builder.Default
    private Integer pageNum = 1;

    @Builder.Default
    private Integer pageSize = 10;

    private String keyword;
    private String sortField;
    private String sortOrder;
    private Map<String, Object> filters;
    private Map<String, Object> params;

    public Integer getOffset() {
        return (pageNum - 1) * pageSize;
    }

    public boolean isAscending() {
        return "asc".equalsIgnoreCase(sortOrder);
    }
}
