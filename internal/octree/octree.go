package octree

import (
	"math"
	"pointcloud-platform/config"
	"pointcloud-platform/internal/parser"
	"pointcloud-platform/pkg/math3d"
	"sync"
)

type OctreeNode struct {
	ID         uint64
	Depth      int
	Bounds     math3d.AABB
	Points     []parser.Point
	PointCount int
	Children   [8]*OctreeNode
	Parent     *OctreeNode
	IsLeaf     bool
	MortonCode uint64
	Center     math3d.Vec3
	HalfSize   math3d.Vec3
}

type Octree struct {
	Root             *OctreeNode
	MaxDepth         int
	MinPointsPerNode int
	MaxPointsPerNode int
	TotalPoints      uint64
	GlobalBounds     math3d.AABB
	NodeCount        uint64
	LeafCount        uint64
	mu               sync.RWMutex
}

type Tile struct {
	DatasetID  string
	LOD        int
	X, Y, Z    int64
	Bounds     math3d.AABB
	PointCount int
	Points     []parser.Point
	Center     math3d.Vec3
	ParentKey  string
	ChildKeys  []string
}

type TileKey struct {
	LOD   int
	X, Y, Z int64
}

func NewOctree(cfg *config.OctreeConfig, bounds math3d.AABB) *Octree {
	return &Octree{
		MaxDepth:         cfg.MaxDepth,
		MinPointsPerNode: cfg.MinPointsPerNode,
		MaxPointsPerNode: cfg.MaxPointsPerNode,
		GlobalBounds:     bounds,
		Root: &OctreeNode{
			Depth:    0,
			Bounds:   bounds,
			IsLeaf:   true,
			Center:   bounds.Center(),
			HalfSize: bounds.Size().Mul(0.5),
		},
	}
}

func (o *Octree) Insert(p parser.Point) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.insert(o.Root, p, 0)
}

func (o *Octree) insert(node *OctreeNode, p parser.Point, depth int) {
	pointVec := math3d.Vec3{X: p.X, Y: p.Y, Z: p.Z}
	if !node.Bounds.Contains(pointVec) {
		return
	}

	node.PointCount++
	node.Points = append(node.Points, p)
	o.TotalPoints++

	if depth >= o.MaxDepth || node.PointCount <= o.MaxPointsPerNode {
		return
	}

	if node.IsLeaf {
		o.splitNode(node)
	}

	childIndex := o.getChildIndex(node, pointVec)
	node.Points = nil
	o.insert(node.Children[childIndex], p, depth+1)
}

func (o *Octree) splitNode(node *OctreeNode) {
	node.IsLeaf = false
	o.NodeCount += 8
	o.LeafCount--

	center := node.Bounds.Center()
	min := node.Bounds.Min
	max := node.Bounds.Max

	childrenBounds := []math3d.AABB{
		{Min: min, Max: center},
		{Min: math3d.Vec3{X: center.X, Y: min.Y, Z: min.Z}, Max: math3d.Vec3{X: max.X, Y: center.Y, Z: center.Z}},
		{Min: math3d.Vec3{X: min.X, Y: center.Y, Z: min.Z}, Max: math3d.Vec3{X: center.X, Y: max.Y, Z: center.Z}},
		{Min: math3d.Vec3{X: center.X, Y: center.Y, Z: min.Z}, Max: math3d.Vec3{X: max.X, Y: max.Y, Z: center.Z}},
		{Min: math3d.Vec3{X: min.X, Y: min.Y, Z: center.Z}, Max: math3d.Vec3{X: center.X, Y: center.Y, Z: max.Z}},
		{Min: math3d.Vec3{X: center.X, Y: min.Y, Z: center.Z}, Max: math3d.Vec3{X: max.X, Y: center.Y, Z: max.Z}},
		{Min: math3d.Vec3{X: min.X, Y: center.Y, Z: center.Z}, Max: math3d.Vec3{X: center.X, Y: max.Y, Z: max.Z}},
		{Min: center, Max: max},
	}

	for i := 0; i < 8; i++ {
		node.Children[i] = &OctreeNode{
			Depth:    node.Depth + 1,
			Bounds:   childrenBounds[i],
			IsLeaf:   true,
			Parent:   node,
			Center:   childrenBounds[i].Center(),
			HalfSize: childrenBounds[i].Size().Mul(0.5),
		}
		o.LeafCount++
	}

	for _, p := range node.Points {
		pointVec := math3d.Vec3{X: p.X, Y: p.Y, Z: p.Z}
		childIndex := o.getChildIndex(node, pointVec)
		node.Children[childIndex].Points = append(node.Children[childIndex].Points, p)
		node.Children[childIndex].PointCount++
	}
	node.Points = nil

	for i := 0; i < 8; i++ {
		if node.Children[i].PointCount > o.MaxPointsPerNode && node.Depth+1 < o.MaxDepth {
			o.splitNode(node.Children[i])
		}
	}
}

