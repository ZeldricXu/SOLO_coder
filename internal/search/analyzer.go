package search

import (
	"regexp"
	"strings"
	"unicode"

	"github.com/blevesearch/bleve/v2/analysis"
	"github.com/blevesearch/bleve/v2/analysis/token/lowercase"
	unicodetokenizer "github.com/blevesearch/bleve/v2/analysis/tokenizer/unicode"
	"github.com/blevesearch/bleve/v2/registry"
)

const (
	ZhAnalyzerName     = "zh_analyzer"
	ZhPinyinAnalyzer   = "zh_pinyin_analyzer"
	PinyinFilterName   = "pinyin_filter"
	ZhTokenizerName    = "zh_tokenizer"
	LangDetectAnalyzer = "lang_detect_analyzer"
)

var pinyinMap = map[rune]string{
	'一': "yi", '二': "er", '三': "san", '四': "si", '五': "wu",
	'六': "liu", '七': "qi", '八': "ba", '九': "jiu", '十': "shi",
	'人': "ren", '口': "kou", '大': "da", '小': "xiao", '中': "zhong",
	'国': "guo", '学': "xue", '生': "sheng", '老': "lao", '师': "shi",
	'时': "shi", '间': "jian", '地': "di", '方': "fang", '上': "shang",
	'下': "xia", '前': "qian", '后': "hou", '左': "zuo", '右': "you",
	'知': "zhi", '识': "shi", '文': "wen", '章': "zhang", '书': "shu",
	'据': "ju", '信': "xin", '息': "xi", '管': "guan",
	'理': "li", '系': "xi", '统': "tong", '模': "mo", '块': "kuai",
	'查': "cha", '找': "zhao", '索': "suo", '引': "yin", '擎': "qing",
	'用': "yong", '户': "hu", '角': "jiao", '色': "se", '权': "quan",
	'限': "xian", '安': "an", '全': "quan", '加': "jia", '密': "mi",
	'解': "jie", '码': "ma", '传': "chuan", '输': "shu", '协': "xie",
	'议': "yi", '接': "jie", '端': "duan", '云': "yun",
	'计': "ji", '算': "suan", '存': "cun", '储': "chu", '网': "wang",
	'络': "luo", '库': "ku", '缓': "huan",
	'队': "dui", '列': "lie", '消': "xiao", '日': "ri",
	'志': "zhi", '监': "jian", '控': "kong", '报': "bao", '警': "jing",
	'测': "ce", '试': "shi", '开': "kai", '发': "fa",
	'运': "yun", '维': "wei", '护': "hu", '升': "sheng",
	'版': "ban", '本': "ben", '配': "pei", '置': "zhi", '逻': "luo",
	'辑': "ji", '业': "ye", '务': "wu", '流': "liu", '程': "cheng",
	'工': "gong", '作': "zuo", '项': "xiang", '目': "mu",
	'划': "hua", '执': "zhi", '行': "xing", '结': "jie", '果': "guo",
	'效': "xiao", '率': "lv", '质': "zhi", '量': "liang", '优': "you",
	'化': "hua", '改': "gai", '善': "shan", '创': "chuang", '新': "xin",
	'技': "ji", '术': "shu", '科': "ke",
	'究': "jiu", '产': "chan", '品': "pin", '服': "fu",
	'市': "shi", '场': "chang", '销': "xiao", '客': "ke",
	'满': "man", '意': "yi", '度': "du", '增': "zeng",
	'长': "chang", '短': "duan", '高': "gao", '低': "di", '快': "kuai",
	'慢': "man", '多': "duo", '少': "shao", '好': "hao", '坏': "huai",
	'旧': "jiu", '热': "re", '冷': "leng", '硬': "ying",
	'软': "ruan", '轻': "qing", '重': "zhong", '远': "yuan", '近': "jin",
}

type ZhTokenizer struct{}

func NewZhTokenizer() *ZhTokenizer {
	return &ZhTokenizer{}
}

