package router

import (
	"fmt"
	"regexp"
	"sort"
	"strings"
	"sync"

	"DF1-56/internal/models"
)

type regexRouteEntry struct {
	pattern    *regexp.Regexp
	paramNames []string
	route      *models.Route
	priority   int
}

type RegexRouter struct {
	routes []*regexRouteEntry
	mu     sync.RWMutex
}

func NewRegexRouter() *RegexRouter {
	return &RegexRouter{
		routes: make([]*regexRouteEntry, 0),
	}
}

func (r *RegexRouter) Insert(path string, pattern string, route *models.Route) error {
	if path == "" {
		return fmt.Errorf("path cannot be empty")
	}
	if pattern == "" {
		return fmt.Errorf("regex pattern cannot be empty")
	}
	if route == nil {
		return fmt.Errorf("route cannot be nil")
	}

	compiled, err := regexp.Compile(pattern)
	if err != nil {
		return fmt.Errorf("invalid regex pattern: %w", err)
	}

	paramNames := extractParamNames(compiled)

	r.mu.Lock()
	defer r.mu.Unlock()

	entry := &regexRouteEntry{
		pattern:    compiled,
		paramNames: paramNames,
		route:      route,
		priority:   calculatePriority(pattern),
	}

	r.routes = append(r.routes, entry)
	sort.Slice(r.routes, func(i, j int) bool {
		return r.routes[i].priority > r.routes[j].priority
	})

	return nil
}

func (r *RegexRouter) Match(path string) (*models.Route, map[string]string) {
	if path == "" {
		return nil, nil
	}

	r.mu.RLock()
	defer r.mu.RUnlock()

	for _, entry := range r.routes {
		if matches := entry.pattern.FindStringSubmatch(path); matches != nil {
			params := make(map[string]string)
			for i, name := range entry.paramNames {
				if i < len(matches)-1 {
					params[name] = matches[i+1]
				}
			}
			return entry.route, params
		}
	}

	return nil, nil
}

func extractParamNames(re *regexp.Regexp) []string {
	names := re.SubexpNames()
	result := make([]string, 0, len(names))
	for _, name := range names {
		if name != "" {
			result = append(result, name)
		}
	}
	return result
}

func calculatePriority(pattern string) int {
	priority := 0

	if strings.Contains(pattern, "^") {
		priority += 100
	}

	if strings.Contains(pattern, "$") {
		priority += 50
	}

	literalCount := 0
	inGroup := false
	for _, ch := range pattern {
		switch {
		case ch == '(' || ch == '[':
			inGroup = true
		case ch == ')' || ch == ']':
			inGroup = false
		case !inGroup && !isMetaCharacter(ch):
			literalCount++
		}
	}
	priority += literalCount * 10

	return priority
}

func isMetaCharacter(ch rune) bool {
	metaChars := map[rune]bool{
		'.': true, '*': true, '+': true, '?': true,
		'^': true, '$': true, '|': true, '(': true,
		')': true, '[': true, ']': true, '{': true,
		'}': true, '\\': true,
	}
	return metaChars[ch]
}
