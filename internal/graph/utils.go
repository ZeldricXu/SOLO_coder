package graph

import (
	"encoding/json"
	"math"
	"sort"
)

type NodeDegree struct {
	ID       uint
	InDegree  int
	OutDegree int
	Total     int
}

func (g *Graph) GetTopNodesByInDegree(n int) []NodeDegree {
	nodes := make([]NodeDegree, 0, len(g.Nodes))
	for _, node := range g.Nodes {
		nodes = append(nodes, NodeDegree{
			ID:       node.ID,
			InDegree:  node.InDegree,
			OutDegree: node.OutDegree,
			Total:     node.InDegree + node.OutDegree,
		})
	}

	sort.Slice(nodes, func(i, j int) bool {
		return nodes[i].InDegree > nodes[j].InDegree
	})

	if n > 0 && n < len(nodes) {
		return nodes[:n]
	}
	return nodes
}

func (g *Graph) GetTopNodesByOutDegree(n int) []NodeDegree {
	nodes := make([]NodeDegree, 0, len(g.Nodes))
	for _, node := range g.Nodes {
		nodes = append(nodes, NodeDegree{
			ID:       node.ID,
			InDegree:  node.InDegree,
			OutDegree: node.OutDegree,
			Total:     node.InDegree + node.OutDegree,
		})
	}

	sort.Slice(nodes, func(i, j int) bool {
		return nodes[i].OutDegree > nodes[j].OutDegree
	})

	if n > 0 && n < len(nodes) {
		return nodes[:n]
	}
	return nodes
}

func (g *Graph) GetAverageDegree() float64 {
	if len(g.Nodes) == 0 {
		return 0
	}
	total := 0
	for _, node := range g.Nodes {
		total += node.InDegree + node.OutDegree
	}
	return float64(total) / float64(len(g.Nodes))
}

func (g *Graph) GetDensity() float64 {
	n := len(g.Nodes)
	if n < 2 {
		return 0
	}
	maxEdges := n * (n - 1)
	return float64(len(g.Edges)*2) / float64(maxEdges)
}

func (g *Graph) GetAllTags() []string {
	tagSet := make(map[string]bool)
	for _, node := range g.Nodes {
		for _, tag := range node.Tags {
			tagSet[tag] = true
		}
	}

	tags := make([]string, 0, len(tagSet))
	for tag := range tagSet {
		tags = append(tags, tag)
	}
	sort.Strings(tags)
	return tags
}

func (g *Graph) GetTagCounts() map[string]int {
	counts := make(map[string]int)
	for _, node := range g.Nodes {
		for _, tag := range node.Tags {
			counts[tag]++
		}
	}
	return counts
}

func (g *Graph) Distance(nodeID1, nodeID2 uint) float64 {
	node1, ok1 := g.Nodes[nodeID1]
	node2, ok2 := g.Nodes[nodeID2]
	if !ok1 || !ok2 {
		return -1
	}
	dx := node1.X - node2.X
	dy := node1.Y - node2.Y
	return math.Sqrt(dx*dx + dy*dy)
}

func (g *Graph) CenterOfMass() (float64, float64) {
	if len(g.Nodes) == 0 {
		return 0, 0
	}

	sumX := 0.0
	sumY := 0.0
	totalSize := 0

	for _, node := range g.Nodes {
		size := node.Size
		if size <= 0 {
			size = 1
		}
		sumX += node.X * float64(size)
		sumY += node.Y * float64(size)
		totalSize += size
	}

	if totalSize == 0 {
		return 0, 0
	}

	return sumX / float64(totalSize), sumY / float64(totalSize)
}

func (g *Graph) GetBoundingBox() (minX, minY, maxX, maxY float64) {
	if len(g.Nodes) == 0 {
		return 0, 0, 0, 0
	}

	first := true
	for _, node := range g.Nodes {
		if first {
			minX = node.X
			maxX = node.X
			minY = node.Y
			maxY = node.Y
			first = false
			continue
		}
		if node.X < minX {
			minX = node.X
		}
		if node.X > maxX {
			maxX = node.X
		}
		if node.Y < minY {
			minY = node.Y
		}
		if node.Y > maxY {
			maxY = node.Y
		}
	}
	return
}

func (g *Graph) ToJSON() ([]byte, error) {
	data := g.ToGraphData()
	return json.Marshal(data)
}

func (g *Graph) ToJSONPretty() ([]byte, error) {
	data := g.ToGraphData()
	return json.MarshalIndent(data, "", "  ")
}

func FramesToJSON(frames []LayoutFrame) ([]byte, error) {
	return json.Marshal(frames)
}

func FramesToJSONPretty(frames []LayoutFrame) ([]byte, error) {
	return json.MarshalIndent(frames, "", "  ")
}

func (g *Graph) GetNodeByID(id uint) *GraphNode {
	if node, ok := g.Nodes[id]; ok {
		return node
	}
	return nil
}

func (g *Graph) GetNodeByPath(path string) *GraphNode {
	for _, node := range g.Nodes {
		if node.Path == path {
			return node
		}
	}
	return nil
}

func (g *Graph) GetNodeByTitle(title string) *GraphNode {
	for _, node := range g.Nodes {
		if node.Title == title {
			return node
		}
	}
	return nil
}

func (g *Graph) Subgraph(nodeIDs []uint) *Graph {
	sub := New(g.cfg)

	idSet := make(map[uint]bool)
	for _, id := range nodeIDs {
		idSet[id] = true
	}

	for id, node := range g.Nodes {
		if idSet[id] {
			newNode := *node
			sub.Nodes[id] = &newNode
		}
	}

	for _, edge := range g.Edges {
		_, hasSource := sub.Nodes[edge.Source]
		_, hasTarget := sub.Nodes[edge.Target]
		if hasSource && hasTarget {
			sub.Edges = append(sub.Edges, edge)
		}
	}

	return sub
}

func (g *Graph) GetConnectedComponents() [][]uint {
	visited := make(map[uint]bool)
	components := make([][]uint, 0)

	adj := g.GetAdjacencyList()

	for id := range g.Nodes {
		if visited[id] {
			continue
		}

		component := make([]uint, 0)
		stack := []uint{id}
		visited[id] = true

		for len(stack) > 0 {
			current := stack[len(stack)-1]
			stack = stack[:len(stack)-1]
			component = append(component, current)

			for _, neighbor := range adj[current] {
				if !visited[neighbor] {
					visited[neighbor] = true
					stack = append(stack, neighbor)
				}
			}
		}

		components = append(components, component)
	}

	return components
}
