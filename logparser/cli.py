import argparse
import sys
import os
from typing import Dict, List, Optional, Any, Iterator, Callable
from abc import ABC, abstractmethod
from datetime import datetime
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from logparser.parser import LogParser, LogEntry, LogLevel
from logparser.search import SearchEngine, create_search_query
from logparser.anomaly import (
    AnomalyDetector, 
    detect_anomalies, 
    AnomalyReport,
    load_anomaly_config,
    get_default_anomaly_config,
    ScoreStrategyType
)
from logparser.stats import (
    StatisticsEngine, 
    generate_statistics, 
    StatisticsReport,
    AggregationConfig,
    load_aggregation_config
)
from logparser.visualizer import Visualizer, RICH_AVAILABLE
from logparser.output import export_json, export_text_report, export_logs_json, export_logs_csv


class CommandHandler(ABC):
    command_name: str = ""
    command_help: str = ""
    command_description: str = ""

    @abstractmethod
    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        pass

    @abstractmethod
    def execute(self, args: argparse.Namespace) -> int:
        pass


class CommandRegistry:
    def __init__(self):
        self._handlers: Dict[str, CommandHandler] = {}

    def register(self, handler: CommandHandler) -> None:
        if not handler.command_name:
            raise ValueError("Handler must have a command_name")
        self._handlers[handler.command_name] = handler

    def get_handler(self, command_name: str) -> Optional[CommandHandler]:
        return self._handlers.get(command_name)

    def get_all_handlers(self) -> List[CommandHandler]:
        return list(self._handlers.values())


class ParseLogStream:
    def __init__(self, file_path: str, format: str, encoding: str):
        self.file_path = file_path
        self.format = format
        self.encoding = encoding
        self._parser: Optional[LogParser] = None
        self._total_count = 0
        self._error_count = 0
        self._warning_count = 0
        self._critical_count = 0
        self._min_time: Optional[datetime] = None
        self._max_time: Optional[datetime] = None

    def stream(self) -> Iterator[LogEntry]:
        self._parser = LogParser(format=self.format)
        entries = self._parser.parse_file(self.file_path, encoding=self.encoding)
        
        for entry in entries:
            self._total_count += 1
            
            if entry.timestamp:
                if self._min_time is None or entry.timestamp < self._min_time:
                    self._min_time = entry.timestamp
                if self._max_time is None or entry.timestamp > self._max_time:
                    self._max_time = entry.timestamp
            
            if entry.level == LogLevel.CRITICAL:
                self._critical_count += 1
            elif entry.level == LogLevel.ERROR:
                self._error_count += 1
            elif entry.level == LogLevel.WARNING:
                self._warning_count += 1
            
            yield entry

    @property
    def total_count(self) -> int:
        return self._total_count

    @property
    def error_count(self) -> int:
        return self._error_count

    @property
    def warning_count(self) -> int:
        return self._warning_count

    @property
    def critical_count(self) -> int:
        return self._critical_count

    @property
    def time_range(self) -> tuple:
        return (self._min_time, self._max_time)


