package planner

import (
	"fmt"
	"sort"

	"github.com/multicloud/cli/internal/common"
)

type GraphNode struct {
	Resource  *common.ResourceConfig
	ID        string
	Name      string
	Status    common.ResourceStatus
	DependsOn []string
	Metadata  map[string]interface{}
}

type GraphEdge struct {
	From     string
	To       string
	Metadata map[string]interface{}
}

type ResourceGraph struct {
	Nodes map[string]*GraphNode
	Edges []*GraphEdge
}

type Plan struct {
	Graph          *ResourceGraph
	Changes        []*common.Change
	Create         []*GraphNode
	Update         []*GraphNode
	Delete         []*GraphNode
	Noop           []*GraphNode
	ParallelGroups [][]*GraphNode
}

func NewResourceGraph() *ResourceGraph {
	return &ResourceGraph{
		Nodes: make(map[string]*GraphNode),
		Edges: make([]*GraphEdge, 0),
	}
}

func (g *ResourceGraph) AddNode(config *common.ResourceConfig) *GraphNode {
	nodeID := config.Name
	node := &GraphNode{
		Resource:  config,
		ID:        nodeID,
		Name:      config.Name,
		Status:    common.StatusPending,
		DependsOn: config.DependsOn,
		Metadata:  make(map[string]interface{}),
	}
	g.Nodes[nodeID] = node
	return node
}

func (g *ResourceGraph) AddEdge(from, to string) error {
	if _, exists := g.Nodes[from]; !exists {
		return common.NewError(common.ErrDependencyError, fmt.Sprintf("dependency node %s not found", from))
	}
	if _, exists := g.Nodes[to]; !exists {
		return common.NewError(common.ErrDependencyError, fmt.Sprintf("dependent node %s not found", to))
	}

	edge := &GraphEdge{
		From: from,
		To:   to,
	}
	g.Edges = append(g.Edges, edge)
	return nil
}

func (g *ResourceGraph) Validate() error {
	visited := make(map[string]bool)
	recStack := make(map[string]bool)

	for nodeID := range g.Nodes {
		if !visited[nodeID] {
			if g.hasCycle(nodeID, visited, recStack) {
				return common.NewError(common.ErrDependencyError, fmt.Sprintf("cyclic dependency detected at %s", nodeID))
			}
		}
	}

	for _, node := range g.Nodes {
		for _, dep := range node.DependsOn {
			if _, exists := g.Nodes[dep]; !exists {
				return common.NewError(common.ErrDependencyError, fmt.Sprintf("dependency %s not found for resource %s", dep, node.Name))
			}
		}
	}

	return nil
}

func (g *ResourceGraph) hasCycle(nodeID string, visited, recStack map[string]bool) bool {
	visited[nodeID] = true
	recStack[nodeID] = true

	node := g.Nodes[nodeID]
	for _, dep := range node.DependsOn {
		if !visited[dep] {
			if g.hasCycle(dep, visited, recStack) {
				return true
			}
		} else if recStack[dep] {
			return true
		}
	}

	recStack[nodeID] = false
	return false
}

func (g *ResourceGraph) TopologicalSort() ([]*GraphNode, error) {
	inDegree := make(map[string]int)
	for nodeID := range g.Nodes {
		inDegree[nodeID] = 0
	}

	for _, node := range g.Nodes {
		for range node.DependsOn {
			inDegree[node.ID]++
		}
	}

	var queue []*GraphNode
	for nodeID, degree := range inDegree {
		if degree == 0 {
			queue = append(queue, g.Nodes[nodeID])
		}
	}

	sort.Slice(queue, func(i, j int) bool {
		return queue[i].Name < queue[j].Name
	})

	var result []*GraphNode
	for len(queue) > 0 {
		node := queue[0]
		queue = queue[1:]
		result = append(result, node)

		for _, other := range g.Nodes {
			for _, dep := range other.DependsOn {
				if dep == node.ID {
					inDegree[other.ID]--
					if inDegree[other.ID] == 0 {
						queue = append(queue, other)
						sort.Slice(queue, func(i, j int) bool {
							return queue[i].Name < queue[j].Name
						})
					}
				}
			}
		}
	}

	if len(result) != len(g.Nodes) {
		return nil, common.NewError(common.ErrDependencyError, "graph has cyclic dependencies")
	}

	return result, nil
}

func (g *ResourceGraph) GetParallelGroups() ([][]*GraphNode, error) {
	sorted, err := g.TopologicalSort()
	if err != nil {
		return nil, err
	}

	nodeLevel := make(map[string]int)
	for _, node := range sorted {
		if len(node.DependsOn) == 0 {
			nodeLevel[node.ID] = 0
		} else {
			maxLevel := 0
			for _, dep := range node.DependsOn {
				if level, ok := nodeLevel[dep]; ok && level > maxLevel {
					maxLevel = level
				}
			}
			nodeLevel[node.ID] = maxLevel + 1
		}
	}

	levelGroups := make(map[int][]*GraphNode)
	for _, node := range sorted {
		level := nodeLevel[node.ID]
		levelGroups[level] = append(levelGroups[level], node)
	}

	var levels []int
	for level := range levelGroups {
		levels = append(levels, level)
	}
	sort.Ints(levels)

	var result [][]*GraphNode
	for _, level := range levels {
		result = append(result, levelGroups[level])
	}

	return result, nil
}

func BuildResourceGraph(configs []*common.ResourceConfig) (*ResourceGraph, error) {
	graph := NewResourceGraph()

	for _, config := range configs {
		graph.AddNode(config)
	}

	for _, node := range graph.Nodes {
		for _, dep := range node.DependsOn {
			if err := graph.AddEdge(dep, node.ID); err != nil {
				return nil, err
			}
		}
	}

	if err := graph.Validate(); err != nil {
		return nil, err
	}

	return graph, nil
}

