package com.datateam.loganalyzer.parser;

public enum LogFormat {
    AUTO_DETECT,
    SYSLOG,
    LOG4J,
    LOGBACK,
    JSON_LINES,
    NGINX,
    APACHE,
    CUSTOM_REGEX,
    CUSTOM_GROK,
    UNKNOWN
}
