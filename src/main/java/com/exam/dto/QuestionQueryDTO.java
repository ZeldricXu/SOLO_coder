package com.exam.dto;

import lombok.Data;

@Data
public class QuestionQueryDTO {
    private Long subjectId;
    private Integer questionType;
    private Integer difficulty;
    private String keyword;
    private Long knowledgePointId;
    private Long tagId;
    private Integer status;
    private Integer pageNum = 1;
    private Integer pageSize = 10;
}
