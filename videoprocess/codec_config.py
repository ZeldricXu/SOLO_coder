import json
from pathlib import Path
from typing import Dict, Any, Optional, List

from videoprocess.config import BASE_DIR


DEFAULT_CODEC_CONFIG = {
    "profiles": {
        "h264": {
            "name": "H.264 (AVC)",
            "codec_name": "libx264",
            "description": "广泛兼容的视频编码",
            "supported_formats": ["mp4", "mkv", "mov", "avi", "flv", "m4v"],
            "default_audio_codec": "aac",
            "presets": {
                "ultrafast": {"crf": 28, "speed": "fastest", "quality": "low"},
                "superfast": {"crf": 28, "speed": "very_fast", "quality": "low"},
                "veryfast": {"crf": 28, "speed": "fast", "quality": "medium"},
                "faster": {"crf": 24, "speed": "medium_fast", "quality": "medium"},
                "fast": {"crf": 24, "speed": "medium", "quality": "good"},
                "medium": {"crf": 23, "speed": "balanced", "quality": "good"},
                "slow": {"crf": 22, "speed": "slow", "quality": "better"},
                "slower": {"crf": 20, "speed": "slower", "quality": "best"},
                "veryslow": {"crf": 18, "speed": "slowest", "quality": "best"},
            },
            "options": {
                "threads": 0,
                "pix_fmt": "yuv420p",
            },
        },
        "h265": {
            "name": "H.265 (HEVC)",
            "codec_name": "libx265",
            "description": "高效视频编码，压缩比更高",
            "supported_formats": ["mp4", "mkv", "mov"],
            "default_audio_codec": "aac",
            "presets": {
                "ultrafast": {"crf": 30, "speed": "fastest", "quality": "low"},
                "superfast": {"crf": 30, "speed": "very_fast", "quality": "low"},
                "veryfast": {"crf": 28, "speed": "fast", "quality": "medium"},
                "faster": {"crf": 26, "speed": "medium_fast", "quality": "medium"},
                "fast": {"crf": 26, "speed": "medium", "quality": "good"},
                "medium": {"crf": 24, "speed": "balanced", "quality": "good"},
                "slow": {"crf": 22, "speed": "slow", "quality": "better"},
                "slower": {"crf": 20, "speed": "slower", "quality": "best"},
                "veryslow": {"crf": 18, "speed": "slowest", "quality": "best"},
            },
            "options": {
                "threads": 0,
                "pix_fmt": "yuv420p10le",
            },
        },
        "vp9": {
            "name": "VP9",
            "codec_name": "libvpx-vp9",
            "description": "Google开源视频编码",
            "supported_formats": ["webm", "mkv"],
            "default_audio_codec": "libvorbis",
            "presets": {
                "realtime": {"crf": 32, "cpu_used": 5, "speed": "fastest", "quality": "low"},
                "good": {"crf": 30, "cpu_used": 2, "speed": "medium", "quality": "good"},
                "best": {"crf": 28, "cpu_used": 0, "speed": "slow", "quality": "best"},
            },
            "options": {
                "threads": 0,
                "tile_columns": 2,
                "row_mt": 1,
            },
        },
        "vp8": {
            "name": "VP8",
            "codec_name": "libvpx",
            "description": "VP8视频编码，兼容性更好",
            "supported_formats": ["webm", "mkv"],
            "default_audio_codec": "libvorbis",
            "presets": {
                "fast": {"crf": 32, "cpu_used": 5, "speed": "fastest", "quality": "low"},
                "medium": {"crf": 30, "cpu_used": 2, "speed": "medium", "quality": "good"},
                "slow": {"crf": 28, "cpu_used": 0, "speed": "slow", "quality": "best"},
            },
            "options": {
                "threads": 0,
            },
        },
        "av1": {
            "name": "AV1",
            "codec_name": "libaom-av1",
            "description": "下一代开源视频编码，压缩比最高",
            "supported_formats": ["webm", "mkv", "mp4"],
            "default_audio_codec": "libopus",
            "presets": {
                "fast": {"crf": 32, "cpu_used": 6, "speed": "fastest", "quality": "low"},
                "medium": {"crf": 30, "cpu_used": 4, "speed": "medium", "quality": "good"},
                "slow": {"crf": 28, "cpu_used": 2, "speed": "slow", "quality": "best"},
            },
            "options": {
                "threads": 0,
                "tiles": "2x2",
            },
        },
        "mpeg4": {
            "name": "MPEG-4",
            "codec_name": "mpeg4",
            "description": "传统MPEG-4编码",
            "supported_formats": ["mp4", "avi", "mkv", "mov"],
            "default_audio_codec": "mp3",
            "presets": {
                "fast": {"qmin": 2, "qmax": 31, "speed": "fastest", "quality": "low"},
                "medium": {"qmin": 2, "qmax": 20, "speed": "medium", "quality": "good"},
                "slow": {"qmin": 1, "qmax": 15, "speed": "slow", "quality": "best"},
            },
            "options": {
                "threads": 0,
            },
        },
        "flv": {
            "name": "FLV",
            "codec_name": "flv",
            "description": "Flash视频编码",
            "supported_formats": ["flv"],
            "default_audio_codec": "mp3",
            "presets": {
                "low": {"bitrate": "500k", "speed": "fast", "quality": "low"},
                "medium": {"bitrate": "1000k", "speed": "medium", "quality": "good"},
                "high": {"bitrate": "2000k", "speed": "slow", "quality": "best"},
            },
            "options": {},
        },
        "wmv2": {
            "name": "WMV2",
            "codec_name": "wmv2",
            "description": "Windows Media Video 2",
            "supported_formats": ["wmv", "avi"],
            "default_audio_codec": "wma2",
            "presets": {
                "low": {"bitrate": "500k", "speed": "fast", "quality": "low"},
                "medium": {"bitrate": "1500k", "speed": "medium", "quality": "good"},
                "high": {"bitrate": "3000k", "speed": "slow", "quality": "best"},
            },
            "options": {},
        },
    },
    "format_codec_mapping": {
        "mp4": ["h264", "h265", "av1", "mpeg4"],
        "webm": ["vp9", "vp8", "av1"],
        "avi": ["mpeg4", "h264", "wmv2"],
        "mkv": ["h264", "h265", "vp9", "vp8", "av1"],
        "mov": ["h264", "h265", "mpeg4"],
        "flv": ["flv", "h264"],
        "wmv": ["wmv2"],
        "m4v": ["h264"],
    },
    "default_config": {
        "default_video_codec": "h264",
        "default_preset": "medium",
        "default_crf": 23,
        "default_audio_bitrate": "128k",
        "default_fps": 30,
        "max_resolution": "3840x2160",
    },
}


