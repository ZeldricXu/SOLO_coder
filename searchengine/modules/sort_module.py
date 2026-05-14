import logging
import signal
from typing import List, Dict, Any, Optional
from datetime import datetime, timedelta
from searchengine.models.base import SearchIndex, SortStrategy, SearchResultItem
from searchengine.modules.sort_strategy_config import strategy_manager, StrategyConfig, ScorerConfig


class SortTimeoutException(Exception):
    pass


class SortModule:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._strategies: Dict[str, SortStrategy] = {}
        self._strategy_manager = strategy_manager
        self._config_loaded = False
        self._load_config()
        self._init_default_strategies()
    
    def _load_config(self) -> bool:
        try:
            if self._strategy_manager.load_config():
                self._config_loaded = True
                self.logger.info("Sort strategy config loaded successfully")
                return True
        except Exception as e:
            self.logger.warning(f"Failed to load sort strategy config: {e}")
        return False
    
    def reload_config(self) -> bool:
        return self._load_config()
    
    def is_config_loaded(self) -> bool:
        return self._config_loaded
    
    def get_strategy_manager(self):
        return self._strategy_manager
    
    def _init_default_strategies(self) -> None:
        relevance_strategy = SortStrategy(
            strategy_id="strategy_relevance",
            strategy_name="相关性排序",
            strategy_type="relevance",
            strategy_config={
                "weight_title": 0.4,
                "weight_content": 0.3,
                "weight_click": 0.2,
                "weight_time": 0.1
            },
            enabled=True
        )
        self._strategies["relevance"] = relevance_strategy
        
        custom_strategy = SortStrategy(
            strategy_id="strategy_custom",
            strategy_name="自定义排序",
            strategy_type="custom",
            strategy_config={
                "sort_field": "publish_time",
                "sort_order": "desc"
            },
            enabled=True
        )
        self._strategies["custom"] = custom_strategy
        
        click_strategy = SortStrategy(
            strategy_id="strategy_click",
            strategy_name="点击率排序",
            strategy_type="click",
            strategy_config={
                "weight_click": 0.7,
                "weight_time": 0.3
            },
            enabled=True
        )
        self._strategies["click"] = click_strategy
        
        time_strategy = SortStrategy(
            strategy_id="strategy_time",
            strategy_name="时间排序",
            strategy_type="time",
            strategy_config={
                "sort_order": "desc",
                "weight_recent": 0.5
            },
            enabled=True
        )
        self._strategies["time"] = time_strategy
        
        self.logger.info("Initialized default sort strategies")
    
    def get_strategy(self, strategy_type: str) -> Optional[SortStrategy]:
        return self._strategies.get(strategy_type)
    
    def list_strategies(self) -> List[SortStrategy]:
        return list(self._strategies.values())
    
    def add_strategy(self, strategy: SortStrategy) -> bool:
        if strategy.strategy_type in self._strategies:
            self.logger.warning(f"Strategy {strategy.strategy_type} already exists, overwriting")
        self._strategies[strategy.strategy_type] = strategy
        return True
    
    def get_configured_strategy(self, strategy_id: str = None):
        return self._strategy_manager.get_strategy(strategy_id)
    
    def list_configured_strategies(self) -> List[Dict[str, Any]]:
        return self._strategy_manager.list_enabled_strategies()
    
    def set_default_strategy(self, strategy_id: str) -> bool:
        return self._strategy_manager.set_default_strategy(strategy_id)
    
    def get_default_strategy(self) -> str:
        return self._strategy_manager.get_default_strategy()
    
    def add_configured_strategy(
        self,
        strategy_id: str,
        name: str,
        description: str,
        scorers: List[Dict[str, Any]],
        enabled: bool = True
    ) -> bool:
        scorer_configs = [ScorerConfig.from_dict(s) for s in scorers]
        strategy = StrategyConfig(
            strategy_id=strategy_id,
            name=name,
            description=description,
            enabled=enabled,
            scorers=scorer_configs
        )
        success = self._strategy_manager.add_strategy(strategy)
        if success:
            self._strategy_manager.save_config()
        return success
    
    def update_configured_strategy(
        self,
        strategy_id: str,
        name: str = None,
        description: str = None,
        scorers: List[Dict[str, Any]] = None,
        enabled: bool = None
    ) -> bool:
        existing = self._strategy_manager.get_strategy(strategy_id)
        if not existing:
            return False
        
        if name is not None:
            existing.name = name
        if description is not None:
            existing.description = description
        if scorers is not None:
            existing.scorers = [ScorerConfig.from_dict(s) for s in scorers]
        if enabled is not None:
            existing.enabled = enabled
        
        success = self._strategy_manager.update_strategy(existing)
        if success:
            self._strategy_manager.save_config()
        return success
    
    def delete_configured_strategy(self, strategy_id: str) -> bool:
        success = self._strategy_manager.delete_strategy(strategy_id)
        if success:
            self._strategy_manager.save_config()
        return success
    
    def sort_with_configured_strategy(
        self,
        indexes: List[SearchIndex],
        strategy_id: str = None,
        search_keywords: List[str] = None,
        context: Dict[str, Any] = None
    ) -> List[SearchResultItem]:
        if not indexes:
            return []
        
        strategy = self._strategy_manager.get_strategy(strategy_id)
        if not strategy:
            if strategy_id:
                self.logger.warning(f"Configured strategy {strategy_id} not found, using legacy sort")
            return self.sort_results(indexes, "relevance", search_keywords or [])
        
        try:
            results = []
            for idx, index in enumerate(indexes):
                results.append({
                    "index": index,
                    "bm25_score": self._calculate_legacy_bm25(index, search_keywords or []),
                    "position": idx
                })
            
            search_context = context or {}
            if search_keywords:
                search_context["query"] = " ".join(search_keywords)
            
            sorted_results = self._strategy_manager.sort_results(
                index=None,
                results=results,
                strategy_id=strategy.strategy_id,
                context=search_context
            )
            
            result_items = []
            for pos, item in enumerate(sorted_results, start=1):
                search_index = item["index"]
                sort_score = item.get("sort_score", 0)
                result_items.append(SearchResultItem(
                    content_id=search_index.content_id,
                    title=search_index.title,
                    relevance=round(sort_score, 4),
                    position=pos,
                    category=search_index.category,
                    author=search_index.author,
                    publish_time=search_index.publish_time,
                    click_count=search_index.click_count
                ))
            
            return result_items
            
        except Exception as e:
            self.logger.error(f"Configured sort error: {e}")
            return self.sort_results(indexes, "relevance", search_keywords or [])
    
    def _calculate_legacy_bm25(self, index: SearchIndex, keywords: List[str]) -> float:
        if not keywords:
            return 0.5
        
        title_score = self._calculate_keyword_score(index.title, keywords)
        content_score = self._calculate_keyword_score(index.content, keywords)
        return title_score * 0.6 + content_score * 0.4
    
    def sort_results(self, 
                     indexes: List[SearchIndex], 
                     sort_type: str,
                     search_keywords: List[str],
                     timeout: float = 5.0) -> List[SearchResultItem]:
        if not indexes:
            return []
        
        strategy = self.get_strategy(sort_type)
        if not strategy or not strategy.enabled:
            self.logger.warning(f"Strategy {sort_type} not found or disabled, using relevance")
            strategy = self.get_strategy("relevance")
        
        try:
            if strategy.strategy_type == "relevance":
                return self._sort_by_relevance(indexes, search_keywords, strategy)
            elif strategy.strategy_type == "custom":
                return self._sort_by_custom(indexes, strategy)
            elif strategy.strategy_type == "click":
                return self._sort_by_click(indexes, strategy)
            elif strategy.strategy_type == "time":
                return self._sort_by_time(indexes, strategy)
            else:
                return self._sort_by_relevance(indexes, search_keywords, strategy)
        except SortTimeoutException:
            self.logger.warning("Sorting timed out, using default simple sort")
            return self._simple_sort(indexes, search_keywords)
        except Exception as e:
            self.logger.error(f"Sorting error: {e}")
            return self._simple_sort(indexes, search_keywords)
    
    def _sort_by_relevance(self, 
                          indexes: List[SearchIndex], 
                          search_keywords: List[str],
                          strategy: SortStrategy) -> List[SearchResultItem]:
        config = strategy.strategy_config
        weight_title = config.get("weight_title", 0.4)
        weight_content = config.get("weight_content", 0.3)
        weight_click = config.get("weight_click", 0.2)
        weight_time = config.get("weight_time", 0.1)
        
        scored_items = []
        for idx, index in enumerate(indexes):
            title_score = self._calculate_keyword_score(index.title, search_keywords)
            content_score = self._calculate_keyword_score(index.content, search_keywords)
            
            max_click = max((i.click_count for i in indexes), default=1)
            click_score = index.click_count / max_click if max_click > 0 else 0
            
            time_score = self._calculate_time_score(index)
            
            final_score = (
                title_score * weight_title +
                content_score * weight_content +
                click_score * weight_click +
                time_score * weight_time
            )
            
            scored_items.append({
                "index": index,
                "score": final_score
            })
        
        scored_items.sort(key=lambda x: x["score"], reverse=True)
        
        result_items = []
        for pos, item in enumerate(scored_items, start=1):
            result_items.append(SearchResultItem(
                content_id=item["index"].content_id,
                title=item["index"].title,
                relevance=round(item["score"], 4),
                position=pos,
                category=item["index"].category,
                author=item["index"].author,
                publish_time=item["index"].publish_time,
                click_count=item["index"].click_count
            ))
        
        return result_items
    
    def _sort_by_custom(self, 
                       indexes: List[SearchIndex], 
                       strategy: SortStrategy) -> List[SearchResultItem]:
        config = strategy.strategy_config
        sort_field = config.get("sort_field", "publish_time")
        sort_order = config.get("sort_order", "desc")
        
        def get_sort_value(index: SearchIndex) -> Any:
            if hasattr(index, sort_field):
                value = getattr(index, sort_field)
                if value is None:
                    return datetime.min if sort_order == "desc" else datetime.max
                return value
            return 0
        
        reverse = (sort_order == "desc")
        sorted_indexes = sorted(indexes, key=get_sort_value, reverse=reverse)
        
        result_items = []
        for pos, index in enumerate(sorted_indexes, start=1):
            result_items.append(SearchResultItem(
                content_id=index.content_id,
                title=index.title,
                relevance=1.0 - (pos - 1) / len(sorted_indexes),
                position=pos,
                category=index.category,
                author=index.author,
                publish_time=index.publish_time,
                click_count=index.click_count
            ))
        
        return result_items
    
    def _sort_by_click(self, 
                      indexes: List[SearchIndex], 
                      strategy: SortStrategy) -> List[SearchResultItem]:
        config = strategy.strategy_config
        weight_click = config.get("weight_click", 0.7)
        weight_time = config.get("weight_time", 0.3)
        
        scored_items = []
        max_click = max((i.click_count for i in indexes), default=1)
        
        for index in indexes:
            click_score = index.click_count / max_click if max_click > 0 else 0
            time_score = self._calculate_time_score(index)
            
            final_score = click_score * weight_click + time_score * weight_time
            
            scored_items.append({
                "index": index,
                "score": final_score
            })
        
        scored_items.sort(key=lambda x: x["score"], reverse=True)
        
        result_items = []
        for pos, item in enumerate(scored_items, start=1):
            result_items.append(SearchResultItem(
                content_id=item["index"].content_id,
                title=item["index"].title,
                relevance=round(item["score"], 4),
                position=pos,
                category=item["index"].category,
                author=item["index"].author,
                publish_time=item["index"].publish_time,
                click_count=item["index"].click_count
            ))
        
        return result_items
    
    def _sort_by_time(self, 
                     indexes: List[SearchIndex], 
                     strategy: SortStrategy) -> List[SearchResultItem]:
        config = strategy.strategy_config
        sort_order = config.get("sort_order", "desc")
        reverse = (sort_order == "desc")
        
        def get_time_value(index: SearchIndex):
            if index.publish_time:
                return index.publish_time
            return index.index_time
        
        sorted_indexes = sorted(indexes, key=get_time_value, reverse=reverse)
        
        result_items = []
        for pos, index in enumerate(sorted_indexes, start=1):
            result_items.append(SearchResultItem(
                content_id=index.content_id,
                title=index.title,
                relevance=1.0 - (pos - 1) / len(sorted_indexes),
                position=pos,
                category=index.category,
                author=index.author,
                publish_time=index.publish_time,
                click_count=index.click_count
            ))
        
        return result_items
    
    def _simple_sort(self, 
                    indexes: List[SearchIndex],
                    search_keywords: List[str]) -> List[SearchResultItem]:
        scored_items = []
        for index in indexes:
            score = self._calculate_keyword_score(index.title, search_keywords)
            scored_items.append({"index": index, "score": score})
        
        scored_items.sort(key=lambda x: x["score"], reverse=True)
        
        result_items = []
        for pos, item in enumerate(scored_items, start=1):
            result_items.append(SearchResultItem(
                content_id=item["index"].content_id,
                title=item["index"].title,
                relevance=round(item["score"], 4),
                position=pos,
                category=item["index"].category,
                author=item["index"].author,
                publish_time=item["index"].publish_time,
                click_count=item["index"].click_count
            ))
        
        return result_items
    
    def _calculate_keyword_score(self, text: str, keywords: List[str]) -> float:
        if not text or not keywords:
            return 0.0
        
        text_lower = text.lower()
        match_count = 0
        
        for keyword in keywords:
            if keyword.lower() in text_lower:
                match_count += 1
        
        return match_count / len(keywords) if keywords else 0.0
    
    def _calculate_time_score(self, index: SearchIndex) -> float:
        now = datetime.utcnow()
        reference_time = index.publish_time or index.index_time
        
        if not reference_time:
            return 0.5
        
        if isinstance(reference_time, str):
            try:
                reference_time = datetime.fromisoformat(reference_time.replace("Z", "+00:00"))
            except:
                return 0.5
        
        age_days = (now - reference_time).days
        max_days = 365
        
        if age_days <= 0:
            return 1.0
        elif age_days >= max_days:
            return 0.0
        else:
            return 1.0 - (age_days / max_days)


sort_module = SortModule()
