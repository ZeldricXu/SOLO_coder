import sys
from datetime import datetime
from typing import Dict, List, Optional, Any, Tuple
from enum import Enum

try:
    from rich.console import Console
    from rich.table import Table
    from rich.panel import Panel
    from rich.text import Text
    from rich.style import Style
    from rich.color import Color
    from rich import print as rprint
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False

from .parser import LogEntry, LogLevel
from .anomaly import AnomalyReport, ExceptionTypeStat, CriticalPeriod
from .stats import StatisticsReport, LevelStats, TimeBucket, SourceStats
from .search import SearchResult


class ColorScheme:
    DEBUG = "blue"
    INFO = "green"
    WARNING = "yellow"
    ERROR = "red"
    CRITICAL = "bold red"
    UNKNOWN = "dim"
    
    LEVEL_COLORS = {
        LogLevel.DEBUG: DEBUG,
        LogLevel.INFO: INFO,
        LogLevel.WARNING: WARNING,
        LogLevel.ERROR: ERROR,
        LogLevel.CRITICAL: CRITICAL,
        LogLevel.UNKNOWN: UNKNOWN,
    }
    
    HEADER = "bold cyan"
    SUCCESS = "green"
    ERROR_COLOR = "red"
    WARNING_COLOR = "yellow"
    INFO_COLOR = "blue"
    BORDER = "dim"


