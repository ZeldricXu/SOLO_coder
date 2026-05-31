package com.edgeplatform.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class PagedRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private int pageNum = 1;
    private int pageSize = 20;
    private String sortBy;
    private String sortDir = "desc";
}
