package com.designsystem.common;

import lombok.Data;

import java.io.Serializable;

@Data
public class PageQuery implements Serializable {
    private Long pageNum = 1L;
    private Long pageSize = 10L;
    private String keyword;
    private String sortBy;
    private String sortOrder = "desc";
}
