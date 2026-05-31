package com.datastandard.modules.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class LogbackLevelChangerImpl implements LogbackLevelChanger {

    private static final String ROOT_LOGGER_NAME = "ROOT";
    private final LoggerContext loggerContext;

    public LogbackLevelChangerImpl() {
        this.loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    @Override
    public boolean changeLogLevel(String packagePath, String level) {
        try {
            Level targetLevel = Level.toLevel(level.toUpperCase(), Level.INFO);
            Logger logger = getLogger(packagePath);

            if (logger != null) {
                Level currentLevel = logger.getLevel();
                if (currentLevel != targetLevel) {
                    logger.setLevel(targetLevel);
                    log.info("Changed log level for [{}] from [{}] to [{}]",
                            packagePath, currentLevel, targetLevel);
                    return true;
                } else {
                    log.debug("Log level for [{}] is already [{}]", packagePath, targetLevel);
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Failed to change log level for [{}] to [{}]", packagePath, level, e);
            return false;
        }
    }

    @Override
    public String getCurrentLevel(String packagePath) {
        Logger logger = getLogger(packagePath);
        if (logger != null) {
            Level level = logger.getLevel();
            return level != null ? level.toString() : null;
        }
        return null;
    }

    @Override
    public List<String> getAllLoggerNames() {
        List<String> loggerNames = new ArrayList<>();
        for (Logger logger : loggerContext.getLoggerList()) {
            String name = logger.getName();
            if (name != null && !name.isEmpty()) {
                loggerNames.add(name);
            }
        }
        Collections.sort(loggerNames);
        return loggerNames;
    }

    @Override
    public void resetLogLevel(String packagePath) {
        Logger logger = getLogger(packagePath);
        if (logger != null) {
            Level originalLevel = logger.getLevel();
            logger.setLevel(null);
            log.info("Reset log level for [{}] from [{}] to inherited", packagePath, originalLevel);
        }
    }

    @Override
    public void resetAllLogLevels() {
        for (Logger logger : loggerContext.getLoggerList()) {
            if (!ROOT_LOGGER_NAME.equals(logger.getName())) {
                logger.setLevel(null);
            }
        }
        log.info("Reset all log levels to inherited values");
    }

    private Logger getLogger(String packagePath) {
        if (ROOT_LOGGER_NAME.equalsIgnoreCase(packagePath) || packagePath == null || packagePath.isEmpty()) {
            return loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        }
        return loggerContext.getLogger(packagePath);
    }

    public String getEffectiveLevel(String packagePath) {
        Logger logger = getLogger(packagePath);
        if (logger != null) {
            Level level = logger.getEffectiveLevel();
            return level != null ? level.toString() : null;
        }
        return null;
    }
}
