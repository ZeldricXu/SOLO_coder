import json
import sys
from datetime import datetime
from typing import Dict, List, Optional, Any, TextIO
from enum import Enum

from .parser import LogEntry, LogLevel
from .anomaly import AnomalyReport, ExceptionTypeStat, CriticalPeriod, AnomalyEvent
from .stats import StatisticsReport, LevelStats, TimeBucket, SourceStats
from .search import SearchResult


class OutputFormat(Enum):
    TEXT = "text"
    JSON = "json"
    JSON_PRETTY = "json_pretty"
    CSV = "csv"


class OutputWriter:
    def __init__(self, output_file: Optional[str] = None, format: OutputFormat = OutputFormat.TEXT):
        self.output_file = output_file
        self.format = format
        self._file_handle: Optional[TextIO] = None

    def __enter__(self) -> "OutputWriter":
        if self.output_file:
            self._file_handle = open(self.output_file, "w", encoding="utf-8")
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if self._file_handle:
            self._file_handle.close()
            self._file_handle = None

    def write(self, content: str):
        if self._file_handle:
            self._file_handle.write(content + "\n")
        else:
            print(content)

    def write_json(self, data: Any, pretty: bool = False):
        if pretty:
            json_str = json.dumps(data, indent=2, ensure_ascii=False, default=self._json_default)
        else:
            json_str = json.dumps(data, ensure_ascii=False, default=self._json_default)
        self.write(json_str)

    def _json_default(self, obj: Any) -> Any:
        if isinstance(obj, datetime):
            return obj.isoformat()
        if isinstance(obj, Enum):
            return obj.value
        if hasattr(obj, "to_dict"):
            return obj.to_dict()
        return str(obj)


