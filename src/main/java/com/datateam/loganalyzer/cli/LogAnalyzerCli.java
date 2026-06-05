package com.datateam.loganalyzer.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(
    name = "log-analyzer",
    version = "1.0.0",
    description = "生产日志分析和异常检测工具",
    mixinStandardHelpOptions = true,
    subcommands = {
        ParseCommand.class,
        AggregateCommand.class,
        DetectCommand.class,
        AlertCommand.class,
        ReportCommand.class,
        MonitorCommand.class
    }
)
public class LogAnalyzerCli implements Callable<Integer> {

    @Option(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose;

    @Option(names = {"-q", "--quiet"}, description = "Quiet mode, only output errors")
    private boolean quiet;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new LogAnalyzerCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        System.out.println("Log Analyzer CLI - 使用 -h 查看帮助信息");
        System.out.println("");
        System.out.println("可用的子命令:");
        System.out.println("  parse     - 解析日志文件并输出结构化结果");
        System.out.println("  aggregate - 按时间粒度聚合计数");
        System.out.println("  detect    - 异常检测（Z-score + 移动平均残差）");
        System.out.println("  alert     - 告警规则评估和推送");
        System.out.println("  report    - 生成日志分析报告");
        System.out.println("  monitor   - 实时监控模式（解析+检测+告警+报告）");
        return 0;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public boolean isQuiet() {
        return quiet;
    }
}