class AnalyzeHandler(CommandHandler):
    command_name = "analyze"
    command_help = "执行日志异常检测分析"
    command_description = "分析日志文件中的异常模式，生成异常检测报告。支持配置化置信度阈值和多种评分策略。"

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument(
            "--file", "-f",
            required=True,
            help="日志文件路径"
        )
        
        parser.add_argument(
            "--format",
            choices=["auto", "json", "text", "syslog"],
            default="auto",
            help="日志格式 (默认: auto 自动检测)"
        )
        
        parser.add_argument(
            "--detect-exception", "-e",
            action="store_true",
            help="启用异常类型检测"
        )
        
        parser.add_argument(
            "--detect-period", "-p",
            action="store_true",
            help="启用异常时段检测"
        )
        
        parser.add_argument(
            "--window-minutes",
            type=int,
            default=5,
            help="时间窗口大小（分钟）(默认: 5)"
        )
        
        parser.add_argument(
            "--error-threshold",
            type=float,
            default=0.05,
            help="错误率阈值 (默认: 0.05)"
        )
        
        parser.add_argument(
            "--anomaly-config",
            help="异常检测配置文件路径 (JSON格式)，包含置信度阈值和评分策略配置"
        )
        
        parser.add_argument(
            "--score-strategy",
            choices=["keyword_weight", "context_analysis", "frequency_based", "hybrid"],
            default="hybrid",
            help="置信度评分策略 (默认: hybrid 混合策略)"
        )
        
        parser.add_argument(
            "--min-confidence",
            type=float,
            default=0.3,
            help="默认最小置信度阈值，低于此值的异常不会被记录 (默认: 0.3)"
        )
        
        parser.add_argument(
            "--critical-confidence",
            type=float,
            default=0.8,
            help="临界置信度阈值，高于此值的异常标记为严重 (默认: 0.8)"
        )
        
        parser.add_argument(
            "--max-events",
            type=int,
            default=1000,
            help="最大异常事件收集数量 (默认: 1000)"
        )
        
        parser.add_argument(
            "--no-event-collection",
            action="store_true",
            help="禁用异常事件收集，只生成统计报告"
        )
        
        parser.add_argument(
            "--encoding",
            default="utf-8",
            help="文件编码 (默认: utf-8)"
        )
        
        parser.add_argument(
            "--output", "-o",
            help="输出文件路径"
        )
        
        parser.add_argument(
            "--output-format",
            choices=["text", "json", "json_pretty"],
            default="text",
            help="输出格式 (默认: text)"
        )
        
        parser.add_argument(
            "--no-visual",
            action="store_true",
            help="禁用富文本可视化输出"
        )

    def execute(self, args: argparse.Namespace) -> int:
        config = {
            "error_threshold": args.error_threshold,
            "window_minutes": args.window_minutes,
        }
        
        if args.anomaly_config:
            config["anomaly_config_path"] = args.anomaly_config
        else:
            detection_config = get_default_anomaly_config()
            detection_config.default_min_threshold = args.min_confidence
            detection_config.default_critical_threshold = args.critical_confidence
            detection_config.score_strategy.strategy_type = ScoreStrategyType(args.score_strategy)
            detection_config.enable_event_collection = not args.no_event_collection
            detection_config.max_events = args.max_events
            config["detection_config"] = detection_config
        
        stream = ParseLogStream(args.file, args.format, args.encoding)
        report = detect_anomalies(stream.stream(), config)
        
        if not args.no_visual and RICH_AVAILABLE:
            visualizer = Visualizer(args.output if args.output_format == "text" else None)
            visualizer.print(visualizer.render_summary_header(
                "ANOMALY DETECTION REPORT",
                f"Analysis ID: {report.analysis_id}"
            ))
            visualizer.print()
            
            self._render_rich_anomaly_report(report, visualizer)
        elif args.output_format == "text":
            text_report = export_text_report(report, "anomaly", args.output)
            if not args.output:
                print(text_report)
        
        if args.output and args.output_format in ["json", "json_pretty"]:
            pretty = args.output_format == "json_pretty"
            export_json(report, args.output, pretty)
        
        return 0

    def _render_rich_anomaly_report(self, report: AnomalyReport, visualizer: Visualizer):
        from rich.table import Table
        from rich.panel import Panel
        from rich.text import Text
        
        console = visualizer.console
        
        summary_table = Table(title="Summary", show_header=True, header_style="bold cyan")
        summary_table.add_column("Metric", style="blue")
        summary_table.add_column("Value", justify="right")
        
        summary_table.add_row("Total Logs", str(report.total_logs))
        summary_table.add_row("Errors", Text(str(report.error_count), style="red"))
        summary_table.add_row("Warnings", Text(str(report.warning_count), style="yellow"))
        summary_table.add_row("Critical", Text(str(report.critical_count), style="bold red"))
        summary_table.add_row("Error Rate", Text(
            f"{report.error_rate * 100:.2f}%",
            style="red" if report.error_rate > 0.05 else "green"
        ))
        
        if report.score_strategy_used:
            summary_table.add_row("Score Strategy", Text(report.score_strategy_used, style="cyan"))
        
        console.print(Panel(summary_table, border_style="cyan"))
        console.print()
        
        if report.exception_types:
            exception_table = Table(title="Exception Types", show_header=True, header_style="bold cyan")
            exception_table.add_column("#", style="dim", width=3)
            exception_table.add_column("Type", style="red")
            exception_table.add_column("Count", justify="right")
            exception_table.add_column("First Seen")
            exception_table.add_column("Peak Time")
            
            for i, et in enumerate(report.exception_types[:10], 1):
                first_seen = et.first_occurrence.strftime("%H:%M:%S") if et.first_occurrence else "N/A"
                peak_time = et.peak_time or "N/A"
                
                exception_table.add_row(
                    str(i),
                    et.exception_type,
                    str(et.count),
                    first_seen,
                    peak_time
                )
            
            console.print(Panel(exception_table, border_style="red"))
            console.print()
        
        if report.critical_periods:
            period_table = Table(title="Critical Periods", show_header=True, header_style="bold cyan")
            period_table.add_column("Start", style="yellow")
            period_table.add_column("End", style="yellow")
            period_table.add_column("Errors", justify="right")
            period_table.add_column("Total", justify="right")
            period_table.add_column("Error Rate", justify="right")
            
            for period in report.critical_periods[:5]:
                start = period.start_time.strftime("%H:%M") if period.start_time else "N/A"
                end = period.end_time.strftime("%H:%M") if period.end_time else "N/A"
                rate_style = "bold red" if period.error_rate > 0.1 else "red"
                
                period_table.add_row(
                    start,
                    end,
                    str(period.error_count),
                    str(period.total_count),
                    Text(f"{period.error_rate * 100:.2f}%", style=rate_style)
                )
            
            console.print(Panel(period_table, border_style="yellow"))
            console.print()
        
        if report.level_distribution:
            dist_table = Table(title="Level Distribution", show_header=True, header_style="bold cyan")
            dist_table.add_column("Level")
            dist_table.add_column("Count", justify="right")
            dist_table.add_column("Percentage", justify="right")
            dist_table.add_column("Bar", width=30)
            
            total = sum(report.level_distribution.values())
            max_count = max(report.level_distribution.values())
            
            for level, count in sorted(report.level_distribution.items()):
                pct = count / total if total > 0 else 0
                bar = visualizer.format_bar(count, max_count, 30)
                
                dist_table.add_row(
                    Text(level, style=visualizer.get_level_color(LogLevel(level))),
                    str(count),
                    f"{pct * 100:.2f}%",
                    bar
                )
            
            console.print(Panel(dist_table, border_style="blue"))
        
        if report.anomaly_events:
            event_table = Table(title="Top Anomaly Events", show_header=True, header_style="bold cyan")
            event_table.add_column("#", style="dim", width=3)
            event_table.add_column("Type", style="red")
            event_table.add_column("Confidence", justify="right")
            event_table.add_column("Source")
            event_table.add_column("Message", width=50)
            
            for i, event in enumerate(report.anomaly_events[:10], 1):
                confidence_style = "bold red" if event.confidence >= 0.8 else "red" if event.confidence >= 0.5 else "yellow"
                message = event.log_entry.message[:47] + "..." if len(event.log_entry.message) > 50 else event.log_entry.message
                
                event_table.add_row(
                    str(i),
                    event.anomaly_type,
                    Text(f"{event.confidence * 100:.1f}%", style=confidence_style),
                    event.log_entry.source,
                    message
                )
            
            console.print(Panel(event_table, border_style="magenta"))


