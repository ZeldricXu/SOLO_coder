package editor

import (
	"strings"
)

type WYSIWYGEditor struct {
	content   string
	html      string
	cursor    CursorPosition
	selection Selection
}

func NewWYSIWYGEditor() *WYSIWYGEditor {
	return &WYSIWYGEditor{}
}

func (w *WYSIWYGEditor) SetContent(content string) {
	w.content = content
}

func (w *WYSIWYGEditor) GetContent() string {
	return w.content
}

func (w *WYSIWYGEditor) SetHTML(html string) {
	w.html = html
}

func (w *WYSIWYGEditor) GetHTML() string {
	return w.html
}

func (w *WYSIWYGEditor) SetCursor(pos CursorPosition) {
	w.cursor = pos
}

func (w *WYSIWYGEditor) GetCursor() CursorPosition {
	return w.cursor
}

func (w *WYSIWYGEditor) SetSelection(sel Selection) {
	w.selection = sel
}

func (w *WYSIWYGEditor) GetSelection() Selection {
	return w.selection
}

type BlockType string

const (
	BlockParagraph   BlockType = "paragraph"
	BlockHeading1    BlockType = "heading_1"
	BlockHeading2    BlockType = "heading_2"
	BlockHeading3    BlockType = "heading_3"
	BlockHeading4    BlockType = "heading_4"
	BlockHeading5    BlockType = "heading_5"
	BlockHeading6    BlockType = "heading_6"
	BlockQuote       BlockType = "quote"
	BlockCodeBlock   BlockType = "code_block"
	BlockUnorderedList BlockType = "unordered_list"
	BlockOrderedList   BlockType = "ordered_list"
	BlockTable       BlockType = "table"
	BlockHR          BlockType = "horizontal_rule"
	BlockMath        BlockType = "math"
)

type Block struct {
	Type     BlockType
	Content  string
	Children []Block
	Language string
	Level    int
}

type InlineType string

const (
	InlineText     InlineType = "text"
	InlineBold     InlineType = "bold"
	InlineItalic   InlineType = "italic"
	InlineStrike   InlineType = "strike"
	InlineCode     InlineType = "code"
	InlineLink     InlineType = "link"
	InlineImage    InlineType = "image"
	InlineWikiLink InlineType = "wiki_link"
	InlineTag      InlineType = "tag"
	InlineMath     InlineType = "math"
)

type Inline struct {
	Type    InlineType
	Content string
	URL     string
	Alt     string
	Target  string
}