class CodecConfigManager:
    def __init__(self, config_path: Optional[str] = None):
        self.config_path = Path(config_path) if config_path else BASE_DIR / "storage" / "codec_config.json"
        self._config: Dict[str, Any] = {}
        self._load_config()

    def _load_config(self):
        if self.config_path.exists():
            try:
                with open(self.config_path, "r", encoding="utf-8") as f:
                    self._config = json.load(f)
            except (json.JSONDecodeError, IOError):
                self._config = DEFAULT_CODEC_CONFIG.copy()
                self._save_config()
        else:
            self._config = DEFAULT_CODEC_CONFIG.copy()
            self._save_config()

    def _save_config(self):
        try:
            self.config_path.parent.mkdir(parents=True, exist_ok=True)
            with open(self.config_path, "w", encoding="utf-8") as f:
                json.dump(self._config, f, indent=2, ensure_ascii=False)
        except IOError:
            pass

    def get_codec_config(self, codec_name: str) -> Optional[Dict[str, Any]]:
        normalized = codec_name.lower()
        if normalized in self._config.get("profiles", {}):
            return self._config["profiles"][normalized]

        for key, config in self._config.get("profiles", {}).items():
            if config.get("codec_name") == normalized:
                return config

        return None

    def get_default_codec_for_format(self, format: str) -> str:
        format = format.lower()
        codecs = self._config.get("format_codec_mapping", {}).get(format, [])
        default_cfg = self._config.get("default_config", {})
        default_codec = default_cfg.get("default_video_codec", "h264")

        if default_codec in codecs:
            return default_codec
        return codecs[0] if codecs else "h264"

    def get_supported_codecs_for_format(self, format: str) -> List[str]:
        return self._config.get("format_codec_mapping", {}).get(format.lower(), [])

    def get_preset_config(self, codec_name: str, preset_name: str) -> Optional[Dict[str, Any]]:
        codec_config = self.get_codec_config(codec_name)
        if not codec_config:
            return None

        presets = codec_config.get("presets", {})
        if preset_name in presets:
            return presets[preset_name]

        default_preset = self._config.get("default_config", {}).get("default_preset", "medium")
        return presets.get(default_preset)

    def get_audio_codec(self, codec_name: str) -> str:
        codec_config = self.get_codec_config(codec_name)
        if codec_config:
            return codec_config.get("default_audio_codec", "aac")
        return "aac"

    def get_transcode_params(
        self,
        codec_name: str,
        preset_name: Optional[str] = None,
        custom_params: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        codec_config = self.get_codec_config(codec_name)
        if not codec_config:
            raise ValueError(f"Unknown codec: {codec_name}")

        preset = preset_name or self._config.get("default_config", {}).get("default_preset", "medium")
        preset_config = self.get_preset_config(codec_name, preset) or {}

        params = {
            "codec": codec_config.get("codec_name", codec_name),
            "audio_codec": codec_config.get("default_audio_codec", "aac"),
            "preset": preset,
        }

        params.update(preset_config)
        params.update(codec_config.get("options", {}))

        if custom_params:
            params.update(custom_params)

        return params

    def add_custom_codec(
        self,
        codec_name: str,
        codec_config: Dict[str, Any],
    ) -> bool:
        if "profiles" not in self._config:
            self._config["profiles"] = {}

        self._config["profiles"][codec_name.lower()] = codec_config
        self._save_config()
        return True

    def update_codec_config(
        self,
        codec_name: str,
        updates: Dict[str, Any],
    ) -> bool:
        codec_name = codec_name.lower()
        if codec_name in self._config.get("profiles", {}):
            self._config["profiles"][codec_name].update(updates)
            self._save_config()
            return True
        return False

    def delete_codec_config(self, codec_name: str) -> bool:
        codec_name = codec_name.lower()
        if codec_name in self._config.get("profiles", {}):
            del self._config["profiles"][codec_name]
            self._save_config()
            return True
        return False

    def add_format_codec_mapping(self, format: str, codecs: List[str]) -> bool:
        format = format.lower()
        if "format_codec_mapping" not in self._config:
            self._config["format_codec_mapping"] = {}

        self._config["format_codec_mapping"][format] = [c.lower() for c in codecs]
        self._save_config()
        return True

    def get_all_codecs(self) -> Dict[str, Dict[str, Any]]:
        return self._config.get("profiles", {})

    def get_default_config(self) -> Dict[str, Any]:
        return self._config.get("default_config", {})

    def update_default_config(self, updates: Dict[str, Any]) -> bool:
        if "default_config" not in self._config:
            self._config["default_config"] = {}

        self._config["default_config"].update(updates)
        self._save_config()
        return True

    def validate_codec_for_format(self, codec_name: str, format: str) -> bool:
        supported = self.get_supported_codecs_for_format(format)
        if not supported:
            return True

        codec_name = codec_name.lower()
        for sc in supported:
            codec_cfg = self.get_codec_config(sc)
            if codec_cfg and codec_cfg.get("codec_name") == codec_name:
                return True
            if sc == codec_name:
                return True

        return False

    def reset_to_default(self):
        self._config = DEFAULT_CODEC_CONFIG.copy()
        self._save_config()

    def reload_config(self):
        self._load_config()


codec_config_manager = CodecConfigManager()