class ReportGenerator:
    def __init__(self, output_file: Optional[str] = None):
        self.output_file = output_file

    def _get_timestamp(self) -> str:
        return datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    def generate_text_report(
        self,
        title: str,
        sections: List[Dict[str, Any]]
    ) -> str:
        lines = []
        
        lines.append("=" * 70)
        lines.append(f"{title:^70}")
        lines.append("=" * 70)
        lines.append(f"Generated: {self._get_timestamp()}")
        lines.append("")

        for section in sections:
            section_title = section.get("title", "")
            content = section.get("content", "")
            lines.append("-" * 70)
            lines.append(f"[ {section_title} ]")
            lines.append("-" * 70)
            lines.append(str(content))
            lines.append("")

        lines.append("=" * 70)
        lines.append("End of Report")
        lines.append("=" * 70)

        return "\n".join(lines)

    def generate_anomaly_text_report(self, report: AnomalyReport) -> str:
        sections = []

        summary_content = []
        summary_content.append(f"Analysis ID: {report.analysis_id}")
        summary_content.append(f"Total Logs Analyzed: {report.total_logs}")
        summary_content.append("")
        summary_content.append(f"  Errors:     {report.error_count:>8}")
        summary_content.append(f"  Warnings:   {report.warning_count:>8}")
        summary_content.append(f"  Critical:   {report.critical_count:>8}")
        summary_content.append("")
        summary_content.append(f"Error Rate:   {report.error_rate * 100:.2f}%")
        
        if report.time_range[0] and report.time_range[1]:
            summary_content.append(f"Time Range:   {report.time_range[0].isoformat()} to {report.time_range[1].isoformat()}")
        
        sections.append({
            "title": "Summary",
            "content": "\n".join(summary_content)
        })

        if report.exception_types:
            exception_content = []
            exception_content.append(f"{'Type':<35} {'Count':>8} {'First Seen':<20} {'Peak Time':<10}")
            exception_content.append("-" * 80)
            
            for et in report.exception_types[:15]:
                first_seen = et.first_occurrence.strftime("%Y-%m-%d %H:%M:%S") if et.first_occurrence else "N/A"
                peak_time = et.peak_time or "N/A"
                exception_content.append(f"{et.exception_type:<35} {et.count:>8} {first_seen:<20} {peak_time:<10}")
            
            sections.append({
                "title": "Exception Types",
                "content": "\n".join(exception_content)
            })

        if report.critical_periods:
            period_content = []
            period_content.append(f"{'Start Time':<20} {'End Time':<20} {'Errors':>8} {'Error Rate':>10}")
            period_content.append("-" * 65)
            
            for period in report.critical_periods[:10]:
                start = period.start_time.strftime("%Y-%m-%d %H:%M") if period.start_time else "N/A"
                end = period.end_time.strftime("%Y-%m-%d %H:%M") if period.end_time else "N/A"
                error_rate = f"{period.error_rate * 100:.2f}%"
                period_content.append(f"{start:<20} {end:<20} {period.error_count:>8} {error_rate:>10}")
            
            sections.append({
                "title": "Critical Periods",
                "content": "\n".join(period_content)
            })

        if report.level_distribution:
            level_content = []
            total = sum(report.level_distribution.values())
            level_content.append(f"{'Level':<10} {'Count':>8} {'Percentage':>12} {'Bar':<30}")
            level_content.append("-" * 65)
            
            for level, count in sorted(report.level_distribution.items()):
                pct = count / total if total > 0 else 0
                bar = "#" * int(pct * 30)
                level_content.append(f"{level:<10} {count:>8} {pct*100:>10.2f}% [{bar:<30}]")
            
            sections.append({
                "title": "Level Distribution",
                "content": "\n".join(level_content)
            })

        if report.source_distribution:
            source_content = []
            total = sum(report.source_distribution.values())
            source_content.append(f"{'Source':<25} {'Count':>8} {'Percentage':>12}")
            source_content.append("-" * 50)
            
            sorted_sources = sorted(report.source_distribution.items(), key=lambda x: x[1], reverse=True)[:15]
            for source, count in sorted_sources:
                pct = count / total if total > 0 else 0
                source_content.append(f"{source:<25} {count:>8} {pct*100:>10.2f}%")
            
            sections.append({
                "title": "Source Distribution",
                "content": "\n".join(source_content)
            })

        if report.anomaly_events:
            event_content = []
            event_content.append(f"{'Type':<20} {'Confidence':>10} {'Source':<15} {'Message Preview':<40}")
            event_content.append("-" * 90)
            
            for event in report.anomaly_events[:20]:
                confidence = f"{event.confidence * 100:.0f}%"
                msg_preview = event.log_entry.message[:37] + "..." if len(event.log_entry.message) > 40 else event.log_entry.message
                event_content.append(f"{event.anomaly_type:<20} {confidence:>10} {event.log_entry.source:<15} {msg_preview:<40}")
            
            sections.append({
                "title": "Anomaly Events (Sample)",
                "content": "\n".join(event_content)
            })

        return self.generate_text_report("ANOMALY DETECTION REPORT", sections)

    def generate_statistics_text_report(self, report: StatisticsReport) -> str:
        sections = []

        summary_content = []
        summary_content.append(f"Total Logs: {report.total_logs}")
        if report.time_range[0] and report.time_range[1]:
            summary_content.append(f"Time Range: {report.time_range[0].isoformat()} to {report.time_range[1].isoformat()}")
        summary_content.append(f"Overall Error Rate: {report.overall_error_rate * 100:.2f}%")
        
        sections.append({
            "title": "Summary",
            "content": "\n".join(summary_content)
        })

        if report.level_stats:
            level_content = []
            level_content.append(f"{'Level':<10} {'Count':>8} {'Percentage':>12} {'Top Source':<20}")
            level_content.append("-" * 55)
            
            for ls in report.level_stats:
                top_source = max(ls.source_distribution.items(), key=lambda x: x[1])[0] if ls.source_distribution else "N/A"
                level_content.append(f"{ls.level:<10} {ls.count:>8} {ls.percentage*100:>10.2f}% {top_source:<20}")
            
            sections.append({
                "title": "Level Statistics",
                "content": "\n".join(level_content)
            })

        if report.source_stats:
            source_content = []
            source_content.append(f"{'#':<3} {'Source':<25} {'Count':>8} {'Error Rate':>10}")
            source_content.append("-" * 55)
            
            for i, ss in enumerate(report.source_stats[:15], 1):
                error_rate = f"{ss.error_rate * 100:.2f}%"
                source_content.append(f"{i:<3} {ss.source:<25} {ss.count:>8} {error_rate:>10}")
            
            sections.append({
                "title": "Top Sources",
                "content": "\n".join(source_content)
            })

        if report.time_buckets:
            time_content = []
            time_content.append(f"{'Time Bucket':<20} {'Total':>8} {'Errors':>8} {'Warnings':>8} {'Error Rate':>10}")
            time_content.append("-" * 65)
            
            for tb in report.time_buckets[:20]:
                error_rate = f"{tb.error_rate * 100:.2f}%"
                time_content.append(f"{tb.bucket_key:<20} {tb.count:>8} {tb.error_count:>8} {tb.warning_count:>8} {error_rate:>10}")
            
            sections.append({
                "title": "Time Distribution",
                "content": "\n".join(time_content)
            })

        if report.peak_periods:
            peak_content = []
            peak_content.append(f"{'Start':<20} {'End':<20} {'Count':>8} {'Error Rate':>10}")
            peak_content.append("-" * 65)
            
            for peak in report.peak_periods:
                start = peak["start"][11:16] if peak["start"] else "N/A"
                end = peak["end"][11:16] if peak["end"] else "N/A"
                error_rate = f"{peak['error_rate'] * 100:.2f}%"
                peak_content.append(f"{start:<20} {end:<20} {peak['count']:>8} {error_rate:>10}")
            
            sections.append({
                "title": "Peak Activity Periods",
                "content": "\n".join(peak_content)
            })

        return self.generate_text_report("STATISTICS REPORT", sections)

    def generate_search_text_report(self, results: List[SearchResult], query: str) -> str:
        sections = []

        summary_content = []
        summary_content.append(f"Search Query: {query}")
        summary_content.append(f"Total Results Found: {len(results)}")
        
        sections.append({
            "title": "Search Summary",
            "content": "\n".join(summary_content)
        })

        if results:
            result_content = []
            result_content.append(f"{'#':<4} {'Time':<20} {'Level':<8} {'Source':<15} {'Match Location':<15}")
            result_content.append("-" * 70)
            
            for i, result in enumerate(results[:50], 1):
                entry = result.log_entry
                timestamp = entry.timestamp.strftime("%Y-%m-%d %H:%M") if entry.timestamp else "N/A"
                result_content.append(f"{i:<4} {timestamp:<20} {entry.level.value:<8} {entry.source:<15} {result.match_location.value:<15}")
                result_content.append(f"     Message: {entry.message[:80]}" + ("..." if len(entry.message) > 80 else ""))
                if result.matched_text:
                    result_content.append(f"     Matched: '{result.matched_text}'")
                result_content.append("")
            
            sections.append({
                "title": f"Search Results (Showing {min(len(results), 50)} of {len(results)})",
                "content": "\n".join(result_content)
            })

        return self.generate_text_report("SEARCH RESULTS REPORT", sections)


