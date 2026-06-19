import io
import re
import hashlib
from pathlib import Path
from typing import Optional, Tuple

from PyQt6.QtGui import QTextImageFormat, QImage, QTextCursor

try:
    import matplotlib
    matplotlib.use("Agg")
    from matplotlib import pyplot as plt
    from matplotlib import mathtext
    MATPLOTLIB_AVAILABLE = True
except ImportError:
    MATPLOTLIB_AVAILABLE = False


class KaTeXRenderer:

    def __init__(self, cache_dir: Optional[str] = None):
        self.cache_dir = Path(cache_dir) if cache_dir else Path.home() / ".knowledge_vault" / "formulas"
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._formula_cache: dict[str, str] = {}

    @staticmethod
    def _get_cache_key(latex: str, inline: bool, fontsize: int) -> str:
        key_str = f"{inline}:{fontsize}:{latex}"
        return hashlib.md5(key_str.encode("utf-8")).hexdigest()

    def _get_cache_path(self, cache_key: str) -> Path:
        return self.cache_dir / f"{cache_key}.png"

    def _render_with_matplotlib(self, latex: str, inline: bool, fontsize: int) -> Optional[str]:
        if not MATPLOTLIB_AVAILABLE:
            return None

        cache_key = self._get_cache_key(latex, inline, fontsize)
        cache_path = self._get_cache_path(cache_key)

        if cache_path.exists():
            self._formula_cache[cache_key] = str(cache_path)
            return str(cache_path)

        try:
            display = not inline
            dpi = 150 if display else 120

            fig = plt.figure(figsize=(0.01, 0.01))
            fig.text(
                0, 0,
                f"${latex}$",
                fontsize=fontsize,
                usetex=False,
                horizontalalignment="left",
                verticalalignment="bottom",
            )

            buf = io.BytesIO()
            fig.savefig(buf, format="png", dpi=dpi, bbox_inches="tight",
                        pad_inches=0.05, transparent=True)
            plt.close(fig)
            buf.seek(0)

            img = QImage.fromData(buf.getvalue(), "PNG")
            if img.isNull():
                return None

            img.save(str(cache_path), "PNG")
            self._formula_cache[cache_key] = str(cache_path)
            return str(cache_path)

        except Exception:
            try:
                plt.close("all")
            except Exception:
                pass
            return None

    def _fallback_render(self, latex: str, inline: bool, fontsize: int) -> Optional[str]:
        import html

        cache_key = self._get_cache_key(f"fallback:{latex}", inline, fontsize)
        cache_path = self._get_cache_path(cache_key)

        if cache_path.exists():
            self._formula_cache[cache_key] = str(cache_path)
            return str(cache_path)

        try:
            escaped = html.escape(latex)
            prefix = "" if inline else "\n"
            display_latex = prefix + escaped + (prefix if not inline else "")

            lines = display_latex.split("\n")
            max_len = max(len(line) for line in lines) if lines else 0
            width = max(max_len * fontsize * 0.6 + 20, 100)
            height = len(lines) * fontsize * 1.5 + 20

            from PyQt6.QtGui import QPainter, QBrush, QColor, QFont, QPen

            img = QImage(int(width), int(height), QImage.Format.Format_ARGB32)
            img.fill(QColor(255, 255, 255, 0))

            painter = QPainter(img)
            painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
            painter.setPen(QColor(70, 70, 70))
            font = QFont("Monospace", fontsize if inline else fontsize + 2)
            painter.setFont(font)

            y_offset = fontsize * 1.2
            for line in lines:
                painter.drawText(10, int(y_offset), line)
                y_offset += fontsize * 1.5

            painter.end()

            img.save(str(cache_path), "PNG")
            self._formula_cache[cache_key] = str(cache_path)
            return str(cache_path)

        except Exception:
            return None

    def render_formula(self, latex: str, inline: bool = True, fontsize: int = 12) -> Optional[str]:
        latex = latex.strip()
        if not latex:
            return None

        result = self._render_with_matplotlib(latex, inline, fontsize)
        if result:
            return result

        return self._fallback_render(latex, inline, fontsize)

    def insert_formula_into_cursor(self, cursor: QTextCursor, latex: str,
                                    inline: bool = True, fontsize: int = 12) -> bool:
        image_path = self.render_formula(latex, inline, fontsize)
        if not image_path:
            return False

        img = QImage(image_path)
        if img.isNull():
            return False

        image_format = QTextImageFormat()
        image_format.setName(image_path)
        image_format.setWidth(img.width())
        image_format.setHeight(img.height())
        image_format.setProperty(QTextImageFormat.Property.Name, image_path)

        if inline:
            cursor.insertImage(image_format)
        else:
            cursor.insertBlock()
            cursor.insertImage(image_format)
            cursor.insertBlock()

        return True

    @staticmethod
    def extract_formulas(text: str) -> list[Tuple[int, int, str, bool]]:
        formulas = []

        block_pattern = re.compile(r"\$\$([^\$]+?)\$\$", re.DOTALL)
        for match in block_pattern.finditer(text):
            formulas.append((match.start(), match.end(), match.group(1).strip(), False))

        inline_pattern = re.compile(r"(?<!\$)\$([^\$\n]+?)\$(?!\$)")
        for match in inline_pattern.finditer(text):
            is_inside_block = any(
                start <= match.start() and match.end() <= end
                for start, end, _, _ in formulas
            )
            if not is_inside_block:
                formulas.append((match.start(), match.end(), match.group(1).strip(), True))

        formulas.sort(key=lambda x: x[0])
        return formulas

    def clear_cache(self):
        self._formula_cache.clear()
        import shutil
        if self.cache_dir.exists():
            shutil.rmtree(self.cache_dir)
            self.cache_dir.mkdir(parents=True, exist_ok=True)
