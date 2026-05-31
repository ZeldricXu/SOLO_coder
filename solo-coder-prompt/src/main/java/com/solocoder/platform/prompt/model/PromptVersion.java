package com.solocoder.platform.prompt.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    private String versionId;
    private String promptId;
    private String content;
    private int versionNumber;
    private String author;
    private String changeLog;
    private Map<String, String> variables;
    private LocalDateTime createdAt;
}
