package graph

import (
	"math/rand"
)

type CommunityResult struct {
	Communities map[int][]uint
	NodeCount   int
	CommunityCount int
}

func (g *Graph) DetectCommunities(maxIterations int) *CommunityResult {
	if len(g.Nodes) == 0 {
		return &CommunityResult{
			Communities:    make(map[int][]uint),
			NodeCount:      0,
			CommunityCount: 0,
		}
	}

	if maxIterations <= 0 {
		maxIterations = 100
	}

	labels := make(map[uint]int)
	nodeList := make([]uint, 0, len(g.Nodes))

	i := 0
	for id := range g.Nodes {
		labels[id] = i
		nodeList = append(nodeList, id)
		i++
	}

	adj := g.GetAdjacencyList()

	for iter := 0; iter < maxIterations; iter++ {
		changed := false

		for _, idx := range rand.Perm(len(nodeList)) {
			nodeID := nodeList[idx]

			neighbors := adj[nodeID]
			if len(neighbors) == 0 {
				continue
			}

			labelCount := make(map[int]int)
			for _, neighborID := range neighbors {
				label := labels[neighborID]
				labelCount[label]++
			}

			maxCount := 0
			var maxLabels []int
			for label, count := range labelCount {
				if count > maxCount {
					maxCount = count
					maxLabels = []int{label}
				} else if count == maxCount {
					maxLabels = append(maxLabels, label)
				}
			}

			if len(maxLabels) > 0 {
				newLabel := maxLabels[rand.Intn(len(maxLabels))]
				if newLabel != labels[nodeID] {
					labels[nodeID] = newLabel
					changed = true
				}
			}
		}

		if !changed {
			break
		}
	}

	communities := make(map[int][]uint)
	for nodeID, label := range labels {
		communities[label] = append(communities[label], nodeID)
	}

	renumbered := make(map[int][]uint)
	newLabel := 0
	for _, nodes := range communities {
		renumbered[newLabel] = nodes
		newLabel++
	}

	for label, nodes := range renumbered {
		for _, nodeID := range nodes {
			if node, ok := g.Nodes[nodeID]; ok {
				node.Community = label
			}
		}
	}

	return &CommunityResult{
		Communities:    renumbered,
		NodeCount:      len(g.Nodes),
		CommunityCount: len(renumbered),
	}
}

func (g *Graph) GetCommunityNodes(communityID int) []*GraphNode {
	nodes := make([]*GraphNode, 0)
	for _, node := range g.Nodes {
		if node.Community == communityID {
			nodes = append(nodes, node)
		}
	}
	return nodes
}

func (g *Graph) GetNodeCommunity(nodeID uint) int {
	if node, ok := g.Nodes[nodeID]; ok {
		return node.Community
	}
	return -1
}

func (g *Graph) GetAllCommunities() map[int][]*GraphNode {
	result := make(map[int][]*GraphNode)
	for _, node := range g.Nodes {
		result[node.Community] = append(result[node.Community], node)
	}
	return result
}
