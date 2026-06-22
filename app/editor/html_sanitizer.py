import re
from html import escape
from typing import Set, Optional

try:
    from lxml import html as lxml_html
    from lxml.html.clean import Cleaner
    LXML_AVAILABLE = True
except ImportError:
    LXML_AVAILABLE = False


ALLOWED_TAGS: Set[str] = {
    "p", "br", "span", "em", "strong", "b", "i", "u", "s", "del",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "ul", "ol", "li",
    "blockquote", "code", "pre",
    "table", "thead", "tbody", "tr", "th", "td",
    "a", "img", "hr", "div",
    "sub", "sup", "mark", "cite",
}

ALLOWED_ATTRIBUTES: Set[str] = {
    "href", "src", "alt", "title", "class", "id",
    "colspan", "rowspan", "target", "rel", "data-*",
}

ALLOWED_PROTOCOLS: Set[str] = {"http", "https", "mailto", "ftp", "file"}


class HtmlSanitizer:
    def __init__(
        self,
        allowed_tags: Optional[Set[str]] = None,
        allowed_attributes: Optional[Set[str]] = None,
        strip_style: bool = True,
        strip_scripts: bool = True,
        remove_comments: bool = True,
    ):
        self.allowed_tags = allowed_tags or ALLOWED_TAGS
        self.allowed_attributes = allowed_attributes or ALLOWED_ATTRIBUTES
        self.strip_style = strip_style
        self.strip_scripts = strip_scripts
        self.remove_comments = remove_comments
        self._removed_tags: Set[str] = set()
        self._removed_scripts_count = 0

    @property
    def removed_tags(self) -> Set[str]:
        return set(self._removed_tags)

    @property
    def removed_scripts_count(self) -> int:
        return self._removed_scripts_count

    def reset_stats(self):
        self._removed_tags.clear()
        self._removed_scripts_count = 0

    def sanitize(self, html_content: str) -> str:
        self.reset_stats()
        if not html_content or not html_content.strip():
            return ""

        if LXML_AVAILABLE:
            return self._sanitize_with_lxml(html_content)
        else:
            return self._sanitize_with_regex(html_content)

    def _sanitize_with_lxml(self, html_content: str) -> str:
        try:
            kwargs = {
                "scripts": self.strip_scripts,
                "javascript": self.strip_scripts,
                "comments": self.remove_comments,
                "style": self.strip_style,
                "links": False,
                "meta": True,
                "add_nofollow": False,
                "page_structure": True,
                "embedded": True,
                "frames": True,
                "forms": True,
                "annoying_tags": True,
                "remove_tags": None,
                "allow_tags": self.allowed_tags,
                "kill_tags": ["script", "style", "iframe", "frame", "frameset"],
            }
            cleaner = Cleaner(**kwargs)
            cleaned_html = cleaner.clean_html(html_content)

            doc = lxml_html.fromstring(cleaned_html)
            for elem in doc.iter():
                if elem.tag not in self.allowed_tags:
                    self._removed_tags.add(elem.tag)
                if elem.tag in ("script", "style"):
                    self._removed_scripts_count += 1
                for attr in list(elem.attrib.keys()):
                    if attr not in self.allowed_attributes and not attr.startswith("data-"):
                        del elem.attrib[attr]

            for elem in doc.xpath("//a"):
                href = elem.get("href", "")
                if self._is_dangerous_url(href):
                    elem.set("href", "#")
                    elem.set("rel", "nofollow noopener")

            for elem in doc.xpath("//img"):
                src = elem.get("src", "")
                if self._is_dangerous_url(src):
                    elem.set("src", "")
                    elem.set("alt", elem.get("alt", "") + " [removed]")

            result = lxml_html.tostring(doc, encoding="unicode", method="html")
            return result
        except Exception:
            return self._sanitize_with_regex(html_content)

    def _sanitize_with_regex(self, html_content: str) -> str:
        sanitized = html_content

        script_pattern = re.compile(
            r'<script[^>]*>.*?</script>',
            re.DOTALL | re.IGNORECASE
        )
        script_matches = script_pattern.findall(sanitized)
        self._removed_scripts_count = len(script_matches)
        sanitized = script_pattern.sub('', sanitized)

        style_pattern = re.compile(
            r'<style[^>]*>.*?</style>',
            re.DOTALL | re.IGNORECASE
        )
        sanitized = style_pattern.sub('', sanitized)

        comment_pattern = re.compile(r'<!--.*?-->', re.DOTALL)
        sanitized = comment_pattern.sub('', sanitized)

        if self.strip_style:
            sanitized = re.sub(r'\s+style="[^"]*"', '', sanitized, flags=re.IGNORECASE)
            sanitized = re.sub(r"\s+style='[^']*'", '', sanitized, flags=re.IGNORECASE)

        event_handler_pattern = re.compile(
            r'\s+on\w+\s*=\s*"[^"]*"',
            re.IGNORECASE
        )
        sanitized = event_handler_pattern.sub('', sanitized)
        event_handler_pattern2 = re.compile(
            r"\s+on\w+\s*=\s*'[^']*'",
            re.IGNORECASE
        )
        sanitized = event_handler_pattern2.sub('', sanitized)

        dangerous_tags = ["iframe", "frame", "frameset", "object", "embed", "applet"]
        for tag in dangerous_tags:
            pattern = re.compile(
                rf'<{tag}[^>]*>.*?</{tag}>',
                re.DOTALL | re.IGNORECASE
            )
            matches = pattern.findall(sanitized)
            if matches:
                self._removed_tags.add(tag)
            sanitized = pattern.sub('', sanitized)

        href_pattern = re.compile(
            r'href\s*=\s*["\']([^"\']+)["\']',
            re.IGNORECASE
        )
        for match in href_pattern.finditer(sanitized):
            url = match.group(1)
            if self._is_dangerous_url(url):
                sanitized = sanitized.replace(match.group(0), 'href="#" rel="nofollow noopener"')

        src_pattern = re.compile(
            r'src\s*=\s*["\']([^"\']+)["\']',
            re.IGNORECASE
        )
        for match in src_pattern.finditer(sanitized):
            url = match.group(1)
            if self._is_dangerous_url(url):
                original = match.group(0)
                sanitized = sanitized.replace(original, 'src="" alt="Removed"')

        return sanitized

    def _is_dangerous_url(self, url: str) -> bool:
        if not url:
            return False
        url_lower = url.lower().strip()

        if url_lower.startswith("javascript:"):
            return True
        if url_lower.startswith("data:") and "html" in url_lower:
            return True
        if url_lower.startswith("vbscript:"):
            return True

        has_protocol = "://" in url_lower
        if has_protocol:
            protocol = url_lower.split("://")[0]
            if protocol not in ALLOWED_PROTOCOLS:
                return True

        return False

    def has_unsafe_content(self, html_content: str) -> bool:
        if not html_content:
            return False

        if re.search(r'<script[^>]*>', html_content, re.IGNORECASE):
            return True
        if re.search(r'javascript:', html_content, re.IGNORECASE):
            return True
        if re.search(r'on\w+\s*=', html_content, re.IGNORECASE):
            return True
        if re.search(r'<iframe[^>]*>', html_content, re.IGNORECASE):
            return True

        return False

    def get_sanitize_report(self) -> dict:
        return {
            "removed_tags": list(self._removed_tags),
            "removed_scripts_count": self._removed_scripts_count,
        }
