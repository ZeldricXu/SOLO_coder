package segment

import (
	"regexp"
	"strings"
	"unicode"
)

var (
	stopWords = map[string]bool{
		"的": true, "了": true, "在": true, "是": true, "我": true, "有": true,
		"和": true, "就": true, "不": true, "人": true, "都": true, "一": true,
		"一个": true, "上": true, "也": true, "很": true, "到": true, "说": true,
		"要": true, "去": true, "你": true, "会": true, "着": true, "没有": true,
		"看": true, "好": true, "自己": true, "这": true, "那": true, "他": true,
		"她": true, "它": true, "们": true, "这个": true, "那个": true, "什么": true,
		"怎么": true, "为什么": true, "哪": true, "哪里": true, "何时": true,
		"the": true, "a": true, "an": true, "and": true, "or": true, "but": true,
		"in": true, "on": true, "at": true, "to": true, "for": true, "of": true,
		"with": true, "by": true, "from": true, "is": true, "are": true, "was": true,
		"were": true, "be": true, "been": true, "being": true, "have": true, "has": true,
		"had": true, "do": true, "does": true, "did": true, "will": true, "would": true,
		"could": true, "should": true, "may": true, "might": true, "must": true,
		"this": true, "that": true, "these": true, "those": true, "it": true,
		"its": true, "i": true, "you": true, "he": true, "she": true, "we": true,
		"they": true, "me": true, "him": true, "her": true, "us": true, "them": true,
		"my": true, "your": true, "his": true, "our": true, "their": true,
		"not": true, "no": true, "nor": true, "so": true, "as": true, "if": true,
		"then": true, "than": true, "because": true, "while": true, "when": true,
		"where": true, "who": true, "which": true, "what": true, "how": true,
		"all": true, "any": true, "each": true, "every": true, "both": true,
		"few": true, "more": true, "most": true, "other": true, "some": true,
		"such": true, "only": true, "own": true, "same": true,
		"too": true, "very": true, "just": true,
	}
)

type Token struct {
	Text     string
	Position int
	Length   int
}

func Segment(text string, enableCJK bool) []Token {
	var tokens []Token
	text = strings.ToLower(text)

	wordRegex := regexp.MustCompile(`[a-zA-Z0-9_]+`)

	for _, match := range wordRegex.FindAllStringIndex(text, -1) {
		start, end := match[0], match[1]
		word := text[start:end]
		if len(word) > 1 && !stopWords[word] {
			tokens = append(tokens, Token{
				Text:     word,
				Position: start,
				Length:   end - start,
			})
		}
	}

	if enableCJK {
		tokens = append(tokens, segmentCJK(text)...)
		tokens = append(tokens, ngramCJK(text, 2)...)
	}

	return tokens
}

func segmentCJK(text string) []Token {
	var tokens []Token
	runes := []rune(text)

	for i := 0; i < len(runes); {
		if isCJK(runes[i]) {
			start := i
			for i < len(runes) && isCJK(runes[i]) {
				i++
			}
			cjkText := string(runes[start:i])
			tokens = append(tokens, Token{
				Text:     cjkText,
				Position: start,
				Length:   i - start,
			})
		} else {
			i++
		}
	}

	return tokens
}

func ngramCJK(text string, n int) []Token {
	var tokens []Token
	runes := []rune(text)

	for i := 0; i <= len(runes)-n; i++ {
		allCJK := true
		for j := 0; j < n; j++ {
			if !isCJK(runes[i+j]) {
				allCJK = false
				break
			}
		}
		if allCJK {
			gram := string(runes[i : i+n])
			if !stopWords[gram] {
				tokens = append(tokens, Token{
					Text:     gram,
					Position: i,
					Length:   n,
				})
			}
		}
	}

	return tokens
}

func isCJK(r rune) bool {
	return unicode.Is(unicode.Han, r) ||
		unicode.Is(unicode.Hiragana, r) ||
		unicode.Is(unicode.Katakana, r) ||
		unicode.Is(unicode.Hangul, r)
}

func FuzzyMatch(token, query string) float64 {
	if token == query {
		return 1.0
	}

	tokenLower := strings.ToLower(token)
	queryLower := strings.ToLower(query)

	if strings.Contains(tokenLower, queryLower) {
		return 0.8
	}

	if strings.HasPrefix(tokenLower, queryLower) {
		return 0.7
	}

	if strings.HasSuffix(tokenLower, queryLower) {
		return 0.6
	}

	distance := levenshteinDistance(tokenLower, queryLower)
	maxLen := float64(max(len(tokenLower), len(queryLower)))
	if maxLen == 0 {
		return 0
	}
	similarity := 1.0 - float64(distance)/maxLen
	if similarity > 0.5 {
		return similarity * 0.5
	}

	return 0
}

func levenshteinDistance(s, t string) int {
	if len(s) == 0 {
		return len(t)
	}
	if len(t) == 0 {
		return len(s)
	}

	r1 := []rune(s)
	r2 := []rune(t)
	len1 := len(r1)
	len2 := len(r2)

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
			curr[j] = min(
				prev[j]+1,
				min(curr[j-1]+1, prev[j-1]+cost),
			)
		}
		prev, curr = curr, prev
	}

	return prev[len2]
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
