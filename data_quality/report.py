from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional, Tuple
from datetime import datetime, timedelta
import json
import os
import matplotlib.pyplot as plt
import matplotlib
import pandas as pd
import numpy as np
from .checker import CheckResult, CheckReport, DataQualityChecker

matplotlib.use("Agg")


class AlertChannel(Enum):
    EMAIL = "email"
    SMS = "sms"
    WEBHOOK = "webhook"
    CONSOLE = "console"


@dataclass
class AlertConfig:
    enabled: bool = True
    threshold: float = 80.0
    channels: List[AlertChannel] = field(default_factory=lambda: [AlertChannel.CONSOLE])
    email_config: Optional[Dict[str, Any]] = None
    webhook_url: Optional[str] = None
    min_interval: int = 300
    handlers: List[Callable[[CheckResult], None]] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "enabled": self.enabled,
            "threshold": self.threshold,
            "channels": [c.value for c in self.channels],
            "email_config": self.email_config,
            "webhook_url": self.webhook_url,
            "min_interval": self.min_interval,
        }


@dataclass
class QualityReport:
    check_result: CheckResult
    generated_at: datetime = field(default_factory=datetime.now)
    charts: Dict[str, str] = field(default_factory=dict)
    alert_sent: bool = False
    alert_message: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "generated_at": self.generated_at.isoformat(),
            "check_result": self.check_result.to_dict(),
            "charts": self.charts,
            "alert_sent": self.alert_sent,
            "alert_message": self.alert_message,
        }

    def to_json(self, indent: int = 2) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False, indent=indent)

    def save(self, output_path: str) -> None:
        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, ensure_ascii=False, indent=2)


class TrendAnalyzer:
    def __init__(self, history: Optional[List[CheckResult]] = None):
        self.history: List[CheckResult] = history or []

    def add_result(self, result: CheckResult) -> None:
        self.history.append(result)
        self.history.sort(key=lambda r: r.check_time)

    def add_results(self, results: List[CheckResult]) -> None:
        self.history.extend(results)
        self.history.sort(key=lambda r: r.check_time)

    def get_trend_data(
        self,
        window_size: int = 7,
    ) -> pd.DataFrame:
        if not self.history:
            return pd.DataFrame()

        data = []
        for result in self.history:
            row = {
                "check_time": result.check_time,
                "overall_score": result.overall_score,
                "pass_rate": result.pass_rate,
                "total_rules": result.total_rules,
                "passed_rules": result.passed_rules,
                "failed_rules": result.failed_rules,
                "total_records": result.total_records,
            }

            for rule_result in result.rule_results:
                row[f"score_{rule_result.rule_name}"] = rule_result.score
                row[f"failed_{rule_result.rule_name}"] = rule_result.failed_count

            data.append(row)

        df = pd.DataFrame(data)

        if len(df) >= window_size:
            df["score_moving_avg"] = df["overall_score"].rolling(window=window_size).mean()
            df["score_trend"] = df["overall_score"].diff()

        return df

    def analyze_trend(
        self,
        lookback_days: int = 30,
    ) -> Dict[str, Any]:
        if not self.history:
            return {
                "has_data": False,
                "message": "没有足够的历史数据",
            }

        cutoff_time = datetime.now() - timedelta(days=lookback_days)
        recent_history = [r for r in self.history if r.check_time >= cutoff_time]

        if len(recent_history) < 2:
            return {
                "has_data": True,
                "message": "历史数据不足，无法进行趋势分析",
                "data_points": len(recent_history),
            }

        df = self.get_trend_data()
        df_recent = df[df["check_time"] >= cutoff_time]

        first_score = df_recent["overall_score"].iloc[0]
        last_score = df_recent["overall_score"].iloc[-1]
        score_change = last_score - first_score

        if score_change > 5:
            trend = "明显提升"
        elif score_change > 0:
            trend = "略有提升"
        elif score_change > -5:
            trend = "基本稳定"
        elif score_change > -10:
            trend = "略有下降"
        else:
            trend = "明显下降"

        avg_score = df_recent["overall_score"].mean()
        min_score = df_recent["overall_score"].min()
        max_score = df_recent["overall_score"].max()
        std_score = df_recent["overall_score"].std()

        failing_rules = []
        for col in df_recent.columns:
            if col.startswith("failed_"):
                rule_name = col.replace("failed_", "")
                total_failed = df_recent[col].sum()
                if total_failed > 0:
                    failing_rules.append({
                        "rule_name": rule_name,
                        "total_failed": total_failed,
                        "avg_failed": df_recent[col].mean(),
                    })

        failing_rules.sort(key=lambda x: x["total_failed"], reverse=True)

        return {
            "has_data": True,
            "time_range": [df_recent["check_time"].min().isoformat(), df_recent["check_time"].max().isoformat()],
            "data_points": len(df_recent),
            "trend": trend,
            "score_change": round(score_change, 2),
            "avg_score": round(avg_score, 2),
            "min_score": round(min_score, 2),
            "max_score": round(max_score, 2),
            "std_score": round(std_score, 2),
            "failing_rules": failing_rules[:10],
            "recommendations": self._generate_trend_recommendations(trend, score_change, std_score),
        }

    def _generate_trend_recommendations(
        self,
        trend: str,
        score_change: float,
        std_score: float,
    ) -> List[str]:
        recommendations = []

        if "下降" in trend:
            recommendations.append("数据质量呈下降趋势，建议加强数据输入校验")
            recommendations.append("分析失败规则的变化趋势，找出质量下降的根本原因")

        if "稳定" in trend:
            recommendations.append("数据质量基本稳定，继续保持当前的数据管理策略")

        if "提升" in trend:
            recommendations.append("数据质量呈提升趋势，建议总结经验并推广到其他数据集")

        if std_score > 10:
            recommendations.append("数据质量波动较大，建议排查是否存在周期性的数据质量问题")

        return recommendations

    def forecast(
        self,
        periods: int = 7,
    ) -> Dict[str, Any]:
        if len(self.history) < 5:
            return {
                "has_data": False,
                "message": "历史数据不足，无法进行预测",
            }

        df = self.get_trend_data()
        scores = df["overall_score"].values
        times = df["check_time"].values

        x = np.arange(len(scores))
        z = np.polyfit(x, scores, 1)
        p = np.poly1d(z)

        future_x = np.arange(len(scores), len(scores) + periods)
        forecasted_scores = p(future_x)

        last_time = times[-1]
        if isinstance(last_time, np.datetime64):
            last_time = pd.Timestamp(last_time).to_pydatetime()

        future_times = []
        for i in range(periods):
            if len(times) > 1:
                if isinstance(times[1], np.datetime64):
                    t0 = pd.Timestamp(times[0]).to_pydatetime()
                    t1 = pd.Timestamp(times[1]).to_pydatetime()
                    interval = t1 - t0
                else:
                    interval = times[1] - times[0]
            else:
                interval = timedelta(days=1)
            future_times.append(last_time + interval * (i + 1))

        return {
            "has_data": True,
            "forecast": [
                {"time": ft.isoformat(), "predicted_score": round(float(fs), 2)}
                for ft, fs in zip(future_times, forecasted_scores)
            ],
            "trend_slope": round(z[0], 4),
            "trend_direction": "上升" if z[0] > 0 else "下降" if z[0] < 0 else "平稳",
        }


