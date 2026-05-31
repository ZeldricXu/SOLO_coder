package com.solocoder.platform.logging.persistence;

import com.solocoder.platform.logging.model.LogLevelConfig;

import java.util.List;
import java.util.Optional;

public interface LogLevelPersistenceService {

    void save(LogLevelConfig config);

    void delete(String loggerName);

    Optional<LogLevelConfig> load(String loggerName);

    List<LogLevelConfig> loadAll();

    void deleteAll();
}
