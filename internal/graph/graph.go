package graph

import (
	"container/list"
	"math"
	"strings"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
)

type Graph struct {
	Nodes map[uint]*GraphNode
	Edges []*GraphEdge
	cfg   *config.Config
}

type GraphNode struct {
	models.GraphNode
	Community int     `json:"community"`
	Highlight bool    `json:"highlight"`
	Visible   bool    `json:"visible"`
	Label     string  `json:"label"`
}

type GraphEdge struct {
	models.GraphEdge
	Weight float64 `json:"weight"`
}

type FilterOptions struct {
	Tags          []string
	TagLogic      string
	MinDegree     int
	MaxDegree     int
	OnlyOrphans   bool
	ExcludeOrphans bool
}

type SearchResult struct {
	Node *GraphNode
	Score float64
}

func New(cfg *config.Config) *Graph {
	return &Graph{
		Nodes: make(map[uint]*GraphNode),
		Edges: make([]*GraphEdge, 0),
		cfg:   cfg,
	}
}

func (g *Graph) BuildFromDB(database *db.Database) error {
	notes, err := database.GetAllNotes()
	if err != nil {
		return err
	}

	links, err := database.GetLinks()
	if err != nil {
		return err
	}

	noteTags := make(map[uint][]string)
	for _, note := range notes {
		tags, err := database.GetTagsByNote(note.ID)
		if err != nil {
			continue
		}
		tagNames := make([]string, 0, len(tags))
		for _, tag := range tags {
			tagNames = append(tagNames, tag.Name)
		}
		noteTags[note.ID] = tagNames
	}

	inDegree := make(map[uint]int)
	outDegree := make(map[uint]int)

	for _, link := range links {
		if link.TargetID > 0 {
			outDegree[link.SourceID]++
			inDegree[link.TargetID]++
		}
	}

	for _, note := range notes {
		inDeg := inDegree[note.ID]
		outDeg := outDegree[note.ID]
		size := g.calculateNodeSize(inDeg)

		node := &GraphNode{
			GraphNode: models.GraphNode{
				ID:        note.ID,
				Path:      note.Path,
				Title:     note.Title,
				Size:      size,
				InDegree:  inDeg,
				OutDegree: outDeg,
				IsOrphan:  inDeg == 0 && outDeg == 0,
				Tags:      noteTags[note.ID],
			},
			Visible: true,
			Label:   note.Title,
		}
		g.Nodes[note.ID] = node
	}

	for _, link := range links {
		if link.TargetID == 0 {
			continue
		}
		if _, ok := g.Nodes[link.SourceID]; !ok {
			continue
		}
		if _, ok := g.Nodes[link.TargetID]; !ok {
			continue
		}
		edge := &GraphEdge{
			GraphEdge: models.GraphEdge{
				Source: link.SourceID,
				Target: link.TargetID,
			},
			Weight: 1.0,
		}
		g.Edges = append(g.Edges, edge)
	}

	return nil
}

func (g *Graph) calculateNodeSize(inDegree int) int {
	minSize := g.cfg.Graph.NodeMinSize
	maxSize := g.cfg.Graph.NodeMaxSize

	if minSize <= 0 {
		minSize = 10
	}
	if maxSize <= 0 {
		maxSize = 50
	}
	if minSize >= maxSize {
		return minSize
	}

	maxInDegree := 0
	for _, node := range g.Nodes {
		if node.InDegree > maxInDegree {
			maxInDegree = node.InDegree
		}
	}

	if maxInDegree == 0 {
		return minSize
	}

	ratio := float64(inDegree) / float64(maxInDegree)
	size := minSize + int(math.Round(ratio*float64(maxSize-minSize)))
	return size
}

func (g *Graph) UpdateNodeSizes() {
	for _, node := range g.Nodes {
		node.Size = g.calculateNodeSize(node.InDegree)
	}
}

func (g *Graph) GetOrphanNodes() []*GraphNode {
	orphans := make([]*GraphNode, 0)
	for _, node := range g.Nodes {
		if node.IsOrphan {
			orphans = append(orphans, node)
		}
	}
	return orphans
}

func (g *Graph) Filter(opts FilterOptions) *Graph {
	filtered := New(g.cfg)

	tagSet := make(map[string]bool)
	for _, tag := range opts.Tags {
		tagSet[strings.ToLower(tag)] = true
	}

	hasTagFilter := len(opts.Tags) > 0
	hasDegreeFilter := opts.MinDegree > 0 || opts.MaxDegree > 0

	for id, node := range g.Nodes {
		visible := true

		if opts.OnlyOrphans && !node.IsOrphan {
			visible = false
		}
		if opts.ExcludeOrphans && node.IsOrphan {
			visible = false
		}

		if visible && hasTagFilter {
			nodeTagSet := make(map[string]bool)
			for _, tag := range node.Tags {
				nodeTagSet[strings.ToLower(tag)] = true
			}

			if opts.TagLogic == "all" {
				for tag := range tagSet {
					if !nodeTagSet[tag] {
						visible = false
						break
					}
				}
			} else {
				hasMatch := false
				for tag := range tagSet {
					if nodeTagSet[tag] {
						hasMatch = true
						break
					}
				}
				if !hasMatch {
					visible = false
				}
			}
		}

		if visible && hasDegreeFilter {
			totalDegree := node.InDegree + node.OutDegree
			if opts.MinDegree > 0 && totalDegree < opts.MinDegree {
				visible = false
			}
			if opts.MaxDegree > 0 && totalDegree > opts.MaxDegree {
				visible = false
			}
		}

		if visible {
			newNode := *node
			newNode.Visible = true
			filtered.Nodes[id] = &newNode
		}
	}

	for _, edge := range g.Edges {
		_, hasSource := filtered.Nodes[edge.Source]
		_, hasTarget := filtered.Nodes[edge.Target]
		if hasSource && hasTarget {
			filtered.Edges = append(filtered.Edges, edge)
		}
	}

	return filtered
}

