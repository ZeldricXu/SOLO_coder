package graph

import (
	"math"
	"math/rand"
)

type LayoutConfig struct {
	Width           float64
	Height          float64
	Iterations      int
	RepulsiveForce  float64
	AttractiveForce float64
	Gravity         float64
	InitialTemp     float64
	MinTemp         float64
	CoolingRate     float64
	CenterX         float64
	CenterY         float64
}

type LayoutFrame struct {
	Nodes []FrameNode `json:"nodes"`
	Step  int         `json:"step"`
}

type FrameNode struct {
	ID uint    `json:"id"`
	X  float64 `json:"x"`
	Y  float64 `json:"y"`
}

func DefaultLayoutConfig() *LayoutConfig {
	return &LayoutConfig{
		Width:           800,
		Height:          600,
		Iterations:      500,
		RepulsiveForce:  200,
		AttractiveForce: 5,
		Gravity:         0.01,
		InitialTemp:     200,
		MinTemp:         0.1,
		CoolingRate:     0.99,
		CenterX:         400,
		CenterY:         300,
	}
}

func (g *Graph) InitRandomPositions(width, height float64) {
	for _, node := range g.Nodes {
		node.X = rand.Float64() * width
		node.Y = rand.Float64() * height
		node.Vx = 0
		node.Vy = 0
	}
}

func (g *Graph) InitCircleLayout(radius float64, centerX, centerY float64) {
	n := len(g.Nodes)
	if n == 0 {
		return
	}

	i := 0
	for _, node := range g.Nodes {
		angle := 2 * math.Pi * float64(i) / float64(n)
		node.X = centerX + radius*math.Cos(angle)
		node.Y = centerY + radius*math.Sin(angle)
		node.Vx = 0
		node.Vy = 0
		i++
	}
}

func (g *Graph) Layout(config *LayoutConfig) {
	if config == nil {
		config = DefaultLayoutConfig()
	}

	if len(g.Nodes) == 0 {
		return
	}

	g.InitRandomPositions(config.Width, config.Height)

	area := config.Width * config.Height
	k := math.Sqrt(area/float64(len(g.Nodes))) * (config.RepulsiveForce / 200.0)

	temp := config.InitialTemp

	nodeList := make([]*GraphNode, 0, len(g.Nodes))
	for _, node := range g.Nodes {
		nodeList = append(nodeList, node)
	}

	edgePairs := make([][2]*GraphNode, 0, len(g.Edges))
	for _, edge := range g.Edges {
		source, ok1 := g.Nodes[edge.Source]
		target, ok2 := g.Nodes[edge.Target]
		if ok1 && ok2 {
			edgePairs = append(edgePairs, [2]*GraphNode{source, target})
		}
	}

	for iter := 0; iter < config.Iterations; iter++ {
		dispX := make(map[uint]float64)
		dispY := make(map[uint]float64)

		for _, node := range nodeList {
			dispX[node.ID] = 0
			dispY[node.ID] = 0
		}

		for i := 0; i < len(nodeList); i++ {
			for j := i + 1; j < len(nodeList); j++ {
				v := nodeList[i]
				u := nodeList[j]

				dx := v.X - u.X
				dy := v.Y - u.Y
				dist := math.Sqrt(dx*dx + dy*dy)

				if dist < 0.01 {
					dist = 0.01
					dx = 0.01
					dy = 0
				}

				force := (k * k) / dist

				dispX[v.ID] += (dx / dist) * force
				dispY[v.ID] += (dy / dist) * force
				dispX[u.ID] -= (dx / dist) * force
				dispY[u.ID] -= (dy / dist) * force
			}
		}

		for _, pair := range edgePairs {
			source := pair[0]
			target := pair[1]

			dx := source.X - target.X
			dy := source.Y - target.Y
			dist := math.Sqrt(dx*dx + dy*dy)

			if dist < 0.01 {
				continue
			}

			force := (dist * dist / k) * (config.AttractiveForce / 5.0)

			dispX[source.ID] -= (dx / dist) * force
			dispY[source.ID] -= (dy / dist) * force
			dispX[target.ID] += (dx / dist) * force
			dispY[target.ID] += (dy / dist) * force
		}

		for _, node := range nodeList {
			if config.Gravity > 0 {
				dx := config.CenterX - node.X
				dy := config.CenterY - node.Y
				dist := math.Sqrt(dx*dx + dy*dy)
				if dist > 0.01 {
					gravityForce := config.Gravity * float64(node.Size)
					dispX[node.ID] += (dx / dist) * gravityForce
					dispY[node.ID] += (dy / dist) * gravityForce
				}
			}
		}

		for _, node := range nodeList {
			dispMag := math.Sqrt(dispX[node.ID]*dispX[node.ID] + dispY[node.ID]*dispY[node.ID])
			if dispMag > 0 {
				node.X += (dispX[node.ID] / dispMag) * math.Min(dispMag, temp)
				node.Y += (dispY[node.ID] / dispMag) * math.Min(dispMag, temp)
			}
		}

		temp *= config.CoolingRate
		if temp < config.MinTemp {
			temp = config.MinTemp
		}
	}
}

