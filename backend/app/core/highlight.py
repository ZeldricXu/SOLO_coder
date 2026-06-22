from pygments import highlight
from pygments.lexers import get_lexer_by_name, guess_lexer, ClassNotFound
from pygments.formatters import HtmlFormatter


LANGUAGE_ALIASES = {
    "js": "javascript",
    "ts": "typescript",
    "py": "python",
    "python3": "python",
    "golang": "go",
    "sh": "bash",
    "shell": "bash",
    "yml": "yaml",
    "md": "markdown",
    "c++": "cpp",
    "c#": "csharp",
    "dockerfile": "docker",
}


def normalize_language(lang: str) -> str:
    if not lang:
        return "text"
    lower = lang.strip().lower()
    return LANGUAGE_ALIASES.get(lower, lower)


def highlight_code(code: str, language: str) -> str:
    lang = normalize_language(language)
    try:
        lexer = get_lexer_by_name(lang, stripall=False)
    except ClassNotFound:
        try:
            lexer = guess_lexer(code)
        except ClassNotFound:
            lexer = get_lexer_by_name("text")

    formatter = HtmlFormatter(
        style="github-dark",
        linenos=False,
        cssclass="hljs",
        wrapcode=True,
        nowrap=False,
    )
    return highlight(code, lexer, formatter)


def get_highlight_css() -> str:
    formatter = HtmlFormatter(style="github-dark")
    return formatter.get_style_defs(".hljs")
