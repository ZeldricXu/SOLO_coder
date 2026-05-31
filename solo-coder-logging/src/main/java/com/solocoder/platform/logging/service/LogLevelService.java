package com.solocoder.platform.logging.service;

import com.solocoder.platform.logging.model.LogLevelAdjustRequest;
import com.solocoder.platform.logging.model.LogLevelConfig;

import java.util.List;
import java.util.Optional;

public interface LogLevelService {

    LogLevelConfig adjustLogLevel(LogLevelAdjustRequest request);

    Optional<LogLevelConfig> getLogLevel(String loggerName);

    List<LogLevelConfig> getAllLogLevels();

    void resetLogLevel(String loggerName);

    void resetAllLogLevels();

    List<LogLevelConfig> recoverFromPersistence();
}
