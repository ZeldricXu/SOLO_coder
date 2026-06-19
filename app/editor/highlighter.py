import re
from typing import Dict

from PyQt6.QtCore import Qt, QRegularExpression
from PyQt6.QtGui import (
    QSyntaxHighlighter,
    QTextCharFormat,
    QFont,
    QColor,
    QTextDocument,
)

from pygments import highlight
from pygments.lexers import get_lexer_by_name
from pygments.formatters import HtmlFormatter
from pygments.util import ClassNotFound


CODE_LANGUAGES = {
    "python", "javascript", "js", "java", "cpp", "c++", "c", "csharp", "c#",
    "sql", "bash", "shell", "sh", "json", "xml", "html", "css", "ruby",
    "go", "rust", "rs", "typescript", "ts", "php", "swift", "kotlin",
    "yaml", "yml", "markdown", "md", "dockerfile", "makefile",
}


class MarkdownHighlighter(QSyntaxHighlighter):

    def __init__(self, document: QTextDocument, theme: str = "light"):
        super().__init__(document)
        self.theme = theme
        self._init_formats()
        self._init_rules()
        self._code_block_stack: list[tuple[int, str]] = []

    def _init_formats(self):
        if self.theme == "dark":
            self._colors = {
                "text": QColor("#e0e0e0"),
                "heading": QColor("#4fc3f7"),
                "bold": QColor("#ffca28"),
                "italic": QColor("#ce93d8"),
                "code": QColor("#80cbc4"),
                "code_block_bg": QColor("#263238"),
                "quote": QColor("#90a4ae"),
                "link": QColor("#64b5f6"),
                "list": QColor("#a5d6a7"),
                "image": QColor("#ff8a65"),
                "math": QColor("#b39ddb"),
                "tag": QColor("#f48fb1"),
                "h1": QColor("#ef5350"),
                "h2": QColor("#ffa726"),
                "h3": QColor("#ffee58"),
                "h4": QColor("#66bb6a"),
                "h5": QColor("#26c6da"),
                "h6": QColor("#ab47bc"),
            }
        else:
            self._colors = {
                "text": QColor("#212121"),
                "heading": QColor("#1565c0"),
                "bold": QColor("#e65100"),
                "italic": QColor("#6a1b9a"),
                "code": QColor("#00695c"),
                "code_block_bg": QColor("#f5f5f5"),
                "quote": QColor("#546e7a"),
                "link": QColor("#1976d2"),
                "list": QColor("#2e7d32"),
                "image": QColor("#d84315"),
                "math": QColor("#4527a0"),
                "tag": QColor("#ad1457"),
                "h1": QColor("#c62828"),
                "h2": QColor("#ef6c00"),
                "h3": QColor("#f9a825"),
                "h4": QColor("#2e7d32"),
                "h5": QColor("#00838f"),
                "h6": QColor("#6a1b9a"),
            }

        self._formats: Dict[str, QTextCharFormat] = {}

        for level in range(1, 7):
            fmt = QTextCharFormat()
            fmt.setForeground(self._colors[f"h{level}"])
            fmt.setFontWeight(QFont.Weight.Bold)
            size_increment = 6 - level + 2
            fmt.setFontPointSize(14 + size_increment)
            self._formats[f"h{level}"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["bold"])
        fmt.setFontWeight(QFont.Weight.Bold)
        self._formats["bold"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["italic"])
        fmt.setFontItalic(True)
        self._formats["italic"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["bold"])
        fmt.setFontWeight(QFont.Weight.Bold)
        fmt.setFontItalic(True)
        self._formats["bold_italic"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["code"])
        fmt.setFontFamily("Monaco")
        self._formats["inline_code"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["code"])
        fmt.setFontFamily("Monaco")
        fmt.setBackground(self._colors["code_block_bg"])
        self._formats["code_block"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["quote"])
        fmt.setFontItalic(True)
        self._formats["quote"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["link"])
        fmt.setFontUnderline(True)
        fmt.setUnderlineColor(self._colors["link"])
        self._formats["link"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["image"])
        self._formats["image"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["list"])
        fmt.setFontWeight(QFont.Weight.Bold)
        self._formats["list"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["math"])
        self._formats["math"] = fmt

        fmt = QTextCharFormat()
        fmt.setForeground(self._colors["tag"])
        self._formats["tag"] = fmt

    def _init_rules(self):
        self._rules = []

        self._rules.append((QRegularExpression(r"^######\s.*$"), 0, "h6"))
        self._rules.append((QRegularExpression(r"^#####\s.*$"), 0, "h5"))
        self._rules.append((QRegularExpression(r"^####\s.*$"), 0, "h4"))
        self._rules.append((QRegularExpression(r"^###\s.*$"), 0, "h3"))
        self._rules.append((QRegularExpression(r"^##\s.*$"), 0, "h2"))
        self._rules.append((QRegularExpression(r"^#\s.*$"), 0, "h1"))

        self._rules.append((QRegularExpression(r"\*\*\*[^\*]+\*\*\*"), 0, "bold_italic"))
        self._rules.append((QRegularExpression(r"___[^_]+___"), 0, "bold_italic"))

        self._rules.append((QRegularExpression(r"\*\*[^\*]+\*\*"), 0, "bold"))
        self._rules.append((QRegularExpression(r"__[^_]+__"), 0, "bold"))

        self._rules.append((QRegularExpression(r"(?<!\*)\*[^\*\n]+\*(?!\*)"), 0, "italic"))
        self._rules.append((QRegularExpression(r"(?<!_)_[^_\n]+_(?!_)"), 0, "italic"))

        self._rules.append((QRegularExpression(r"`[^`\n]+`"), 0, "inline_code"))

        self._rules.append((QRegularExpression(r"^>\s?.*$"), 0, "quote"))

        self._rules.append((QRegularExpression(r"^\s*[-*+]\s"), 0, "list"))
        self._rules.append((QRegularExpression(r"^\s*\d+\.\s"), 0, "list"))

        self._rules.append((QRegularExpression(r"!\[[^\]]*\]\([^)]+\)"), 0, "image"))

        self._rules.append((QRegularExpression(r"(?<!\!)\[[^\]]*\]\([^)]+\)"), 0, "link"))
        self._rules.append((QRegularExpression(r"<https?://[^>\s]+>"), 0, "link"))

        self._rules.append((QRegularExpression(r"\$[^\$\n]+\$"), 0, "math"))
        self._rules.append((QRegularExpression(r"\$\$[^\$]+\$\$"), 0, "math"))

        self._rules.append((QRegularExpression(r"@note/\d+"), 0, "link"))
        self._rules.append((QRegularExpression(r"#\w+"), 0, "tag"))

    def highlightBlock(self, text: str):
        block = self.currentBlock()
        block_num = block.blockNumber()

        prev_state = self.previousBlockState()
        in_code_block = prev_state != -1
        code_lang = ""
        if in_code_block:
            code_lang = prev_state if prev_state > 0 else ""

        code_start_match = re.match(r"^```(\w*)\s*$", text)
        if code_start_match and not in_code_block:
            in_code_block = True
            code_lang = code_start_match.group(1) or ""
            self.setFormat(0, len(text), self._formats["code_block"])
            self.setCurrentBlockState(len(code_lang) if code_lang else 1)
            return

        if re.match(r"^```\s*$", text) and in_code_block:
            in_code_block = False
            self.setFormat(0, len(text), self._formats["code_block"])
            self.setCurrentBlockState(-1)
            return

        if in_code_block:
            self.setFormat(0, len(text), self._formats["code_block"])
            self.setCurrentBlockState(len(code_lang) if code_lang else 1)
            return

        self.setCurrentBlockState(-1)

        for pattern, index, format_key in self._rules:
            regex = pattern
            match_iterator = regex.globalMatch(text)
            while match_iterator.hasNext():
                match = match_iterator.next()
                start = match.capturedStart(index)
                length = match.capturedLength(index)
                self.setFormat(start, length, self._formats[format_key])


def highlight_code_to_html(code: str, language: str) -> str:
    try:
        lexer = get_lexer_by_name(language, stripall=False)
    except ClassNotFound:
        lexer = get_lexer_by_name("text", stripall=False)
    formatter = HtmlFormatter(linenos=False, cssclass="codehilite", wrapcode=True)
    return highlight(code, lexer, formatter)
