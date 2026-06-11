package router

import (
	"strings"
)

type PathParamExtractor struct{}

func NewPathParamExtractor() *PathParamExtractor {
	return &PathParamExtractor{}
}

func (p *PathParamExtractor) Extract(pattern string, path string) map[string]string {
	params := make(map[string]string)

	patternParts := splitPathIntoSegments(pattern)
	pathParts := splitPathIntoSegments(path)

	if len(patternParts) == 0 || len(pathParts) == 0 {
		return params
	}

	for i, patternPart := range patternParts {
		if i >= len(pathParts) {
			break
		}

		switch {
		case strings.HasPrefix(patternPart, ":"):
			paramName := patternPart[1:]
			if paramName != "" {
				params[paramName] = pathParts[i]
			}
		case patternPart == "*":
			if i < len(pathParts)-1 {
				params["*"] = strings.Join(pathParts[i:], "/")
			} else {
				params["*"] = pathParts[i]
			}
			return params
		default:
		}
	}

	return params
}

func ExtractPathParams(pattern string, path string) map[string]string {
	extractor := NewPathParamExtractor()
	return extractor.Extract(pattern, path)
}

func splitPathIntoSegments(path string) []string {
	if path == "/" {
		return []string{}
	}

	path = strings.Trim(path, "/")
	if path == "" {
		return []string{}
	}

	return strings.Split(path, "/")
}

func hasTrailingSlash(path string) bool {
	return len(path) > 1 && strings.HasSuffix(path, "/")
}
