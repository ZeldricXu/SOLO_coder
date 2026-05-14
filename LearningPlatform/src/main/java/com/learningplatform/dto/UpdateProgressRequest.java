
package com.learningplatform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProgressRequest {

    @NotBlank(message = "进度ID不能为空")
    private String progressId;

    @NotBlank(message = "章节ID不能为空")
    private String chapterId;

    private Boolean completed;

    private Long learningTime;
}