func (w *WYSIWYGEditor) ParseBlocks() []Block {
	content := w.content
	lines := splitLines(content)
	var blocks []Block

	i := 0
	for i < len(lines) {
		line := lines[i]

		if strings.HasPrefix(line, "```") {
			lang := strings.TrimPrefix(line, "```")
			codeLines := []string{}
			i++
			for i < len(lines) && !strings.HasPrefix(lines[i], "```") {
				codeLines = append(codeLines, lines[i])
				i++
			}
			if i < len(lines) {
				i++
			}
			blocks = append(blocks, Block{
				Type:     BlockCodeBlock,
				Content:  strings.Join(codeLines, "\n"),
				Language: lang,
			})
			continue
		}

		if strings.HasPrefix(line, "$$") {
			mathLines := []string{}
			i++
			for i < len(lines) && !strings.HasPrefix(lines[i], "$$") {
				mathLines = append(mathLines, lines[i])
				i++
			}
			if i < len(lines) {
				i++
			}
			blocks = append(blocks, Block{
				Type:    BlockMath,
				Content: strings.Join(mathLines, "\n"),
			})
			continue
		}

		if strings.HasPrefix(line, "#") {
			level := 0
			for level < len(line) && level < 6 && line[level] == '#' {
				level++
			}
			if level > 0 && level <= 6 && level < len(line) && line[level] == ' ' {
				blockType := BlockType("heading_" + string(rune('0'+level)))
				blocks = append(blocks, Block{
					Type:    blockType,
					Content: strings.TrimSpace(line[level+1:]),
					Level:   level,
				})
				i++
				continue
			}
		}

		if strings.HasPrefix(line, "> ") {
			quoteLines := []string{strings.TrimPrefix(line, "> ")}
			i++
			for i < len(lines) && strings.HasPrefix(lines[i], "> ") {
				quoteLines = append(quoteLines, strings.TrimPrefix(lines[i], "> "))
				i++
			}
			blocks = append(blocks, Block{
				Type:    BlockQuote,
				Content: strings.Join(quoteLines, "\n"),
			})
			continue
		}

		if strings.HasPrefix(line, "- ") || strings.HasPrefix(line, "* ") || strings.HasPrefix(line, "+ ") {
			listItems := []string{strings.TrimSpace(line[2:])}
			i++
			for i < len(lines) {
				if strings.HasPrefix(lines[i], "- ") || strings.HasPrefix(lines[i], "* ") || strings.HasPrefix(lines[i], "+ ") {
					listItems = append(listItems, strings.TrimSpace(lines[i][2:]))
					i++
				} else {
					break
				}
			}
			var children []Block
			for _, item := range listItems {
				children = append(children, Block{
					Type:    BlockUnorderedList,
					Content: item,
				})
			}
			blocks = append(blocks, Block{
				Type:     BlockUnorderedList,
				Children: children,
			})
			continue
		}

		if matchOrderedList(line) {
			listItems := []string{skipOrderedList(line)}
			i++
			for i < len(lines) && matchOrderedList(lines[i]) {
				listItems = append(listItems, skipOrderedList(lines[i]))
				i++
			}
			var children []Block
			for _, item := range listItems {
				children = append(children, Block{
					Type:    BlockOrderedList,
					Content: item,
				})
			}
			blocks = append(blocks, Block{
				Type:     BlockOrderedList,
				Children: children,
			})
			continue
		}

		trimmed := strings.TrimSpace(line)
		if (trimmed == "---" || trimmed == "***" || trimmed == "___") && len(trimmed) >= 3 {
			allSame := true
			first := trimmed[0]
			for j := 1; j < len(trimmed); j++ {
				if trimmed[j] != first {
					allSame = false
					break
				}
			}
			if allSame {
				blocks = append(blocks, Block{
					Type: BlockHR,
				})
				i++
				continue
			}
		}

		if strings.Contains(line, "|") && i+1 < len(lines) && isTableSeparator(lines[i+1]) {
			tableLines := []string{line}
			i++
			for i < len(lines) && strings.Contains(lines[i], "|") && strings.TrimSpace(lines[i]) != "" {
				tableLines = append(tableLines, lines[i])
				i++
			}
			blocks = append(blocks, Block{
				Type:    BlockTable,
				Content: strings.Join(tableLines, "\n"),
			})
			continue
		}

		if strings.TrimSpace(line) == "" {
			i++
			continue
		}

		paraLines := []string{line}
		i++
		for i < len(lines) && strings.TrimSpace(lines[i]) != "" &&
			!strings.HasPrefix(lines[i], "#") &&
			!strings.HasPrefix(lines[i], "> ") &&
			!strings.HasPrefix(lines[i], "- ") &&
			!strings.HasPrefix(lines[i], "* ") &&
			!strings.HasPrefix(lines[i], "+ ") &&
			!strings.HasPrefix(lines[i], "```") &&
			!matchOrderedList(lines[i]) {
			paraLines = append(paraLines, lines[i])
			i++
		}
		blocks = append(blocks, Block{
			Type:    BlockParagraph,
			Content: strings.Join(paraLines, " "),
		})
	}

	return blocks
}

