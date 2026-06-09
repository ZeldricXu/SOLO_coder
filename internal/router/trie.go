package router

import (
	"fmt"
	"strings"
	"sync"

	"DF1-56/internal/models"
)

type trieNode struct {
	children    map[string]*trieNode
	paramChild  *trieNode
	wildcard    *trieNode
	route       *models.Route
	paramName   string
	isParam     bool
	isWildcard  bool
}

type TrieRouter struct {
	root *trieNode
	mu   sync.RWMutex
}

func NewTrieRouter() *TrieRouter {
	return &TrieRouter{
		root: &trieNode{
			children: make(map[string]*trieNode),
		},
	}
}

func (t *TrieRouter) Insert(path string, route *models.Route) error {
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

	parts := splitPath(path)
	current := t.root

	for i, part := range parts {
		if part == "" {
			continue
		}

		switch {
		case part == "*":
			if current.wildcard == nil {
				current.wildcard = &trieNode{
					isWildcard: true,
					children:   make(map[string]*trieNode),
				}
			}
			current = current.wildcard
		case strings.HasPrefix(part, ":"):
			paramName := part[1:]
			if paramName == "" {
				return fmt.Errorf("parameter name cannot be empty at position %d", i)
			}
			if current.paramChild == nil {
				current.paramChild = &trieNode{
					isParam:   true,
					paramName: paramName,
					children:  make(map[string]*trieNode),
				}
			} else if current.paramChild.paramName != paramName {
				return fmt.Errorf("parameter name conflict: existing %s, new %s at position %d",
					current.paramChild.paramName, paramName, i)
			}
			current = current.paramChild
		default:
			if _, exists := current.children[part]; !exists {
				current.children[part] = &trieNode{
					children: make(map[string]*trieNode),
				}
			}
			current = current.children[part]
		}
	}

	if current.route != nil {
		return fmt.Errorf("route already exists for path: %s", path)
	}
	current.route = route

	return nil
}

func (t *TrieRouter) Match(path string) (*models.Route, map[string]string) {
	if path == "" {
		return nil, nil
	}

	t.mu.RLock()
	defer t.mu.RUnlock()

	parts := splitPath(path)
	params := make(map[string]string)

	return t.matchNode(t.root, parts, 0, params)
}

func (t *TrieRouter) matchNode(node *trieNode, parts []string, index int, params map[string]string) (*models.Route, map[string]string) {
	if index == len(parts) {
		if node.route != nil {
			return node.route, params
		}
		if node.wildcard != nil && node.wildcard.route != nil {
			return node.wildcard.route, params
		}
		return nil, nil
	}

	part := parts[index]
	if part == "" {
		if index == 0 && index == len(parts)-1 && node.route != nil {
			return node.route, params
		}
		return nil, nil
	}

	if child, exists := node.children[part]; exists {
		paramsCopy := copyParams(params)
		if route, matchedParams := t.matchNode(child, parts, index+1, paramsCopy); route != nil {
			return route, matchedParams
		}
	}

	if node.paramChild != nil {
		paramsCopy := copyParams(params)
		paramsCopy[node.paramChild.paramName] = part
		if route, matchedParams := t.matchNode(node.paramChild, parts, index+1, paramsCopy); route != nil {
			return route, matchedParams
		}
	}

	if node.wildcard != nil && node.wildcard.route != nil {
		return node.wildcard.route, params
	}

	return nil, nil
}

func splitPath(path string) []string {
	if path == "/" {
		return []string{""}
	}

	hasTrailingSlash := strings.HasSuffix(path, "/")
	path = strings.Trim(path, "/")

	if path == "" {
		return []string{""}
	}

	parts := strings.Split(path, "/")

	if hasTrailingSlash {
		parts = append(parts, "")
	}

	return parts
}

func copyParams(params map[string]string) map[string]string {
	copy := make(map[string]string, len(params))
	for k, v := range params {
		copy[k] = v
	}
	return copy
}
