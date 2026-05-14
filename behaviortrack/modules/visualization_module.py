import logging
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
from matplotlib.font_manager import FontProperties
import numpy as np

from ..config import settings
from ..storage import MongoStorage


logger = logging.getLogger(__name__)


class VisualizationModule:
    def __init__(self) -> None:
        self.storage = MongoStorage()
        self.output_dir = Path(settings.VISUALIZATION_OUTPUT_DIR)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
        self._setup_matplotlib()
    
    def _setup_matplotlib(self) -> None:
        plt.rcParams["figure.figsize"] = (12, 8)
        plt.rcParams["font.size"] = 12
        plt.rcParams["axes.titlesize"] = 16
        plt.rcParams["axes.labelsize"] = 14
        plt.rcParams["figure.dpi"] = 100
        
        try:
            font = FontProperties(family="Arial Unicode MS", size=12)
            plt.rcParams["font.family"] = font.get_family()
        except Exception:
            pass
    
    def generate_event_trend_chart(
        self,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        output_filename: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            if not start_date:
                start_date = (date.today() - timedelta(days=7)).isoformat()
            if not end_date:
                end_date = date.today().isoformat()
            
            pipeline = [
                {
                    "$match": {
                        "timestamp": {
                            "$gte": f"{start_date}T00:00:00Z",
                            "$lte": f"{end_date}T23:59:59Z"
                        }
                    }
                },
                {
                    "$project": {
                        "date": {"$substr": ["$timestamp", 0, 10]},
                        "event_type": 1
                    }
                },
                {
                    "$group": {
                        "_id": {"date": "$date", "event_type": "$event_type"},
                        "count": {"$sum": 1}
                    }
                },
                {"$sort": {"_id.date": 1}}
            ]
            
            results = self.storage.aggregate_events(pipeline)
            
            if not results:
                return {
                    "success": False,
                    "error": "No data available for visualization"
                }
            
            dates = sorted(set(r["_id"]["date"] for r in results))
            event_types = sorted(set(r["_id"]["event_type"] for r in results))
            
            data_dict = {et: [0] * len(dates) for et in event_types}
            
            for result in results:
                d = result["_id"]["date"]
                et = result["_id"]["event_type"]
                idx = dates.index(d)
                data_dict[et][idx] = result["count"]
            
            fig, ax = plt.subplots()
            x = np.arange(len(dates))
            width = 0.8 / len(event_types) if event_types else 0.8
            
            for i, (event_type, counts) in enumerate(data_dict.items()):
                ax.bar(x + i * width, counts, width, label=event_type)
            
            ax.set_xlabel("日期")
            ax.set_ylabel("事件数量")
            ax.set_title(f"事件趋势 ({start_date} 至 {end_date})")
            ax.set_xticks(x + width * (len(event_types) - 1) / 2)
            ax.set_xticklabels(dates, rotation=45, ha="right")
            ax.legend()
            plt.tight_layout()
            
            if not output_filename:
                output_filename = f"event_trend_{start_date}_{end_date}.png"
            
            output_path = self.output_dir / output_filename
            plt.savefig(output_path, format="png", bbox_inches="tight")
            plt.close()
            
            logger.info(f"Generated event trend chart: {output_path}")
            
            return {
                "success": True,
                "file_path": str(output_path),
                "chart_type": "event_trend",
                "start_date": start_date,
                "end_date": end_date
            }
            
        except Exception as e:
            logger.exception(f"Error generating event trend chart: {str(e)}")
            plt.close()
            return {
                "success": False,
                "error": str(e)
            }
    
    def generate_event_distribution_pie(
        self,
        limit: int = 10,
        output_filename: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            pipeline = [
                {"$group": {"_id": "$event_type", "count": {"$sum": 1}}},
                {"$sort": {"count": -1}},
                {"$limit": limit}
            ]
            
            results = self.storage.aggregate_events(pipeline)
            
            if not results:
                return {
                    "success": False,
                    "error": "No data available for visualization"
                }
            
            labels = [r["_id"] for r in results]
            sizes = [r["count"] for r in results]
            
            fig, ax = plt.subplots()
            wedges, texts, autotexts = ax.pie(
                sizes,
                labels=labels,
                autopct="%1.1f%%",
                startangle=90
            )
            ax.axis("equal")
            ax.set_title("事件类型分布")
            
            plt.setp(autotexts, size=10, weight="bold")
            plt.tight_layout()
            
            if not output_filename:
                output_filename = "event_distribution.png"
            
            output_path = self.output_dir / output_filename
            plt.savefig(output_path, format="png", bbox_inches="tight")
            plt.close()
            
            logger.info(f"Generated event distribution pie chart: {output_path}")
            
            return {
                "success": True,
                "file_path": str(output_path),
                "chart_type": "pie",
                "total_events": sum(sizes)
            }
            
        except Exception as e:
            logger.exception(f"Error generating distribution pie chart: {str(e)}")
            plt.close()
            return {
                "success": False,
                "error": str(e)
            }
    
    def generate_hourly_activity_chart(
        self,
        output_filename: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            pipeline = [
                {
                    "$project": {
                        "hour": {"$hour": {"$toDate": "$timestamp"}}
                    }
                },
                {"$group": {"_id": "$hour", "count": {"$sum": 1}}},
                {"$sort": {"_id": 1}}
            ]
            
            results = self.storage.aggregate_events(pipeline)
            
            if not results:
                return {
                    "success": False,
                    "error": "No data available for visualization"
                }
            
            hours = list(range(24))
            counts = [0] * 24
            
            for result in results:
                hour = result["_id"]
                if 0 <= hour < 24:
                    counts[hour] = result["count"]
            
            fig, ax = plt.subplots()
            ax.plot(hours, counts, marker="o", linewidth=2, color="#2563eb")
            ax.fill_between(hours, counts, alpha=0.3, color="#2563eb")
            
            ax.set_xlabel("小时 (0-23)")
            ax.set_ylabel("事件数量")
            ax.set_title("24小时活跃度分布")
            ax.set_xticks(hours)
            ax.grid(True, alpha=0.3)
            plt.tight_layout()
            
            if not output_filename:
                output_filename = "hourly_activity.png"
            
            output_path = self.output_dir / output_filename
            plt.savefig(output_path, format="png", bbox_inches="tight")
            plt.close()
            
            logger.info(f"Generated hourly activity chart: {output_path}")
            
            return {
                "success": True,
                "file_path": str(output_path),
                "chart_type": "line",
                "peak_hour": counts.index(max(counts))
            }
            
        except Exception as e:
            logger.exception(f"Error generating hourly activity chart: {str(e)}")
            plt.close()
            return {
                "success": False,
                "error": str(e)
            }
    
    def generate_active_users_chart(
        self,
        period: str = "daily",
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        output_filename: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            if not start_date:
                if period == "daily":
                    start_date = (date.today() - timedelta(days=7)).isoformat()
                elif period == "weekly":
                    start_date = (date.today() - timedelta(weeks=4)).isoformat()
                else:
                    start_date = (date.today() - timedelta(days=30)).isoformat()
            if not end_date:
                end_date = date.today().isoformat()
            
            if period == "daily":
                date_format = {"$substr": ["$timestamp", 0, 10]}
            elif period == "weekly":
                date_format = {
                    "$dateToString": {
                        "format": "%G-W%V",
                        "date": {"$toDate": "$timestamp"}
                    }
                }
            else:
                date_format = {"$substr": ["$timestamp", 0, 7]}
            
            pipeline = [
                {
                    "$match": {
                        "timestamp": {
                            "$gte": f"{start_date}T00:00:00Z",
                            "$lte": f"{end_date}T23:59:59Z"
                        }
                    }
                },
                {
                    "$project": {
                        "period": date_format,
                        "user_id": 1
                    }
                },
                {
                    "$group": {
                        "_id": {
                            "period": "$period",
                            "user_id": "$user_id"
                        }
                    }
                },
                {
                    "$group": {
                        "_id": "$_id.period",
                        "active_users": {"$sum": 1}
                    }
                },
                {"$sort": {"_id": 1}}
            ]
            
            results = self.storage.aggregate_events(pipeline)
            
            if not results:
                return {
                    "success": False,
                    "error": "No data available for visualization"
                }
            
            periods = [r["_id"] for r in results]
            active_users = [r["active_users"] for r in results]
            
            fig, ax = plt.subplots()
            bars = ax.bar(periods, active_users, color="#10b981", alpha=0.8)
            
            ax.set_xlabel(f"时间段 ({period})")
            ax.set_ylabel("活跃用户数")
            ax.set_title(f"活跃用户趋势 ({start_date} 至 {end_date})")
            ax.set_xticklabels(periods, rotation=45, ha="right")
            
            for bar, count in zip(bars, active_users):
                height = bar.get_height()
                ax.text(
                    bar.get_x() + bar.get_width() / 2,
                    height,
                    str(count),
                    ha="center",
                    va="bottom"
                )
            
            plt.tight_layout()
            
            if not output_filename:
                output_filename = f"active_users_{period}_{start_date}_{end_date}.png"
            
            output_path = self.output_dir / output_filename
            plt.savefig(output_path, format="png", bbox_inches="tight")
            plt.close()
            
            logger.info(f"Generated active users chart: {output_path}")
            
            return {
                "success": True,
                "file_path": str(output_path),
                "chart_type": "bar",
                "period": period,
                "avg_active_users": round(sum(active_users) / len(active_users), 2) if active_users else 0
            }
            
        except Exception as e:
            logger.exception(f"Error generating active users chart: {str(e)}")
            plt.close()
            return {
                "success": False,
                "error": str(e)
            }
    
    def generate_tag_distribution_chart(
        self,
        output_filename: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            pipeline = [
                {"$unwind": "$profile_tags"},
                {"$group": {"_id": "$profile_tags", "count": {"$sum": 1}}},
                {"$sort": {"count": -1}}
            ]
            
            results = list(self.storage.profiles_collection.aggregate(pipeline))
            
            if not results:
                return {
                    "success": False,
                    "error": "No profile tags found"
                }
            
            tags = [r["_id"] for r in results]
            counts = [r["count"] for r in results]
            
            fig, ax = plt.subplots()
            y_pos = np.arange(len(tags))
            
            bars = ax.barh(y_pos, counts, color="#f59e0b", alpha=0.8)
            ax.set_yticks(y_pos)
            ax.set_yticklabels(tags)
            ax.set_xlabel("用户数量")
            ax.set_title("用户标签分布")
            ax.invert_yaxis()
            
            for bar, count in zip(bars, counts):
                width = bar.get_width()
                ax.text(
                    width,
                    bar.get_y() + bar.get_height() / 2,
                    str(count),
                    ha="left",
                    va="center"
                )
            
            plt.tight_layout()
            
            if not output_filename:
                output_filename = "tag_distribution.png"
            
            output_path = self.output_dir / output_filename
            plt.savefig(output_path, format="png", bbox_inches="tight")
            plt.close()
            
            logger.info(f"Generated tag distribution chart: {output_path}")
            
            return {
                "success": True,
                "file_path": str(output_path),
                "chart_type": "horizontal_bar"
            }
            
        except Exception as e:
            logger.exception(f"Error generating tag distribution chart: {str(e)}")
            plt.close()
            return {
                "success": False,
                "error": str(e)
            }
    
    def list_visualizations(self) -> Dict[str, Any]:
        try:
            files = []
            for file_path in sorted(self.output_dir.iterdir()):
                if file_path.is_file() and file_path.suffix in [".png", ".jpg", ".jpeg"]:
                    stat = file_path.stat()
                    files.append({
                        "filename": file_path.name,
                        "size_bytes": stat.st_size
                    })
            
            return {
                "success": True,
                "files": files,
                "output_dir": str(self.output_dir)
            }
            
        except Exception as e:
            logger.exception(f"Error listing visualizations: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_visualization(
        self,
        filename: str
    ) -> Optional[Path]:
        try:
            file_path = self.output_dir / filename
            if file_path.exists() and file_path.is_file():
                return file_path
        except Exception as e:
            logger.exception(f"Error getting visualization: {str(e)}")
        return None