def export_json(
    data: Any,
    output_file: Optional[str] = None,
    pretty: bool = True
) -> str:
    def default_handler(obj: Any) -> Any:
        if isinstance(obj, datetime):
            return obj.isoformat()
        if isinstance(obj, Enum):
            return obj.value
        if hasattr(obj, "to_dict"):
            return obj.to_dict()
        return str(obj)

    json_str = json.dumps(
        data,
        indent=2 if pretty else None,
        ensure_ascii=False,
        default=default_handler
    )

    if output_file:
        with open(output_file, "w", encoding="utf-8") as f:
            f.write(json_str)

    return json_str


def export_text_report(
    report: Any,
    report_type: str,
    output_file: Optional[str] = None
) -> str:
    generator = ReportGenerator(output_file)
    text_report = ""

    if report_type == "anomaly" and isinstance(report, AnomalyReport):
        text_report = generator.generate_anomaly_text_report(report)
    elif report_type == "statistics" and isinstance(report, StatisticsReport):
        text_report = generator.generate_statistics_text_report(report)
    elif report_type == "search":
        if isinstance(report, list) and (len(report) == 0 or isinstance(report[0], SearchResult)):
            text_report = generator.generate_search_text_report(report, "search query")
    
    if output_file:
        with open(output_file, "w", encoding="utf-8") as f:
            f.write(text_report)

    return text_report


def export_logs_json(
    entries: List[LogEntry],
    output_file: Optional[str] = None,
    pretty: bool = True
) -> str:
    data = [entry.to_dict() for entry in entries]
    return export_json(data, output_file, pretty)


def export_logs_csv(
    entries: List[LogEntry],
    output_file: Optional[str] = None
) -> str:
    import csv
    import io

    output = io.StringIO()
    fieldnames = ["log_id", "timestamp", "level", "source", "message", "stack_trace", "fields", "raw_line"]
    
    writer = csv.DictWriter(output, fieldnames=fieldnames)
    writer.writeheader()
    
    for entry in entries:
        row = {
            "log_id": entry.log_id,
            "timestamp": entry.timestamp.isoformat() if entry.timestamp else "",
            "level": entry.level.value,
            "source": entry.source,
            "message": entry.message,
            "stack_trace": entry.stack_trace or "",
            "fields": json.dumps(entry.fields) if entry.fields else "",
            "raw_line": entry.raw_line
        }
        writer.writerow(row)

    csv_content = output.getvalue()
    
    if output_file:
        with open(output_file, "w", encoding="utf-8", newline="") as f:
            f.write(csv_content)

    return csv_content
