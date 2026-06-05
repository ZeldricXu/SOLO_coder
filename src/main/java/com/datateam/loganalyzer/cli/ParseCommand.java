package com.datateam.loganalyzer.cli;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.parser.LogFormat;
import com.datateam.loganalyzer.parser.LogParser;
import com.datateam.loganalyzer.parser.LogParserFactory;
import com.datateam.loganalyzer.parser.MultiLineMerger;
import com.datateam.loganalyzer.util.FileUtils;
import com.datateam.loganalyzer.util.JsonUtils;
import com.datateam.loganalyzer.util.TimeUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "parse",
    description = "解析日志文件，输出结构化结果",
    mixinStandardHelpOptions = true
)
public class ParseCommand implements Callable<Integer> {

    @Parameters(description = "日志文件路径，支持多个文件或目录，留空则从stdin读取", arity = "0..*")
    private List<String> inputPaths;

    @Option(names = {"-f", "--format"}, description =
        "日志格式: AUTO_DETECT, SYSLOG, LOG4J, JSON_LINES, CUSTOM_REGEX, CUSTOM_GROK")
    private LogFormat format = LogFormat.AUTO_DETECT;

    @Option(names = {"-p", "--pattern"}, description = "自定义regex或grok模式")
    private String pattern;

    @Option(names = {"--grok-patterns-dir"}, description = "自定义grok模式目录")
    private String grokPatternsDir;

    @Option(names = {"-s", "--service"}, description = "服务名称")
    private String serviceName;

    @Option(names = {"--merge-multiline"}, description = "合并多行堆栈跟踪", defaultValue = "true")
    private boolean mergeMultiline = true;

    @Option(names = {"-o", "--output"}, description = "输出格式: text, json, pretty")
    private String outputFormat = "text";

    @Option(names = {"-n", "--limit"}, description = "输出前N条记录")
    private Integer limit;

    @Option(names = {"--level"}, description = "只输出指定级别日志: TRACE,DEBUG,INFO,WARN,ERROR,FATAL")
    private String levelFilter;

    @Option(names = {"--show-raw"}, description = "显示原始日志行")
    private boolean showRaw;

    @Override
    public Integer call() throws Exception {
        List<String> rawLines;

        if (inputPaths == null || inputPaths.isEmpty()) {
            rawLines = FileUtils.readFromStdin();
        } else {
            rawLines = new java.util.ArrayList<>();
            List<File> files = FileUtils.expandFilePaths(inputPaths);
            for (File file : files) {
                rawLines.addAll(FileUtils.readAllLines(file));
            }
        }

        if (rawLines.isEmpty()) {
            System.err.println("没有输入数据");
            return 1;
        }

        LogFormat detectedFormat = format;
        if (format == LogFormat.AUTO_DETECT) {
            detectedFormat = LogParserFactory.detectFormat(rawLines);
            System.err.println("自动检测到日志格式: " + detectedFormat);
        }

        LogParser parser = LogParserFactory.createParser(detectedFormat, pattern, grokPatternsDir, serviceName);

        List<LogEvent> events;
        if (mergeMultiline) {
            MultiLineMerger merger = new MultiLineMerger(parser);
            events = merger.processLines(rawLines);
        } else {
            events = parser.parseAll(rawLines);
        }

        if (levelFilter != null && !levelFilter.isEmpty()) {
            final String filter = levelFilter.toUpperCase();
            events.removeIf(e -> e.getLevel() == null ||
                !e.getLevel().name().equals(filter));
        }

        if (limit != null && limit > 0 && events.size() > limit) {
            events = events.subList(0, limit);
        }

        if (events.isEmpty()) {
            System.out.println("没有匹配的日志事件");
            return 0;
        }

        outputEvents(events);

        return 0;
    }

    private void outputEvents(List<LogEvent> events) throws Exception {
        switch (outputFormat.toLowerCase()) {
            case "json":
                for (LogEvent event : events) {
                    System.out.println(JsonUtils.toJson(event));
                }
                break;
            case "pretty":
                System.out.println(JsonUtils.toJson(events));
                break;
            case "text":
            default:
                for (LogEvent event : events) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[").append(TimeUtils.formatInstant(event.getTimestamp())).append("]");
                    sb.append(" [").append(event.getLevel()).append("]");
                    if (event.getService() != null) {
                        sb.append(" [").append(event.getService()).append("]");
                    }
                    if (event.getLogger() != null) {
                        sb.append(" [").append(event.getLogger()).append("]");
                    }
                    sb.append(" ").append(event.getMessage() != null ?
                        (event.getMessage().length() > 200 ?
                            event.getMessage().substring(0, 200) + "..." :
                            event.getMessage()) : "");
                    if (event.getErrorType() != null) {
                        sb.append(" {").append(event.getErrorType()).append("}");
                    }
                    System.out.println(sb.toString());

                    if (showRaw && event.getRawLine() != null) {
                        System.out.println("  RAW: " + event.getRawLine());
                    }
                    if (event.getStackTrace() != null) {
                        System.out.println("  STACKTRACE:");
                        for (String line : event.getStackTrace().split("\n")) {
                            System.out.println("    " + line);
                        }
                    }
                }
                break;
        }

        System.out.println("\n总计解析 " + events.size() + " 条日志");
    }
}
