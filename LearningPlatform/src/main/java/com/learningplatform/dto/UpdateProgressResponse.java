
package com.learningplatform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProgressResponse {
    private Integer progressPercent;
    private String progressStatus;
    private Integer chaptersCompleted;
    private Integer totalChapters;
}