func (o *Octree) getChildIndex(node *OctreeNode, p math3d.Vec3) int {
	center := node.Bounds.Center()
	index := 0
	if p.X >= center.X {
		index |= 1
	}
	if p.Y >= center.Y {
		index |= 2
	}
	if p.Z >= center.Z {
		index |= 4
	}
	return index
}

func (o *Octree) BuildFromPoints(points []parser.Point) {
	for _, p := range points {
		o.Insert(p)
	}
}

func (o *Octree) QueryByFrustum(frustum *math3d.Frustum, maxPoints int) []*OctreeNode {
	o.mu.RLock()
	defer o.mu.RUnlock()

	var result []*OctreeNode
	queue := []*OctreeNode{o.Root}

	for len(queue) > 0 && len(result) < maxPoints {
		node := queue[0]
		queue = queue[1:]

		if !frustum.IntersectsAABB(node.Bounds) {
			continue
		}

		if node.IsLeaf || node.Depth >= o.MaxDepth-2 {
			result = append(result, node)
			continue
		}

		for _, child := range node.Children {
			if child != nil {
				queue = append(queue, child)
			}
		}
	}

	return result
}

func (o *Octree) QueryByAABB(aabb math3d.AABB) []*OctreeNode {
	o.mu.RLock()
	defer o.mu.RUnlock()

	var result []*OctreeNode
	o.queryByAABB(o.Root, aabb, &result)
	return result
}

func (o *Octree) queryByAABB(node *OctreeNode, aabb math3d.AABB, result *[]*OctreeNode) {
	if !node.Bounds.Intersects(aabb) {
		return
	}

	if node.IsLeaf {
		*result = append(*result, node)
		return
	}

	for _, child := range node.Children {
		if child != nil {
			o.queryByAABB(child, aabb, result)
		}
	}
}

func (o *Octree) GetAllLeafNodes() []*OctreeNode {
	o.mu.RLock()
	defer o.mu.RUnlock()

	var leaves []*OctreeNode
	var collect func(*OctreeNode)
	collect = func(node *OctreeNode) {
		if node == nil {
			return
		}
		if node.IsLeaf {
			leaves = append(leaves, node)
			return
		}
		for _, child := range node.Children {
			collect(child)
		}
	}
	collect(o.Root)
	return leaves
}

func (o *Octree) GetNodeAtPosition(lod int, x, y, z int64) *OctreeNode {
	levelSize := float64(1 << uint(lod))
	nodeSize := o.GlobalBounds.Size().Div(levelSize)

	min := o.GlobalBounds.Min
	nodeMin := math3d.Vec3{
		X: min.X + float64(x)*nodeSize.X,
		Y: min.Y + float64(y)*nodeSize.Y,
		Z: min.Z + float64(z)*nodeSize.Z,
	}
	nodeMax := math3d.Vec3{
		X: nodeMin.X + nodeSize.X,
		Y: nodeMin.Y + nodeSize.Y,
		Z: nodeMin.Z + nodeSize.Z,
	}
	targetAABB := math3d.NewAABB(nodeMin, nodeMax)

	o.mu.RLock()
	defer o.mu.RUnlock()

	current := o.Root
	for current != nil && current.Depth < lod {
		if current.IsLeaf {
			break
		}
		center := current.Bounds.Center()
		idx := 0
		if targetAABB.Min.X >= center.X {
			idx |= 1
		}
		if targetAABB.Min.Y >= center.Y {
			idx |= 2
		}
		if targetAABB.Min.Z >= center.Z {
			idx |= 4
		}
		current = current.Children[idx]
	}

	return current
}

func EncodeMorton3D(x, y, z uint32) uint64 {
	var code uint64 = 0
	for i := 0; i < 21; i++ {
		code |= uint64((x>>i)&1) << uint(3*i)
		code |= uint64((y>>i)&1) << uint(3*i+1)
		code |= uint64((z>>i)&1) << uint(3*i+2)
	}
	return code
}

func DecodeMorton3D(code uint64) (x, y, z uint32) {
	x = 0
	y = 0
	z = 0
	for i := 0; i < 21; i++ {
		x |= uint32((code>>uint(3*i))&1) << uint(i)
		y |= uint32((code>>uint(3*i+1))&1) << uint(i)
		z |= uint32((code>>uint(3*i+2))&1) << uint(i)
	}
	return
}
