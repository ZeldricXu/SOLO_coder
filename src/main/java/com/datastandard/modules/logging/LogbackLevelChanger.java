package com.datastandard.modules.logging;

public interface LogbackLevelChanger {

    boolean changeLogLevel(String packagePath, String level);

    String getCurrentLevel(String packagePath);

    java.util.List<String> getAllLoggerNames();

    void resetLogLevel(String packagePath);

    void resetAllLogLevels();
}
