package markdown

import (
	"bytes"
	"regexp"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/pkg/utils"
	"gopkg.in/yaml.v3"
)

type ParseResult struct {
	Title     string
	PlainText string
	Tags      []string
	Links     []WikiLink
	HTML      string
	Metadata  map[string]interface{}
}

type WikiLink struct {
	Target     string
	Display    string
	Anchor     string
	Alias      string
	LineNum    int
	IsEmbedded bool
}

type Parser interface {
	Parse(content string, sourcePath string) (*ParseResult, error)
	ExtractPlainText(content string) string
	ExtractTitle(content string) string
}

type MarkdownParser struct {
	cfg       *config.Config
	renderer  *WikiRenderer
	linkExt   *LinkExtractor
	tagExt    *TagExtractor
}

func NewParser(cfg *config.Config) *MarkdownParser {
	return &MarkdownParser{
		cfg:      cfg,
		renderer: NewWikiRenderer(cfg),
		linkExt:  NewLinkExtractor(),
		tagExt:   NewTagExtractor(),
	}
}

func (p *MarkdownParser) Parse(content string, sourcePath string) (*ParseResult, error) {
	result := &ParseResult{
		Metadata: make(map[string]interface{}),
	}

	body := p.extractFrontMatter(content, result)

	result.Title = p.ExtractTitle(body)

	result.Links = p.linkExt.Extract(body)

	result.Tags = p.tagExt.Extract(body)
	for k, v := range result.Metadata {
		if k == "tags" || k == "tag" {
			if tagStr, ok := v.(string); ok {
				for _, t := range strings.Split(tagStr, ",") {
					t = strings.TrimSpace(t)
					if t != "" {
						result.Tags = append(result.Tags, t)
					}
				}
			} else if tagList, ok := v.([]interface{}); ok {
				for _, t := range tagList {
					if tagStr, ok := t.(string); ok {
						tagStr = strings.TrimSpace(tagStr)
						if tagStr != "" {
							result.Tags = append(result.Tags, tagStr)
						}
					}
				}
			}
		}
	}
	result.Tags = utils.UniqueStrings(result.Tags)

	result.PlainText = p.ExtractPlainText(body)

	html, err := p.renderer.Render(body, sourcePath)
	if err != nil {
		return nil, err
	}
	result.HTML = html

	return result, nil
}

func (p *MarkdownParser) extractFrontMatter(content string, result *ParseResult) string {
	re := regexp.MustCompile(`^---\s*\n(.*?)\n---\s*\n?`)
	matches := re.FindStringSubmatch(content)

	if len(matches) >= 2 {
		frontMatter := matches[1]
		body := content[len(matches[0]):]

		var metadata map[string]interface{}
		if err := yaml.Unmarshal([]byte(frontMatter), &metadata); err == nil {
			result.Metadata = metadata

			if title, ok := metadata["title"].(string); ok && title != "" {
				result.Title = title
			}
		}

		return body
	}

	return content
}

func (p *MarkdownParser) ExtractTitle(content string) string {
	return utils.ExtractTitle(content)
}

func (p *MarkdownParser) ExtractPlainText(content string) string {
	content = removeCodeBlocks(content)
	content = removeFrontMatter(content)
	content = removeWikiLinks(content)
	content = removeHTMLTags(content)
	content = removeMarkdownSyntax(content)
	content = strings.TrimSpace(content)
	return content
}

func removeCodeBlocks(content string) string {
	re := regexp.MustCompile("```[\\s\\S]*?```")
	return re.ReplaceAllString(content, "")
}

func removeFrontMatter(content string) string {
	re := regexp.MustCompile(`(?s)^---\s*\n.*?\n---\s*\n?`)
	return re.ReplaceAllString(content, "")
}

func removeWikiLinks(content string) string {
	re := regexp.MustCompile(`\[\[([^\]]+)\]\]`)
	return re.ReplaceAllStringFunc(content, func(match string) string {
		inner := match[2 : len(match)-2]
		parts := strings.SplitN(inner, "|", 2)
		if len(parts) == 2 {
			return parts[1]
		}
		parts = strings.SplitN(inner, "#", 2)
		return parts[0]
	})
}

func removeHTMLTags(content string) string {
	re := regexp.MustCompile(`<[^>]+>`)
	return re.ReplaceAllString(content, "")
}

func removeMarkdownSyntax(content string) string {
	content = regexp.MustCompile(`#{1,6}\s+`).ReplaceAllString(content, "")
	content = regexp.MustCompile(`\*\*([^*]+)\*\*`).ReplaceAllString(content, "$1")
	content = regexp.MustCompile(`\*([^*]+)\*`).ReplaceAllString(content, "$1")
	content = regexp.MustCompile(`__([^_]+)__`).ReplaceAllString(content, "$1")
	content = regexp.MustCompile(`_([^_]+)_`).ReplaceAllString(content, "$1")
	content = regexp.MustCompile(`~~([^~]+)~~`).ReplaceAllString(content, "$1")
	content = regexp.MustCompile(`\[([^\]]+)\]\([^)]+\)`).ReplaceAllString(content, "$1")
	content = regexp.MustCompile("`([^`]+)`").ReplaceAllString(content, "$1")
	content = regexp.MustCompile(`^>\s+`).ReplaceAllString(content, "")
	content = regexp.MustCompile(`^[-*+]\s+`).ReplaceAllString(content, "")
	content = regexp.MustCompile(`^\d+\.\s+`).ReplaceAllString(content, "")
	content = regexp.MustCompile(`^---\s*$`).ReplaceAllString(content, "")
	content = regexp.MustCompile(`\$\$[\s\S]*?\$\$`).ReplaceAllString(content, "")
	content = regexp.MustCompile(`\$[^$\n]+\$`).ReplaceAllString(content, "")
	content = regexp.MustCompile(`!{0,1}\[\[([^\]]+)\]\]`).ReplaceAllString(content, "$1")
	content = regexp.MustCompile(`#([\p{L}\p{N}_/]+)`).ReplaceAllString(content, "$1")

	var buf bytes.Buffer
	lines := strings.Split(content, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line != "" {
			buf.WriteString(line)
			buf.WriteString(" ")
		}
	}
	content = buf.String()

	content = regexp.MustCompile(`\s+`).ReplaceAllString(content, " ")
	return strings.TrimSpace(content)
}