func (w *WYSIWYGEditor) ParseInlines(text string) []Inline {
	var inlines []Inline
	i := 0

	for i < len(text) {
		if i+2 <= len(text) && text[i:i+2] == "[[" {
			end := strings.Index(text[i+2:], "]]")
			if end != -1 {
				content := text[i+2 : i+2+end]
				parts := strings.SplitN(content, "|", 2)
				target := parts[0]
				display := target
				if len(parts) > 1 {
					display = parts[1]
				}
				inlines = append(inlines, Inline{
					Type:    InlineWikiLink,
					Content: display,
					Target:  target,
				})
				i += 4 + end
				continue
			}
		}

		if text[i] == '#' {
			if i == 0 || (text[i-1] == ' ' || text[i-1] == '\t' || text[i-1] == '(' || text[i-1] == '[') {
				j := i + 1
				for j < len(text) {
					c := text[j]
					if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
						(c >= '0' && c <= '9') || c == '_' || c == '/' || c == '-' {
						j++
					} else {
						break
					}
				}
				if j > i+1 {
					inlines = append(inlines, Inline{
						Type:    InlineTag,
						Content: text[i:j],
						Target:  text[i+1 : j],
					})
					i = j
					continue
				}
			}
		}

		if i+1 < len(text) && text[i] == '!' && text[i+1] == '[' {
			bracketEnd := findClosingBracket(text, i+1)
			if bracketEnd > 0 && bracketEnd+1 < len(text) && text[bracketEnd+1] == '(' {
				parenEnd := findClosingParen(text, bracketEnd+1)
				if parenEnd > 0 {
					alt := text[i+2 : bracketEnd]
					url := text[bracketEnd+2 : parenEnd]
					inlines = append(inlines, Inline{
						Type: InlineImage,
						Alt:  alt,
						URL:  url,
					})
					i = parenEnd + 1
					continue
				}
			}
		}

		if text[i] == '[' {
			bracketEnd := findClosingBracket(text, i)
			if bracketEnd > 0 && bracketEnd+1 < len(text) && text[bracketEnd+1] == '(' {
				parenEnd := findClosingParen(text, bracketEnd+1)
				if parenEnd > 0 {
					linkText := text[i+1 : bracketEnd]
					url := text[bracketEnd+2 : parenEnd]
					inlines = append(inlines, Inline{
						Type:    InlineLink,
						Content: linkText,
						URL:     url,
					})
					i = parenEnd + 1
					continue
				}
			}
		}

		if text[i] == '`' {
			j := i + 1
			for j < len(text) && text[j] != '`' && text[j] != '\n' {
				j++
			}
			if j < len(text) && text[j] == '`' && j > i+1 {
				inlines = append(inlines, Inline{
					Type:    InlineCode,
					Content: text[i+1 : j],
				})
				i = j + 1
				continue
			}
		}

		if text[i] == '*' {
			if i+1 < len(text) && text[i+1] == '*' {
				j := i + 2
				for j+1 < len(text) && !(text[j] == '*' && text[j+1] == '*') {
					j++
				}
				if j+1 < len(text) && text[j] == '*' && text[j+1] == '*' && j > i+2 {
					inlines = append(inlines, Inline{
						Type:    InlineBold,
						Content: text[i+2 : j],
					})
					i = j + 2
					continue
				}
			} else {
				j := i + 1
				for j < len(text) && text[j] != '*' && text[j] != '\n' {
					j++
				}
				if j < len(text) && text[j] == '*' && j > i+1 {
					inlines = append(inlines, Inline{
						Type:    InlineItalic,
						Content: text[i+1 : j],
					})
					i = j + 1
					continue
				}
			}
		}

		if text[i] == '~' && i+1 < len(text) && text[i+1] == '~' {
			j := i + 2
			for j+1 < len(text) && !(text[j] == '~' && text[j+1] == '~') {
				j++
			}
			if j+1 < len(text) && text[j] == '~' && text[j+1] == '~' && j > i+2 {
				inlines = append(inlines, Inline{
					Type:    InlineStrike,
					Content: text[i+2 : j],
				})
				i = j + 2
				continue
			}
		}

		if text[i] == '$' {
			if i+1 < len(text) && text[i+1] == '$' {
				i++
				continue
			}
			j := i + 1
			for j < len(text) && text[j] != '$' && text[j] != '\n' {
				if text[j] == '\\' && j+1 < len(text) {
					j++
				}
				j++
			}
			if j < len(text) && text[j] == '$' && j > i+1 {
				inlines = append(inlines, Inline{
					Type:    InlineMath,
					Content: text[i+1 : j],
				})
				i = j + 1
				continue
			}
		}

		if len(inlines) > 0 && inlines[len(inlines)-1].Type == InlineText {
			inlines[len(inlines)-1].Content += string(text[i])
		} else {
			inlines = append(inlines, Inline{
				Type:    InlineText,
				Content: string(text[i]),
			})
		}
		i++
	}

	return inlines
}

type TableData struct {
	Headers []string
	Rows    [][]string
	Align   []string
}

