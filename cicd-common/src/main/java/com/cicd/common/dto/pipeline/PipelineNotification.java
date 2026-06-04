package com.cicd.common.dto.pipeline;

import com.cicd.common.enums.NotificationChannel;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class PipelineNotification {

    @JsonProperty("events")
    private Map<String, List<NotificationChannel>> events;

    @JsonProperty("recipients")
    private List<String> recipients;

    @JsonProperty("notify_committer")
    private boolean notifyCommitter;
}
