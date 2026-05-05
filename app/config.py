import json
import os
from pathlib import Path
from dotenv import load_dotenv

load_dotenv()

_DEFAULT_CONFIG = {
    "server": {
        "host": "0.0.0.0",
        "port": 5000,
        "debug": False
    },
    "influxdb": {
        "url": "http://localhost:8086",
        "token": "root-token",
        "org": "metric_monitor",
        "bucket": "metrics"
    },
    "collector": {
        "interval_seconds": 60,
        "server_id": "server_01",
        "enabled_metrics": ["cpu_usage", "memory_usage", "disk_usage", "network_io"]
    },
    "alert": {
        "silence_default_seconds": 300
    },
    "notification": {
        "email": {
            "smtp_server": "smtp.example.com",
            "smtp_port": 587,
            "sender": "",
            "password": "",
            "recipients": []
        },
        "dingtalk": {
            "webhook_url": "",
            "secret": ""
        }
    },
    "alert_rules": []
}


def load_config(config_file=None):
    if config_file is None:
        config_file = Path(__file__).parent.parent / "config.json"
    
    config = _DEFAULT_CONFIG.copy()
    
    if config_file.exists():
        try:
            with open(config_file, "r", encoding="utf-8") as f:
                file_config = json.load(f)
                _deep_update(config, file_config)
        except Exception:
            pass
    
    _load_env_overrides(config)
    
    return config


def _deep_update(base, override):
    for key, value in override.items():
        if key in base and isinstance(base[key], dict) and isinstance(value, dict):
            _deep_update(base[key], value)
        else:
            base[key] = value


def _load_env_overrides(config):
    influxdb_config = config["influxdb"]
    if os.getenv("INFLUXDB_URL"):
        influxdb_config["url"] = os.getenv("INFLUXDB_URL")
    if os.getenv("INFLUXDB_TOKEN"):
        influxdb_config["token"] = os.getenv("INFLUXDB_TOKEN")
    if os.getenv("INFLUXDB_ORG"):
        influxdb_config["org"] = os.getenv("INFLUXDB_ORG")
    if os.getenv("INFLUXDB_BUCKET"):
        influxdb_config["bucket"] = os.getenv("INFLUXDB_BUCKET")
    
    email_config = config["notification"]["email"]
    if os.getenv("EMAIL_SMTP_SERVER"):
        email_config["smtp_server"] = os.getenv("EMAIL_SMTP_SERVER")
    if os.getenv("EMAIL_SMTP_PORT"):
        email_config["smtp_port"] = int(os.getenv("EMAIL_SMTP_PORT"))
    if os.getenv("EMAIL_SENDER"):
        email_config["sender"] = os.getenv("EMAIL_SENDER")
    if os.getenv("EMAIL_PASSWORD"):
        email_config["password"] = os.getenv("EMAIL_PASSWORD")
    if os.getenv("EMAIL_RECIPIENTS"):
        email_config["recipients"] = [r.strip() for r in os.getenv("EMAIL_RECIPIENTS").split(",")]
    
    dingtalk_config = config["notification"]["dingtalk"]
    if os.getenv("DINGTALK_WEBHOOK_URL"):
        dingtalk_config["webhook_url"] = os.getenv("DINGTALK_WEBHOOK_URL")
    if os.getenv("DINGTALK_SECRET"):
        dingtalk_config["secret"] = os.getenv("DINGTALK_SECRET")
