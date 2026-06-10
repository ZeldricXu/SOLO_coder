package com.exam.dto;

import lombok.Data;

@Data
public class PageQueryDTO {
    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private String keyword;
    private Long subjectId;
    private Integer status;
}
