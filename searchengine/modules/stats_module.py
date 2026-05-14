import logging
import json
import os
from typing import Dict, Any, Optional, List
from datetime import datetime, date
from collections import defaultdict
from searchengine.config.settings import settings
from searchengine.models.base import SearchStats


class StatsModule:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._stats: Dict[str, SearchStats] = {}
        self._stat_counter = 0
        self._load_from_file()
    
    def _get_today_key(self) -> str:
        return date.today().strftime("%Y-%m-%d")
    
    def _generate_stat_id(self) -> str:
        self._stat_counter += 1
        return f"stat_{self._stat_counter:03d}"
    
    def _get_or_create_today_stats(self) -> SearchStats:
        today_key = self._get_today_key()
        if today_key not in self._stats:
            self._stats[today_key] = SearchStats(
                stat_id=self._generate_stat_id(),
                stat_date=today_key,
                search_count=0,
                click_count=0,
                avg_search_time=0.0,
                hot_keywords=[]
            )
        return self._stats[today_key]
    
    def increment_search_count(self) -> int:
        stats = self._get_or_create_today_stats()
        stats.search_count += 1
        self._save_to_file()
        return stats.search_count
    
    def increment_click_count(self) -> int:
        stats = self._get_or_create_today_stats()
        stats.click_count += 1
        self._save_to_file()
        return stats.click_count
    
    def update_search_time(self, search_time_ms: float) -> None:
        stats = self._get_or_create_today_stats()
        total_time = stats.avg_search_time * (stats.search_count - 1)
        total_time += search_time_ms
        stats.avg_search_time = total_time / stats.search_count if stats.search_count > 0 else 0
        self._save_to_file()
    
    def update_hot_keyword(self, keyword: str, count: int = 1) -> None:
        stats = self._get_or_create_today_stats()
        
        found = False
        for hot in stats.hot_keywords:
            if hot.get("keyword") == keyword:
                hot["count"] = hot.get("count", 0) + count
                found = True
                break
        
        if not found:
            stats.hot_keywords.append({
                "keyword": keyword,
                "count": count
            })
        
        stats.hot_keywords.sort(key=lambda x: x.get("count", 0), reverse=True)
        stats.hot_keywords = stats.hot_keywords[:100]
        self._save_to_file()
    
    def get_today_stats(self) -> SearchStats:
        return self._get_or_create_today_stats()
    
    def get_stats_by_date(self, stat_date: str) -> Optional[SearchStats]:
        return self._stats.get(stat_date)
    
    def get_all_stats(self) -> List[SearchStats]:
        return list(self._stats.values())
    
    def get_hot_keywords(self, top_n: int = 10) -> List[Dict[str, Any]]:
        stats = self._get_or_create_today_stats()
        return stats.hot_keywords[:top_n]
    
    def get_total_search_count(self) -> int:
        return sum(s.search_count for s in self._stats.values())
    
    def get_total_click_count(self) -> int:
        return sum(s.click_count for s in self._stats.values())
    
    def get_overall_avg_search_time(self) -> float:
        total_time = sum(s.avg_search_time * s.search_count for s in self._stats.values())
        total_searches = sum(s.search_count for s in self._stats.values())
        return total_time / total_searches if total_searches > 0 else 0.0
    
    def clear_old_stats(self, days_to_keep: int = 30) -> int:
        today = date.today()
        keys_to_delete = []
        
        for key in self._stats:
            try:
                stat_date = datetime.strptime(key, "%Y-%m-%d").date()
                age = (today - stat_date).days
                if age > days_to_keep:
                    keys_to_delete.append(key)
            except:
                keys_to_delete.append(key)
        
        deleted_count = len(keys_to_delete)
        for key in keys_to_delete:
            del self._stats[key]
        
        self._save_to_file()
        self.logger.info(f"Cleared {deleted_count} old stats entries")
        return deleted_count
    
    def _save_to_file(self) -> None:
        try:
            data = {
                key: stat.model_dump()
                for key, stat in self._stats.items()
            }
            with open(settings.STAT_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, default=str, indent=2, ensure_ascii=False)
        except Exception as e:
            self.logger.warning(f"Failed to save stats to file: {e}")
    
    def _load_from_file(self) -> None:
        if not os.path.exists(settings.STAT_FILE):
            return
        
        try:
            with open(settings.STAT_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            
            for key, stat_data in data.items():
                try:
                    self._stats[key] = SearchStats(**stat_data)
                    stat_id = stat_data.get("stat_id", "")
                    if stat_id.startswith("stat_"):
                        try:
                            num = int(stat_id.replace("stat_", ""))
                            self._stat_counter = max(self._stat_counter, num)
                        except:
                            pass
                except Exception as e:
                    self.logger.warning(f"Failed to load stat {key}: {e}")
            
            self.logger.info(f"Loaded {len(self._stats)} stats entries from file")
        except Exception as e:
            self.logger.warning(f"Failed to load stats from file: {e}")


stats_module = StatsModule()
