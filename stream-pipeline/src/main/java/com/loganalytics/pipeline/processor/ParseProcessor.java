package com.loganalytics.pipeline.processor;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.pipeline.parse.LogParser;

import java.util.Map;

public class ParseProcessor implements Processor {
    private LogParser parser;

    public ParseProcessor() {
        this.parser = new LogParser();
    }

    public ParseProcessor(LogParser parser) {
        this.parser = parser;
    }

    @Override
    public String getType() {
        return "parse";
    }

    @Override
    public LogEvent process(LogEvent event) {
        return parser.parse(event);
    }

    @Override
    public void configure(Map<String, String> params) {
        parser = new LogParser();
        parser.initialize(params);
    }

    @Override
    public void close() {
    }

    public LogParser getParser() {
        return parser;
    }
}
