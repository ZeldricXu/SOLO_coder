import logging
import json
import threading
import os
import importlib
from typing import Dict, Any, List, Optional, Callable
from datetime import datetime
from dataclasses import dataclass, field
from abc import ABC, abstractmethod


@dataclass
class ScorerConfig:
    name: str
    weight: float = 1.0
    description: str = ""
    params: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "weight": self.weight,
            "description": self.description,
            "params": self.params
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'ScorerConfig':
        return cls(
            name=data["name"],
            weight=data.get("weight", 1.0),
            description=data.get("description", ""),
            params=data.get("params", {})
        )


@dataclass
class StrategyConfig:
    strategy_id: str
    name: str = ""
    description: str = ""
    enabled: bool = True
    scorers: List[ScorerConfig] = field(default_factory=list)
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "enabled": self.enabled,
            "scorers": [s.to_dict() for s in self.scorers]
        }
    
    @classmethod
    def from_dict(cls, strategy_id: str, data: Dict[str, Any]) -> 'StrategyConfig':
        return cls(
            strategy_id=strategy_id,
            name=data.get("name", strategy_id),
            description=data.get("description", ""),
            enabled=data.get("enabled", True),
            scorers=[ScorerConfig.from_dict(s) for s in data.get("scorers", [])]
        )
    
    def get_total_weight(self) -> float:
        return sum(s.weight for s in self.scorers)
    
    def normalize_weights(self) -> None:
        total = self.get_total_weight()
        if total > 0:
            for scorer in self.scorers:
                scorer.weight = scorer.weight / total


class Scorer(ABC):
    @abstractmethod
    def score(self, index, result: Dict[str, Any], context: Dict[str, Any] = None) -> float:
        pass
    
    @abstractmethod
    def name(self) -> str:
        pass


class BM25Scorer(Scorer):
    def name(self) -> str:
        return "bm25"
    
    def score(self, index, result: Dict[str, Any], context: Dict[str, Any] = None) -> float:
        bm25_score = result.get("bm25_score", 0)
        
        if not hasattr(index, '_bm25_max_score') or index._bm25_max_score is None:
            if hasattr(index, 'search'):
                query = context.get("query", "") if context else ""
                if query:
                    index._bm25_max_score = max(1.0, bm25_score * 1.5)
                else:
                    index._bm25_max_score = 100.0
            else:
                index._bm25_max_score = 100.0
        
        max_score = getattr(index, '_bm25_max_score', 100.0)
        if max_score > 0:
            return min(1.0, bm25_score / max_score)
        return 0.0


class RecencyScorer(Scorer):
    def name(self) -> str:
        return "recency"
    
    def score(self, index, result: Dict[str, Any], context: Dict[str, Any] = None) -> float:
        try:
            search_index = result.get("index")
            if search_index is None:
                return 0.0
            
            if hasattr(search_index, 'publish_date') and search_index.publish_date:
                if isinstance(search_index.publish_date, str):
                    try:
                        from datetime import datetime
                        publish_datetime = datetime.fromisoformat(search_index.publish_date.replace('Z', '+00:00'))
                    except ValueError:
                        return 0.3
                else:
                    publish_datetime = search_index.publish_date
                
                now = datetime.utcnow()
                days_old = (now - publish_datetime).days
                return max(0.0, 1.0 - (days_old / 365.0))
            return 0.3
        except Exception:
            return 0.3


class PopularityScorer(Scorer):
    def name(self) -> str:
        return "popularity"
    
    def score(self, index, result: Dict[str, Any], context: Dict[str, Any] = None) -> float:
        search_index = result.get("index")
        if search_index is None:
            return 0.0
        
        click_count = getattr(search_index, 'click_count', 0)
        
        max_clicks = getattr(index, '_max_clicks', None)
        if max_clicks is None:
            index._max_clicks = max(100, click_count * 2)
        
        max_clicks = getattr(index, '_max_clicks', 100)
        if max_clicks > 0:
            return min(1.0, click_count / max_clicks)
        return 0.0