func (w *WYSIWYGEditor) ParseTable(content string) *TableData {
	lines := splitLines(content)
	if len(lines) < 2 {
		return nil
	}

	table := &TableData{}

	headers := splitTableRow(lines[0])
	table.Headers = headers

	if !isTableSeparator(lines[1]) {
		return nil
	}

	align := make([]string, len(headers))
	sepParts := splitTableRow(lines[1])
	for i, part := range sepParts {
		if i >= len(align) {
			break
		}
		part = strings.TrimSpace(part)
		left := strings.HasPrefix(part, ":")
		right := strings.HasSuffix(part, ":")
		if left && right {
			align[i] = "center"
		} else if right {
			align[i] = "right"
		} else if left {
			align[i] = "left"
		} else {
			align[i] = ""
		}
	}
	table.Align = align

	for i := 2; i < len(lines); i++ {
		if strings.TrimSpace(lines[i]) == "" {
			continue
		}
		row := splitTableRow(lines[i])
		table.Rows = append(table.Rows, row)
	}

	return table
}

func splitTableRow(line string) []string {
	line = strings.TrimSpace(line)
	line = strings.TrimPrefix(line, "|")
	line = strings.TrimSuffix(line, "|")
	parts := strings.Split(line, "|")
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
	}
	return parts
}

type MathRenderer interface {
	RenderInline(formula string) (string, error)
	RenderBlock(formula string) (string, error)
}

type CodeHighlighter interface {
	Highlight(code, language string) (string, error)
}

type WYSIWYGRenderer struct {
	mathRenderer   MathRenderer
	codeHighlighter CodeHighlighter
}

func NewWYSIWYGRenderer() *WYSIWYGRenderer {
	return &WYSIWYGRenderer{}
}

func (r *WYSIWYGRenderer) SetMathRenderer(renderer MathRenderer) {
	r.mathRenderer = renderer
}

func (r *WYSIWYGRenderer) SetCodeHighlighter(highlighter CodeHighlighter) {
	r.codeHighlighter = highlighter
}

func (r *WYSIWYGRenderer) Render(content string) string {
	editor := NewWYSIWYGEditor()
	editor.SetContent(content)
	blocks := editor.ParseBlocks()

	var html strings.Builder
	for _, block := range blocks {
		html.WriteString(r.renderBlock(block))
	}

	return html.String()
}

func (r *WYSIWYGRenderer) renderBlock(block Block) string {
	switch block.Type {
	case BlockParagraph:
		return "<p>" + r.renderInlines(block.Content) + "</p>\n"
	case BlockHeading1:
		return "<h1>" + r.renderInlines(block.Content) + "</h1>\n"
	case BlockHeading2:
		return "<h2>" + r.renderInlines(block.Content) + "</h2>\n"
	case BlockHeading3:
		return "<h3>" + r.renderInlines(block.Content) + "</h3>\n"
	case BlockHeading4:
		return "<h4>" + r.renderInlines(block.Content) + "</h4>\n"
	case BlockHeading5:
		return "<h5>" + r.renderInlines(block.Content) + "</h5>\n"
	case BlockHeading6:
		return "<h6>" + r.renderInlines(block.Content) + "</h6>\n"
	case BlockQuote:
		return "<blockquote>" + r.renderInlines(block.Content) + "</blockquote>\n"
	case BlockCodeBlock:
		return r.renderCodeBlock(block)
	case BlockUnorderedList:
		if len(block.Children) > 0 {
			var items strings.Builder
			for _, child := range block.Children {
				items.WriteString("<li>")
				items.WriteString(r.renderInlines(child.Content))
				items.WriteString("</li>\n")
			}
			return "<ul>\n" + items.String() + "</ul>\n"
		}
		return "<ul><li>" + r.renderInlines(block.Content) + "</li></ul>\n"
	case BlockOrderedList:
		if len(block.Children) > 0 {
			var items strings.Builder
			for _, child := range block.Children {
				items.WriteString("<li>")
				items.WriteString(r.renderInlines(child.Content))
				items.WriteString("</li>\n")
			}
			return "<ol>\n" + items.String() + "</ol>\n"
		}
		return "<ol><li>" + r.renderInlines(block.Content) + "</li></ol>\n"
	case BlockTable:
		return r.renderTable(block.Content)
	case BlockHR:
		return "<hr />\n"
	case BlockMath:
		return r.renderBlockMath(block.Content)
	default:
		return "<p>" + r.renderInlines(block.Content) + "</p>\n"
	}
}