func (t *ZhTokenizer) Tokenize(input []byte) analysis.TokenStream {
	result := make(analysis.TokenStream, 0)
	runes := []rune(string(input))
	position := 1
	tokenStart := 0

	i := 0
	for i < len(runes) {
		r := runes[i]

		if unicode.Is(unicode.Han, r) {
			if tokenStart < i {
				enToken := string(runes[tokenStart:i])
				if token := t.buildToken(enToken, tokenStart, i, position); token != nil {
					result = append(result, token)
					position++
				}
			}

			charToken := t.buildToken(string(r), i, i+1, position)
			if charToken != nil {
				result = append(result, charToken)
				position++
			}

			if i+1 < len(runes) && unicode.Is(unicode.Han, runes[i+1]) {
				biToken := t.buildToken(string(runes[i:i+2]), i, i+2, position)
				if biToken != nil {
					result = append(result, biToken)
					position++
				}
			}

			if i+2 < len(runes) && unicode.Is(unicode.Han, runes[i+1]) && unicode.Is(unicode.Han, runes[i+2]) {
				triToken := t.buildToken(string(runes[i:i+3]), i, i+3, position)
				if triToken != nil {
					result = append(result, triToken)
					position++
				}
			}

			i++
			tokenStart = i
		} else if unicode.IsLetter(r) || unicode.IsDigit(r) {
			i++
		} else {
			if tokenStart < i {
				enToken := string(runes[tokenStart:i])
				if token := t.buildToken(enToken, tokenStart, i, position); token != nil {
					result = append(result, token)
					position++
				}
			}
			i++
			tokenStart = i
		}
	}

	if tokenStart < len(runes) {
		enToken := string(runes[tokenStart:])
		if token := t.buildToken(enToken, tokenStart, len(runes), position); token != nil {
			result = append(result, token)
		}
	}

	return result
}

func (t *ZhTokenizer) buildToken(text string, start, end, position int) *analysis.Token {
	if len(strings.TrimSpace(text)) == 0 {
		return nil
	}
	return &analysis.Token{
		Term:     []byte(strings.ToLower(text)),
		Start:    start,
		End:      end,
		Position: position,
		Type:     analysis.AlphaNumeric,
		KeyWord:  false,
	}
}

func ZhTokenizerConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.Tokenizer, error) {
	return NewZhTokenizer(), nil
}

type PinyinTokenFilter struct{}

func NewPinyinTokenFilter() *PinyinTokenFilter {
	return &PinyinTokenFilter{}
}

func (f *PinyinTokenFilter) Filter(input analysis.TokenStream) analysis.TokenStream {
	result := make(analysis.TokenStream, 0, len(input)*2)

	for _, token := range input {
		result = append(result, token)

		term := string(token.Term)
		runes := []rune(term)

		if len(runes) == 1 && unicode.Is(unicode.Han, runes[0]) {
			if py, ok := pinyinMap[runes[0]]; ok {
				pyToken := &analysis.Token{
					Term:     []byte(py),
					Start:    token.Start,
					End:      token.End,
					Position: token.Position + 1,
					Type:     token.Type,
					KeyWord:  false,
				}
				result = append(result, pyToken)

				if len(py) > 0 {
					firstLetter := string(py[0])
					flToken := &analysis.Token{
						Term:     []byte(firstLetter),
						Start:    token.Start,
						End:      token.End,
						Position: token.Position + 2,
						Type:     token.Type,
						KeyWord:  false,
					}
					result = append(result, flToken)
				}
			}
		} else {
			hasHan := false
			var pinyinBuilder strings.Builder
			var firstLetterBuilder strings.Builder
			for _, r := range runes {
				if unicode.Is(unicode.Han, r) {
					hasHan = true
					if py, ok := pinyinMap[r]; ok {
						pinyinBuilder.WriteString(py)
						if len(py) > 0 {
							firstLetterBuilder.WriteByte(py[0])
						}
					}
				}
			}
			if hasHan {
				pyStr := pinyinBuilder.String()
				if pyStr != "" {
					pyToken := &analysis.Token{
						Term:     []byte(pyStr),
						Start:    token.Start,
						End:      token.End,
						Position: token.Position + 1,
						Type:     token.Type,
						KeyWord:  false,
					}
					result = append(result, pyToken)
				}
				flStr := firstLetterBuilder.String()
				if flStr != "" {
					flToken := &analysis.Token{
						Term:     []byte(flStr),
						Start:    token.Start,
						End:      token.End,
						Position: token.Position + 2,
						Type:     token.Type,
						KeyWord:  false,
					}
					result = append(result, flToken)
				}
			}
		}
	}

	return result
}

func PinyinFilterConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.TokenFilter, error) {
	return NewPinyinTokenFilter(), nil
}

type EdgeNgramTokenFilter struct {
	minGram int
	maxGram int
}

func NewEdgeNgramTokenFilter(minGram, maxGram int) *EdgeNgramTokenFilter {
	if minGram < 1 {
		minGram = 1
	}
	if maxGram < minGram {
		maxGram = minGram
	}
	return &EdgeNgramTokenFilter{
		minGram: minGram,
		maxGram: maxGram,
	}
}

func (f *EdgeNgramTokenFilter) Filter(input analysis.TokenStream) analysis.TokenStream {
	result := make(analysis.TokenStream, 0)

	for _, token := range input {
		result = append(result, token)

		term := string(token.Term)
		runes := []rune(term)
		runeCount := len(runes)

		for n := f.minGram; n <= f.maxGram && n <= runeCount; n++ {
			gram := string(runes[:n])
			if gram != term {
				gramToken := &analysis.Token{
					Term:     []byte(gram),
					Start:    token.Start,
					End:      token.Start + n,
					Position: token.Position,
					Type:     token.Type,
					KeyWord:  false,
				}
				result = append(result, gramToken)
			}
		}
	}

	return result
}

func EdgeNgramFilterConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.TokenFilter, error) {
	minGram := 1
	maxGram := 20
	if v, ok := config["min_gram"].(float64); ok {
		minGram = int(v)
	}
	if v, ok := config["max_gram"].(float64); ok {
		maxGram = int(v)
	}
	return NewEdgeNgramTokenFilter(minGram, maxGram), nil
}

var englishStopWords = map[string]struct{}{
	"a": {}, "an": {}, "the": {}, "and": {}, "or": {}, "but": {},
	"is": {}, "are": {}, "was": {}, "were": {}, "be": {}, "been": {},
	"being": {}, "have": {}, "has": {}, "had": {}, "do": {}, "does": {},
	"did": {}, "will": {}, "would": {}, "could": {}, "should": {},
	"may": {}, "might": {}, "must": {}, "shall": {}, "can": {},
	"this": {}, "that": {}, "these": {}, "those": {}, "i": {},
	"you": {}, "he": {}, "she": {}, "it": {}, "we": {}, "they": {},
	"me": {}, "him": {}, "her": {}, "us": {}, "them": {},
	"my": {}, "your": {}, "his": {}, "its": {}, "our": {}, "their": {},
	"of": {}, "in": {}, "on": {}, "at": {}, "to": {}, "for": {},
	"with": {}, "by": {}, "from": {}, "about": {}, "as": {},
	"into": {}, "through": {}, "during": {}, "before": {}, "after": {},
	"above": {}, "below": {}, "between": {}, "out": {}, "off": {},
	"over": {}, "under": {}, "again": {}, "further": {}, "then": {},
	"once": {}, "here": {}, "there": {}, "when": {}, "where": {},
	"why": {}, "how": {}, "all": {}, "any": {}, "both": {},
	"each": {}, "few": {}, "more": {}, "most": {}, "other": {},
	"some": {}, "such": {}, "no": {}, "nor": {}, "not": {},
	"only": {}, "own": {}, "same": {}, "so": {}, "than": {},
	"too": {}, "very": {}, "s": {}, "t": {}, "just": {}, "don": {},
	"now": {},
}

type StopWordTokenFilter struct {
	stopWords map[string]struct{}
}

func NewStopWordTokenFilter() *StopWordTokenFilter {
	return &StopWordTokenFilter{
		stopWords: englishStopWords,
	}
}

func (f *StopWordTokenFilter) Filter(input analysis.TokenStream) analysis.TokenStream {
	result := make(analysis.TokenStream, 0, len(input))
	for _, token := range input {
		term := string(token.Term)
		if _, isStop := f.stopWords[term]; !isStop {
			result = append(result, token)
		}
	}
	return result
}

func StopWordFilterConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.TokenFilter, error) {
	return NewStopWordTokenFilter(), nil
}

