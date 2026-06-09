package com.datateam.loganalyzer.parser;

import io.krakens.grok.api.Grok;
import io.krakens.grok.api.GrokCompiler;
import io.krakens.grok.api.exception.GrokException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class GrokPatternRegistry {

    private static final Logger logger = LoggerFactory.getLogger(GrokPatternRegistry.class);

    private static GrokPatternRegistry instance;

    private final Map<String, Grok> compiledPatterns;
    private final Map<String, String> patternDefinitions;
    private final GrokCompiler compiler;
    private volatile boolean initialized = false;

    public static final String LOG4J_COMMON = "LOG4J_COMMON";
    public static final String SYSLOG_RFC5424 = "SYSLOG_RFC5424";
    public static final String SYSLOG_RFC3164 = "SYSLOG_RFC3164";
    public static final String NGINX_ACCESS = "NGINX_ACCESS";
    public static final String JSON_LINES = "JSON_LINES";

    private GrokPatternRegistry() {
        this.compiledPatterns = new ConcurrentHashMap<>();
        this.patternDefinitions = new HashMap<>();
        this.compiler = GrokCompiler.newInstance();
        registerBuiltinPatterns();
    }

    public static synchronized GrokPatternRegistry getInstance() {
        if (instance == null) {
            instance = new GrokPatternRegistry();
        }
        return instance;
    }

    private void registerBuiltinPatterns() {
        compiler.registerDefaultPatterns();

        patternDefinitions.put(LOG4J_COMMON,
            "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level}\\s+%{JAVACLASS:logger}\\s*:\\s*%{GREEDYDATA:message}");

        patternDefinitions.put(SYSLOG_RFC5424,
            "<%{INT:pri}>%{INT:version}\\s+%{TIMESTAMP_ISO8601:timestamp}\\s+%{IPORHOST:hostname}\\s+%{NOTSPACE:service}\\s+%{NOTSPACE:procid}\\s+%{NOTSPACE:msgid}\\s+%{GREEDYDATA:message}");

        patternDefinitions.put(SYSLOG_RFC3164,
            "<%{INT:pri}>%{SYSLOGTIMESTAMP:timestamp}\\s+%{IPORHOST:hostname}\\s+%{NOTSPACE:logger}\\s*:\\s*%{GREEDYDATA:message}");

        patternDefinitions.put(NGINX_ACCESS,
            "%{IPORHOST:client_ip}\\s+-\\s+%{NOTSPACE:remote_user}\\s+\\[%{HTTPDATE:timestamp}\\]\\s+\"%{WORD:method}\\s+%{URIPATH:path}%{URIPARAM:query}?\\s+HTTP/%{NUMBER:http_version}\"\\s+%{INT:status}\\s+%{INT:bytes}");

        patternDefinitions.put(JSON_LINES,
            "%{GREEDYDATA:json_data}");
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        logger.info("Compiling built-in Grok patterns...");
        for (Map.Entry<String, String> entry : patternDefinitions.entrySet()) {
            try {
                Grok grok = compiler.compile(entry.getValue());
                compiledPatterns.put(entry.getKey(), grok);
                logger.debug("Compiled pattern: {}", entry.getKey());
            } catch (GrokException e) {
                logger.error("Failed to compile pattern {}: {}", entry.getKey(), e.getMessage());
            }
        }

        initialized = true;
        logger.info("GrokPatternRegistry initialized with {} built-in patterns", compiledPatterns.size());
    }

    public synchronized void loadCustomPatterns(String patternsDir) {
        if (patternsDir == null) {
            return;
        }

        File dir = new File(patternsDir);
        if (!dir.isDirectory()) {
            logger.warn("Custom patterns directory not found: {}", patternsDir);
            return;
        }

        File[] files = dir.listFiles((f) -> f.isFile() && !f.isHidden());
        if (files == null) {
            return;
        }

        for (File f : files) {
            try (InputStream is = new FileInputStream(f)) {
                compiler.register(is);
                logger.info("Loaded custom patterns from: {}", f.getName());
            } catch (Exception e) {
                logger.warn("Failed to load patterns from {}: {}", f.getName(), e.getMessage());
            }
        }
    }

    public Grok getCompiledPattern(String patternName) {
        if (!initialized) {
            initialize();
        }
        return compiledPatterns.get(patternName);
    }

    public Grok compileAndCache(String patternName, String pattern) throws GrokException {
        if (!initialized) {
            initialize();
        }

        Grok existing = compiledPatterns.get(patternName);
        if (existing != null) {
            return existing;
        }

        synchronized (this) {
            existing = compiledPatterns.get(patternName);
            if (existing != null) {
                return existing;
            }

            Grok grok = compiler.compile(pattern);
            compiledPatterns.put(patternName, grok);
            logger.debug("Compiled and cached pattern: {}", patternName);
            return grok;
        }
    }

    public Grok compile(String pattern) throws GrokException {
        if (!initialized) {
            initialize();
        }
        return compiler.compile(pattern);
    }

    public boolean hasPattern(String patternName) {
        if (!initialized) {
            initialize();
        }
        return compiledPatterns.containsKey(patternName);
    }

    public Map<String, Grok> getAllCompiledPatterns() {
        if (!initialized) {
            initialize();
        }
        return new HashMap<>(compiledPatterns);
    }

    public synchronized void clear() {
        compiledPatterns.clear();
        initialized = false;
        instance = null;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
