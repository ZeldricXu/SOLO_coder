package dag

import (
	"errors"
	"fmt"
	"sort"
	"strings"
)

var (
	ErrCyclicDependency = errors.New("cyclic dependency detected")
	ErrNodeNotFound     = errors.New("node not found")
	ErrInvalidGraph     = errors.New("invalid graph structure")
)

type Node struct {
	ID       string            `json:"id"`
	Name     string            `json:"name"`
	TaskID   string            `json:"task_id"`
	Metadata map[string]string `json:"metadata"`
}

type Edge struct {
	From     string `json:"from"`
	To       string `json:"to"`
	Condition string `json:"condition"`
}

type Graph struct {
	Nodes map[string]*Node `json:"nodes"`
	Edges []Edge           `json:"edges"`
}

type ExecutionPlan struct {
	Nodes      []string          `json:"nodes"`
	Levels     map[string]int    `json:"levels"`
	Parallel   [][]string        `json:"parallel"`
	Dependents map[string][]string `json:"dependents"`
}

type Orchestrator struct{}

func NewOrchestrator() *Orchestrator {
	return &Orchestrator{}
}

func (o *Orchestrator) BuildGraph(nodes []Node, edges []Edge) (*Graph, error) {
	g := &Graph{
		Nodes: make(map[string]*Node),
		Edges: make([]Edge, 0, len(edges)),
	}

	for i := range nodes {
		g.Nodes[nodes[i].ID] = &nodes[i]
	}

	for _, edge := range edges {
		if _, exists := g.Nodes[edge.From]; !exists {
			return nil, fmt.Errorf("%w: from node %s", ErrNodeNotFound, edge.From)
		}
		if _, exists := g.Nodes[edge.To]; !exists {
			return nil, fmt.Errorf("%w: to node %s", ErrNodeNotFound, edge.To)
		}
		g.Edges = append(g.Edges, edge)
	}

	return g, nil
}

func (o *Orchestrator) DetectCycle(g *Graph) error {
	visited := make(map[string]bool)
	recStack := make(map[string]bool)

	var dfs func(nodeID string) bool
	dfs = func(nodeID string) bool {
		visited[nodeID] = true
		recStack[nodeID] = true

		for _, edge := range g.Edges {
			if edge.From == nodeID {
				if !visited[edge.To] {
					if dfs(edge.To) {
						return true
					}
				} else if recStack[edge.To] {
					return true
				}
			}
		}

		recStack[nodeID] = false
		return false
	}

	for nodeID := range g.Nodes {
		if !visited[nodeID] {
			if dfs(nodeID) {
				return ErrCyclicDependency
			}
		}
	}

	return nil
}

func (o *Orchestrator) TopologicalSort(g *Graph) ([]string, error) {
	inDegree := make(map[string]int)
	for nodeID := range g.Nodes {
		inDegree[nodeID] = 0
	}

	for _, edge := range g.Edges {
		inDegree[edge.To]++
	}

	var queue []string
	for nodeID, degree := range inDegree {
		if degree == 0 {
			queue = append(queue, nodeID)
		}
	}

	sort.Strings(queue)

	var result []string
	for len(queue) > 0 {
		nodeID := queue[0]
		queue = queue[1:]
		result = append(result, nodeID)

		var nextNodes []string
		for _, edge := range g.Edges {
			if edge.From == nodeID {
				inDegree[edge.To]--
				if inDegree[edge.To] == 0 {
					nextNodes = append(nextNodes, edge.To)
				}
			}
		}
		sort.Strings(nextNodes)
		queue = append(queue, nextNodes...)
	}

	if len(result) != len(g.Nodes) {
		return nil, ErrCyclicDependency
	}

	return result, nil
}