func (g *Graph) LayoutAnimated(config *LayoutConfig, frameInterval int) []LayoutFrame {
	if config == nil {
		config = DefaultLayoutConfig()
	}

	if len(g.Nodes) == 0 {
		return []LayoutFrame{}
	}

	g.InitRandomPositions(config.Width, config.Height)

	area := config.Width * config.Height
	k := math.Sqrt(area/float64(len(g.Nodes))) * (config.RepulsiveForce / 200.0)

	temp := config.InitialTemp

	nodeList := make([]*GraphNode, 0, len(g.Nodes))
	for _, node := range g.Nodes {
		nodeList = append(nodeList, node)
	}

	edgePairs := make([][2]*GraphNode, 0, len(g.Edges))
	for _, edge := range g.Edges {
		source, ok1 := g.Nodes[edge.Source]
		target, ok2 := g.Nodes[edge.Target]
		if ok1 && ok2 {
			edgePairs = append(edgePairs, [2]*GraphNode{source, target})
		}
	}

	frames := make([]LayoutFrame, 0)

	if frameInterval <= 0 {
		frameInterval = 10
	}

	for iter := 0; iter < config.Iterations; iter++ {
		dispX := make(map[uint]float64)
		dispY := make(map[uint]float64)

		for _, node := range nodeList {
			dispX[node.ID] = 0
			dispY[node.ID] = 0
		}

		for i := 0; i < len(nodeList); i++ {
			for j := i + 1; j < len(nodeList); j++ {
				v := nodeList[i]
				u := nodeList[j]

				dx := v.X - u.X
				dy := v.Y - u.Y
				dist := math.Sqrt(dx*dx + dy*dy)

				if dist < 0.01 {
					dist = 0.01
					dx = 0.01
					dy = 0
				}

				force := (k * k) / dist

				dispX[v.ID] += (dx / dist) * force
				dispY[v.ID] += (dy / dist) * force
				dispX[u.ID] -= (dx / dist) * force
				dispY[u.ID] -= (dy / dist) * force
			}
		}

		for _, pair := range edgePairs {
			source := pair[0]
			target := pair[1]

			dx := source.X - target.X
			dy := source.Y - target.Y
			dist := math.Sqrt(dx*dx + dy*dy)

			if dist < 0.01 {
				continue
			}

			force := (dist * dist / k) * (config.AttractiveForce / 5.0)

			dispX[source.ID] -= (dx / dist) * force
			dispY[source.ID] -= (dy / dist) * force
			dispX[target.ID] += (dx / dist) * force
			dispY[target.ID] += (dy / dist) * force
		}

		for _, node := range nodeList {
			if config.Gravity > 0 {
				dx := config.CenterX - node.X
				dy := config.CenterY - node.Y
				dist := math.Sqrt(dx*dx + dy*dy)
				if dist > 0.01 {
					gravityForce := config.Gravity * float64(node.Size)
					dispX[node.ID] += (dx / dist) * gravityForce
					dispY[node.ID] += (dy / dist) * gravityForce
				}
			}
		}

		for _, node := range nodeList {
			dispMag := math.Sqrt(dispX[node.ID]*dispX[node.ID] + dispY[node.ID]*dispY[node.ID])
			if dispMag > 0 {
				node.X += (dispX[node.ID] / dispMag) * math.Min(dispMag, temp)
				node.Y += (dispY[node.ID] / dispMag) * math.Min(dispMag, temp)
			}
		}

		temp *= config.CoolingRate
		if temp < config.MinTemp {
			temp = config.MinTemp
		}

		if iter%frameInterval == 0 || iter == config.Iterations-1 {
			frame := LayoutFrame{
				Step:  iter,
				Nodes: make([]FrameNode, 0, len(nodeList)),
			}
			for _, node := range nodeList {
				frame.Nodes = append(frame.Nodes, FrameNode{
					ID: node.ID,
					X:  node.X,
					Y:  node.Y,
				})
			}
			frames = append(frames, frame)
		}
	}

	return frames
}