func (r *WYSIWYGRenderer) renderInlines(text string) string {
	editor := NewWYSIWYGEditor()
	inlines := editor.ParseInlines(text)

	var html strings.Builder
	for _, inline := range inlines {
		html.WriteString(r.renderInline(inline))
	}

	return html.String()
}

func (r *WYSIWYGRenderer) renderInline(inline Inline) string {
	switch inline.Type {
	case InlineText:
		return escapeHTML(inline.Content)
	case InlineBold:
		return "<strong>" + r.renderInlines(inline.Content) + "</strong>"
	case InlineItalic:
		return "<em>" + r.renderInlines(inline.Content) + "</em>"
	case InlineStrike:
		return "<del>" + r.renderInlines(inline.Content) + "</del>"
	case InlineCode:
		return "<code>" + escapeHTML(inline.Content) + "</code>"
	case InlineLink:
		return `<a href="` + escapeHTML(inline.URL) + `">` + r.renderInlines(inline.Content) + "</a>"
	case InlineImage:
		return `<img src="` + escapeHTML(inline.URL) + `" alt="` + escapeHTML(inline.Alt) + `" />`
	case InlineWikiLink:
		return `<a class="wiki-link" href="` + escapeHTML(inline.Target) + `">` + escapeHTML(inline.Content) + "</a>"
	case InlineTag:
		return `<span class="tag">` + escapeHTML(inline.Content) + "</span>"
	case InlineMath:
		return r.renderInlineMath(inline.Content)
	default:
		return escapeHTML(inline.Content)
	}
}

func (r *WYSIWYGRenderer) renderCodeBlock(block Block) string {
	code := block.Content
	lang := block.Language

	if r.codeHighlighter != nil {
		highlighted, err := r.codeHighlighter.Highlight(code, lang)
		if err == nil {
			return `<pre><code class="language-` + escapeHTML(lang) + `">` + highlighted + `</code></pre>`
		}
	}

	return `<pre><code class="language-` + escapeHTML(lang) + `">` + escapeHTML(code) + `</code></pre>`
}

func (r *WYSIWYGRenderer) renderTable(content string) string {
	editor := NewWYSIWYGEditor()
	table := editor.ParseTable(content)
	if table == nil {
		return "<table></table>"
	}

	var html strings.Builder
	html.WriteString("<table>\n")
	html.WriteString("<thead>\n<tr>\n")
	for _, header := range table.Headers {
		html.WriteString("<th>")
		html.WriteString(r.renderInlines(header))
		html.WriteString("</th>\n")
	}
	html.WriteString("</tr>\n</thead>\n")

	html.WriteString("<tbody>\n")
	for _, row := range table.Rows {
		html.WriteString("<tr>\n")
		for _, cell := range row {
			html.WriteString("<td>")
			html.WriteString(r.renderInlines(cell))
			html.WriteString("</td>\n")
		}
		html.WriteString("</tr>\n")
	}
	html.WriteString("</tbody>\n")
	html.WriteString("</table>\n")

	return html.String()
}

func (r *WYSIWYGRenderer) renderInlineMath(formula string) string {
	if r.mathRenderer != nil {
		rendered, err := r.mathRenderer.RenderInline(formula)
		if err == nil {
			return rendered
		}
	}
	return `<span class="math-inline">$` + escapeHTML(formula) + `$</span>`
}

func (r *WYSIWYGRenderer) renderBlockMath(formula string) string {
	if r.mathRenderer != nil {
		rendered, err := r.mathRenderer.RenderBlock(formula)
		if err == nil {
			return rendered
		}
	}
	return `<div class="math-block">$$` + escapeHTML(formula) + `$$</div>`
}

func escapeHTML(s string) string {
	s = strings.ReplaceAll(s, "&", "&amp;")
	s = strings.ReplaceAll(s, "<", "&lt;")
	s = strings.ReplaceAll(s, ">", "&gt;")
	s = strings.ReplaceAll(s, `"`, "&quot;")
	s = strings.ReplaceAll(s, "'", "&#39;")
	return s
}
