package editor

import (
	"html"
	"strings"
)

type HighlightTokenType string

const (
	TokenHeading   HighlightTokenType = "heading"
	TokenBold      HighlightTokenType = "bold"
	TokenItalic    HighlightTokenType = "italic"
	TokenStrike    HighlightTokenType = "strike"
	TokenCode      HighlightTokenType = "code"
	TokenCodeBlock HighlightTokenType = "code_block"
	TokenLink      HighlightTokenType = "link"
	TokenImage     HighlightTokenType = "image"
	TokenList      HighlightTokenType = "list"
	TokenQuote     HighlightTokenType = "quote"
	TokenHR        HighlightTokenType = "hr"
	TokenTable     HighlightTokenType = "table"
	TokenWikiLink  HighlightTokenType = "wiki_link"
	TokenTag       HighlightTokenType = "tag"
	TokenMath      HighlightTokenType = "math"
	TokenBlockMath HighlightTokenType = "block_math"
	TokenComment   HighlightTokenType = "comment"
	TokenFrontMatter HighlightTokenType = "frontmatter"
)

type HighlightToken struct {
	Type    HighlightTokenType
	Content string
	Start   int
	End     int
}

type SourceMode struct {
	content string
	tokens  []HighlightToken
	lines   []string
}

func NewSourceMode() *SourceMode {
	return &SourceMode{}
}

func (s *SourceMode) SetContent(content string) {
	s.content = content
	s.lines = splitLines(content)
	s.tokenize()
}

func (s *SourceMode) GetLines() []string {
	return s.lines
}

func (s *SourceMode) GetLineCount() int {
	return len(s.lines)
}

func (s *SourceMode) GetLineNumbers() []int {
	numbers := make([]int, len(s.lines))
	for i := range s.lines {
		numbers[i] = i + 1
	}
	return numbers
}

func (s *SourceMode) tokenize() {
	s.tokens = s.tokens[:0]
	content := s.content

	s.tokenizeFrontMatter(content)
	s.tokenizeCodeBlocks(content)
	s.tokenizeBlockMath(content)
	s.tokenizeHeadings(content)
	s.tokenizeLists(content)
	s.tokenizeQuotes(content)
	s.tokenizeHorizontalRules(content)
	s.tokenizeTables(content)
	s.tokenizeWikiLinks(content)
	s.tokenizeTags(content)
	s.tokenizeLinks(content)
	s.tokenizeImages(content)
	s.tokenizeInlineMath(content)
	s.tokenizeInlineFormats(content)
}

func (s *SourceMode) tokenizeFrontMatter(content string) {
	if !strings.HasPrefix(content, "---") {
		return
	}

	end := strings.Index(content[3:], "---")
	if end == -1 {
		return
	}

	end += 3
	if end+3 <= len(content) {
		s.tokens = append(s.tokens, HighlightToken{
			Type:    TokenFrontMatter,
			Content: content[:end+3],
			Start:   0,
			End:     end + 3,
		})
	}
}

func (s *SourceMode) tokenizeCodeBlocks(content string) {
	start := 0
	for {
		idx := strings.Index(content[start:], "```")
		if idx == -1 {
			break
		}

		blockStart := start + idx
		rest := content[blockStart+3:]
		endIdx := strings.Index(rest, "```")
		if endIdx == -1 {
			break
		}

		blockEnd := blockStart + 3 + endIdx + 3
		s.tokens = append(s.tokens, HighlightToken{
			Type:    TokenCodeBlock,
			Content: content[blockStart:blockEnd],
			Start:   blockStart,
			End:     blockEnd,
		})

		start = blockEnd
	}
}

func (s *SourceMode) tokenizeBlockMath(content string) {
	start := 0
	for {
		idx := strings.Index(content[start:], "$$")
		if idx == -1 {
			break
		}

		blockStart := start + idx
		rest := content[blockStart+2:]
		endIdx := strings.Index(rest, "$$")
		if endIdx == -1 {
			break
		}

		blockEnd := blockStart + 2 + endIdx + 2
		s.tokens = append(s.tokens, HighlightToken{
			Type:    TokenBlockMath,
			Content: content[blockStart:blockEnd],
			Start:   blockStart,
			End:     blockEnd,
		})

		start = blockEnd
	}
}

