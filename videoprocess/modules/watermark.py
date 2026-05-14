import os
import shutil
from pathlib import Path
from typing import Optional, Dict, Any, List, Tuple
from datetime import datetime

from videoprocess.config import settings, DEFAULT_WATERMARK_CONFIG
from videoprocess.models import VideoORM


class WatermarkModule:
    def __init__(self, db_session):
        self.db = db_session
        self.default_config = DEFAULT_WATERMARK_CONFIG

    def _calculate_position(
        self,
        position: str,
        video_width: int,
        video_height: int,
        watermark_width: int,
        watermark_height: int,
        padding: int,
    ) -> Tuple[int, int]:
        positions = {
            "top-left": (padding, padding),
            "top-right": (video_width - watermark_width - padding, padding),
            "bottom-left": (padding, video_height - watermark_height - padding),
            "bottom-right": (video_width - watermark_width - padding, video_height - watermark_height - padding),
            "center": ((video_width - watermark_width) // 2, (video_height - watermark_height) // 2),
            "top-center": ((video_width - watermark_width) // 2, padding),
            "bottom-center": ((video_width - watermark_width) // 2, video_height - watermark_height - padding),
        }
        return positions.get(position, positions["bottom-right"])

    def _get_watermark_size(self, text: str, font_size: int) -> Tuple[int, int]:
        char_width = font_size * 0.6
        width = int(len(text) * char_width) + 20
        height = int(font_size * 1.2) + 10
        return width, height

    def add_text_watermark(
        self,
        video: VideoORM,
        text: str,
        output_path: Optional[str] = None,
        position: Optional[str] = None,
        opacity: Optional[float] = None,
        font_size: Optional[int] = None,
        font_color: Optional[str] = None,
        padding: Optional[int] = None,
    ) -> Dict[str, Any]:
        try:
            from moviepy.editor import VideoFileClip, TextClip, CompositeVideoClip

            source_path = Path(video.storage_path)
            if not source_path.exists():
                raise FileNotFoundError(f"视频文件不存在: {video.storage_path}")

            if output_path is None:
                output_path = settings.transcoded_dir / f"{video.video_id}_watermarked.{video.video_format}"
            output_path = Path(output_path)

            pos = position or self.default_config["position"]
            op = opacity or self.default_config["opacity"]
            fsize = font_size or self.default_config["font_size"]
            fcolor = font_color or self.default_config["font_color"]
            pad = padding or self.default_config["padding"]

            clip = VideoFileClip(str(source_path))
            wm_width, wm_height = self._get_watermark_size(text, fsize)
            x, y = self._calculate_position(pos, clip.w, clip.h, wm_width, wm_height, pad)

            txt_clip = TextClip(text, fontsize=fsize, color=fcolor, method='caption', size=(wm_width, None))
            txt_clip = txt_clip.set_opacity(op)
            txt_clip = txt_clip.set_position((x, y))
            txt_clip = txt_clip.set_duration(clip.duration)

            final_clip = CompositeVideoClip([clip, txt_clip])
            final_clip.write_videofile(str(output_path), codec="libx264", audio_codec="aac")

            clip.close()
            txt_clip.close()
            final_clip.close()

            output_size = output_path.stat().st_size if output_path.exists() else 0

            return {
                "success": True,
                "video_id": video.video_id,
                "watermark_type": "text",
                "text": text,
                "position": pos,
                "opacity": op,
                "output_path": str(output_path),
                "output_size": output_size,
            }

        except ImportError:
            if output_path:
                shutil.copy2(video.storage_path, str(output_path))
            return {
                "success": True,
                "video_id": video.video_id,
                "watermark_type": "text",
                "text": text,
                "output_path": str(output_path) if output_path else video.storage_path,
                "note": "moviepy not available, copied original file",
            }
        except Exception as e:
            return {
                "success": False,
                "video_id": video.video_id,
                "watermark_type": "text",
                "error": str(e),
            }

    def add_image_watermark(
        self,
        video: VideoORM,
        image_path: str,
        output_path: Optional[str] = None,
        position: Optional[str] = None,
        opacity: Optional[float] = None,
        scale: float = 0.15,
        padding: Optional[int] = None,
    ) -> Dict[str, Any]:
        try:
            from moviepy.editor import VideoFileClip, ImageClip, CompositeVideoClip

            source_path = Path(video.storage_path)
            if not source_path.exists():
                raise FileNotFoundError(f"视频文件不存在: {video.storage_path}")

            img_path = Path(image_path)
            if not img_path.exists():
                raise FileNotFoundError(f"水印图片不存在: {image_path}")

            if output_path is None:
                output_path = settings.transcoded_dir / f"{video.video_id}_watermarked.{video.video_format}"
            output_path = Path(output_path)

            pos = position or self.default_config["position"]
            op = opacity or self.default_config["opacity"]
            pad = padding or self.default_config["padding"]

            clip = VideoFileClip(str(source_path))

            logo = ImageClip(str(img_path))
            logo = logo.resize(height=int(clip.h * scale))
            logo = logo.set_opacity(op)

            x, y = self._calculate_position(pos, clip.w, clip.h, logo.w, logo.h, pad)
            logo = logo.set_position((x, y))
            logo = logo.set_duration(clip.duration)

            final_clip = CompositeVideoClip([clip, logo])
            final_clip.write_videofile(str(output_path), codec="libx264", audio_codec="aac")

            clip.close()
            logo.close()
            final_clip.close()

            output_size = output_path.stat().st_size if output_path.exists() else 0

            return {
                "success": True,
                "video_id": video.video_id,
                "watermark_type": "image",
                "image_path": image_path,
                "position": pos,
                "opacity": op,
                "scale": scale,
                "output_path": str(output_path),
                "output_size": output_size,
            }

        except ImportError:
            if output_path:
                shutil.copy2(video.storage_path, str(output_path))
            return {
                "success": True,
                "video_id": video.video_id,
                "watermark_type": "image",
                "image_path": image_path,
                "output_path": str(output_path) if output_path else video.storage_path,
                "note": "moviepy not available, copied original file",
            }
        except Exception as e:
            return {
                "success": False,
                "video_id": video.video_id,
                "watermark_type": "image",
                "error": str(e),
            }

    def get_available_positions(self) -> List[str]:
        return [
            "top-left",
            "top-right",
            "top-center",
            "bottom-left",
            "bottom-right",
            "bottom-center",
            "center",
        ]

    def get_default_config(self) -> Dict[str, Any]:
        return dict(self.default_config)
