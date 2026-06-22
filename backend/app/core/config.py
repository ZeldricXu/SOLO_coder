from pydantic_settings import BaseSettings
from typing import Optional, List
from functools import lru_cache


class Settings(BaseSettings):
    APP_NAME: str = "周报自动汇总系统"
    APP_ENV: str = "development"
    APP_DEBUG: bool = True

    DATABASE_URL: str = "sqlite:///./weekly_report.db"

    SECRET_KEY: str = "change-me-in-production-please-1234567890"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 1440

    WECOM_BOT_WEBHOOK: Optional[str] = None
    WECOM_CORP_ID: Optional[str] = None
    WECOM_CORP_SECRET: Optional[str] = None
    WECOM_AGENT_ID: Optional[str] = None

    FEISHU_APP_ID: Optional[str] = None
    FEISHU_APP_SECRET: Optional[str] = None
    FEISHU_BOT_WEBHOOK: Optional[str] = None

    SMTP_HOST: str = "smtp.example.com"
    SMTP_PORT: int = 465
    SMTP_USER: str = "noreply@example.com"
    SMTP_PASSWORD: str = ""
    SMTP_USE_SSL: bool = True

    DEFAULT_REPORT_EMAILS: str = "admin@example.com"

    CONFLUENCE_BASE_URL: Optional[str] = None
    CONFLUENCE_USERNAME: Optional[str] = None
    CONFLUENCE_API_TOKEN: Optional[str] = None
    CONFLUENCE_SPACE_KEY: Optional[str] = None

    YUQUE_BASE_URL: str = "https://www.yuque.com/api/v2"
    YUQUE_TOKEN: Optional[str] = None
    YUQUE_LOGIN: Optional[str] = None
    YUQUE_REPO: Optional[str] = None

    NOTION_TOKEN: Optional[str] = None
    NOTION_DATABASE_ID: Optional[str] = None

    REMINDER_MONDAY_HOUR: int = 9
    REMINDER_MONDAY_MINUTE: int = 0
    REMINDER_WEDNESDAY_HOUR: int = 10
    REMINDER_WEDNESDAY_MINUTE: int = 0
    REMINDER_FRIDAY_HOUR: int = 10
    REMINDER_FRIDAY_MINUTE: int = 0
    SUMMARY_FRIDAY_HOUR: int = 18
    SUMMARY_FRIDAY_MINUTE: int = 0
    DEADLINE_REMINDER_HOURS_BEFORE: int = 2

    @property
    def report_email_list(self) -> List[str]:
        return [e.strip() for e in self.DEFAULT_REPORT_EMAILS.split(",") if e.strip()]

    class Config:
        env_file = ".env"
        case_sensitive = True


@lru_cache()
def get_settings() -> Settings:
    return Settings()