func (s *SourceMode) tokenizeHeadings(content string) {
	lines := splitLines(content)
	offset := 0

	for _, line := range lines {
		level := 0
		for level < len(line) && level < 6 && line[level] == '#' {
			level++
		}

		if level > 0 && level <= 6 && level < len(line) && line[level] == ' ' {
			s.tokens = append(s.tokens, HighlightToken{
				Type:    TokenHeading,
				Content: line,
				Start:   offset,
				End:     offset + len(line),
			})
		}

		offset += len(line) + 1
	}
}

func (s *SourceMode) tokenizeLists(content string) {
	lines := splitLines(content)
	offset := 0

	for _, line := range lines {
		trimmed := strings.TrimLeft(line, " \t")
		if strings.HasPrefix(trimmed, "- ") || strings.HasPrefix(trimmed, "* ") || strings.HasPrefix(trimmed, "+ ") {
			s.tokens = append(s.tokens, HighlightToken{
				Type:    TokenList,
				Content: line,
				Start:   offset,
				End:     offset + len(line),
			})
		} else if matchOrderedList(trimmed) {
			s.tokens = append(s.tokens, HighlightToken{
				Type:    TokenList,
				Content: line,
				Start:   offset,
				End:     offset + len(line),
			})
		}
		offset += len(line) + 1
	}
}

func (s *SourceMode) tokenizeQuotes(content string) {
	lines := splitLines(content)
	offset := 0

	for _, line := range lines {
		trimmed := strings.TrimLeft(line, " \t")
		if strings.HasPrefix(trimmed, "> ") {
			s.tokens = append(s.tokens, HighlightToken{
				Type:    TokenQuote,
				Content: line,
				Start:   offset,
				End:     offset + len(line),
			})
		}
		offset += len(line) + 1
	}
}

func (s *SourceMode) tokenizeHorizontalRules(content string) {
	lines := splitLines(content)
	offset := 0

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if (trimmed == "---" || trimmed == "***" || trimmed == "___") && len(trimmed) >= 3 {
			allSame := true
			first := trimmed[0]
			for i := 1; i < len(trimmed); i++ {
				if trimmed[i] != first {
					allSame = false
					break
				}
			}
			if allSame {
				s.tokens = append(s.tokens, HighlightToken{
					Type:    TokenHR,
					Content: line,
					Start:   offset,
					End:     offset + len(line),
				})
			}
		}
		offset += len(line) + 1
	}
}

func (s *SourceMode) tokenizeTables(content string) {
	lines := splitLines(content)
	offset := 0

	inTable := false

	for i, line := range lines {
		if strings.Contains(line, "|") && strings.TrimSpace(line) != "" {
			if !inTable && i+1 < len(lines) {
				nextLine := lines[i+1]
				if isTableSeparator(nextLine) {
					inTable = true
				}
			}
			if inTable {
				s.tokens = append(s.tokens, HighlightToken{
					Type:    TokenTable,
					Content: line,
					Start:   offset,
					End:     offset + len(line),
				})
			}
		} else if inTable {
			inTable = false
		}
		offset += len(line) + 1
	}
}

func isTableSeparator(line string) bool {
	trimmed := strings.TrimSpace(line)
	if !strings.Contains(trimmed, "|") {
		return false
	}

	parts := strings.Split(trimmed, "|")
	hasDashes := false
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if strings.HasPrefix(part, ":") && strings.HasSuffix(part, ":") {
			part = part[1 : len(part)-1]
		} else if strings.HasPrefix(part, ":") {
			part = part[1:]
		} else if strings.HasSuffix(part, ":") {
			part = part[:len(part)-1]
		}
		for _, c := range part {
			if c != '-' {
				return false
			}
		}
		if len(part) > 0 {
			hasDashes = true
		}
	}
	return hasDashes
}

func (s *SourceMode) tokenizeWikiLinks(content string) {
	start := 0
	for {
		idx := strings.Index(content[start:], "[[")
		if idx == -1 {
			break
		}

		linkStart := start + idx
		rest := content[linkStart+2:]
		endIdx := strings.Index(rest, "]]")
		if endIdx == -1 {
			break
		}

		linkEnd := linkStart + 2 + endIdx + 2
		s.tokens = append(s.tokens, HighlightToken{
			Type:    TokenWikiLink,
			Content: content[linkStart:linkEnd],
			Start:   linkStart,
			End:     linkEnd,
		})

		start = linkEnd
	}
}

