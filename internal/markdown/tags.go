package markdown

import (
	"regexp"
	"strings"
)

type TagExtractor struct {
	tagRegex *regexp.Regexp
}

type TagInfo struct {
	Name      string
	FullPath  string
	LineNum   int
	IsNested  bool
	ParentTag string
}

func NewTagExtractor() *TagExtractor {
	return &TagExtractor{
		tagRegex: regexp.MustCompile(`(?m)#([\p{L}\p{N}_/-][\p{L}\p{N}_/\.-]*)`),
	}
}

func (e *TagExtractor) Extract(content string) []string {
	tags := make([]string, 0)
	seen := make(map[string]bool)
	lines := strings.Split(content, "\n")

	for lineNum, line := range lines {
		if isInCodeBlock(lines, lineNum) {
			continue
		}

		matches := e.tagRegex.FindAllStringSubmatchIndex(line, -1)
		for _, match := range matches {
			if insideInlineCode(line, match[0]) {
				continue
			}

			if !isValidTagPosition(line, match[0]) {
				continue
			}

			tagName := line[match[2]:match[3]]
			tagName = strings.Trim(tagName, ".")
			tagName = strings.Trim(tagName, "/")

			if tagName == "" {
				continue
			}

			if !seen[tagName] {
				seen[tagName] = true
				tags = append(tags, tagName)
			}
		}
	}

	return tags
}

func (e *TagExtractor) ExtractDetailed(content string) []TagInfo {
	var tagInfos []TagInfo
	seen := make(map[string]bool)
	lines := strings.Split(content, "\n")

	for lineNum, line := range lines {
		if isInCodeBlock(lines, lineNum) {
			continue
		}

		matches := e.tagRegex.FindAllStringSubmatchIndex(line, -1)
		for _, match := range matches {
			if insideInlineCode(line, match[0]) {
				continue
			}

			if !isValidTagPosition(line, match[0]) {
				continue
			}

			tagName := line[match[2]:match[3]]
			tagName = strings.Trim(tagName, ".")
			tagName = strings.Trim(tagName, "/")

			if tagName == "" {
				continue
			}

			if seen[tagName] {
				continue
			}
			seen[tagName] = true

			tagInfo := TagInfo{
				Name:     tagName,
				FullPath: tagName,
				LineNum:  lineNum + 1,
			}

			if strings.Contains(tagName, "/") {
				tagInfo.IsNested = true
				parts := strings.Split(tagName, "/")
				tagInfo.ParentTag = strings.Join(parts[:len(parts)-1], "/")
				tagInfo.Name = parts[len(parts)-1]
			}

			tagInfos = append(tagInfos, tagInfo)
		}
	}

	return tagInfos
}

func isValidTagPosition(line string, pos int) bool {
	if pos == 0 {
		return true
	}

	prevChar := line[pos-1]
	return prevChar == ' ' || prevChar == '\t' || prevChar == '\n' ||
		prevChar == '(' || prevChar == '[' || prevChar == '{' ||
		prevChar == ',' || prevChar == ':' || prevChar == ';' ||
		prevChar == '!' || prevChar == '?' || prevChar == '.'
}

func ExtractTagNames(content string) []string {
	extractor := NewTagExtractor()
	return extractor.Extract(content)
}

func GetTagHierarchy(tags []string) map[string][]string {
	hierarchy := make(map[string][]string)

	for _, tag := range tags {
		if strings.Contains(tag, "/") {
			parts := strings.Split(tag, "/")
			for i := 0; i < len(parts)-1; i++ {
				parent := strings.Join(parts[:i+1], "/")
				child := strings.Join(parts[:i+2], "/")
				if !contains(hierarchy[parent], child) {
					hierarchy[parent] = append(hierarchy[parent], child)
				}
			}
		}
		if _, ok := hierarchy[tag]; !ok {
			hierarchy[tag] = []string{}
		}
	}

	return hierarchy
}

func contains(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}