class SearchHandler(CommandHandler):
    command_name = "search"
    command_help = "执行日志关键字/正则搜索"
    command_description = "在日志文件中搜索匹配指定条件的日志条目"

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument(
            "--file", "-f",
            required=True,
            help="日志文件路径"
        )
        
        parser.add_argument(
            "--format",
            choices=["auto", "json", "text", "syslog"],
            default="auto",
            help="日志格式 (默认: auto 自动检测)"
        )
        
        search_group = parser.add_mutually_exclusive_group(required=True)
        search_group.add_argument(
            "--keyword", "-k",
            help="搜索关键字"
        )
        search_group.add_argument(
            "--regex", "-r",
            help="正则表达式模式"
        )
        search_group.add_argument(
            "--exact", "-x",
            help="精确匹配字符串"
        )
        
        parser.add_argument(
            "--locations", "-l",
            nargs="+",
            choices=["message", "source", "level", "fields", "all"],
            default=["message"],
            help="搜索位置 (默认: message)"
        )
        
        parser.add_argument(
            "--case-sensitive", "-c",
            action="store_true",
            help="区分大小写"
        )
        
        parser.add_argument(
            "--invert-match", "-v",
            action="store_true",
            help="反向匹配（显示不匹配的行）"
        )
        
        parser.add_argument(
            "--min-level",
            choices=["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"],
            help="最低日志级别过滤"
        )
        
        parser.add_argument(
            "--encoding",
            default="utf-8",
            help="文件编码 (默认: utf-8)"
        )
        
        parser.add_argument(
            "--output", "-o",
            help="输出文件路径"
        )
        
        parser.add_argument(
            "--output-format",
            choices=["text", "json", "json_pretty", "csv"],
            default="text",
            help="输出格式 (默认: text)"
        )
        
        parser.add_argument(
            "--limit", "-n",
            type=int,
            default=100,
            help="限制输出的结果数量 (默认: 100, 0 表示不限制)"
        )
        
        parser.add_argument(
            "--no-visual",
            action="store_true",
            help="禁用富文本可视化输出"
        )

    def execute(self, args: argparse.Namespace) -> int:
        query = create_search_query(
            keyword=args.keyword,
            regex=args.regex,
            exact=args.exact,
            locations=args.locations,
            case_sensitive=args.case_sensitive,
            invert_match=args.invert_match,
            min_level=args.min_level
        )

        engine = SearchEngine()
        stream = ParseLogStream(args.file, args.format, args.encoding)
        results = []
        count = 0
        
        for result in engine.search_entries(stream.stream(), query):
            if args.limit > 0 and count >= args.limit:
                break
            results.append(result)
            count += 1
        
        print(f"找到 {len(results)} 个匹配结果")
        
        if args.output:
            if args.output_format == "csv":
                matched_entries = [r.log_entry for r in results]
                export_logs_csv(matched_entries, args.output)
            elif args.output_format in ["json", "json_pretty"]:
                pretty = args.output_format == "json_pretty"
                export_json(results, args.output, pretty)
            elif args.output_format == "text":
                export_text_report(results, "search", args.output)
        
        if not args.output or not args.no_visual:
            if not args.no_visual and RICH_AVAILABLE:
                visualizer = Visualizer()
                visualizer.print(visualizer.render_summary_header(
                    "SEARCH RESULTS",
                    f"Found {len(results)} matches"
                ))
                visualizer.print()
                
                for i, result in enumerate(results[:min(50, len(results))], 1):
                    visualizer.print(f"--- Result {i} ---")
                    visualizer.print(visualizer.render_search_result(result))
                    visualizer.print()
            else:
                for i, result in enumerate(results[:min(50, len(results))], 1):
                    entry = result.log_entry
                    timestamp = entry.timestamp.isoformat() if entry.timestamp else "N/A"
                    print(f"[{timestamp}] [{entry.level.value}] [{entry.source}] {entry.message}")
        
        return 0