func (s *SourceMode) tokenizeTags(content string) {
	for i := 0; i < len(content); i++ {
		if content[i] != '#' {
			continue
		}

		if i > 0 {
			prev := content[i-1]
			if prev != ' ' && prev != '\n' && prev != '\t' && prev != '(' && prev != '[' {
				continue
			}
		}

		j := i + 1
		for j < len(content) {
			c := content[j]
			if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
				(c >= '0' && c <= '9') || c == '_' || c == '/' || c == '-' {
				j++
			} else {
				break
			}
		}

		if j > i+1 {
			s.tokens = append(s.tokens, HighlightToken{
				Type:    TokenTag,
				Content: content[i:j],
				Start:   i,
				End:     j,
			})
		}

		i = j - 1
	}
}

func (s *SourceMode) tokenizeLinks(content string) {
	inCodeBlock := false
	inCode := false

	for i := 0; i < len(content); i++ {
		if content[i] == '`' {
			if i+2 < len(content) && content[i:i+3] == "```" {
				inCodeBlock = !inCodeBlock
				i += 2
				continue
			}
			if !inCodeBlock {
				inCode = !inCode
			}
			continue
		}

		if inCodeBlock || inCode {
			continue
		}

		if content[i] == '!' {
			if i+1 < len(content) && content[i+1] == '[' {
				end := findClosingBracket(content, i+1)
				if end > 0 && end+1 < len(content) && content[end+1] == '(' {
					urlEnd := findClosingParen(content, end+1)
					if urlEnd > 0 {
						s.tokens = append(s.tokens, HighlightToken{
							Type:    TokenImage,
							Content: content[i : urlEnd+1],
							Start:   i,
							End:     urlEnd + 1,
						})
						i = urlEnd
						continue
					}
				}
			}
			continue
		}

		if content[i] == '[' {
			if i > 0 && content[i-1] == '!' {
				continue
			}

			end := findClosingBracket(content, i)
			if end > 0 && end+1 < len(content) && content[end+1] == '(' {
				urlEnd := findClosingParen(content, end+1)
				if urlEnd > 0 {
					s.tokens = append(s.tokens, HighlightToken{
						Type:    TokenLink,
						Content: content[i : urlEnd+1],
						Start:   i,
						End:     urlEnd + 1,
					})
					i = urlEnd
					continue
				}
			}
		}
	}
}

func findClosingBracket(s string, start int) int {
	depth := 0
	for i := start; i < len(s); i++ {
		if s[i] == '[' {
			depth++
		} else if s[i] == ']' {
			depth--
			if depth == 0 {
				return i
			}
		}
	}
	return -1
}

func findClosingParen(s string, start int) int {
	depth := 0
	for i := start; i < len(s); i++ {
		if s[i] == '(' {
			depth++
		} else if s[i] == ')' {
			depth--
			if depth == 0 {
				return i
			}
		}
	}
	return -1
}

func (s *SourceMode) tokenizeImages(content string) {
}

func (s *SourceMode) tokenizeInlineMath(content string) {
	inCodeBlock := false
	inCode := false

	for i := 0; i < len(content); i++ {
		if content[i] == '`' {
			if i+2 < len(content) && content[i:i+3] == "```" {
				inCodeBlock = !inCodeBlock
				i += 2
				continue
			}
			if !inCodeBlock {
				inCode = !inCode
			}
			continue
		}

		if inCodeBlock || inCode {
			continue
		}

		if content[i] == '$' {
			if i+1 < len(content) && content[i+1] == '$' {
				continue
			}

			end := -1
			for j := i + 1; j < len(content); j++ {
				if content[j] == '$' && content[j-1] != '\\' {
					end = j
					break
				}
				if content[j] == '\n' {
					break
				}
			}

			if end > i+1 {
				s.tokens = append(s.tokens, HighlightToken{
					Type:    TokenMath,
					Content: content[i : end+1],
					Start:   i,
					End:     end + 1,
				})
				i = end
			}
		}
	}
}

