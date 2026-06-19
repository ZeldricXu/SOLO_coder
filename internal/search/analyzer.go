package search

import (
	"sync"
	"unicode"

	"github.com/blevesearch/bleve/v2"
	"github.com/blevesearch/bleve/v2/analysis"
	"github.com/blevesearch/bleve/v2/analysis/token/lowercase"
	unicodetokenizer "github.com/blevesearch/bleve/v2/analysis/tokenizer/unicode"
	"github.com/blevesearch/bleve/v2/registry"
)

const (
	ZhAnalyzerName   = "zh_analyzer"
	ZhPinyinAnalyzer = "zh_pinyin_analyzer"
)

var (
	analyzerRegisterOnce sync.Once
	analyzerRegisterErr  error
)

var charToPinyinMap = map[rune]string{
	'一': "yi", '二': "er", '三': "san", '四': "si", '五': "wu",
	'中': "zhong", '国': "guo", '人': "ren",
	'文': "wen", '章': "zhang", '字': "zi",
	'搜': "sou", '索': "suo", '引': "yin", '擎': "qing",
	'标': "biao", '名': "ming", '称': "cheng",
	'用': "yong", '户': "hu", '管': "guan", '理': "li",
	'数': "shu", '据': "ju", '信': "xin", '息': "xi",
	'网': "wang", '络': "luo", '电': "dian", '脑': "nao",
	'技': "ji", '术': "shu", '科': "ke", '学': "xue",
	'公': "gong", '司': "si", '企': "qi", '业': "ye",
	'团': "tuan", '队': "dui",
	'问': "wen", '题': "ti", '解': "jie", '决': "jue",
	'新': "xin", '时': "shi", '间': "jian",
	'开': "kai", '发': "fa", '测': "ce", '试': "shi",
	'维': "wei", '护': "hu", '服': "fu", '务': "wu",
	'安': "an", '全': "quan",
	'登': "deng", '录': "lu",
	'修': "xiu", '改': "gai", '删': "shan", '除': "chu",
	'增': "zeng", '移': "yi", '动': "dong",
	'知': "zhi", '识': "shi", '库': "ku",
	'产': "chan", '品': "pin", '册': "ce",
	'培': "pei", '训': "xun", '材': "cai", '料': "liao",
	'装': "zhuang", '指': "zhi", '南': "nan",
}

func ToPinyin(text string) string {
	var result []rune
	for _, r := range text {
		if pinyin, ok := charToPinyinMap[r]; ok {
			result = append(result, []rune(pinyin)...)
			result = append(result, ' ')
		} else {
			result = append(result, r)
		}
	}
	return string(result)
}

func DetectLanguage(text string) string {
	var zhCount, enCount int
	for _, r := range text {
		if unicode.Is(unicode.Han, r) {
			zhCount++
		} else if unicode.IsLetter(r) && r <= unicode.MaxASCII {
			enCount++
		}
	}
	if zhCount > enCount {
		return "zh"
	}
	return "en"
}

type pinyinTokenFilter struct{}

func newPinyinTokenFilter() analysis.TokenFilter {
	return &pinyinTokenFilter{}
}

func (f *pinyinTokenFilter) Filter(input analysis.TokenStream) analysis.TokenStream {
	for _, token := range input {
		token.Term = []byte(ToPinyin(string(token.Term)))
	}
	return input
}

func pinyinTokenFilterConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.TokenFilter, error) {
	return newPinyinTokenFilter(), nil
}

type zhAnalyzer struct{}

func newZhAnalyzer() analysis.Analyzer {
	return &zhAnalyzer{}
}

func (a *zhAnalyzer) Analyze(input []byte) analysis.TokenStream {
	tokenizer := unicodetokenizer.NewUnicodeTokenizer()
	filter1 := lowercase.NewLowerCaseFilter()
	stream := tokenizer.Tokenize(input)
	stream = filter1.Filter(stream)
	return stream
}

func zhAnalyzerConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.Analyzer, error) {
	return newZhAnalyzer(), nil
}

type zhPinyinAnalyzer struct{}

func newZhPinyinAnalyzer() analysis.Analyzer {
	return &zhPinyinAnalyzer{}
}

func (a *zhPinyinAnalyzer) Analyze(input []byte) analysis.TokenStream {
	tokenizer := unicodetokenizer.NewUnicodeTokenizer()
	lowerFilter := lowercase.NewLowerCaseFilter()
	pinyinFilter := newPinyinTokenFilter()

	stream := tokenizer.Tokenize(input)
	stream = lowerFilter.Filter(stream)
	stream = pinyinFilter.Filter(stream)
	return stream
}

func zhPinyinAnalyzerConstructor(config map[string]interface{}, cache *registry.Cache) (analysis.Analyzer, error) {
	return newZhPinyinAnalyzer(), nil
}

func RegisterCustomAnalyzers() error {
	analyzerRegisterOnce.Do(func() {
		defer func() {
			if r := recover(); r != nil {
			}
		}()
		registry.RegisterTokenFilter("pinyin_filter", pinyinTokenFilterConstructor)
		registry.RegisterAnalyzer(ZhAnalyzerName, zhAnalyzerConstructor)
		registry.RegisterAnalyzer(ZhPinyinAnalyzer, zhPinyinAnalyzerConstructor)
		_ = bleve.NewIndexMapping()
	})
	return analyzerRegisterErr
}
