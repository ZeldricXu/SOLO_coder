import csv
import json
import uuid
from datetime import datetime
from typing import List, Dict, Optional
from pathlib import Path
import io

from app.core.config import settings
from app.modules.result_storage import result_storage


class Exporter:
    def __init__(self):
        self._exports_dir = settings.EXPORTS_DIR

    def _generate_export_id(self) -> str:
        return f"export_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"

    def _get_export_path(self, export_id: str, format: str) -> Path:
        filename = f"{export_id}.{format}"
        return self._exports_dir / filename

    def export_to_json(
        self,
        results: List[Dict],
        include_metadata: bool = True
    ) -> Dict:
        try:
            export_id = self._generate_export_id()
            export_path = self._get_export_path(export_id, "json")

            export_data = {
                "export_id": export_id,
                "exported_at": datetime.now().isoformat(),
                "total_count": len(results),
                "results": results
            }

            if include_metadata:
                export_data["metadata"] = {
                    "format": "json",
                    "version": "1.0",
                    "generated_by": "TextClassifier"
                }

            with open(export_path, "w", encoding="utf-8") as f:
                json.dump(export_data, f, ensure_ascii=False, indent=2)

            return {
                "success": True,
                "message": "JSON导出成功",
                "export_id": export_id,
                "file_path": str(export_path),
                "total_count": len(results)
            }

        except Exception as e:
            return {
                "success": False,
                "message": f"JSON导出失败: {str(e)}"
            }

    def export_to_csv(
        self,
        results: List[Dict],
        include_metadata: bool = True
    ) -> Dict:
        try:
            export_id = self._generate_export_id()
            export_path = self._get_export_path(export_id, "csv")

            fieldnames = [
                "result_id", "request_id", "text",
                "categories", "categories_confidence",
                "sentiment", "sentiment_confidence",
                "keywords", "model_version", "confidence_threshold",
                "classified_at"
            ]

            with open(export_path, "w", encoding="utf-8-sig", newline="") as f:
                writer = csv.DictWriter(f, fieldnames=fieldnames)
                writer.writeheader()

                for result in results:
                    categories = result.get("categories", [])
                    category_labels = "; ".join([c.get("label", "") for c in categories])
                    category_confidences = "; ".join([str(c.get("confidence", 0)) for c in categories])

                    sentiment = result.get("sentiment", {})
                    sentiment_label = sentiment.get("label", "neutral")
                    sentiment_confidence = sentiment.get("confidence", 0.5)

                    keywords = result.get("keywords", [])
                    keywords_str = "; ".join(keywords)

                    row = {
                        "result_id": result.get("result_id", ""),
                        "request_id": result.get("request_id", ""),
                        "text": result.get("text", ""),
                        "categories": category_labels,
                        "categories_confidence": category_confidences,
                        "sentiment": sentiment_label,
                        "sentiment_confidence": sentiment_confidence,
                        "keywords": keywords_str,
                        "model_version": result.get("model_version", ""),
                        "confidence_threshold": result.get("confidence_threshold", 0.0),
                        "classified_at": result.get("classified_at", "")
                    }
                    writer.writerow(row)

            return {
                "success": True,
                "message": "CSV导出成功",
                "export_id": export_id,
                "file_path": str(export_path),
                "total_count": len(results)
            }

        except Exception as e:
            return {
                "success": False,
                "message": f"CSV导出失败: {str(e)}"
            }

    def export_by_query(
        self,
        format: str = "json",
        limit: int = None,
        offset: int = 0,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        model_version: Optional[str] = None,
        include_metadata: bool = True
    ) -> Dict:
        query_result = result_storage.list_results(
            limit=limit or 10000,
            offset=offset,
            start_time=start_time,
            end_time=end_time,
            model_version=model_version
        )

        results = query_result.get("results", [])

        if format.lower() == "csv":
            return self.export_to_csv(results, include_metadata)
        else:
            return self.export_to_json(results, include_metadata)

    def export_by_ids(
        self,
        result_ids: List[str],
        format: str = "json",
        include_metadata: bool = True
    ) -> Dict:
        results = []
        for result_id in result_ids:
            result = result_storage.get_result(result_id)
            if result:
                results.append(result)

        if not results:
            return {
                "success": False,
                "message": "未找到指定的结果"
            }

        if format.lower() == "csv":
            return self.export_to_csv(results, include_metadata)
        else:
            return self.export_to_json(results, include_metadata)

    def get_export_file(self, export_id: str) -> Optional[Dict]:
        json_path = self._get_export_path(export_id, "json")
        csv_path = self._get_export_path(export_id, "csv")

        if json_path.exists():
            return {
                "export_id": export_id,
                "format": "json",
                "file_path": str(json_path),
                "file_size": json_path.stat().st_size,
                "created_at": datetime.fromtimestamp(json_path.stat().st_ctime).isoformat()
            }
        elif csv_path.exists():
            return {
                "export_id": export_id,
                "format": "csv",
                "file_path": str(csv_path),
                "file_size": csv_path.stat().st_size,
                "created_at": datetime.fromtimestamp(csv_path.stat().st_ctime).isoformat()
            }

        return None

    def list_exports(self) -> List[Dict]:
        exports = []

        if not self._exports_dir.exists():
            return exports

        for file_path in self._exports_dir.iterdir():
            if file_path.is_file():
                if file_path.suffix in [".json", ".csv"]:
                    export_id = file_path.stem
                    file_info = {
                        "export_id": export_id,
                        "format": file_path.suffix[1:],
                        "file_path": str(file_path),
                        "file_size": file_path.stat().st_size,
                        "created_at": datetime.fromtimestamp(file_path.stat().st_ctime).isoformat()
                    }
                    exports.append(file_info)

        exports.sort(key=lambda x: x["created_at"], reverse=True)
        return exports

    def delete_export(self, export_id: str) -> Dict:
        json_path = self._get_export_path(export_id, "json")
        csv_path = self._get_export_path(export_id, "csv")

        deleted = False

        if json_path.exists():
            json_path.unlink()
            deleted = True

        if csv_path.exists():
            csv_path.unlink()
            deleted = True

        if deleted:
            return {
                "success": True,
                "message": f"导出文件删除成功: {export_id}"
            }
        else:
            return {
                "success": False,
                "message": f"导出文件不存在: {export_id}"
            }

    def results_to_json_string(
        self,
        results: List[Dict],
        include_metadata: bool = True
    ) -> str:
        export_data = {
            "exported_at": datetime.now().isoformat(),
            "total_count": len(results),
            "results": results
        }

        if include_metadata:
            export_data["metadata"] = {
                "format": "json",
                "version": "1.0",
                "generated_by": "TextClassifier"
            }

        return json.dumps(export_data, ensure_ascii=False, indent=2)

    def results_to_csv_string(
        self,
        results: List[Dict]
    ) -> str:
        output = io.StringIO()

        fieldnames = [
            "result_id", "request_id", "text",
            "categories", "categories_confidence",
            "sentiment", "sentiment_confidence",
            "keywords", "model_version", "confidence_threshold",
            "classified_at"
        ]

        writer = csv.DictWriter(output, fieldnames=fieldnames)
        writer.writeheader()

        for result in results:
            categories = result.get("categories", [])
            category_labels = "; ".join([c.get("label", "") for c in categories])
            category_confidences = "; ".join([str(c.get("confidence", 0)) for c in categories])

            sentiment = result.get("sentiment", {})
            sentiment_label = sentiment.get("label", "neutral")
            sentiment_confidence = sentiment.get("confidence", 0.5)

            keywords = result.get("keywords", [])
            keywords_str = "; ".join(keywords)

            row = {
                "result_id": result.get("result_id", ""),
                "request_id": result.get("request_id", ""),
                "text": result.get("text", ""),
                "categories": category_labels,
                "categories_confidence": category_confidences,
                "sentiment": sentiment_label,
                "sentiment_confidence": sentiment_confidence,
                "keywords": keywords_str,
                "model_version": result.get("model_version", ""),
                "confidence_threshold": result.get("confidence_threshold", 0.0),
                "classified_at": result.get("classified_at", "")
            }
            writer.writerow(row)

        return output.getvalue()


exporter = Exporter()
