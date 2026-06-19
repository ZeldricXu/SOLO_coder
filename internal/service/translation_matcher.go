package service

import (
	"context"
	"fmt"
	"strings"
	"unicode/utf8"

	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/google/uuid"
)

type TranslationMatcher struct{}

func (m *TranslationMatcher) ExtractParagraphs(content model.ProseMirrorDoc) []string {
	var paragraphs []string
	extractTextFromNodes(content.Content, &paragraphs)
	return paragraphs
}

func extractTextFromNodes(nodes []map[string]interface{}, result *[]string) {
	for _, node := range nodes {
		if nodeType, ok := node["type"].(string); ok {
			switch nodeType {
			case "paragraph":
				if text, has := extractText(node); has && strings.TrimSpace(text) != "" {
					*result = append(*result, strings.TrimSpace(text))
				}
			case "heading", "blockquote", "list_item":
				if text, has := extractText(node); has && strings.TrimSpace(text) != "" {
					*result = append(*result, strings.TrimSpace(text))
				}
			default:
				if childContent, ok := node["content"].([]interface{}); ok {
					childNodes := make([]map[string]interface{}, 0, len(childContent))
					for _, c := range childContent {
						if cm, ok := c.(map[string]interface{}); ok {
							childNodes = append(childNodes, cm)
						}
					}
					extractTextFromNodes(childNodes, result)
				}
			}
		}
	}
}

func extractText(node map[string]interface{}) (string, bool) {
	var sb strings.Builder
	foundText := false

	if content, ok := node["content"].([]interface{}); ok {
		for _, item := range content {
			if itemMap, ok := item.(map[string]interface{}); ok {
				if itemType, ok := itemMap["type"].(string); ok && itemType == "text" {
					if text, ok := itemMap["text"].(string); ok {
						sb.WriteString(text)
						foundText = true
					}
				}
				if marks, ok := itemMap["marks"].([]interface{}); ok {
					_ = marks
				}
			}
		}
	}

	if text, ok := node["text"].(string); ok {
		sb.WriteString(text)
		foundText = true
	}

	return sb.String(), foundText
}

func (m *TranslationMatcher) ComputeSimilarity(s1, s2 string) float64 {
	s1 = strings.TrimSpace(s1)
	s2 = strings.TrimSpace(s2)

	if s1 == s2 {
		return 1.0
	}

	if s1 == "" || s2 == "" {
		return 0.0
	}

	distance := levenshteinDistance(s1, s2)
	maxLen := float64(max(utf8.RuneCountInString(s1), utf8.RuneCountInString(s2)))
	if maxLen == 0 {
		return 0.0
	}

	similarity := 1.0 - (float64(distance) / maxLen)
	if similarity < 0 {
		similarity = 0.0
	}

	if similarity < 1.0 {
		substringScore := computeSubstringScore(s1, s2)
		if substringScore > similarity {
			similarity = substringScore
		}
	}

	return similarity
}

func levenshteinDistance(s1, s2 string) int {
	r1 := []rune(s1)
	r2 := []rune(s2)

	len1 := len(r1)
	len2 := len(r2)

	if len1 == 0 {
		return len2
	}
	if len2 == 0 {
		return len1
	}

	prev := make([]int, len2+1)
	curr := make([]int, len2+1)

	for j := 0; j <= len2; j++ {
		prev[j] = j
	}

	for i := 1; i <= len1; i++ {
		curr[0] = i
		for j := 1; j <= len2; j++ {
			cost := 1
			if r1[i-1] == r2[j-1] {
				cost = 0
			}
			curr[j] = min(min(curr[j-1]+1, prev[j]+1), prev[j-1]+cost)
		}
		prev, curr = curr, prev
	}

	return prev[len2]
}

func computeSubstringScore(s1, s2 string) float64 {
	s1Lower := strings.ToLower(s1)
	s2Lower := strings.ToLower(s2)

	shorter := s1Lower
	longer := s2Lower
	if len(s1Lower) > len(s2Lower) {
		shorter = s2Lower
		longer = s1Lower
	}

	if len(shorter) == 0 {
		return 0.0
	}

	if strings.Contains(longer, shorter) {
		return float64(len(shorter)) / float64(len(longer))
	}

	longestLen := longestCommonSubstringLength(s1Lower, s2Lower)
	maxLen := float64(max(len(s1), len(s2)))
	if maxLen == 0 {
		return 0.0
	}
	return float64(longestLen) / maxLen
}

func longestCommonSubstringLength(s1, s2 string) int {
	r1 := []rune(s1)
	r2 := []rune(s2)

	len1 := len(r1)
	len2 := len(r2)

	if len1 == 0 || len2 == 0 {
		return 0
	}

	prev := make([]int, len2+1)
	curr := make([]int, len2+1)
	maxLen := 0

	for i := 1; i <= len1; i++ {
		for j := 1; j <= len2; j++ {
			if r1[i-1] == r2[j-1] {
				curr[j] = prev[j-1] + 1
				if curr[j] > maxLen {
					maxLen = curr[j]
				}
			} else {
				curr[j] = 0
			}
		}
		prev, curr = curr, prev
		for k := range curr {
			curr[k] = 0
		}
	}

	return maxLen
}

func (m *TranslationMatcher) MatchParagraph(ctx context.Context, tenantID uuid.UUID, srcLang, tgtLang, paragraph string, tmList []*model.TranslationMemory, threshold float64) (string, float64, bool) {
	_ = ctx
	_ = tenantID
	_ = srcLang
	_ = tgtLang

	if threshold < 0 || threshold > 1 {
		threshold = 0.7
	}

	bestText := ""
	bestScore := 0.0
	found := false

	for _, tm := range tmList {
		score := m.ComputeSimilarity(paragraph, tm.SourceText)
		if score >= threshold && score > bestScore {
			bestScore = score
			bestText = tm.TargetText
			found = true
		}
	}

	if bestScore >= 0.99 {
		return bestText, 1.0, true
	}

	return bestText, bestScore, found
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

var _ = fmt.Sprintf
