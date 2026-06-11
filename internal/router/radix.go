package router

import (
	"fmt"
	"strings"
	"sync"

	"DF1-56/internal/models"
)

type radixNodeKind int

const (
	radixStatic   radixNodeKind = iota
	radixParam
	radixWildcard
)

type radixNode struct {
	prefix       string
	kind         radixNodeKind
	paramName    string
	route        *models.Route
	children     []*radixNode
	paramChild   *radixNode
	wildcardChild *radixNode
}

type RadixTree struct {
	root *radixNode
	mu   sync.RWMutex
}

func NewRadixTree() *RadixTree {
	return &RadixTree{
		root: &radixNode{
			children: make([]*radixNode, 0),
		},
	}
}

func (t *RadixTree) Insert(path string, route *models.Route) error {
	if path == "" {
		return fmt.Errorf("path cannot be empty")
	}
	if route == nil {
		return fmt.Errorf("route cannot be nil")
	}
	if !strings.HasPrefix(path, "/") {
		return fmt.Errorf("path must start with /")
	}

	t.mu.Lock()
	defer t.mu.Unlock()

	segments := splitPathSegments(path)
	return t.insertNode(t.root, segments, route)
}

func (t *RadixTree) insertNode(node *radixNode, segments []string, route *models.Route) error {
	if len(segments) == 0 {
		if node.route != nil {
			return fmt.Errorf("route already exists")
		}
		node.route = route
		return nil
	}

	currentSeg := segments[0]
	remaining := segments[1:]

	switch {
	case currentSeg == "*":
		if node.wildcardChild == nil {
			node.wildcardChild = &radixNode{
				prefix:   "*",
				kind:     radixWildcard,
				children: make([]*radixNode, 0),
			}
		}
		if node.wildcardChild.route != nil {
			return fmt.Errorf("route already exists for wildcard path")
		}
		node.wildcardChild.route = route
		return nil

	case strings.HasPrefix(currentSeg, ":"):
		paramName := currentSeg[1:]
		if paramName == "" {
			return fmt.Errorf("parameter name cannot be empty")
		}
		if node.paramChild == nil {
			node.paramChild = &radixNode{
				prefix:    currentSeg,
				kind:      radixParam,
				paramName: paramName,
				children:  make([]*radixNode, 0),
			}
		} else if node.paramChild.paramName != paramName {
			return fmt.Errorf("parameter name conflict: existing %s, new %s",
				node.paramChild.paramName, paramName)
		}
		return t.insertNode(node.paramChild, remaining, route)

	default:
		for _, child := range node.children {
			if child.kind != radixStatic {
				continue
			}

			commonPrefix := findCommonPrefix(child.prefix, currentSeg)

			if commonPrefix == child.prefix && commonPrefix == currentSeg {
				return t.insertNode(child, remaining, route)
			}

			if commonPrefix != "" {
				if commonPrefix == child.prefix {
					childPrefix := strings.TrimPrefix(currentSeg, commonPrefix)
					childSegments := []string{childPrefix}
					childSegments = append(childSegments, remaining...)
					return t.insertNode(child, childSegments, route)
				}

				if commonPrefix == currentSeg {
					childChildPrefix := strings.TrimPrefix(child.prefix, commonPrefix)
					childChild := &radixNode{
						prefix:       childChildPrefix,
						kind:         radixStatic,
						route:        child.route,
						children:     child.children,
						paramChild:   child.paramChild,
						wildcardChild: child.wildcardChild,
					}

					child.prefix = commonPrefix
					child.route = nil
					child.children = []*radixNode{childChild}
					child.paramChild = nil
					child.wildcardChild = nil

					return t.insertNode(child, remaining, route)
				}

				childChildPrefix := strings.TrimPrefix(child.prefix, commonPrefix)
				newChildPrefix := strings.TrimPrefix(currentSeg, commonPrefix)

				childChild := &radixNode{
					prefix:       childChildPrefix,
					kind:         radixStatic,
					route:        child.route,
					children:     child.children,
					paramChild:   child.paramChild,
					wildcardChild: child.wildcardChild,
				}

				newChild := &radixNode{
					prefix:   newChildPrefix,
					kind:     radixStatic,
					children: make([]*radixNode, 0),
				}

				child.prefix = commonPrefix
				child.route = nil
				child.children = []*radixNode{childChild, newChild}
				child.paramChild = nil
				child.wildcardChild = nil

				return t.insertNode(newChild, remaining, route)
			}
		}

		newNode := &radixNode{
			prefix:   currentSeg,
			kind:     radixStatic,
			children: make([]*radixNode, 0),
		}
		node.children = append(node.children, newNode)
		return t.insertNode(newNode, remaining, route)
	}
}

