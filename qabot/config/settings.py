from typing import Dict, Any


class IndexUpdateConfig:
    def __init__(self):
        self.MIN_UPDATE_INTERVAL_SECONDS: int = 10
        self.MAX_UPDATE_INTERVAL_SECONDS: int = 300
        self.HIGH_ACTIVITY_THRESHOLD: int = 100
        self.MEDIUM_ACTIVITY_THRESHOLD: int = 30
        self.HIGH_ACTIVITY_INTERVAL: int = 10
        self.MEDIUM_ACTIVITY_INTERVAL: int = 60
        self.LOW_ACTIVITY_INTERVAL: int = 300


class FeedbackOptimizationConfig:
    def __init__(self):
        self.ENABLE_AUTO_OPTIMIZATION: bool = True
        self.LOW_SATISFACTION_THRESHOLD: int = 2
        self.OPTIMIZATION_HISTORY_WINDOW: int = 100
        self.MIN_FEEDBACK_COUNT_FOR_OPTIMIZATION: int = 3
        self.KEYWORD_BOOST_THRESHOLD: float = 0.5
        self.MAX_KEYWORDS_PER_KNOWLEDGE: int = 20


class ReplyTypeConfig:
    def __init__(self):
        self.DEFAULT_REPLY_TYPE: str = "auto"
        self.REPLY_TYPES: Dict[str, Dict[str, Any]] = {
            "knowledge_match": {
                "name": "知识匹配回复",
                "description": "直接使用匹配到的知识内容作为回复",
                "enabled": True,
                "priority": 1,
                "requires_match": True,
                "template_id": "template_general"
            },
            "template": {
                "name": "模板回复",
                "description": "使用配置的回复模板生成回复",
                "enabled": True,
                "priority": 2,
                "requires_match": True,
                "template_id": None
            },
            "hybrid": {
                "name": "混合回复",
                "description": "结合知识内容和模板生成回复",
                "enabled": True,
                "priority": 3,
                "requires_match": True,
                "template_id": "template_hybrid"
            },
            "default": {
                "name": "默认回复",
                "description": "当无匹配知识时使用的默认回复",
                "enabled": True,
                "priority": 4,
                "requires_match": False,
                "template_id": "template_default"
            }
        }


class RedisConfig:
    def __init__(self):
        self.HOST: str = "localhost"
        self.PORT: int = 6379
        self.DB: int = 0
        self.PASSWORD: str = ""
        self.RECOMMEND_QUEUE_KEY: str = "qabot:recommend:queue"
        self.RECOMMEND_RESULT_KEY_PREFIX: str = "qabot:recommend:result:"
        self.MAX_RETRIES: int = 3
        self.RETRY_DELAY_SECONDS: int = 5
        self.ENABLE_PERSISTENCE: bool = False


class Settings:
    def __init__(self):
        self.APP_NAME: str = "QABot 知识问答与智能客服服务"
        self.APP_VERSION: str = "2.0.0"
        
        self.API_PREFIX: str = "/api/v1"
        
        self.RETRIEVAL_TOP_K: int = 5
        self.KEYWORD_MATCH_WEIGHT: float = 0.6
        self.SEMANTIC_MATCH_WEIGHT: float = 0.4
        
        self.RECOMMEND_TOP_N: int = 3
        self.HOT_RECOMMEND_MIN_VIEWS: int = 50
        
        self.DEFAULT_TEMPLATE_ID: str = "template_default"
        
        self.MIN_MATCH_SCORE: float = 0.1
        
        self.ENABLE_KEYWORD_PRIORITY: bool = True
        
        self.index_update: IndexUpdateConfig = IndexUpdateConfig()
        self.feedback: FeedbackOptimizationConfig = FeedbackOptimizationConfig()
        self.reply_type: ReplyTypeConfig = ReplyTypeConfig()
        self.redis: RedisConfig = RedisConfig()
    
    def get_reply_type_config(self, reply_type: str) -> Dict[str, Any]:
        return self.reply_type.REPLY_TYPES.get(reply_type, {})
    
    def is_reply_type_enabled(self, reply_type: str) -> bool:
        config = self.get_reply_type_config(reply_type)
        return config.get("enabled", False)
    
    def calculate_update_interval(self, view_count: int) -> int:
        if view_count >= self.index_update.HIGH_ACTIVITY_THRESHOLD:
            return self.index_update.HIGH_ACTIVITY_INTERVAL
        elif view_count >= self.index_update.MEDIUM_ACTIVITY_THRESHOLD:
            return self.index_update.MEDIUM_ACTIVITY_INTERVAL
        else:
            return self.index_update.LOW_ACTIVITY_INTERVAL


settings = Settings()

__all__ = [
    "Settings",
    "IndexUpdateConfig",
    "FeedbackOptimizationConfig",
    "ReplyTypeConfig",
    "RedisConfig",
    "settings"
]
