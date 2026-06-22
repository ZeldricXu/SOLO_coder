import re
from typing import Optional, List, Tuple

from .document_model import DocumentModel, DocumentNode, NodeType


class MarkdownParser:
    def __init__(self):
        self._heading_re = re.compile(r'^(#{1,6})\s+(.+)$')
        self._fenced_code_re = re.compile(r'^```(\w*)\s*$')
        self._inline_code_re = re.compile(r'`([^`]+)`')
        self._bold_re = re.compile(r'\*\*([^*]+)\*\*')
        self._italic_re = re.compile(r'\*([^*]+)\*')
        self._link_re = re.compile(r'\[([^\]]+)\]\(([^)]+)\)')
        self._image_re = re.compile(r'!\[([^\]]*)\]\(([^)]+)\)')
        self._formula_inline_re = re.compile(r'\$([^$\n]+)\$')
        self._formula_block_re = re.compile(r'\$\$\s*([^$]+?)\s*\$\$', re.DOTALL)
        self._list_item_re = re.compile(r'^(\s*)([-*+]|\d+\.)\s+(.+)$')
        self._quote_re = re.compile(r'^>\s+(.+)$')
        self._table_sep_re = re.compile(r'^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$')
        self._table_row_re = re.compile(r'^\s*\|(.+)\|\s*$')

    def parse(self, markdown_text: str) -> DocumentModel:
        model = DocumentModel()
        lines = markdown_text.split('\n') if markdown_text else []
        i = 0

        while i < len(lines):
            line = lines[i]

            if not line.strip():
                i += 1
                continue

            heading_match = self._heading_re.match(line)
            if heading_match:
                level = len(heading_match.group(1))
                text = heading_match.group(2)
                model.append_heading(level, text)
                i += 1
                continue

            if line.strip().startswith('```') or line.strip().startswith('~~~'):
                lang_match = self._fenced_code_re.match(line.strip())
                lang = lang_match.group(1) if lang_match else ''
                code_lines = []
                i += 1
                while i < len(lines) and not lines[i].strip().startswith(('```', '~~~')):
                    code_lines.append(lines[i])
                    i += 1
                i += 1
                model.append_code_block(lang, '\n'.join(code_lines))
                continue

            formula_match = self._formula_block_re.match('\n'.join(lines[i:]))
            if formula_match and (i == 0 or (i > 0 and not lines[i-1].strip())):
                formula = formula_match.group(1).strip()
                model.append_formula(formula, inline=False)
                lines_consumed = len(formula_match.group(0).split('\n'))
                i += lines_consumed
                continue

            if i + 1 < len(lines) and self._table_sep_re.match(lines[i+1]) and '|' in line:
                headers = self._parse_table_row(line)
                data_rows = []
                i += 2
                while i < len(lines) and lines[i].strip():
                    row = self._parse_table_row(lines[i])
                    if row:
                        data_rows.append(row)
                    i += 1
                if headers:
                    model.append_table(data_rows, headers)
                    continue

            list_match = self._list_item_re.match(line)
            if list_match:
                items = []
                ordered = bool(re.match(r'^\d+\.', list_match.group(2)))
                while i < len(lines) and self._list_item_re.match(lines[i]):
                    m = self._list_item_re.match(lines[i])
                    if m:
                        items.append(m.group(3))
                    i += 1
                model.append_list(items, ordered=ordered)
                continue

            quote_match = self._quote_re.match(line)
            if quote_match:
                quote_lines = [quote_match.group(1)]
                i += 1
                while i < len(lines) and self._quote_re.match(lines[i]):
                    m = self._quote_re.match(lines[i])
                    if m:
                        quote_lines.append(m.group(1))
                    i += 1
                model.append_quote(' '.join(quote_lines))
                continue

            if line.strip() == '---' or line.strip() == '***':
                i += 1
                continue

            para_lines = [line]
            i += 1
            while i < len(lines) and lines[i].strip() and not self._is_block_start(lines[i]):
                para_lines.append(lines[i])
                i += 1

            para_text = ' '.join(para_lines)
            para_node = self._parse_paragraph(para_text)
            model._push_undo()
            model.root.add_child(para_node)

        return model

    def _is_block_start(self, line: str) -> bool:
        if self._heading_re.match(line):
            return True
        if line.strip().startswith(('```', '~~~')):
            return True
        if self._list_item_re.match(line):
            return True
        if self._quote_re.match(line):
            return True
        if line.strip() in ('---', '***'):
            return True
        if self._table_sep_re.match(line):
            return True
        return False

    def _parse_table_row(self, line: str) -> Optional[List[str]]:
        row_match = self._table_row_re.match(line)
        if row_match:
            cells = [c.strip() for c in row_match.group(1).split('|')]
            return cells
        if '|' in line:
            cells = [c.strip() for c in line.split('|')]
            return cells
        return None

    def _parse_paragraph(self, text: str) -> DocumentNode:
        para = DocumentNode(NodeType.PARAGRAPH)
        remaining = text

        pos = 0
        while pos < len(remaining):
            image_match = self._image_re.search(remaining, pos)
            formula_match = self._formula_inline_re.search(remaining, pos)
            link_match = self._link_re.search(remaining, pos)
            code_match = self._inline_code_re.search(remaining, pos)
            bold_match = self._bold_re.search(remaining, pos)
            italic_match = self._italic_re.search(remaining, pos)

            matches = []
            if image_match:
                matches.append(('image', image_match))
            if formula_match:
                matches.append(('formula', formula_match))
            if link_match:
                matches.append(('link', link_match))
            if code_match:
                matches.append(('code', code_match))
            if bold_match:
                matches.append(('bold', bold_match))
            if italic_match:
                matches.append(('italic', italic_match))

            if not matches:
                if pos < len(remaining):
                    para.add_child(DocumentNode(
                        NodeType.TEXT,
                        text_content=remaining[pos:]
                    ))
                break

            matches.sort(key=lambda m: m[1].start())
            mtype, match = matches[0]

            if match.start() > pos:
                para.add_child(DocumentNode(
                    NodeType.TEXT,
                    text_content=remaining[pos:match.start()]
                ))

            if mtype == 'image':
                alt = match.group(1)
                src = match.group(2)
                img_node = DocumentNode(
                    NodeType.IMAGE,
                    attributes={"path": src, "alt": alt, "title": ""}
                )
                para.add_child(img_node)
            elif mtype == 'formula':
                latex = match.group(1)
                formula_node = DocumentNode(
                    NodeType.FORMULA,
                    attributes={"latex": latex, "inline": True}
                )
                para.add_child(formula_node)
            elif mtype == 'link':
                text = match.group(1)
                href = match.group(2)
                link_node = DocumentNode(
                    NodeType.LINK,
                    attributes={"href": href}
                )
                link_node.add_child(DocumentNode(
                    NodeType.TEXT,
                    text_content=text
                ))
                para.add_child(link_node)
            elif mtype == 'code':
                code = match.group(1)
                code_node = DocumentNode(
                    NodeType.INLINE_CODE,
                    text_content=code
                )
                para.add_child(code_node)
            elif mtype == 'bold':
                bold_text = match.group(1)
                bold_node = DocumentNode(
                    NodeType.TEXT,
                    attributes={"bold": True},
                    text_content=bold_text
                )
                para.add_child(bold_node)
            elif mtype == 'italic':
                italic_text = match.group(1)
                italic_node = DocumentNode(
                    NodeType.TEXT,
                    attributes={"italic": True},
                    text_content=italic_text
                )
                para.add_child(italic_node)

            pos = match.end()

        return para