func BuildPlan(graph *ResourceGraph, currentState map[string]*common.Resource) (*Plan, error) {
	plan := &Plan{
		Graph:   graph,
		Changes: make([]*common.Change, 0),
		Create:  make([]*GraphNode, 0),
		Update:  make([]*GraphNode, 0),
		Delete:  make([]*GraphNode, 0),
		Noop:    make([]*GraphNode, 0),
	}

	desiredNames := make(map[string]bool)
	for _, node := range graph.Nodes {
		desiredNames[node.Name] = true
	}

	for name, resource := range currentState {
		if !desiredNames[name] {
			change := &common.Change{
				ResourceID:   resource.ID,
				ResourceName: name,
				Action:       common.ActionDelete,
				Old:          resource,
			}
			plan.Changes = append(plan.Changes, change)
			plan.Delete = append(plan.Delete, &GraphNode{
				ID:   name,
				Name: name,
			})
		}
	}

	for _, node := range graph.Nodes {
		current, exists := currentState[node.Name]
		if !exists {
			change := &common.Change{
				ResourceID:   "",
				ResourceName: node.Name,
				Action:       common.ActionCreate,
				New:          node.Resource,
			}
			plan.Changes = append(plan.Changes, change)
			plan.Create = append(plan.Create, node)
			node.Status = common.StatusPending
		} else {
			diff := computeDiff(current, node.Resource)
			if len(diff) > 0 {
				change := &common.Change{
					ResourceID:   current.ID,
					ResourceName: node.Name,
					Action:       common.ActionUpdate,
					Old:          current,
					New:          node.Resource,
					Diff:         diff,
				}
				plan.Changes = append(plan.Changes, change)
				plan.Update = append(plan.Update, node)
				node.Status = common.StatusUpdating
			} else {
				change := &common.Change{
					ResourceID:   current.ID,
					ResourceName: node.Name,
					Action:       common.ActionNoop,
					Old:          current,
				}
				plan.Changes = append(plan.Changes, change)
				plan.Noop = append(plan.Noop, node)
				node.Status = common.StatusRunning
			}
		}
	}

	parallelGroups, err := graph.GetParallelGroups()
	if err != nil {
		return nil, err
	}
	plan.ParallelGroups = parallelGroups

	return plan, nil
}

func computeDiff(current *common.Resource, desired *common.ResourceConfig) map[string]common.DiffItem {
	diff := make(map[string]common.DiffItem)

	if current.Type != desired.Type {
		diff["type"] = common.DiffItem{
			Old:        current.Type,
			New:        desired.Type,
			Path:       "type",
			ChangeType: "update",
		}
	}

	if current.Region != desired.Region {
		diff["region"] = common.DiffItem{
			Old:        current.Region,
			New:        desired.Region,
			Path:       "region",
			ChangeType: "update",
		}
	}

	for key, desiredVal := range desired.Properties {
		currentVal, exists := current.Properties[key]
		if !exists {
			diff["properties."+key] = common.DiffItem{
				Old:        nil,
				New:        desiredVal,
				Path:       "properties." + key,
				ChangeType: "add",
			}
		} else if !valuesEqual(currentVal, desiredVal) {
			diff["properties."+key] = common.DiffItem{
				Old:        currentVal,
				New:        desiredVal,
				Path:       "properties." + key,
				ChangeType: "update",
			}
		}
	}

	for key, currentVal := range current.Properties {
		if _, exists := desired.Properties[key]; !exists {
			diff["properties."+key] = common.DiffItem{
				Old:        currentVal,
				New:        nil,
				Path:       "properties." + key,
				ChangeType: "remove",
			}
		}
	}

	for key, desiredVal := range desired.Tags {
		currentVal, exists := current.Tags[key]
		if !exists {
			diff["tags."+key] = common.DiffItem{
				Old:        nil,
				New:        desiredVal,
				Path:       "tags." + key,
				ChangeType: "add",
			}
		} else if currentVal != desiredVal {
			diff["tags."+key] = common.DiffItem{
				Old:        currentVal,
				New:        desiredVal,
				Path:       "tags." + key,
				ChangeType: "update",
			}
		}
	}

	for key, currentVal := range current.Tags {
		if _, exists := desired.Tags[key]; !exists && key != "ManagedBy" && key != "CloudProvider" {
			diff["tags."+key] = common.DiffItem{
				Old:        currentVal,
				New:        nil,
				Path:       "tags." + key,
				ChangeType: "remove",
			}
		}
	}

	return diff
}

func valuesEqual(a, b interface{}) bool {
	switch av := a.(type) {
	case map[string]interface{}:
		bv, ok := b.(map[string]interface{})
		if !ok {
			return false
		}
		if len(av) != len(bv) {
			return false
		}
		for k, v := range av {
			if !valuesEqual(v, bv[k]) {
				return false
			}
		}
		return true
	case []interface{}:
		bv, ok := b.([]interface{})
		if !ok {
			return false
		}
		if len(av) != len(bv) {
			return false
		}
		for i, v := range av {
			if !valuesEqual(v, bv[i]) {
				return false
			}
		}
		return true
	default:
		return a == b
	}
}

func (p *Plan) Summary() map[string]int {
	return map[string]int{
		"create": len(p.Create),
		"update": len(p.Update),
		"delete": len(p.Delete),
		"noop":   len(p.Noop),
		"total":  len(p.Changes),
	}
}

func (p *Plan) HasChanges() bool {
	return len(p.Create) > 0 || len(p.Update) > 0 || len(p.Delete) > 0
}