class CategoryScorer(Scorer):
    def __init__(self, params: Dict[str, Any] = None):
        self._params = params or {}
        self._boost_categories = set(self._params.get("boost_categories", []))
        self._boost_factor = float(self._params.get("boost_factor", 1.5))
    
    def name(self) -> str:
        return "category"
    
    def score(self, index, result: Dict[str, Any], context: Dict[str, Any] = None) -> float:
        search_index = result.get("index")
        if search_index is None:
            return 0.0
        
        category = getattr(search_index, 'category', '')
        user_category = context.get("user_category", "") if context else ""
        
        score = 0.0
        if category in self._boost_categories:
            score = 0.5
        
        if user_category and category == user_category:
            score = 1.0
        
        return min(1.0, score * self._boost_factor / self._boost_factor)


class QualityScorer(Scorer):
    def __init__(self, params: Dict[str, Any] = None):
        self._params = params or {}
        self._read_time_threshold = self._params.get("read_time_threshold", 5)
        self._word_count_threshold = self._params.get("word_count_threshold", 1000)
    
    def name(self) -> str:
        return "quality"
    
    def score(self, index, result: Dict[str, Any], context: Dict[str, Any] = None) -> float:
        search_index = result.get("index")
        if search_index is None:
            return 0.0
        
        read_time = getattr(search_index, 'read_time', 0)
        content = getattr(search_index, 'content', '')
        word_count = len(content.split())
        
        scores = []
        
        if read_time >= self._read_time_threshold:
            scores.append(1.0)
        elif read_time > 0:
            scores.append(read_time / self._read_time_threshold)
        else:
            scores.append(0.3)
        
        if word_count >= self._word_count_threshold:
            scores.append(1.0)
        elif word_count > 0:
            scores.append(word_count / self._word_count_threshold)
        else:
            scores.append(0.3)
        
        return sum(scores) / len(scores)


class AuthorScorer(Scorer):
    def name(self) -> str:
        return "author"
    
    def score(self, index, result: Dict[str, Any], context: Dict[str, Any] = None) -> float:
        search_index = result.get("index")
        if search_index is None:
            return 0.0
        
        author_rating = getattr(search_index, 'author_rating', 0)
        
        if isinstance(author_rating, (int, float)):
            return min(1.0, author_rating / 5.0)
        
        if isinstance(author_rating, str) and author_rating.isdigit():
            return min(1.0, float(author_rating) / 5.0)
        
        return 0.3


class ScorerFactory:
    _instance = None
    _lock = threading.Lock()
    
    def __new__(cls):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
                    cls._instance._initialized = False
        return cls._instance
    
    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        self.logger = logging.getLogger(__name__)
        self._scorers: Dict[str, Scorer] = {}
        self._lock = threading.RLock()
        self._default_scorers = {
            "bm25": BM25Scorer,
            "recency": RecencyScorer,
            "popularity": PopularityScorer,
            "category": CategoryScorer,
            "quality": QualityScorer,
            "author": AuthorScorer
        }
    
    def register_scorer(self, name: str, scorer_class):
        with self._lock:
            self._default_scorers[name] = scorer_class
            if name in self._scorers:
                del self._scorers[name]
            self.logger.info(f"Registered scorer: {name}")
    
    def get_scorer(self, scorer_config: ScorerConfig) -> Optional[Scorer]:
        with self._lock:
            cache_key = f"{scorer_config.name}:{json.dumps(scorer_config.params, sort_keys=True)}"
            
            if cache_key in self._scorers:
                return self._scorers[cache_key]
            
            scorer_class = self._default_scorers.get(scorer_config.name)
            if scorer_class:
                try:
                    if scorer_config.params:
                        scorer = scorer_class(scorer_config.params)
                    else:
                        scorer = scorer_class()
                    self._scorers[cache_key] = scorer
                    return scorer
                except Exception as e:
                    self.logger.error(f"Failed to create scorer {scorer_config.name}: {e}")
                    return None
            
            self.logger.warning(f"Unknown scorer: {scorer_config.name}")
            return None


