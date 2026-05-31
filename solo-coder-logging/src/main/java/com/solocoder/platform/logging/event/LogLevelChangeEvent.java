package com.solocoder.platform.logging.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogLevelChangeEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String loggerName;
    private String oldLevel;
    private String newLevel;
    private String source;
    private LocalDateTime timestamp;
    private String nodeId;

    public static LogLevelChangeEvent of(String loggerName, String oldLevel, String newLevel, String source, String nodeId) {
        return new LogLevelChangeEvent(loggerName, oldLevel, newLevel, source, LocalDateTime.now(), nodeId);
    }
}