func (s *SourceMode) tokenizeInlineFormats(content string) {
	inCodeBlock := false
	inCode := false

	for i := 0; i < len(content); i++ {
		if content[i] == '`' {
			if i+2 < len(content) && content[i:i+3] == "```" {
				inCodeBlock = !inCodeBlock
				i += 2
				continue
			}
			if !inCodeBlock {
				if inCode {
					inCode = false
				} else {
					inCode = true
					start := i
					j := i + 1
					for j < len(content) && content[j] != '`' && content[j] != '\n' {
						j++
					}
					if j < len(content) && content[j] == '`' {
						s.tokens = append(s.tokens, HighlightToken{
							Type:    TokenCode,
							Content: content[start : j+1],
							Start:   start,
							End:     j + 1,
						})
						i = j
					}
				}
			}
			continue
		}

		if inCodeBlock || inCode {
			continue
		}

		if content[i] == '*' {
			if i+1 < len(content) && content[i+1] == '*' {
				start := i
				j := i + 2
				for j+1 < len(content) && !(content[j] == '*' && content[j+1] == '*') {
					j++
				}
				if j+1 < len(content) && content[j] == '*' && content[j+1] == '*' {
					s.tokens = append(s.tokens, HighlightToken{
						Type:    TokenBold,
						Content: content[start : j+2],
						Start:   start,
						End:     j + 2,
					})
					i = j + 1
					continue
				}
			} else {
				start := i
				j := i + 1
				for j < len(content) && content[j] != '*' && content[j] != '\n' {
					j++
				}
				if j < len(content) && content[j] == '*' && j > i+1 {
					s.tokens = append(s.tokens, HighlightToken{
						Type:    TokenItalic,
						Content: content[start : j+1],
						Start:   start,
						End:     j + 1,
					})
					i = j
					continue
				}
			}
		}

		if content[i] == '~' {
			if i+1 < len(content) && content[i+1] == '~' {
				start := i
				j := i + 2
				for j+1 < len(content) && !(content[j] == '~' && content[j+1] == '~') {
					j++
				}
				if j+1 < len(content) && content[j] == '~' && content[j+1] == '~' {
					s.tokens = append(s.tokens, HighlightToken{
						Type:    TokenStrike,
						Content: content[start : j+2],
						Start:   start,
						End:     j + 2,
					})
					i = j + 1
					continue
				}
			}
		}
	}
}

func (s *SourceMode) RenderHighlightedHTML() string {
	if len(s.tokens) == 0 {
		return html.EscapeString(s.content)
	}

	var result strings.Builder
	content := s.content
	lastEnd := 0

	tokens := s.tokens

	for _, token := range tokens {
		if token.Start >= lastEnd {
			if token.Start > lastEnd {
				result.WriteString(html.EscapeString(content[lastEnd:token.Start]))
			}

			class := string(token.Type)
			result.WriteString(`<span class="` + class + `">`)
			result.WriteString(html.EscapeString(token.Content))
			result.WriteString(`</span>`)

			lastEnd = token.End
		}
	}

	if lastEnd < len(content) {
		result.WriteString(html.EscapeString(content[lastEnd:]))
	}

	return result.String()
}

func (s *SourceMode) GetTokens() []HighlightToken {
	return s.tokens
}

func (s *SourceMode) GetTokenAt(offset int) *HighlightToken {
	for _, token := range s.tokens {
		if offset >= token.Start && offset < token.End {
			return &token
		}
	}
	return nil
}

func (s *SourceMode) GetLineContent(lineNum int) string {
	if lineNum < 0 || lineNum >= len(s.lines) {
		return ""
	}
	return s.lines[lineNum]
}

func (s *SourceMode) GetLineStartOffset(lineNum int) int {
	if lineNum < 0 || lineNum >= len(s.lines) {
		return 0
	}

	offset := 0
	for i := 0; i < lineNum; i++ {
		offset += len(s.lines[i]) + 1
	}
	return offset
}

func (s *SourceMode) GetLineEndOffset(lineNum int) int {
	if lineNum < 0 || lineNum >= len(s.lines) {
		return 0
	}
	return s.GetLineStartOffset(lineNum) + len(s.lines[lineNum])
}
