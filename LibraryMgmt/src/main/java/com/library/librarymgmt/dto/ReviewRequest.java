package com.library.librarymgmt.dto;

import lombok.Data;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class ReviewRequest {
    @NotBlank(message = "图书ID不能为空")
    private String book_id;

    @NotBlank(message = "读者ID不能为空")
    private String reader_id;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最小为1")
    @Max(value = 5, message = "评分最大为5")
    private Integer review_rating;

    private String review_content;
}