class ReportGenerator:
    def __init__(
        self,
        alert_config: Optional[AlertConfig] = None,
        output_dir: str = "./quality_reports",
    ):
        self.alert_config = alert_config or AlertConfig()
        self.output_dir = output_dir
        self.trend_analyzer = TrendAnalyzer()
        self._last_alert_time: Dict[str, datetime] = {}

        os.makedirs(output_dir, exist_ok=True)

    def generate(
        self,
        check_result: CheckResult,
        include_charts: bool = True,
        check_report: Optional[CheckReport] = None,
        save: bool = True,
    ) -> QualityReport:
        self.trend_analyzer.add_result(check_result)

        charts = {}
        if include_charts:
            charts = self._generate_charts(check_result)

        report = QualityReport(
            check_result=check_result,
            generated_at=datetime.now(),
            charts=charts,
        )

        if self.alert_config.enabled:
            alert_triggered, alert_message = self._check_alert(check_result)
            report.alert_sent = alert_triggered
            report.alert_message = alert_message
            if alert_triggered:
                self._send_alert(check_result, alert_message)

        if save:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            report_path = os.path.join(self.output_dir, f"quality_report_{timestamp}.json")
            report.save(report_path)

        return report

    def _generate_charts(
        self,
        check_result: CheckResult,
    ) -> Dict[str, str]:
        charts = {}
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

        score_chart = self._plot_score_distribution(check_result, timestamp)
        if score_chart:
            charts["score_distribution"] = score_chart

        failed_chart = self._plot_failed_rules(check_result, timestamp)
        if failed_chart:
            charts["failed_rules"] = failed_chart

        if len(self.trend_analyzer.history) >= 2:
            trend_chart = self._plot_trend(timestamp)
            if trend_chart:
                charts["quality_trend"] = trend_chart

        return charts

    def _plot_score_distribution(
        self,
        check_result: CheckResult,
        timestamp: str,
    ) -> Optional[str]:
        try:
            fig, ax = plt.subplots(figsize=(10, 6))

            rules = [r.rule_name for r in check_result.rule_results]
            scores = [r.score for r in check_result.rule_results]
            colors = ["#2ecc71" if s >= 80 else "#f39c12" if s >= 60 else "#e74c3c" for s in scores]

            bars = ax.bar(rules, scores, color=colors)
            ax.set_ylabel("得分")
            ax.set_title("各规则得分分布")
            ax.set_ylim(0, 100)
            ax.axhline(y=80, color="gray", linestyle="--", alpha=0.7, label="优秀线(80)")
            ax.axhline(y=60, color="red", linestyle="--", alpha=0.7, label="及格线(60)")
            ax.legend()
            plt.xticks(rotation=45, ha="right")

            for bar, score in zip(bars, scores):
                height = bar.get_height()
                ax.text(bar.get_x() + bar.get_width() / 2., height + 1,
                       f"{score:.1f}", ha="center", va="bottom", fontsize=9)

            plt.tight_layout()

            chart_path = os.path.join(self.output_dir, f"score_distribution_{timestamp}.png")
            plt.savefig(chart_path, dpi=100, bbox_inches="tight")
            plt.close(fig)

            return chart_path
        except Exception as e:
            return None

    def _plot_failed_rules(
        self,
        check_result: CheckResult,
        timestamp: str,
    ) -> Optional[str]:
        try:
            failed_results = check_result.get_failed_results()
            if not failed_results:
                return None

            fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 6))

            rules = [r.rule_name for r in failed_results]
            failed_counts = [r.failed_count for r in failed_results]

            colors = plt.cm.Set3(np.linspace(0, 1, len(rules)))

            wedges, texts, autotexts = ax1.pie(
                failed_counts,
                labels=rules,
                colors=colors,
                autopct="%1.1f%%",
                startangle=90,
            )
            ax1.set_title("失败规则占比")

            bars = ax2.bar(rules, failed_counts, color=colors)
            ax2.set_ylabel("失败记录数")
            ax2.set_title("各规则失败记录数")
            plt.xticks(rotation=45, ha="right")

            for bar, count in zip(bars, failed_counts):
                height = bar.get_height()
                ax2.text(bar.get_x() + bar.get_width() / 2., height + 1,
                        str(count), ha="center", va="bottom", fontsize=9)

            plt.tight_layout()

            chart_path = os.path.join(self.output_dir, f"failed_rules_{timestamp}.png")
            plt.savefig(chart_path, dpi=100, bbox_inches="tight")
            plt.close(fig)

            return chart_path
        except Exception as e:
            return None

    def _plot_trend(
        self,
        timestamp: str,
    ) -> Optional[str]:
        try:
            df = self.trend_analyzer.get_trend_data()
            if len(df) < 2:
                return None

            fig, ax = plt.subplots(figsize=(12, 6))

            ax.plot(df["check_time"], df["overall_score"], marker="o", linewidth=2, label="实际得分")

            if "score_moving_avg" in df.columns:
                ax.plot(df["check_time"], df["score_moving_avg"], linestyle="--", color="orange",
                       label="移动平均")

            ax.fill_between(
                df["check_time"],
                df["overall_score"].where(df["overall_score"] >= 80),
                80,
                alpha=0.2,
                color="green",
                label="优秀区间",
            )
            ax.fill_between(
                df["check_time"],
                df["overall_score"].where((df["overall_score"] >= 60) & (df["overall_score"] < 80)),
                60,
                alpha=0.2,
                color="yellow",
                label="良好区间",
            )
            ax.fill_between(
                df["check_time"],
                df["overall_score"].where(df["overall_score"] < 60),
                0,
                alpha=0.2,
                color="red",
                label="危险区间",
            )

            ax.set_xlabel("检查时间")
            ax.set_ylabel("总体得分")
            ax.set_title("数据质量趋势分析")
            ax.set_ylim(0, 100)
            ax.legend()
            ax.grid(True, alpha=0.3)
            plt.xticks(rotation=45, ha="right")

            plt.tight_layout()

            chart_path = os.path.join(self.output_dir, f"quality_trend_{timestamp}.png")
            plt.savefig(chart_path, dpi=100, bbox_inches="tight")
            plt.close(fig)

            return chart_path
        except Exception as e:
            return None

    def _check_alert(
        self,
        check_result: CheckResult,
    ) -> Tuple[bool, Optional[str]]:
        if check_result.overall_score >= self.alert_config.threshold:
            return False, None

        alert_key = f"alert_{check_result.check_time.strftime('%Y%m%d')}"
        last_alert = self._last_alert_time.get(alert_key)

        if last_alert:
            time_since_last = (datetime.now() - last_alert).total_seconds()
            if time_since_last < self.alert_config.min_interval:
                return False, None

        level = check_result.get_quality_level()
        failed_rules = check_result.get_failed_results()
        failed_rule_names = [r.rule_name for r in failed_rules]

        message = (
            f"【数据质量告警】\n"
            f"时间: {check_result.check_time.strftime('%Y-%m-%d %H:%M:%S')}\n"
            f"总体得分: {check_result.overall_score:.2f}\n"
            f"质量等级: {level}\n"
            f"失败规则: {', '.join(failed_rule_names)}\n"
            f"影响记录数: {len(check_result.get_all_failed_indices())}"
        )

        return True, message

    def _send_alert(
        self,
        check_result: CheckResult,
        message: str,
    ) -> None:
        alert_key = f"alert_{check_result.check_time.strftime('%Y%m%d')}"
        self._last_alert_time[alert_key] = datetime.now()

        for channel in self.alert_config.channels:
            try:
                if channel == AlertChannel.CONSOLE:
                    print(message)
                elif channel == AlertChannel.WEBHOOK and self.alert_config.webhook_url:
                    self._send_webhook(message)
                elif channel == AlertChannel.EMAIL and self.alert_config.email_config:
                    self._send_email(message)

                for handler in self.alert_config.handlers:
                    try:
                        handler(check_result)
                    except Exception:
                        pass
            except Exception:
                pass

    def _send_webhook(self, message: str) -> None:
        try:
            import urllib.request
            data = json.dumps({"text": message}).encode("utf-8")
            req = urllib.request.Request(
                self.alert_config.webhook_url,
                data=data,
                headers={"Content-Type": "application/json"},
            )
            urllib.request.urlopen(req, timeout=10)
        except Exception:
            pass

    def _send_email(self, message: str) -> None:
        try:
            import smtplib
            from email.mime.text import MIMEText
            from email.header import Header

            config = self.alert_config.email_config or {}
            smtp_host = config.get("smtp_host")
            smtp_port = config.get("smtp_port", 587)
            username = config.get("username")
            password = config.get("password")
            sender = config.get("sender", username)
            receivers = config.get("receivers", [])

            if not (smtp_host and username and password and receivers):
                return

            msg = MIMEText(message, "plain", "utf-8")
            msg["From"] = Header(sender)
            msg["To"] = Header(",".join(receivers))
            msg["Subject"] = Header("数据质量告警", "utf-8")

            with smtplib.SMTP(smtp_host, smtp_port) as server:
                server.starttls()
                server.login(username, password)
                server.sendmail(sender, receivers, msg.as_string())
        except Exception:
            pass

    def generate_summary_report(
        self,
        checker: Optional[DataQualityChecker] = None,
        lookback_days: int = 30,
    ) -> Dict[str, Any]:
        if checker:
            self.trend_analyzer = TrendAnalyzer(checker.get_history())

        trend_analysis = self.trend_analyzer.analyze_trend(lookback_days=lookback_days)
        forecast = self.trend_analyzer.forecast(periods=7)

        if self.trend_analyzer.history:
            latest_result = self.trend_analyzer.history[-1]
            current_score = latest_result.overall_score
            quality_level = latest_result.get_quality_level()
        else:
            current_score = 0
            quality_level = "无数据"

        return {
            "report_time": datetime.now().isoformat(),
            "current_status": {
                "overall_score": round(current_score, 2),
                "quality_level": quality_level,
                "total_checks": len(self.trend_analyzer.history),
            },
            "trend_analysis": trend_analysis,
            "forecast": forecast,
            "recommendations": self._generate_summary_recommendations(trend_analysis, forecast),
        }

    def _generate_summary_recommendations(
        self,
        trend_analysis: Dict[str, Any],
        forecast: Dict[str, Any],
    ) -> List[str]:
        recommendations = []

        if not trend_analysis.get("has_data"):
            recommendations.append("建议尽快建立数据质量检查机制，定期执行数据质量校验")
            return recommendations

        if trend_analysis.get("trend") in ["明显下降", "略有下降"]:
            recommendations.append("数据质量呈下降趋势，建议成立专项小组进行质量提升")

        if forecast.get("trend_direction") == "下降":
            recommendations.append("预测显示数据质量将继续下降，建议立即采取干预措施")

        failing_rules = trend_analysis.get("failing_rules", [])
        if failing_rules:
            top_rule = failing_rules[0]
            recommendations.append(
                f"规则 '{top_rule['rule_name']}' 失败次数最多（{top_rule['total_failed']}次），"
                f"建议优先处理"
            )

        return recommendations

    def export_report_html(
        self,
        report: QualityReport,
        output_path: Optional[str] = None,
    ) -> str:
        if not output_path:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            output_path = os.path.join(self.output_dir, f"quality_report_{timestamp}.html")

        check_result = report.check_result

        html_content = f"""
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>数据质量报告</title>
    <style>
        body {{ font-family: Arial, sans-serif; margin: 20px; }}
        .header {{ background: #f5f5f5; padding: 20px; border-radius: 8px; margin-bottom: 20px; }}
        .score-box {{ font-size: 48px; font-weight: bold; text-align: center; padding: 20px; }}
        .score-excellent {{ color: #2ecc71; }}
        .score-good {{ color: #f39c12; }}
        .score-poor {{ color: #e74c3c; }}
        table {{ width: 100%; border-collapse: collapse; margin: 20px 0; }}
        th, td {{ border: 1px solid #ddd; padding: 12px; text-align: left; }}
        th {{ background: #f5f5f5; }}
        .passed {{ color: #2ecc71; }}
        .failed {{ color: #e74c3c; }}
        .chart {{ margin: 20px 0; text-align: center; }}
        .chart img {{ max-width: 100%; border-radius: 8px; }}
        .alert {{ background: #ffebee; padding: 15px; border-radius: 8px; margin: 20px 0; }}
        .recommendations {{ background: #e3f2fd; padding: 15px; border-radius: 8px; margin: 20px 0; }}
    </style>
</head>
<body>
    <div class="header">
        <h1>数据质量报告</h1>
        <p>生成时间: {report.generated_at.strftime('%Y-%m-%d %H:%M:%S')}</p>
    </div>
"""

        score_class = "score-poor"
        if check_result.overall_score >= 90:
            score_class = "score-excellent"
        elif check_result.overall_score >= 70:
            score_class = "score-good"

        html_content += f"""
    <div class="score-box {score_class}">
        {check_result.overall_score:.2f} 分
        <div style="font-size: 20px; margin-top: 10px;">质量等级: {check_result.get_quality_level()}</div>
    </div>
"""

        if report.alert_sent and report.alert_message:
            html_content += f"""
    <div class="alert">
        <h3>⚠️ 告警信息</h3>
        <pre>{report.alert_message}</pre>
    </div>
"""

        html_content += f"""
    <h2>检查概览</h2>
    <table>
        <tr><th>指标</th><th>值</th></tr>
        <tr><td>检查模式</td><td>{check_result.mode.value}</td></tr>
        <tr><td>数据记录数</td><td>{check_result.total_records}</td></tr>
        <tr><td>规则总数</td><td>{check_result.total_rules}</td></tr>
        <tr><td>通过规则数</td><td class="passed">{check_result.passed_rules}</td></tr>
        <tr><td>失败规则数</td><td class="failed">{check_result.failed_rules}</td></tr>
        <tr><td>规则通过率</td><td>{check_result.pass_rate * 100:.2f}%</td></tr>
    </table>
"""

        html_content += """
    <h2>规则详情</h2>
    <table>
        <tr><th>规则名称</th><th>类型</th><th>列</th><th>状态</th><th>失败数</th><th>通过率</th><th>得分</th><th>消息</th></tr>
"""

        for result in check_result.rule_results:
            status_class = "passed" if result.passed else "failed"
            status_text = "通过" if result.passed else "失败"
            html_content += f"""
        <tr>
            <td>{result.rule_name}</td>
            <td>{result.rule_type.value}</td>
            <td>{result.column or '-'}</td>
            <td class="{status_class}">{status_text}</td>
            <td>{result.failed_count}</td>
            <td>{result.pass_rate * 100:.2f}%</td>
            <td>{result.score:.2f}</td>
            <td>{result.message}</td>
        </tr>
"""

        html_content += """
    </table>
"""

        if report.charts:
            html_content += "<h2>可视化图表</h2>"
            for chart_name, chart_path in report.charts.items():
                html_content += f"""
    <div class="chart">
        <h3>{chart_name}</h3>
        <img src="{chart_path}" alt="{chart_name}">
    </div>
"""

        html_content += """
</body>
</html>
"""

        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            f.write(html_content)

        return output_path