func (g *Graph) Step(config *LayoutConfig, temp float64) float64 {
	if config == nil {
		config = DefaultLayoutConfig()
	}

	if len(g.Nodes) == 0 {
		return temp * config.CoolingRate
	}

	area := config.Width * config.Height
	k := math.Sqrt(area/float64(len(g.Nodes))) * (config.RepulsiveForce / 200.0)

	nodeList := make([]*GraphNode, 0, len(g.Nodes))
	for _, node := range g.Nodes {
		nodeList = append(nodeList, node)
	}

	dispX := make(map[uint]float64)
	dispY := make(map[uint]float64)

	for _, node := range nodeList {
		dispX[node.ID] = 0
		dispY[node.ID] = 0
	}

	for i := 0; i < len(nodeList); i++ {
		for j := i + 1; j < len(nodeList); j++ {
			v := nodeList[i]
			u := nodeList[j]

			dx := v.X - u.X
			dy := v.Y - u.Y
			dist := math.Sqrt(dx*dx + dy*dy)

			if dist < 0.01 {
				dist = 0.01
				dx = 0.01
				dy = 0
			}

			force := (k * k) / dist

			dispX[v.ID] += (dx / dist) * force
			dispY[v.ID] += (dy / dist) * force
			dispX[u.ID] -= (dx / dist) * force
			dispY[u.ID] -= (dy / dist) * force
		}
	}

	for _, edge := range g.Edges {
		source, ok1 := g.Nodes[edge.Source]
		target, ok2 := g.Nodes[edge.Target]
		if !ok1 || !ok2 {
			continue
		}

		dx := source.X - target.X
		dy := source.Y - target.Y
		dist := math.Sqrt(dx*dx + dy*dy)

		if dist < 0.01 {
			continue
		}

		force := (dist * dist / k) * (config.AttractiveForce / 5.0)

		dispX[source.ID] -= (dx / dist) * force
		dispY[source.ID] -= (dy / dist) * force
		dispX[target.ID] += (dx / dist) * force
		dispY[target.ID] += (dy / dist) * force
	}

	for _, node := range nodeList {
		if config.Gravity > 0 {
			dx := config.CenterX - node.X
			dy := config.CenterY - node.Y
			dist := math.Sqrt(dx*dx + dy*dy)
			if dist > 0.01 {
				gravityForce := config.Gravity * float64(node.Size)
				dispX[node.ID] += (dx / dist) * gravityForce
				dispY[node.ID] += (dy / dist) * gravityForce
			}
		}
	}

	for _, node := range nodeList {
		dispMag := math.Sqrt(dispX[node.ID]*dispX[node.ID] + dispY[node.ID]*dispY[node.ID])
		if dispMag > 0 {
			node.X += (dispX[node.ID] / dispMag) * math.Min(dispMag, temp)
			node.Y += (dispY[node.ID] / dispMag) * math.Min(dispMag, temp)
		}
	}

	return temp * config.CoolingRate
}
