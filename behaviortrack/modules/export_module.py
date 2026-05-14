import logging
import json
import csv
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

import pandas as pd

from ..config import settings
from ..storage import MongoStorage


logger = logging.getLogger(__name__)


class ExportModule:
    def __init__(self) -> None:
        self.storage = MongoStorage()
        self.export_dir = Path(settings.EXPORT_OUTPUT_DIR)
        self.export_dir.mkdir(parents=True, exist_ok=True)
    
    def export_events(
        self,
        file_format: str = "json",
        user_id: Optional[str] = None,
        event_type: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        limit: int = 10000
    ) -> Dict[str, Any]:
        try:
            query: Dict[str, Any] = {}
            
            if user_id:
                query["user_id"] = user_id
            if event_type:
                query["event_type"] = event_type
            if start_date or end_date:
                query["timestamp"] = {}
                if start_date:
                    query["timestamp"]["$gte"] = start_date
                if end_date:
                    query["timestamp"]["$lte"] = end_date
            
            events = self.storage.find_events(query, limit=limit)
            event_dicts = [e.to_dict() for e in events]
            
            if not event_dicts:
                return {
                    "success": False,
                    "error": "No events found for export"
                }
            
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"events_{timestamp}"
            
            if file_format == "json":
                file_path = self._export_to_json(event_dicts, filename)
            elif file_format == "csv":
                file_path = self._export_to_csv(event_dicts, filename)
            elif file_format == "xlsx":
                file_path = self._export_to_excel(event_dicts, filename)
            else:
                return {
                    "success": False,
                    "error": f"Unsupported format: {file_format}"
                }
            
            return {
                "success": True,
                "file_path": str(file_path),
                "file_format": file_format,
                "record_count": len(event_dicts)
            }
            
        except Exception as e:
            logger.exception(f"Error exporting events: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def export_user_profile(
        self,
        user_id: str,
        file_format: str = "json"
    ) -> Dict[str, Any]:
        try:
            profile = self.storage.find_profile_by_user_id(user_id)
            
            if not profile:
                return {
                    "success": False,
                    "error": "Profile not found"
                }
            
            profile_dict = profile.to_dict()
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"profile_{user_id}_{timestamp}"
            
            if file_format == "json":
                file_path = self._export_to_json([profile_dict], filename)
            elif file_format == "csv":
                file_path = self._export_to_csv([profile_dict], filename)
            else:
                return {
                    "success": False,
                    "error": f"Unsupported format: {file_format}"
                }
            
            return {
                "success": True,
                "file_path": str(file_path),
                "file_format": file_format
            }
            
        except Exception as e:
            logger.exception(f"Error exporting profile: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def export_statistics(
        self,
        stats_type: str = "daily",
        file_format: str = "json",
        start_date: Optional[str] = None,
        end_date: Optional[str] = None
    ) -> Dict[str, Any]:
        try:
            if stats_type == "daily":
                pipeline = [
                    {
                        "$project": {
                            "date": {"$substr": ["$timestamp", 0, 10]},
                            "user_id": 1,
                            "event_type": 1
                        }
                    },
                    {
                        "$group": {
                            "_id": {
                                "date": "$date",
                                "event_type": "$event_type"
                            },
                            "event_count": {"$sum": 1},
                            "user_count": {"$addToSet": "$user_id"}
                        }
                    },
                    {
                        "$project": {
                            "_id": 0,
                            "date": "$_id.date",
                            "event_type": "$_id.event_type",
                            "event_count": 1,
                            "user_count": {"$size": "$user_count"}
                        }
                    },
                    {"$sort": {"date": 1, "event_type": 1}}
                ]
                
                if start_date or end_date:
                    match_stage = {"$match": {"timestamp": {}}}
                    if start_date:
                        match_stage["$match"]["timestamp"]["$gte"] = start_date
                    if end_date:
                        match_stage["$match"]["timestamp"]["$lte"] = end_date
                    pipeline.insert(0, match_stage)
                
                stats_data = self.storage.aggregate_events(pipeline)
            elif stats_type == "event_distribution":
                pipeline = [
                    {"$group": {"_id": "$event_type", "count": {"$sum": 1}}},
                    {"$sort": {"count": -1}}
                ]
                results = self.storage.aggregate_events(pipeline)
                stats_data = [
                    {"event_type": r["_id"], "count": r["count"]}
                    for r in results
                ]
            else:
                return {
                    "success": False,
                    "error": f"Unsupported stats type: {stats_type}"
                }
            
            if not stats_data:
                return {
                    "success": False,
                    "error": "No statistics data found"
                }
            
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"stats_{stats_type}_{timestamp}"
            
            if file_format == "json":
                file_path = self._export_to_json(stats_data, filename)
            elif file_format == "csv":
                file_path = self._export_to_csv(stats_data, filename)
            elif file_format == "xlsx":
                file_path = self._export_to_excel(stats_data, filename)
            else:
                return {
                    "success": False,
                    "error": f"Unsupported format: {file_format}"
                }
            
            return {
                "success": True,
                "file_path": str(file_path),
                "file_format": file_format,
                "record_count": len(stats_data)
            }
            
        except Exception as e:
            logger.exception(f"Error exporting statistics: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def _export_to_json(
        self,
        data: List[Dict[str, Any]],
        filename: str
    ) -> Path:
        file_path = self.export_dir / f"{filename}.json"
        
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        
        logger.info(f"Exported {len(data)} records to {file_path}")
        return file_path
    
    def _export_to_csv(
        self,
        data: List[Dict[str, Any]],
        filename: str
    ) -> Path:
        file_path = self.export_dir / f"{filename}.csv"
        
        if not data:
            with open(file_path, "w", encoding="utf-8") as f:
                pass
            return file_path
        
        all_keys = set()
        for item in data:
            all_keys.update(self._flatten_keys(item))
        
        with open(file_path, "w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=sorted(all_keys))
            writer.writeheader()
            
            for item in data:
                flat_item = self._flatten_dict(item)
                writer.writerow(flat_item)
        
        logger.info(f"Exported {len(data)} records to {file_path}")
        return file_path
    
    def _export_to_excel(
        self,
        data: List[Dict[str, Any]],
        filename: str
    ) -> Path:
        file_path = self.export_dir / f"{filename}.xlsx"
        
        df = pd.DataFrame(data)
        df.to_excel(file_path, index=False, engine="openpyxl")
        
        logger.info(f"Exported {len(data)} records to {file_path}")
        return file_path
    
    def _flatten_dict(
        self,
        d: Dict[str, Any],
        parent_key: str = "",
        sep: str = "."
    ) -> Dict[str, Any]:
        items: Dict[str, Any] = {}
        for k, v in d.items():
            new_key = f"{parent_key}{sep}{k}" if parent_key else k
            if isinstance(v, dict):
                items.update(self._flatten_dict(v, new_key, sep=sep))
            elif isinstance(v, list):
                items[new_key] = json.dumps(v, ensure_ascii=False)
            else:
                items[new_key] = v
        return items
    
    def _flatten_keys(
        self,
        d: Dict[str, Any],
        parent_key: str = "",
        sep: str = "."
    ) -> List[str]:
        keys = []
        for k, v in d.items():
            new_key = f"{parent_key}{sep}{k}" if parent_key else k
            if isinstance(v, dict):
                keys.extend(self._flatten_keys(v, new_key, sep=sep))
            else:
                keys.append(new_key)
        return keys
    
    def list_export_files(self) -> Dict[str, Any]:
        try:
            files = []
            for file_path in sorted(self.export_dir.iterdir()):
                if file_path.is_file():
                    stat = file_path.stat()
                    files.append({
                        "filename": file_path.name,
                        "size_bytes": stat.st_size,
                        "created_at": datetime.fromtimestamp(stat.st_ctime).isoformat()
                    })
            
            return {
                "success": True,
                "files": files,
                "export_dir": str(self.export_dir)
            }
            
        except Exception as e:
            logger.exception(f"Error listing export files: {str(e)}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def get_export_file(
        self,
        filename: str
    ) -> Optional[Path]:
        try:
            file_path = self.export_dir / filename
            if file_path.exists() and file_path.is_file():
                return file_path
        except Exception as e:
            logger.exception(f"Error getting export file: {str(e)}")
        return None
