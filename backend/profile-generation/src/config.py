import os
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any
from pathlib import Path


@dataclass
class MySQLConfig:
    host: str = "localhost"
    port: int = 3306
    user: str = "root"
    password: str = "password"
    database: str = "gamestats"
    
    @property
    def connection_string(self) -> str:
        return f"mysql+pymysql://{self.user}:{self.password}@{self.host}:{self.port}/{self.database}"


@dataclass
class RedisConfig:
    host: str = "localhost"
    port: int = 6379
    password: Optional[str] = None
    db: int = 0


@dataclass
class ProfileConfig:
    active_days_threshold: int = 3
    active_events_threshold: int = 10
    high_pay_threshold: float = 100.0
    medium_pay_threshold: float = 10.0
    churn_days_threshold: int = 7
    high_churn_days: int = 14


@dataclass
class RuleEngineConfig:
    config_path: str = "config/tag_rules.yaml"
    auto_reload: bool = True
    reload_interval_seconds: int = 300
    use_default_rules_if_missing: bool = True
    enabled_categories: List[str] = field(default_factory=lambda: [
        "activity", "payment", "social", "gameplay", "churn", "lifecycle"
    ])
    
    def get_absolute_path(self, base_dir: Optional[str] = None) -> str:
        if os.path.isabs(self.config_path):
            return self.config_path
        
        if base_dir:
            return os.path.join(base_dir, self.config_path)
        
        current_dir = os.path.dirname(os.path.abspath(__file__))
        project_root = os.path.dirname(os.path.dirname(current_dir))
        return os.path.join(project_root, self.config_path)


@dataclass
class ChurnPredictionConfig:
    high_risk_days: int = 14
    medium_risk_days: int = 7
    low_activity_days: int = 3
    low_activity_events: int = 5
    non_payer_weight: float = 0.1
    
    risk_weights: Dict[str, float] = field(default_factory=lambda: {
        "high_inactivity_days": 0.6,
        "medium_inactivity_days": 0.3,
        "low_activity_days": 0.3,
        "low_events": 0.2,
        "non_payer": 0.1
    })
    
    risk_thresholds: Dict[str, float] = field(default_factory=lambda: {
        "high": 0.7,
        "medium": 0.4
    })


@dataclass
class ScoringConfig:
    activity_max_events: float = 100.0
    activity_days_weight: float = 1.0 / 90.0
    payment_max_amount: float = 500.0
    social_max_interactions: float = 50.0


@dataclass
class Config:
    mysql: MySQLConfig = None
    redis: RedisConfig = None
    profile: ProfileConfig = None
    rule_engine: RuleEngineConfig = None
    churn_prediction: ChurnPredictionConfig = None
    scoring: ScoringConfig = None
    
    def __init__(self):
        self.mysql = MySQLConfig(
            host=os.getenv("MYSQL_HOST", "localhost"),
            port=int(os.getenv("MYSQL_PORT", 3306)),
            user=os.getenv("MYSQL_USER", "root"),
            password=os.getenv("MYSQL_PASSWORD", "password"),
            database=os.getenv("MYSQL_DATABASE", "gamestats")
        )
        
        self.redis = RedisConfig(
            host=os.getenv("REDIS_HOST", "localhost"),
            port=int(os.getenv("REDIS_PORT", 6379)),
            password=os.getenv("REDIS_PASSWORD"),
            db=int(os.getenv("REDIS_DB", 0))
        )
        
        self.profile = ProfileConfig(
            active_days_threshold=int(os.getenv("ACTIVE_DAYS_THRESHOLD", 3)),
            active_events_threshold=int(os.getenv("ACTIVE_EVENTS_THRESHOLD", 10)),
            high_pay_threshold=float(os.getenv("HIGH_PAY_THRESHOLD", 100.0)),
            medium_pay_threshold=float(os.getenv("MEDIUM_PAY_THRESHOLD", 10.0)),
            churn_days_threshold=int(os.getenv("CHURN_DAYS_THRESHOLD", 7)),
            high_churn_days=int(os.getenv("HIGH_CHURN_DAYS", 14))
        )
        
        self.rule_engine = RuleEngineConfig(
            config_path=os.getenv("TAG_RULES_CONFIG_PATH", "config/tag_rules.yaml"),
            auto_reload=os.getenv("RULES_AUTO_RELOAD", "true").lower() == "true",
            reload_interval_seconds=int(os.getenv("RULES_RELOAD_INTERVAL", 300)),
            use_default_rules_if_missing=os.getenv("USE_DEFAULT_RULES", "true").lower() == "true"
        )
        
        self.churn_prediction = ChurnPredictionConfig(
            high_risk_days=int(os.getenv("HIGH_RISK_DAYS", 14)),
            medium_risk_days=int(os.getenv("MEDIUM_RISK_DAYS", 7)),
            low_activity_days=int(os.getenv("LOW_ACTIVITY_DAYS", 3)),
            low_activity_events=int(os.getenv("LOW_ACTIVITY_EVENTS", 5)),
            non_payer_weight=float(os.getenv("NON_PAYER_WEIGHT", 0.1))
        )
        
        self.scoring = ScoringConfig(
            activity_max_events=float(os.getenv("ACTIVITY_MAX_EVENTS", 100.0)),
            activity_days_weight=float(os.getenv("ACTIVITY_DAYS_WEIGHT", 0.0111)),
            payment_max_amount=float(os.getenv("PAYMENT_MAX_AMOUNT", 500.0)),
            social_max_interactions=float(os.getenv("SOCIAL_MAX_INTERACTIONS", 50.0))
        )
    
    def to_dict(self) -> Dict[str, Any]:
        return {
            "mysql": {
                "host": self.mysql.host,
                "port": self.mysql.port,
                "user": self.mysql.user,
                "database": self.mysql.database
            },
            "redis": {
                "host": self.redis.host,
                "port": self.redis.port,
                "db": self.redis.db
            },
            "profile": {
                "active_days_threshold": self.profile.active_days_threshold,
                "high_pay_threshold": self.profile.high_pay_threshold,
                "medium_pay_threshold": self.profile.medium_pay_threshold,
                "churn_days_threshold": self.profile.churn_days_threshold,
                "high_churn_days": self.profile.high_churn_days
            },
            "rule_engine": {
                "config_path": self.rule_engine.config_path,
                "auto_reload": self.rule_engine.auto_reload,
                "reload_interval_seconds": self.rule_engine.reload_interval_seconds,
                "enabled_categories": self.rule_engine.enabled_categories
            },
            "churn_prediction": {
                "high_risk_days": self.churn_prediction.high_risk_days,
                "medium_risk_days": self.churn_prediction.medium_risk_days,
                "risk_thresholds": self.churn_prediction.risk_thresholds
            },
            "scoring": {
                "activity_max_events": self.scoring.activity_max_events,
                "payment_max_amount": self.scoring.payment_max_amount,
                "social_max_interactions": self.scoring.social_max_interactions
            }
        }


config = Config()
