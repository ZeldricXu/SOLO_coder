package com.iotplatform.common.dto;

import lombok.Data;

@Data
public class PageQuery {

    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String orderBy;
    private String orderDir = "desc";
}
