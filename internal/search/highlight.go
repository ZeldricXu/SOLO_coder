package search

import (
	"regexp"
	"strings"
	"unicode/utf8"

	"github.com/solocoder/knowledgebase/pkg/utils"
)

type Highlighter struct {
	BeforeTag string
	AfterTag  string
}

func NewHighlighter(before, after string) *Highlighter {
	return &Highlighter{
		BeforeTag: before,
		AfterTag:  after,
	}
}

func DefaultHighlighter() *Highlighter {
	return NewHighlighter("<mark>", "</mark>")
}

func (h *Highlighter) Highlight(text string, keywords []string) string {
	if len(keywords) == 0 || text == "" {
		return text
	}

	result := text
	for _, kw := range keywords {
		if kw == "" {
			continue
		}
		result = utils.Highlight(result, kw, h.BeforeTag, h.AfterTag)
	}
	return result
}

func (h *Highlighter) GenerateExcerpt(text string, keywords []string, maxLen int) string {
	if text == "" {
		return ""
	}
	if maxLen <= 0 {
		maxLen = 200
	}

	runes := []rune(text)
	if len(runes) <= maxLen {
		return h.Highlight(text, keywords)
	}

	bestPos := findBestPosition(runes, keywords)
	halfLen := maxLen / 2
	start := bestPos - halfLen
	end := bestPos + halfLen

	if start < 0 {
		start = 0
		end = maxLen
	}
	if end > len(runes) {
		end = len(runes)
		start = end - maxLen
		if start < 0 {
			start = 0
		}
	}

	prefix := ""
	suffix := ""
	if start > 0 {
		prefix = "..."
	}
	if end < len(runes) {
		suffix = "..."
	}

	excerpt := string(runes[start:end])
	return prefix + h.Highlight(excerpt, keywords) + suffix
}

func findBestPosition(runes []rune, keywords []string) int {
	if len(keywords) == 0 {
		return len(runes) / 2
	}

	text := string(runes)
	lowerText := strings.ToLower(text)

	bestPos := len(runes) / 2
	minDist := len(runes)

	for _, kw := range keywords {
		if kw == "" {
			continue
		}
		kwLower := strings.ToLower(kw)
		idx := strings.Index(lowerText, kwLower)
		if idx >= 0 {
			runeIdx := utf8.RuneCountInString(text[:idx])
			mid := runeIdx + utf8.RuneCountInString(kw)/2
			dist := abs(mid - len(runes)/2)
			if dist < minDist {
				minDist = dist
				bestPos = mid
			}
		}
	}

	return bestPos
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}

func (h *Highlighter) ExtractHighlights(text string, keywords []string, maxCount int) []string {
	if len(keywords) == 0 || text == "" || maxCount <= 0 {
		return nil
	}

	var highlights []string
	lowerText := strings.ToLower(text)

	for _, kw := range keywords {
		if kw == "" {
			continue
		}
		re := regexp.MustCompile(`(?i)` + regexp.QuoteMeta(kw))

		matches := re.FindAllStringIndex(text, -1)
		for i, match := range matches {
			if i >= maxCount/len(keywords) {
				break
			}

			start := match[0]
			end := match[1]

			contextStart := start - 30
			contextEnd := end + 30
			if contextStart < 0 {
				contextStart = 0
			}
			if contextEnd > len(text) {
				contextEnd = len(text)
			}

			runeStart := utf8.RuneCountInString(text[:contextStart])
			runeEnd := utf8.RuneCountInString(text[:contextEnd])
			runes := []rune(text)
			context := string(runes[runeStart:runeEnd])

			prefix := ""
			suffix := ""
			if runeStart > 0 {
				prefix = "..."
			}
			if runeEnd < len(runes) {
				suffix = "..."
			}

			highlighted := prefix + utils.Highlight(context, kw, h.BeforeTag, h.AfterTag) + suffix
			highlights = append(highlights, highlighted)
		}

		_ = lowerText
	}

	return utils.UniqueStrings(highlights)
}

func (h *Highlighter) HighlightTitle(title string, keywords []string) string {
	return h.Highlight(title, keywords)
}