func (g *Graph) Search(query string) []SearchResult {
	results := make([]SearchResult, 0)
	if query == "" {
		return results
	}

	queryLower := strings.ToLower(query)
	queryParts := strings.Fields(queryLower)

	for _, node := range g.Nodes {
		score := 0.0
		titleLower := strings.ToLower(node.Title)
		pathLower := strings.ToLower(node.Path)

		if strings.Contains(titleLower, queryLower) {
			score += 1.0
		}
		if strings.Contains(pathLower, queryLower) {
			score += 0.5
		}

		for _, part := range queryParts {
			if strings.Contains(titleLower, part) {
				score += 0.3
			}
			if strings.Contains(pathLower, part) {
				score += 0.15
			}
		}

		if score > 0 {
			results = append(results, SearchResult{
				Node:  node,
				Score: score,
			})
		}
	}

	for i := 0; i < len(results)-1; i++ {
		for j := i + 1; j < len(results); j++ {
			if results[j].Score > results[i].Score {
				results[i], results[j] = results[j], results[i]
			}
		}
	}

	return results
}

func (g *Graph) HighlightNodes(nodeIDs []uint) {
	for _, node := range g.Nodes {
		node.Highlight = false
	}
	for _, id := range nodeIDs {
		if node, ok := g.Nodes[id]; ok {
			node.Highlight = true
		}
	}
}

func (g *Graph) GetNeighbors(nodeID uint, hops int) []*GraphNode {
	if hops < 1 {
		hops = 1
	}

	visited := make(map[uint]bool)
	neighbors := make([]*GraphNode, 0)

	if _, ok := g.Nodes[nodeID]; !ok {
		return neighbors
	}

	visited[nodeID] = true
	currentLevel := map[uint]bool{nodeID: true}

	for hop := 0; hop < hops; hop++ {
		nextLevel := make(map[uint]bool)
		for id := range currentLevel {
			for _, edge := range g.Edges {
				var neighborID uint
				if edge.Source == id {
					neighborID = edge.Target
				} else if edge.Target == id {
					neighborID = edge.Source
				} else {
					continue
				}
				if !visited[neighborID] {
					visited[neighborID] = true
					nextLevel[neighborID] = true
				}
			}
		}
		currentLevel = nextLevel
	}

	delete(visited, nodeID)
	for id := range visited {
		if node, ok := g.Nodes[id]; ok {
			neighbors = append(neighbors, node)
		}
	}

	return neighbors
}

func (g *Graph) ShortestPath(sourceID, targetID uint) []uint {
	if sourceID == targetID {
		return []uint{sourceID}
	}

	if _, ok := g.Nodes[sourceID]; !ok {
		return nil
	}
	if _, ok := g.Nodes[targetID]; !ok {
		return nil
	}

	adj := make(map[uint][]uint)
	for _, edge := range g.Edges {
		adj[edge.Source] = append(adj[edge.Source], edge.Target)
		adj[edge.Target] = append(adj[edge.Target], edge.Source)
	}

	visited := make(map[uint]bool)
	parent := make(map[uint]uint)
	queue := list.New()

	visited[sourceID] = true
	queue.PushBack(sourceID)

	found := false
	for queue.Len() > 0 {
		front := queue.Front()
		current := front.Value.(uint)
		queue.Remove(front)

		if current == targetID {
			found = true
			break
		}

		for _, neighbor := range adj[current] {
			if !visited[neighbor] {
				visited[neighbor] = true
				parent[neighbor] = current
				queue.PushBack(neighbor)
			}
		}
	}

	if !found {
		return nil
	}

	path := make([]uint, 0)
	current := targetID
	for current != sourceID {
		path = append([]uint{current}, path...)
		current = parent[current]
	}
	path = append([]uint{sourceID}, path...)

	return path
}

func (g *Graph) ToGraphData() *models.GraphData {
	data := &models.GraphData{
		Nodes: make([]models.GraphNode, 0, len(g.Nodes)),
		Edges: make([]models.GraphEdge, 0, len(g.Edges)),
	}

	for _, node := range g.Nodes {
		if !node.Visible {
			continue
		}
		data.Nodes = append(data.Nodes, node.GraphNode)
	}

	for _, edge := range g.Edges {
		data.Edges = append(data.Edges, edge.GraphEdge)
	}

	return data
}

func (g *Graph) GetNodeCount() int {
	return len(g.Nodes)
}

func (g *Graph) GetEdgeCount() int {
	return len(g.Edges)
}

func (g *Graph) GetAdjacencyList() map[uint][]uint {
	adj := make(map[uint][]uint)
	for _, edge := range g.Edges {
		adj[edge.Source] = append(adj[edge.Source], edge.Target)
		adj[edge.Target] = append(adj[edge.Target], edge.Source)
	}
	return adj
}
