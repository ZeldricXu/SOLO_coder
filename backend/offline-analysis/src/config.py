import os
from dataclasses import dataclass
from typing import Optional


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
class InfluxDBConfig:
    url: str = "http://localhost:8086"
    token: str = "my-token"
    org: str = "gamestats"
    bucket: str = "events"


@dataclass
class AnalysisConfig:
    retention_days: int = 90
    daily_batch_hour: int = 3
    max_retries: int = 3
    retry_delay: int = 60


@dataclass
class Config:
    mysql: MySQLConfig = None
    influxdb: InfluxDBConfig = None
    analysis: AnalysisConfig = None
    
    def __init__(self):
        self.mysql = MySQLConfig(
            host=os.getenv("MYSQL_HOST", "localhost"),
            port=int(os.getenv("MYSQL_PORT", 3306)),
            user=os.getenv("MYSQL_USER", "root"),
            password=os.getenv("MYSQL_PASSWORD", "password"),
            database=os.getenv("MYSQL_DATABASE", "gamestats")
        )
        
        self.influxdb = InfluxDBConfig(
            url=os.getenv("INFLUXDB_URL", "http://localhost:8086"),
            token=os.getenv("INFLUXDB_TOKEN", "my-token"),
            org=os.getenv("INFLUXDB_ORG", "gamestats"),
            bucket=os.getenv("INFLUXDB_BUCKET", "events")
        )
        
        self.analysis = AnalysisConfig(
            retention_days=int(os.getenv("RETENTION_DAYS", 90)),
            daily_batch_hour=int(os.getenv("DAILY_BATCH_HOUR", 3)),
            max_retries=int(os.getenv("MAX_RETRIES", 3)),
            retry_delay=int(os.getenv("RETRY_DELAY", 60))
        )


config = Config()