class SortStrategyManager:
    _instance = None
    _lock = threading.Lock()
    
    def __new__(cls):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
                    cls._instance._initialized = False
        return cls._instance
    
    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        self.logger = logging.getLogger(__name__)
        self._strategies: Dict[str, StrategyConfig] = {}
        self._default_strategy_id: str = "balanced"
        self._global_settings: Dict[str, Any] = {}
        self._lock = threading.RLock()
        self._config_file: Optional[str] = None
        self._scorer_factory = ScorerFactory()
        self._listeners: List[Callable] = []
    
    def add_listener(self, listener: Callable):
        with self._lock:
            self._listeners.append(listener)
    
    def remove_listener(self, listener: Callable) -> bool:
        with self._lock:
            try:
                self._listeners.remove(listener)
                return True
            except ValueError:
                return False
    
    def _notify_listeners(self, event: str, data: Dict[str, Any]):
        for listener in self._listeners:
            try:
                listener(event, data)
            except Exception as e:
                self.logger.error(f"Strategy manager listener error: {e}")
    
    def load_config(self, config_path: str = None) -> bool:
        with self._lock:
            if config_path is None:
                base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
                config_path = os.path.join(base_dir, "config", "sort_strategies.json")
            
            self._config_file = config_path
            
            if not os.path.exists(config_path):
                self.logger.warning(f"Config file not found: {config_path}")
                return False
            
            try:
                with open(config_path, 'r', encoding='utf-8') as f:
                    config_data = json.load(f)
                
                self._strategies = {}
                for strategy_id, strategy_data in config_data.get("strategies", {}).items():
                    self._strategies[strategy_id] = StrategyConfig.from_dict(strategy_id, strategy_data)
                
                self._default_strategy_id = config_data.get("default_strategy", "balanced")
                self._global_settings = config_data.get("global_settings", {})
                
                self.logger.info(f"Loaded {len(self._strategies)} sort strategies from {config_path}")
                self._notify_listeners("config_loaded", {"config_path": config_path})
                
                return True
                
            except Exception as e:
                self.logger.error(f"Failed to load sort strategy config: {e}")
                return False
    
    def save_config(self, config_path: str = None) -> bool:
        with self._lock:
            if config_path is None:
                config_path = self._config_file
            
            if config_path is None:
                return False
            
            try:
                config_data = {
                    "version": "1.0.0",
                    "last_updated": datetime.utcnow().isoformat() + "Z",
                    "default_strategy": self._default_strategy_id,
                    "strategies": {},
                    "global_settings": self._global_settings
                }
                
                for strategy_id, strategy in self._strategies.items():
                    config_data["strategies"][strategy_id] = strategy.to_dict()
                
                os.makedirs(os.path.dirname(config_path), exist_ok=True)
                with open(config_path, 'w', encoding='utf-8') as f:
                    json.dump(config_data, f, indent=2, ensure_ascii=False)
                
                self.logger.info(f"Saved sort strategies to {config_path}")
                self._notify_listeners("config_saved", {"config_path": config_path})
                
                return True
                
            except Exception as e:
                self.logger.error(f"Failed to save sort strategy config: {e}")
                return False
    
    def add_strategy(self, strategy: StrategyConfig) -> bool:
        with self._lock:
            if strategy.strategy_id in self._strategies:
                return False
            
            self._strategies[strategy.strategy_id] = strategy
            self._notify_listeners("strategy_added", {"strategy_id": strategy.strategy_id})
            self.logger.info(f"Added strategy: {strategy.strategy_id}")
            return True
    
    def update_strategy(self, strategy: StrategyConfig) -> bool:
        with self._lock:
            if strategy.strategy_id not in self._strategies:
                return False
            
            self._strategies[strategy.strategy_id] = strategy
            self._notify_listeners("strategy_updated", {"strategy_id": strategy.strategy_id})
            self.logger.info(f"Updated strategy: {strategy.strategy_id}")
            return True
    
    def delete_strategy(self, strategy_id: str) -> bool:
        with self._lock:
            if strategy_id not in self._strategies:
                return False
            
            if strategy_id == self._default_strategy_id:
                self.logger.warning(f"Cannot delete default strategy: {strategy_id}")
                return False
            
            del self._strategies[strategy_id]
            self._notify_listeners("strategy_deleted", {"strategy_id": strategy_id})
            self.logger.info(f"Deleted strategy: {strategy_id}")
            return True
    
    def get_strategy(self, strategy_id: str = None) -> Optional[StrategyConfig]:
        with self._lock:
            if strategy_id is None:
                strategy_id = self._default_strategy_id
            
            return self._strategies.get(strategy_id)
    
    def get_all_strategies(self) -> Dict[str, StrategyConfig]:
        with self._lock:
            return {k: v for k, v in self._strategies.items()}
    
    def list_enabled_strategies(self) -> List[Dict[str, Any]]:
        with self._lock:
            return [
                {
                    "id": sid,
                    "name": s.name,
                    "description": s.description,
                    "scorer_count": len(s.scorers),
                    "is_default": sid == self._default_strategy_id
                }
                for sid, s in self._strategies.items()
                if s.enabled
            ]
    
    def set_default_strategy(self, strategy_id: str) -> bool:
        with self._lock:
            if strategy_id not in self._strategies:
                return False
            
            if not self._strategies[strategy_id].enabled:
                self.logger.warning(f"Cannot set disabled strategy as default: {strategy_id}")
                return False
            
            self._default_strategy_id = strategy_id
            self._notify_listeners("default_changed", {"strategy_id": strategy_id})
            self.logger.info(f"Set default strategy: {strategy_id}")
            return True
    
    def get_default_strategy(self) -> str:
        with self._lock:
            return self._default_strategy_id
    
    def enable_strategy(self, strategy_id: str) -> bool:
        with self._lock:
            strategy = self._strategies.get(strategy_id)
            if not strategy:
                return False
            
            strategy.enabled = True
            self._notify_listeners("strategy_enabled", {"strategy_id": strategy_id})
            self.logger.info(f"Enabled strategy: {strategy_id}")
            return True
    
    def disable_strategy(self, strategy_id: str) -> bool:
        with self._lock:
            strategy = self._strategies.get(strategy_id)
            if not strategy:
                return False
            
            if strategy_id == self._default_strategy_id:
                self.logger.warning(f"Cannot disable default strategy: {strategy_id}")
                return False
            
            strategy.enabled = False
            self._notify_listeners("strategy_disabled", {"strategy_id": strategy_id})
            self.logger.info(f"Disabled strategy: {strategy_id}")
            return True
    
    def calculate_score(
        self,
        index,
        result: Dict[str, Any],
        strategy_id: str = None,
        context: Dict[str, Any] = None
    ) -> float:
        strategy = self.get_strategy(strategy_id)
        if not strategy:
            return result.get("bm25_score", 0)
        
        context = context or {}
        total_score = 0.0
        
        for scorer_config in strategy.scorers:
            scorer = self._scorer_factory.get_scorer(scorer_config)
            if scorer:
                try:
                    score = scorer.score(index, result, context)
                    total_score += score * scorer_config.weight
                except Exception as e:
                    self.logger.error(f"Scorer {scorer_config.name} error: {e}")
        
        return total_score
    
    def sort_results(
        self,
        index,
        results: List[Dict[str, Any]],
        strategy_id: str = None,
        context: Dict[str, Any] = None,
        reverse: bool = True
    ) -> List[Dict[str, Any]]:
        if not results:
            return []
        
        strategy = self.get_strategy(strategy_id)
        if not strategy:
            return sorted(results, key=lambda r: r.get("bm25_score", 0), reverse=reverse)
        
        strategy_id_actual = strategy.strategy_id
        
        scored_results = []
        for result in results:
            try:
                score = self.calculate_score(index, result, strategy_id_actual, context)
                scored_results.append((score, result))
            except Exception as e:
                self.logger.error(f"Error scoring result: {e}")
                scored_results.append((result.get("bm25_score", 0), result))
        
        scored_results.sort(key=lambda x: x[0], reverse=reverse)
        
        sorted_results = []
        for score, result in scored_results:
            result_copy = result.copy()
            result_copy["sort_score"] = score
            result_copy["sort_strategy"] = strategy_id_actual
            sorted_results.append(result_copy)
        
        return sorted_results
    
    def get_global_settings(self) -> Dict[str, Any]:
        with self._lock:
            return self._global_settings.copy()
    
    def update_global_settings(self, settings: Dict[str, Any]):
        with self._lock:
            self._global_settings.update(settings)
            self._notify_listeners("settings_updated", {"settings": settings})


strategy_manager = SortStrategyManager()
