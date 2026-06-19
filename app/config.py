import json
import os
from pathlib import Path
from dataclasses import dataclass, asdict, field
from typing import Optional


@dataclass
class Config:
    app_data_dir: str = field(default_factory=lambda: str(Path.home() / ".knowledge_vault"))
    last_opened_note: Optional[int] = None
    theme: str = "light"
    font_family: str = "PingFang SC"
    font_size: int = 14
    auto_save_interval_ms: int = 30000
    image_max_width: int = 2000
    thumbnail_size: int = 256

    @property
    def database_path(self) -> str:
        return str(Path(self.app_data_dir) / "knowledge.db")

    @property
    def attachments_dir(self) -> str:
        return str(Path(self.app_data_dir) / "attachments")

    @property
    def thumbnails_dir(self) -> str:
        return str(Path(self.attachments_dir) / "thumbnails")

    @property
    def images_dir(self) -> str:
        return str(Path(self.attachments_dir) / "images")

    @property
    def exports_dir(self) -> str:
        return str(Path(self.app_data_dir) / "exports")

    @property
    def config_file(self) -> str:
        return str(Path(self.app_data_dir) / "config.json")

    def ensure_directories(self):
        for d in [
            self.app_data_dir,
            self.attachments_dir,
            self.thumbnails_dir,
            self.images_dir,
            self.exports_dir,
        ]:
            Path(d).mkdir(parents=True, exist_ok=True)

    @classmethod
    def load(cls) -> "Config":
        config = cls()
        if os.path.exists(config.config_file):
            try:
                with open(config.config_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                for k, v in data.items():
                    if hasattr(config, k):
                        setattr(config, k, v)
            except (json.JSONDecodeError, IOError):
                pass
        return config

    def save(self):
        self.ensure_directories()
        with open(self.config_file, "w", encoding="utf-8") as f:
            json.dump(asdict(self), f, indent=2, ensure_ascii=False)