class StatsHandler(CommandHandler):
    command_name = "stats"
    command_help = "生成日志统计报表"
    command_description = "生成日志统计报表，支持按级别、时间、源模块聚合，自定义维度，以及高基数维度的采样优化。"

    def add_arguments(self, parser: argparse.ArgumentParser) -> None:
        parser.add_argument(
            "--file", "-f",
            required=True,
            help="日志文件路径"
        )
        
        parser.add_argument(
            "--format",
            choices=["auto", "json", "text", "syslog"],
            default="auto",
            help="日志格式 (默认: auto 自动检测)"
        )
        
        parser.add_argument(
            "--by-level",
            action="store_true",
            help="按日志级别统计"
        )
        
        parser.add_argument(
            "--by-time",
            action="store_true",
            help="按时间统计"
        )
        
        parser.add_argument(
            "--by-source",
            action="store_true",
            help="按源模块统计"
        )
        
        parser.add_argument(
            "--time-granularity", "-g",
            choices=["second", "minute", "hour", "day", "week", "month"],
            default="hour",
            help="时间统计粒度 (默认: hour)"
        )
        
        parser.add_argument(
            "--aggregation-config", "-c",
            help="自定义聚合维度配置文件路径 (JSON格式)"
        )
        
        parser.add_argument(
            "--high-cardinality-threshold",
            type=int,
            default=10000,
            help="高基数阈值，超过此数量的唯一值将启用采样估算 (默认: 10000)"
        )
        
        parser.add_argument(
            "--reservoir-size",
            type=int,
            default=2000,
            help="采样蓄水池大小 (默认: 2000)"
        )
        
        parser.add_argument(
            "--disable-sampling",
            action="store_true",
            help="禁用高基数采样优化，始终使用全量统计"
        )
        
        parser.add_argument(
            "--encoding",
            default="utf-8",
            help="文件编码 (默认: utf-8)"
        )
        
        parser.add_argument(
            "--output", "-o",
            help="输出文件路径"
        )
        
        parser.add_argument(
            "--output-format",
            choices=["text", "json", "json_pretty"],
            default="text",
            help="输出格式 (默认: text)"
        )
        
        parser.add_argument(
            "--no-visual",
            action="store_true",
            help="禁用富文本可视化输出"
        )

    def execute(self, args: argparse.Namespace) -> int:
        by_level = args.by_level
        by_time = args.by_time
        by_source = args.by_source
        
        if not by_level and not by_time and not by_source and not args.aggregation_config:
            by_level = True
            by_time = True
            by_source = True
        
        stream = ParseLogStream(args.file, args.format, args.encoding)
        
        config = {}
        if args.aggregation_config:
            agg_config = load_aggregation_config(args.aggregation_config)
            
            if args.disable_sampling:
                for dim in agg_config.dimensions:
                    if dim.high_cardinality:
                        dim.high_cardinality.enabled = False
            else:
                for dim in agg_config.dimensions:
                    if dim.high_cardinality is None:
                        from logparser.stats import HighCardinalityConfig, SamplingStrategy
                        dim.high_cardinality = HighCardinalityConfig(
                            enabled=True,
                            threshold=args.high_cardinality_threshold,
                            strategy=SamplingStrategy.RESERVOIR,
                            reservoir_size=args.reservoir_size
                        )
            
            config["aggregation_config"] = agg_config

        report = generate_statistics(
            stream.stream(),
            by_level=by_level,
            by_time=by_time,
            by_source=by_source,
            time_granularity=args.time_granularity,
            config=config
        )
        
        if not args.no_visual and RICH_AVAILABLE:
            visualizer = Visualizer(args.output if args.output_format == "text" else None)
            visualizer.print(visualizer.render_summary_header(
                "STATISTICS REPORT",
                f"Total: {report.total_logs} logs"
            ))
            visualizer.print()
            
            self._render_rich_statistics_report(report, visualizer, by_level, by_time, by_source)
        elif args.output_format == "text":
            text_report = export_text_report(report, "statistics", args.output)
            if not args.output:
                print(text_report)
        
        if args.output and args.output_format in ["json", "json_pretty"]:
            pretty = args.output_format == "json_pretty"
            export_json(report, args.output, pretty)
        
        return 0

    def _render_rich_statistics_report(
        self, 
        report: StatisticsReport, 
        visualizer: Visualizer, 
        by_level: bool, 
        by_time: bool, 
        by_source: bool
    ):
        from rich.table import Table
        from rich.panel import Panel
        from rich.text import Text
        
        console = visualizer.console
        
        summary_table = Table(title="Summary", show_header=True, header_style="bold cyan")
        summary_table.add_column("Metric", style="blue")
        summary_table.add_column("Value", justify="right")
        
        summary_table.add_row("Total Logs", str(report.total_logs))
        if report.time_range[0] and report.time_range[1]:
            summary_table.add_row("Time Range Start", report.time_range[0].isoformat())
            summary_table.add_row("Time Range End", report.time_range[1].isoformat())
        summary_table.add_row("Overall Error Rate", Text(
            f"{report.overall_error_rate * 100:.2f}%",
            style="red" if report.overall_error_rate > 0.05 else "green"
        ))
        
        console.print(Panel(summary_table, border_style="cyan"))
        console.print()
        
        if by_level and report.level_stats:
            level_table = Table(title="Level Statistics", show_header=True, header_style="bold cyan")
            level_table.add_column("Level")
            level_table.add_column("Count", justify="right")
            level_table.add_column("Percentage", justify="right")
            level_table.add_column("Top Source")
            level_table.add_column("Distribution", width=25)
            
            max_count = max(ls.count for ls in report.level_stats) if report.level_stats else 1
            
            for ls in report.level_stats:
                top_source = max(ls.source_distribution.items(), key=lambda x: x[1])[0] if ls.source_distribution else "N/A"
                bar = visualizer.format_bar(ls.count, max_count, 25)
                
                level_table.add_row(
                    Text(ls.level, style=visualizer.get_level_color(LogLevel(ls.level))),
                    str(ls.count),
                    f"{ls.percentage * 100:.2f}%",
                    top_source,
                    bar
                )
            
            console.print(Panel(level_table, border_style="blue"))
            console.print()
        
        if by_source and report.source_stats:
            source_table = Table(title="Top Sources", show_header=True, header_style="bold cyan")
            source_table.add_column("#", style="dim", width=3)
            source_table.add_column("Source", style="blue")
            source_table.add_column("Count", justify="right")
            source_table.add_column("Error Rate", justify="right")
            source_table.add_column("Activity", width=20)
            
            max_count = max(ss.count for ss in report.source_stats) if report.source_stats else 1
            
            for i, ss in enumerate(report.source_stats[:10], 1):
                error_rate_style = "red" if ss.error_rate > 0.1 else "yellow" if ss.error_rate > 0 else "green"
                bar = visualizer.format_bar(ss.count, max_count, 20)
                
                source_table.add_row(
                    str(i),
                    ss.source,
                    str(ss.count),
                    Text(f"{ss.error_rate * 100:.2f}%", style=error_rate_style),
                    bar
                )
            
            console.print(Panel(source_table, border_style="green"))
            console.print()
        
        if by_time and report.time_buckets:
            time_table = Table(title="Time Distribution", show_header=True, header_style="bold cyan")
            time_table.add_column("Time Bucket")
            time_table.add_column("Total", justify="right")
            time_table.add_column("Errors", justify="right")
            time_table.add_column("Warnings", justify="right")
            time_table.add_column("Error Rate", justify="right")
            time_table.add_column("Activity", width=20)
            
            max_count = max(tb.count for tb in report.time_buckets) if report.time_buckets else 1
            
            for tb in report.time_buckets[:15]:
                error_rate_style = "red" if tb.error_rate > 0.1 else "yellow" if tb.error_rate > 0 else "green"
                bar = visualizer.format_bar(tb.count, max_count, 20)
                
                time_table.add_row(
                    tb.bucket_key,
                    str(tb.count),
                    Text(str(tb.error_count), style="red"),
                    Text(str(tb.warning_count), style="yellow"),
                    Text(f"{tb.error_rate * 100:.2f}%", style=error_rate_style),
                    bar
                )
            
            console.print(Panel(time_table, border_style="yellow"))
            console.print()
        
        if report.custom_dimension_stats:
            for custom_stat in report.custom_dimension_stats:
                dimension_title = f"Custom Dimension: {custom_stat.dimension_name}"
                
                custom_table = Table(title=dimension_title, show_header=True, header_style="bold cyan")
                custom_table.add_column("#", style="dim", width=3)
                custom_table.add_column("Value", style="cyan")
                custom_table.add_column("Estimated Count", justify="right")
                custom_table.add_column("Percentage", justify="right")
                
                if custom_stat.sampling_estimate and custom_stat.sampling_estimate.is_sampled:
                    custom_table.add_column("Sample Info", style="dim")
                
                max_count = max(custom_stat.values.values()) if custom_stat.values else 1
                
                for i, (value, count) in enumerate(custom_stat.values.items(), 1):
                    pct = custom_stat.percentages.get(value, 0.0)
                    
                    row = [
                        str(i),
                        value,
                        str(count),
                        f"{pct * 100:.2f}%"
                    ]
                    
                    if custom_stat.sampling_estimate and custom_stat.sampling_estimate.is_sampled:
                        sample_info = f"sampled (±{custom_stat.sampling_estimate.margin_of_error*100:.1f}%)"
                        row.append(sample_info)
                    
                    custom_table.add_row(*row)
                
                console.print(Panel(custom_table, border_style="magenta"))
                console.print()
        
        if report.peak_periods:
            peak_table = Table(title="Peak Activity Periods", show_header=True, header_style="bold cyan")
            peak_table.add_column("Start")
            peak_table.add_column("End")
            peak_table.add_column("Count", justify="right")
            peak_table.add_column("Error Rate", justify="right")
            
            for peak in report.peak_periods[:5]:
                start = peak["start"][11:16] if peak["start"] else "N/A"
                end = peak["end"][11:16] if peak["end"] else "N/A"
                
                peak_table.add_row(
                    start,
                    end,
                    str(peak["count"]),
                    f"{peak['error_rate'] * 100:.2f}%"
                )
            
            console.print(Panel(peak_table, border_style="magenta"))