func (t *RadixTree) Match(path string) (*models.Route, map[string]string) {
	if path == "" {
		return nil, nil
	}

	t.mu.RLock()
	defer t.mu.RUnlock()

	params := make(map[string]string)
	segments := splitPathSegments(path)
	result, matchedParams := t.matchNode(t.root, segments, params)
	return result, matchedParams
}

func (t *RadixTree) matchNode(node *radixNode, segments []string, params map[string]string) (*models.Route, map[string]string) {
	if len(segments) == 0 {
		if node.route != nil {
			return node.route, params
		}
		if node.wildcardChild != nil && node.wildcardChild.route != nil {
			return node.wildcardChild.route, params
		}
		return nil, nil
	}

	currentSeg := segments[0]
	remaining := segments[1:]

	for _, child := range node.children {
		if child.kind != radixStatic {
			continue
		}

		if strings.HasPrefix(currentSeg, child.prefix) {
			remainingSeg := strings.TrimPrefix(currentSeg, child.prefix)
			var nextSegments []string
			if remainingSeg == "" {
				nextSegments = remaining
			} else {
				nextSegments = append([]string{remainingSeg}, remaining...)
			}

			if route, matchedParams := t.matchNode(child, nextSegments, copyParams(params)); route != nil {
				return route, matchedParams
			}
		} else if strings.HasPrefix(child.prefix, currentSeg) && len(remaining) == 0 {
			if child.route != nil && child.prefix == currentSeg {
				return child.route, params
			}
		}
	}

	if node.paramChild != nil {
		paramsCopy := copyParams(params)
		paramsCopy[node.paramChild.paramName] = currentSeg

		if route, matchedParams := t.matchNode(node.paramChild, remaining, paramsCopy); route != nil {
			return route, matchedParams
		}
	}

	if node.wildcardChild != nil && node.wildcardChild.route != nil {
		return node.wildcardChild.route, params
	}

	return nil, nil
}

func findCommonPrefix(a, b string) string {
	minLen := len(a)
	if len(b) < minLen {
		minLen = len(b)
	}

	i := 0
	for i < minLen && a[i] == b[i] {
		i++
	}

	return a[:i]
}

func splitPathSegments(path string) []string {
	if path == "/" {
		return []string{}
	}

	hasTrailing := hasTrailingSlash(path)
	path = strings.Trim(path, "/")

	if path == "" {
		return []string{}
	}

	parts := strings.Split(path, "/")

	if hasTrailing {
		parts = append(parts, "")
	}

	return parts
}

type MethodRadixRouter struct {
	trees map[string]*RadixTree
	mu    sync.RWMutex
}

func NewMethodRadixRouter() *MethodRadixRouter {
	return &MethodRadixRouter{
		trees: make(map[string]*RadixTree),
	}
}

func (m *MethodRadixRouter) Insert(method, path string, route *models.Route) error {
	methodKey := strings.ToUpper(method)
	if methodKey == "" {
		methodKey = "*"
	}

	m.mu.Lock()
	tree, exists := m.trees[methodKey]
	if !exists {
		tree = NewRadixTree()
		m.trees[methodKey] = tree
	}
	m.mu.Unlock()

	return tree.Insert(path, route)
}

func (m *MethodRadixRouter) Match(method, path string) (*models.Route, map[string]string) {
	methodKey := strings.ToUpper(method)

	m.mu.RLock()
	tree, exists := m.trees[methodKey]
	m.mu.RUnlock()

	if exists {
		if route, params := tree.Match(path); route != nil {
			return route, params
		}
	}

	m.mu.RLock()
	tree, exists = m.trees["*"]
	m.mu.RUnlock()

	if exists {
		return tree.Match(path)
	}

	return nil, nil
}
