
package com.learningplatform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StartLearningRequest {

    @NotBlank(message = "课程ID不能为空")
    private String courseId;

    @NotBlank(message = "学员ID不能为空")
    private String studentId;
}