class CLIApplication:
    def __init__(self):
        self._registry = CommandRegistry()
        self._register_default_handlers()

    def _register_default_handlers(self) -> None:
        handlers = [
            AnalyzeHandler(),
            SearchHandler(),
            StatsHandler(),
        ]
        for handler in handlers:
            self._registry.register(handler)

    def register_handler(self, handler: CommandHandler) -> None:
        self._registry.register(handler)

    def create_argument_parser(self) -> argparse.ArgumentParser:
        parser = argparse.ArgumentParser(
            prog="logparser",
            description="LogParser - 日志分析与异常检测自动化工具",
            formatter_class=argparse.RawDescriptionHelpFormatter,
            epilog="""
示例用法:
  logparser analyze --file app.log --anomaly-config detection_config.json
  logparser analyze --file app.log --score-strategy hybrid --min-confidence 0.4
  logparser search --file app.log --keyword "ERROR"
  logparser stats --file app.log --by-level --by-hour --aggregation-config dimensions.json
  logparser stats --file app.log --high-cardinality-threshold 5000 --reservoir-size 1000
            """
        )
        
        parser.add_argument(
            "--version",
            action="version",
            version="%(prog)s 1.0.0"
        )
        
        subparsers = parser.add_subparsers(
            title="可用命令",
            dest="command",
            help="使用 logparser <command> --help 查看详细帮助"
        )
        
        for handler in self._registry.get_all_handlers():
            subparser = subparsers.add_parser(
                handler.command_name,
                help=handler.command_help,
                description=handler.command_description
            )
            handler.add_arguments(subparser)
        
        return parser

    def run(self, args: Optional[List[str]] = None) -> int:
        parser = self.create_argument_parser()
        parsed_args = parser.parse_args(args)
        
        if not parsed_args.command:
            parser.print_help()
            return 0
        
        handler = self._registry.get_handler(parsed_args.command)
        if not handler:
            print(f"错误: 未知命令 '{parsed_args.command}'")
            return 1
        
        try:
            return handler.execute(parsed_args)
        except KeyboardInterrupt:
            print("\n操作被用户中断")
            return 130
        except FileNotFoundError as e:
            print(f"错误: 找不到文件 - {e}")
            return 1
        except PermissionError as e:
            print(f"错误: 权限不足 - {e}")
            return 1
        except Exception as e:
            print(f"错误: {e}")
            import traceback
            traceback.print_exc()
            return 1


def main():
    app = CLIApplication()
    return app.run()


if __name__ == "__main__":
    sys.exit(main())
