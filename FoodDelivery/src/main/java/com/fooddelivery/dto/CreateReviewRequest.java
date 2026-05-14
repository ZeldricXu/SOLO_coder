package com.fooddelivery.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReviewRequest {
    @NotBlank(message = "订单ID不能为空")
    private String order_id;

    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分至少为1")
    @Max(value = 5, message = "评分最多为5")
    private Integer review_rating;

    private String review_content;

    private String user_id;
}