class Visualizer:
    def __init__(self, output_file: Optional[str] = None):
        self.output_file = output_file
        if RICH_AVAILABLE:
            if output_file:
                self.console = Console(file=open(output_file, "w", encoding="utf-8"), width=120)
            else:
                self.console = Console(width=120)
        else:
            self.console = None

    def get_level_color(self, level: LogLevel) -> str:
        return ColorScheme.LEVEL_COLORS.get(level, ColorScheme.UNKNOWN)

    def format_percentage(self, value: float, decimal_places: int = 2) -> str:
        return f"{value * 100:.{decimal_places}}%"

    def format_bar(self, value: float, max_value: float, width: int = 30) -> str:
        if max_value <= 0:
            return " " * width
        filled = int((value / max_value) * width)
        bar = "█" * filled + "░" * (width - filled)
        return bar

    def create_ascii_bar(self, value: float, max_value: float, width: int = 20) -> str:
        if max_value <= 0:
            return " " * width
        filled = int((value / max_value) * width)
        return "#" * filled + " " * (width - filled)

    def render_log_entry(self, entry: LogEntry, show_raw: bool = False) -> Any:
        if not RICH_AVAILABLE:
            return self._render_log_entry_plain(entry, show_raw)

        table = Table(show_header=False, box=None, expand=True)
        table.add_column("Field", style=ColorScheme.INFO_COLOR, width=12)
        table.add_column("Value", style="white")

        timestamp_str = entry.timestamp.isoformat() if entry.timestamp else "N/A"
        level_color = self.get_level_color(entry.level)

        table.add_row("Log ID", entry.log_id)
        table.add_row("Timestamp", timestamp_str)
        table.add_row("Level", Text(entry.level.value, style=level_color))
        table.add_row("Source", entry.source)
        table.add_row("Message", Text(entry.message, style="white"))
        
        if entry.stack_trace:
            table.add_row("Stack Trace", Text(entry.stack_trace[:200] + "..." if len(entry.stack_trace) > 200 else entry.stack_trace, style="dim"))
        
        if entry.fields:
            fields_str = ", ".join(f"{k}={v}" for k, v in entry.fields.items())
            table.add_row("Fields", Text(fields_str, style="cyan"))

        if show_raw:
            table.add_row("Raw Line", Text(entry.raw_line, style="dim"))

        return Panel(table, border_style=ColorScheme.BORDER)

    def _render_log_entry_plain(self, entry: LogEntry, show_raw: bool = False) -> str:
        lines = []
        lines.append(f"[{entry.log_id}]")
        if entry.timestamp:
            lines.append(f"  Timestamp: {entry.timestamp.isoformat()}")
        lines.append(f"  Level: {entry.level.value}")
        lines.append(f"  Source: {entry.source}")
        lines.append(f"  Message: {entry.message}")
        if entry.stack_trace:
            lines.append(f"  Stack Trace: {entry.stack_trace[:100]}...")
        if entry.fields:
            fields_str = ", ".join(f"{k}={v}" for k, v in entry.fields.items())
            lines.append(f"  Fields: {fields_str}")
        if show_raw:
            lines.append(f"  Raw: {entry.raw_line}")
        return "\n".join(lines)

    def render_search_result(self, result: SearchResult, highlight: bool = True) -> Any:
        if not RICH_AVAILABLE:
            return self._render_search_result_plain(result)

        entry = result.log_entry
        level_color = self.get_level_color(entry.level)
        
        if highlight and result.matched_text:
            message_text = Text()
            message = entry.message
            start = result.match_start
            end = result.match_end
            
            if 0 <= start < end <= len(message):
                message_text.append(message[:start])
                message_text.append(message[start:end], style="bold yellow on black")
                message_text.append(message[end:])
            else:
                message_text.append(message)
        else:
            message_text = Text(entry.message)

        table = Table(show_header=False, box=None)
        table.add_column("Label", style=ColorScheme.INFO_COLOR, width=10)
        table.add_column("Value")
        
        timestamp_str = entry.timestamp.isoformat() if entry.timestamp else "N/A"
        table.add_row("Time", timestamp_str)
        table.add_row("Level", Text(entry.level.value, style=level_color))
        table.add_row("Source", entry.source)
        table.add_row("Message", message_text)
        table.add_row("Match", Text(f"'{result.matched_text}' in {result.match_location.value}", style="yellow"))

        return Panel(table, border_style=ColorScheme.WARNING_COLOR)

    def _render_search_result_plain(self, result: SearchResult) -> str:
        entry = result.log_entry
        lines = []
        timestamp_str = entry.timestamp.isoformat() if entry.timestamp else "N/A"
        lines.append(f"[{timestamp_str}] [{entry.level.value}] [{entry.source}]")
        lines.append(f"  Message: {entry.message}")
        lines.append(f"  Matched: '{result.matched_text}' at {result.match_location.value}")
        return "\n".join(lines)

    def render_anomaly_report(self, report: AnomalyReport) -> Any:
        if not RICH_AVAILABLE:
            return self._render_anomaly_report_plain(report)

        panels = []

        summary_table = Table(title="Anomaly Summary", show_header=True, header_style=ColorScheme.HEADER)
        summary_table.add_column("Metric", style=ColorScheme.INFO_COLOR)
        summary_table.add_column("Value", justify="right")

        summary_table.add_row("Analysis ID", report.analysis_id)
        summary_table.add_row("Total Logs", str(report.total_logs))
        summary_table.add_row("Error Count", Text(str(report.error_count), style=ColorScheme.ERROR_COLOR))
        summary_table.add_row("Warning Count", Text(str(report.warning_count), style=ColorScheme.WARNING_COLOR))
        summary_table.add_row("Critical Count", Text(str(report.critical_count), style="bold red"))
        summary_table.add_row("Error Rate", Text(self.format_percentage(report.error_rate), 
                                                  style=ColorScheme.ERROR_COLOR if report.error_rate > 0.05 else "green"))
        
        panels.append(Panel(summary_table, title="Summary", border_style=ColorScheme.HEADER))

        if report.exception_types:
            exception_table = Table(title="Exception Types", show_header=True, header_style=ColorScheme.HEADER)
            exception_table.add_column("#", style="dim", width=3)
            exception_table.add_column("Type", style=ColorScheme.ERROR_COLOR)
            exception_table.add_column("Count", justify="right")
            exception_table.add_column("First Seen")
            exception_table.add_column("Peak Time")
            exception_table.add_column("Sources")

            max_count = max(et.count for et in report.exception_types) if report.exception_types else 1

            for i, et in enumerate(report.exception_types[:10], 1):
                first_seen = et.first_occurrence.strftime("%H:%M:%S") if et.first_occurrence else "N/A"
                peak_time = et.peak_time or "N/A"
                sources = ", ".join(list(set(et.sources))[:3]) if et.sources else "N/A"
                
                exception_table.add_row(
                    str(i),
                    et.exception_type,
                    str(et.count),
                    first_seen,
                    peak_time,
                    sources
                )

            panels.append(Panel(exception_table, title="Exception Statistics", border_style=ColorScheme.ERROR_COLOR))

        if report.critical_periods:
            period_table = Table(title="Critical Periods", show_header=True, header_style=ColorScheme.HEADER)
            period_table.add_column("Start", style=ColorScheme.WARNING_COLOR)
            period_table.add_column("End", style=ColorScheme.WARNING_COLOR)
            period_table.add_column("Errors", justify="right")
            period_table.add_column("Total", justify="right")
            period_table.add_column("Error Rate", justify="right")
            period_table.add_column("Top Exception")

            for period in report.critical_periods[:5]:
                start = period.start_time.strftime("%H:%M") if period.start_time else "N/A"
                end = period.end_time.strftime("%H:%M") if period.end_time else "N/A"
                rate_style = "bold red" if period.error_rate > 0.1 else ColorScheme.ERROR_COLOR
                top_exception = period.top_exceptions[0][0] if period.top_exceptions else "N/A"

                period_table.add_row(
                    start,
                    end,
                    str(period.error_count),
                    str(period.total_count),
                    Text(self.format_percentage(period.error_rate), style=rate_style),
                    top_exception
                )

            panels.append(Panel(period_table, title="Critical Periods", border_style=ColorScheme.WARNING_COLOR))

        if report.level_distribution:
            dist_table = Table(title="Level Distribution", show_header=True, header_style=ColorScheme.HEADER)
            dist_table.add_column("Level")
            dist_table.add_column("Count", justify="right")
            dist_table.add_column("Percentage", justify="right")
            dist_table.add_column("Bar", width=30)

            total = sum(report.level_distribution.values())
            for level, count in sorted(report.level_distribution.items()):
                pct = count / total if total > 0 else 0
                bar = self.format_bar(count, max(report.level_distribution.values()))
                dist_table.add_row(
                    Text(level, style=self.get_level_color(LogLevel(level))),
                    str(count),
                    self.format_percentage(pct),
                    bar
                )

            panels.append(Panel(dist_table, title="Level Distribution", border_style=ColorScheme.INFO_COLOR))

        return "\n".join("" for _ in panels)

    def _render_anomaly_report_plain(self, report: AnomalyReport) -> str:
        lines = []
        lines.append("=" * 60)
        lines.append("ANOMALY DETECTION REPORT")
        lines.append("=" * 60)
        lines.append("")
        lines.append("Summary:")
        lines.append(f"  Analysis ID: {report.analysis_id}")
        lines.append(f"  Total Logs: {report.total_logs}")
        lines.append(f"  Errors: {report.error_count}")
        lines.append(f"  Warnings: {report.warning_count}")
        lines.append(f"  Critical: {report.critical_count}")
        lines.append(f"  Error Rate: {self.format_percentage(report.error_rate)}")

        if report.exception_types:
            lines.append("")
            lines.append("Exception Types:")
            for et in report.exception_types[:10]:
                lines.append(f"  - {et.exception_type}: {et.count} occurrences")

        if report.critical_periods:
            lines.append("")
            lines.append("Critical Periods:")
            for period in report.critical_periods[:5]:
                start = period.start_time.strftime("%H:%M") if period.start_time else "N/A"
                end = period.end_time.strftime("%H:%M") if period.end_time else "N/A"
                lines.append(f"  - {start} - {end}: {period.error_count} errors ({self.format_percentage(period.error_rate)})")

        return "\n".join(lines)

    def render_statistics_report(self, report: StatisticsReport) -> Any:
        if not RICH_AVAILABLE:
            return self._render_statistics_report_plain(report)

        panels = []

        summary_table = Table(title="Statistics Summary", show_header=True, header_style=ColorScheme.HEADER)
        summary_table.add_column("Metric", style=ColorScheme.INFO_COLOR)
        summary_table.add_column("Value", justify="right")

        summary_table.add_row("Total Logs", str(report.total_logs))
        time_start = report.time_range[0].isoformat() if report.time_range[0] else "N/A"
        time_end = report.time_range[1].isoformat() if report.time_range[1] else "N/A"
        summary_table.add_row("Time Range Start", time_start)
        summary_table.add_row("Time Range End", time_end)
        summary_table.add_row("Overall Error Rate", Text(
            self.format_percentage(report.overall_error_rate),
            style=ColorScheme.ERROR_COLOR if report.overall_error_rate > 0.05 else "green"
        ))

        panels.append(Panel(summary_table, title="Summary", border_style=ColorScheme.HEADER))

        if report.level_stats:
            level_table = Table(title="Level Statistics", show_header=True, header_style=ColorScheme.HEADER)
            level_table.add_column("Level")
            level_table.add_column("Count", justify="right")
            level_table.add_column("Percentage", justify="right")
            level_table.add_column("Top Source")
            level_table.add_column("Bar", width=25)

            max_count = max(ls.count for ls in report.level_stats) if report.level_stats else 1

            for ls in report.level_stats:
                top_source = max(ls.source_distribution.items(), key=lambda x: x[1])[0] if ls.source_distribution else "N/A"
                bar = self.format_bar(ls.count, max_count)

                level_table.add_row(
                    Text(ls.level, style=self.get_level_color(LogLevel(ls.level))),
                    str(ls.count),
                    self.format_percentage(ls.percentage),
                    top_source,
                    bar
                )

            panels.append(Panel(level_table, title="Level Breakdown", border_style=ColorScheme.INFO_COLOR))

        if report.source_stats:
            source_table = Table(title="Top Sources", show_header=True, header_style=ColorScheme.HEADER)
            source_table.add_column("#", style="dim", width=3)
            source_table.add_column("Source", style=ColorScheme.INFO_COLOR)
            source_table.add_column("Count", justify="right")
            source_table.add_column("Error Rate", justify="right")
            source_table.add_column("Distribution")

            max_count = max(ss.count for ss in report.source_stats) if report.source_stats else 1

            for i, ss in enumerate(report.source_stats[:10], 1):
                error_rate_style = "red" if ss.error_rate > 0.1 else "yellow" if ss.error_rate > 0 else "green"
                bar = self.format_bar(ss.count, max_count, width=20)

                source_table.add_row(
                    str(i),
                    ss.source,
                    str(ss.count),
                    Text(self.format_percentage(ss.error_rate), style=error_rate_style),
                    bar
                )

            panels.append(Panel(source_table, title="Source Statistics", border_style=ColorScheme.SUCCESS))

        if report.time_buckets:
            time_table = Table(title="Time Distribution", show_header=True, header_style=ColorScheme.HEADER)
            time_table.add_column("Time Bucket")
            time_table.add_column("Total", justify="right")
            time_table.add_column("Errors", justify="right")
            time_table.add_column("Warnings", justify="right")
            time_table.add_column("Error Rate", justify="right")
            time_table.add_column("Activity", width=20)

            max_count = max(tb.count for tb in report.time_buckets) if report.time_buckets else 1

            for tb in report.time_buckets[:15]:
                bar = self.format_bar(tb.count, max_count, width=20)
                error_rate_style = "red" if tb.error_rate > 0.1 else "yellow" if tb.error_rate > 0 else "green"

                time_table.add_row(
                    tb.bucket_key,
                    str(tb.count),
                    Text(str(tb.error_count), style=ColorScheme.ERROR_COLOR),
                    Text(str(tb.warning_count), style=ColorScheme.WARNING_COLOR),
                    Text(self.format_percentage(tb.error_rate), style=error_rate_style),
                    bar
                )

            panels.append(Panel(time_table, title="Time Analysis", border_style=ColorScheme.WARNING_COLOR))

        if report.peak_periods:
            peak_table = Table(title="Peak Periods", show_header=True, header_style=ColorScheme.HEADER)
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
                    self.format_percentage(peak["error_rate"])
                )

            panels.append(Panel(peak_table, title="Peak Activity", border_style="magenta"))

        return "\n".join("" for _ in panels)

    def _render_statistics_report_plain(self, report: StatisticsReport) -> str:
        lines = []
        lines.append("=" * 60)
        lines.append("STATISTICS REPORT")
        lines.append("=" * 60)
        lines.append("")
        lines.append("Summary:")
        lines.append(f"  Total Logs: {report.total_logs}")
        time_start = report.time_range[0].isoformat() if report.time_range[0] else "N/A"
        time_end = report.time_range[1].isoformat() if report.time_range[1] else "N/A"
        lines.append(f"  Time Range: {time_start} to {time_end}")
        lines.append(f"  Error Rate: {self.format_percentage(report.overall_error_rate)}")

        if report.level_stats:
            lines.append("")
            lines.append("Level Statistics:")
            for ls in report.level_stats:
                bar = self.create_ascii_bar(ls.percentage, 1.0)
                lines.append(f"  {ls.level:10} [{bar}] {ls.count:6} ({self.format_percentage(ls.percentage)})")

        if report.source_stats:
            lines.append("")
            lines.append("Top Sources:")
            for i, ss in enumerate(report.source_stats[:5], 1):
                lines.append(f"  {i}. {ss.source}: {ss.count} logs, error rate: {self.format_percentage(ss.error_rate)}")

        return "\n".join(lines)

    def print(self, *args, **kwargs):
        if RICH_AVAILABLE:
            for arg in args:
                if hasattr(arg, '__rich_console__') or isinstance(arg, (Panel, Table, Text)):
                    self.console.print(arg)
                else:
                    rprint(arg)
        else:
            for arg in args:
                if isinstance(arg, str):
                    print(arg)
                else:
                    print(str(arg))

    def render_summary_header(self, title: str, subtitle: str = "") -> Any:
        if not RICH_AVAILABLE:
            return f"\n{'='*60}\n{title}\n{'='*60}\n{subtitle}\n"

        header_text = Text()
        header_text.append(title, style="bold cyan")
        if subtitle:
            header_text.append(f"\n{subtitle}", style="dim")

        return Panel(
            header_text,
            border_style="cyan",
            expand=True
        )

    def render_progress_bar(self, current: int, total: int, width: int = 50) -> str:
        if total <= 0:
            return " " * width
        
        percentage = current / total
        filled = int(percentage * width)
        
        if RICH_AVAILABLE:
            bar = "█" * filled + "░" * (width - filled)
            return f"[{bar}] {percentage*100:.1f}%"
        else:
            bar = "#" * filled + " " * (width - filled)
            return f"[{bar}] {percentage*100:.1f}%"


def visualize_logs(entries: List[LogEntry], output_file: Optional[str] = None):
    visualizer = Visualizer(output_file)
    for entry in entries[:50]:
        visualizer.print(visualizer.render_log_entry(entry))
        visualizer.print()


def visualize_search_results(results: List[SearchResult], output_file: Optional[str] = None):
    visualizer = Visualizer(output_file)
    for result in results[:50]:
        visualizer.print(visualizer.render_search_result(result))
        visualizer.print()


def visualize_anomaly_report(report: AnomalyReport, output_file: Optional[str] = None):
    visualizer = Visualizer(output_file)
    visualizer.print(visualizer.render_summary_header("ANOMALY DETECTION REPORT", report.analysis_id))
    visualizer.print()
    visualizer.render_anomaly_report(report)


def visualize_statistics(report: StatisticsReport, output_file: Optional[str] = None):
    visualizer = Visualizer(output_file)
    visualizer.print(visualizer.render_summary_header("STATISTICS REPORT", f"Total: {report.total_logs} logs"))
    visualizer.print()
    visualizer.render_statistics_report(report)