func (o *Orchestrator) GenerateExecutionPlan(g *Graph) (*ExecutionPlan, error) {
	if err := o.DetectCycle(g); err != nil {
		return nil, err
	}

	sorted, err := o.TopologicalSort(g)
	if err != nil {
		return nil, err
	}

	levels := make(map[string]int)
	dependents := make(map[string][]string)

	for _, nodeID := range sorted {
		levels[nodeID] = 0
		dependents[nodeID] = []string{}
	}

	for _, edge := range g.Edges {
		if levels[edge.To] <= levels[edge.From] {
			levels[edge.To] = levels[edge.From] + 1
		}
		dependents[edge.From] = append(dependents[edge.From], edge.To)
	}

	maxLevel := 0
	for _, level := range levels {
		if level > maxLevel {
			maxLevel = level
		}
	}

	parallel := make([][]string, maxLevel+1)
	for nodeID, level := range levels {
		parallel[level] = append(parallel[level], nodeID)
	}

	for i := range parallel {
		sort.Strings(parallel[i])
	}

	return &ExecutionPlan{
		Nodes:      sorted,
		Levels:     levels,
		Parallel:   parallel,
		Dependents: dependents,
	}, nil
}

func (o *Orchestrator) GetDependencies(g *Graph, nodeID string) []string {
	var deps []string
	for _, edge := range g.Edges {
		if edge.To == nodeID {
			deps = append(deps, edge.From)
		}
	}
	sort.Strings(deps)
	return deps
}

func (o *Orchestrator) GetDependents(g *Graph, nodeID string) []string {
	var deps []string
	for _, edge := range g.Edges {
		if edge.From == nodeID {
			deps = append(deps, edge.To)
		}
	}
	sort.Strings(deps)
	return deps
}

func (o *Orchestrator) GetRootNodes(g *Graph) []string {
	inDegree := make(map[string]int)
	for nodeID := range g.Nodes {
		inDegree[nodeID] = 0
	}

	for _, edge := range g.Edges {
		inDegree[edge.To]++
	}

	var roots []string
	for nodeID, degree := range inDegree {
		if degree == 0 {
			roots = append(roots, nodeID)
		}
	}
	sort.Strings(roots)
	return roots
}

func (o *Orchestrator) GetLeafNodes(g *Graph) []string {
	outDegree := make(map[string]int)
	for nodeID := range g.Nodes {
		outDegree[nodeID] = 0
	}

	for _, edge := range g.Edges {
		outDegree[edge.From]++
	}

	var leaves []string
	for nodeID, degree := range outDegree {
		if degree == 0 {
			leaves = append(leaves, nodeID)
		}
	}
	sort.Strings(leaves)
	return leaves
}

func (o *Orchestrator) ValidateDAG(g *Graph) error {
	if len(g.Nodes) == 0 {
		return fmt.Errorf("%w: no nodes in graph", ErrInvalidGraph)
	}

	return o.DetectCycle(g)
}

func (o *Orchestrator) GetCriticalPath(g *Graph, weights map[string]int) ([]string, int) {
	if len(g.Nodes) == 0 {
		return []string{}, 0
	}

	sorted, err := o.TopologicalSort(g)
	if err != nil {
		return []string{}, 0
	}

	dist := make(map[string]int)
	parent := make(map[string]string)

	for _, nodeID := range sorted {
		dist[nodeID] = weights[nodeID]
		parent[nodeID] = ""
	}

	for _, nodeID := range sorted {
		for _, edge := range g.Edges {
			if edge.From == nodeID {
				if dist[edge.To] < dist[nodeID]+weights[edge.To] {
					dist[edge.To] = dist[nodeID] + weights[edge.To]
					parent[edge.To] = nodeID
				}
			}
		}
	}

	maxDist := 0
	endNode := ""
	for nodeID, d := range dist {
		if d > maxDist {
			maxDist = d
			endNode = nodeID
		}
	}

	var path []string
	current := endNode
	for current != "" {
		path = append([]string{current}, path...)
		current = parent[current]
	}

	return path, maxDist
}

func (o *Orchestrator) PrintGraph(g *Graph) string {
	var sb strings.Builder

	sb.WriteString("Nodes:\n")
	for id, node := range g.Nodes {
		sb.WriteString(fmt.Sprintf("  - %s: %s\n", id, node.Name))
	}

	sb.WriteString("Edges:\n")
	for _, edge := range g.Edges {
		sb.WriteString(fmt.Sprintf("  - %s -> %s\n", edge.From, edge.To))
	}

	return sb.String()
}