class DocumentHtmlRenderer:
    def __init__(self, images_dir: str = "", inline_images: bool = False):
        self.images_dir = images_dir
        self.inline_images = inline_images

    def render(self, model: DocumentModel) -> str:
        return self._render_node(model.root)

    def _render_node(self, node: DocumentNode, indent: int = 0) -> str:
        indent_str = "  " * indent
        result = ""

        if node.node_type == NodeType.DOCUMENT:
            parts = []
            for child in node.children:
                parts.append(self._render_node(child, indent))
            return "\n".join(parts)

        if node.node_type == NodeType.PARAGRAPH:
            content = self._render_children(node, indent + 1)
            return f"{indent_str}<p>{content}</p>"

        if node.node_type == NodeType.HEADING:
            level = node.attributes.get("level", 1)
            content = self._render_children(node, indent + 1)
            return f"{indent_str}<h{level}>{content}</h{level}>"

        if node.node_type == NodeType.CODE_BLOCK:
            lang = node.attributes.get("language", "")
            lang_class = f' class="language-{lang}"' if lang else ''
            from html import escape
            code = escape(node.text_content)
            return f'{indent_str}<pre><code{lang_class}>{code}</code></pre>'

        if node.node_type == NodeType.IMAGE:
            src = node.attributes.get("path", "")
            alt = node.attributes.get("alt", "")
            title = node.attributes.get("title", "")
            if self.inline_images and src:
                src = self._image_to_base64(src)
            attrs = [f'src="{src}"']
            if alt:
                attrs.append(f'alt="{alt}"')
            if title:
                attrs.append(f'title="{title}"')
            return f'{indent_str}<img {" ".join(attrs)} />'

        if node.node_type == NodeType.FORMULA:
            latex = node.attributes.get("latex", "")
            inline = node.attributes.get("inline", False)
            if inline:
                return f'<span class="katex-inline" data-latex="{latex}">${latex}$</span>'
            else:
                return f'{indent_str}<div class="katex-block" data-latex="{latex}">$${latex}$$</div>'

        if node.node_type == NodeType.TABLE:
            parts = [f"{indent_str}<table>"]
            for child in node.children:
                parts.append(self._render_node(child, indent + 1))
            parts.append(f"{indent_str}</table>")
            return "\n".join(parts)

        if node.node_type == NodeType.TABLE_ROW:
            is_header = node.attributes.get("is_header", False)
            cell_tag = "th" if is_header else "td"
            parts = [f"{indent_str}<tr>"]
            for child in node.children:
                cell_content = self._render_children(child, indent + 2)
                parts.append(f"  {indent_str}<{cell_tag}>{cell_content}</{cell_tag}>")
            parts.append(f"{indent_str}</tr>")
            return "\n".join(parts)

        if node.node_type == NodeType.LIST:
            ordered = node.attributes.get("ordered", False)
            tag = "ol" if ordered else "ul"
            parts = [f"{indent_str}<{tag}>"]
            for child in node.children:
                parts.append(self._render_node(child, indent + 1))
            parts.append(f"{indent_str}</{tag}>")
            return "\n".join(parts)

        if node.node_type == NodeType.LIST_ITEM:
            content = self._render_children(node, indent + 1)
            return f"{indent_str}<li>{content}</li>"

        if node.node_type == NodeType.QUOTE:
            content = self._render_children(node, indent + 1)
            return f"{indent_str}<blockquote>{content}</blockquote>"

        if node.node_type == NodeType.TEXT:
            from html import escape
            text = escape(node.text_content)
            if node.attributes.get("bold"):
                text = f"<strong>{text}</strong>"
            if node.attributes.get("italic"):
                text = f"<em>{text}</em>"
            return text

        if node.node_type == NodeType.LINK:
            href = node.attributes.get("href", "#")
            content = self._render_children(node, indent)
            return f'<a href="{href}">{content}</a>'

        if node.node_type == NodeType.INLINE_CODE:
            from html import escape
            return f"<code>{escape(node.text_content)}</code>"

        return self._render_children(node, indent)

    def _render_children(self, node: DocumentNode, indent: int = 0) -> str:
        return "".join(self._render_node(c, indent) for c in node.children)

    def _image_to_base64(self, image_path: str) -> str:
        import base64
        from pathlib import Path
        try:
            path = Path(image_path)
            if not path.is_absolute() and self.images_dir:
                path = Path(self.images_dir) / image_path
            if path.exists():
                ext = path.suffix.lower().lstrip(".")
                if ext == "jpg":
                    ext = "jpeg"
                with open(path, "rb") as f:
                    data = base64.b64encode(f.read()).decode("utf-8")
                return f"data:image/{ext};base64,{data}"
        except Exception:
            pass
        return image_path
