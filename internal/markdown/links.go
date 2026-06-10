package markdown

import (
	"regexp"
	"strings"
)

type LinkExtractor struct {
	wikiLinkRegex *regexp.Regexp
}

func NewLinkExtractor() *LinkExtractor {
	return &LinkExtractor{
		wikiLinkRegex: regexp.MustCompile(`!?\[\[([^\]\n]+)\]\]`),
	}
}

func (e *LinkExtractor) Extract(content string) []WikiLink {
	var links []WikiLink
	lines := strings.Split(content, "\n")

	for lineNum, line := range lines {
		if isInCodeBlock(lines, lineNum) {
			continue
		}

		matches := e.wikiLinkRegex.FindAllStringSubmatchIndex(line, -1)
		for _, match := range matches {
			fullMatch := line[match[0]:match[1]]
			inner := line[match[2]:match[3]]

			if insideInlineCode(line, match[0]) {
				continue
			}

			link := parseWikiLink(inner, lineNum+1)
			link.IsEmbedded = strings.HasPrefix(fullMatch, "!")
			links = append(links, link)
		}
	}

	return links
}

func parseWikiLink(inner string, lineNum int) WikiLink {
	link := WikiLink{
		LineNum: lineNum,
	}

	parts := strings.SplitN(inner, "|", 2)
	targetPart := parts[0]

	if len(parts) > 1 {
		link.Alias = strings.TrimSpace(parts[1])
		link.Display = link.Alias
	}

	anchorParts := strings.SplitN(targetPart, "#", 2)
	link.Target = strings.TrimSpace(anchorParts[0])

	if len(anchorParts) > 1 {
		link.Anchor = strings.TrimSpace(anchorParts[1])
	}

	if link.Display == "" {
		if link.Anchor != "" {
			link.Display = link.Target + "#" + link.Anchor
		} else {
			link.Display = link.Target
		}
	}

	return link
}

func isInCodeBlock(lines []string, lineNum int) bool {
	inCodeBlock := false
	for i := 0; i <= lineNum && i < len(lines); i++ {
		line := strings.TrimSpace(lines[i])
		if strings.HasPrefix(line, "```") {
			inCodeBlock = !inCodeBlock
		}
	}
	return inCodeBlock
}

func insideInlineCode(line string, pos int) bool {
	count := 0
	for i := 0; i < pos && i < len(line); i++ {
		if line[i] == '`' {
			count++
		}
	}
	return count%2 == 1
}

func ExtractLinkTargets(content string) []string {
	extractor := NewLinkExtractor()
	links := extractor.Extract(content)
	targets := make([]string, 0, len(links))
	seen := make(map[string]bool)
	for _, link := range links {
		if !seen[link.Target] {
			seen[link.Target] = true
			targets = append(targets, link.Target)
		}
	}
	return targets
}