func TextToPinyin(text string) string {
	var builder strings.Builder
	runes := []rune(text)
	for _, r := range runes {
		if unicode.Is(unicode.Han, r) {
			if py, ok := pinyinMap[r]; ok {
				builder.WriteString(py)
				builder.WriteString(" ")
			} else {
				builder.WriteRune(r)
			}
		} else {
			builder.WriteRune(r)
		}
	}
	return strings.TrimSpace(builder.String())
}

func TextToPinyinInitials(text string) string {
	var builder strings.Builder
	runes := []rune(text)
	for _, r := range runes {
		if unicode.Is(unicode.Han, r) {
			if py, ok := pinyinMap[r]; ok && len(py) > 0 {
				builder.WriteByte(py[0])
			}
		} else if unicode.IsLetter(r) {
			builder.WriteRune(unicode.ToLower(r))
		}
	}
	return builder.String()
}

func IsChinese(text string) bool {
	for _, r := range text {
		if unicode.Is(unicode.Han, r) {
			return true
		}
	}
	return false
}

func DetectLanguage(text string) string {
	chineseCount := 0
	englishCount := 0
	totalRunes := 0

	for _, r := range text {
		if !unicode.IsSpace(r) && !unicode.IsPunct(r) {
			totalRunes++
			if unicode.Is(unicode.Han, r) {
				chineseCount++
			} else if unicode.IsLetter(r) {
				englishCount++
			}
		}
	}

	if totalRunes == 0 {
		return "unknown"
	}

	if chineseCount > totalRunes/3 {
		return "zh"
	}
	return "en"
}

var nonAlphanumericRegex = regexp.MustCompile(`[^a-zA-Z0-9\p{Han}]+`)

func NormalizeText(text string) string {
	text = nonAlphanumericRegex.ReplaceAllString(text, " ")
	text = strings.TrimSpace(text)
	text = strings.ToLower(text)
	return text
}

func RegisterCustomAnalyzers() error {
	registry.RegisterTokenizer(ZhTokenizerName, ZhTokenizerConstructor)
	registry.RegisterTokenFilter(PinyinFilterName, PinyinFilterConstructor)
	registry.RegisterTokenFilter("edge_ngram_filter", EdgeNgramFilterConstructor)
	registry.RegisterTokenFilter("stop_word_filter", StopWordFilterConstructor)
	registry.RegisterAnalyzer(ZhAnalyzerName, ZhAnalyzerConstructor)
	registry.RegisterAnalyzer(ZhPinyinAnalyzer, ZhPinyinAnalyzerConstructor)
	return nil
}

func ZhAnalyzerConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.Analyzer, error) {
	tokenizer, err := cache.TokenizerNamed(ZhTokenizerName)
	if err != nil {
		return nil, err
	}

	lowercaseFilter, err := cache.TokenFilterNamed(lowercase.Name)
	if err != nil {
		return nil, err
	}

	stopFilter, err := cache.TokenFilterNamed("stop_word_filter")
	if err != nil {
		return nil, err
	}

	rv := analysis.DefaultAnalyzer{
		Tokenizer: tokenizer,
		TokenFilters: []analysis.TokenFilter{
			lowercaseFilter,
			stopFilter,
		},
	}
	return &rv, nil
}

func ZhPinyinAnalyzerConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.Analyzer, error) {
	tokenizer, err := cache.TokenizerNamed(ZhTokenizerName)
	if err != nil {
		return nil, err
	}

	lowercaseFilter, err := cache.TokenFilterNamed(lowercase.Name)
	if err != nil {
		return nil, err
	}

	stopFilter, err := cache.TokenFilterNamed("stop_word_filter")
	if err != nil {
		return nil, err
	}

	pinyinFilter, err := cache.TokenFilterNamed(PinyinFilterName)
	if err != nil {
		return nil, err
	}

	rv := analysis.DefaultAnalyzer{
		Tokenizer: tokenizer,
		TokenFilters: []analysis.TokenFilter{
			lowercaseFilter,
			stopFilter,
			pinyinFilter,
		},
	}
	return &rv, nil
}

func init() {
	_ = unicodetokenizer.NewUnicodeTokenizer()
	_ = RegisterCustomAnalyzers()
}
