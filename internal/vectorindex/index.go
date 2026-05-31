package vectorindex

import (
	"container/heap"
	"context"
	"encoding/gob"
	"errors"
	"fmt"
	"math"
	"math/rand"
	"os"
	"session154/internal/logger"
	"sort"
	"sync"

	"go.uber.org/zap"
)

type Vector []float32

type VectorID string

type VectorWithID struct {
	ID     VectorID
	Vector Vector
	Meta   map[string]interface{}
}

type IndexType string

const (
	IndexFlatL2    IndexType = "flat_l2"
	IndexFlatIP    IndexType = "flat_ip"
	IndexIVFFlat   IndexType = "ivf_flat"
	IndexHNSW      IndexType = "hnsw"
)

type DistanceType string

const (
	DistanceL2       DistanceType = "l2"
	DistanceCosine   DistanceType = "cosine"
	DistanceInnerProduct DistanceType = "ip"
)

type SearchResult struct {
	ID       VectorID
	Score    float32
	Distance float32
	Meta     map[string]interface{}
}

type VectorIndex interface {
	Build(ctx context.Context, vectors []VectorWithID) error
	Add(vectors ...VectorWithID) error
	Remove(ids ...VectorID) error
	Search(query Vector, k int, efSearch int) ([]SearchResult, error)
	Size() int
	Save(path string) error
	Load(path string) error
}

type FlatIndex struct {
	vectors      map[VectorID]VectorWithID
	idList       []VectorID
	distanceType DistanceType
	dim          int
	mu           sync.RWMutex
}

func NewFlatIndex(distanceType DistanceType, dim int) *FlatIndex {
	return &FlatIndex{
		vectors:      make(map[VectorID]VectorWithID),
		distanceType: distanceType,
		dim:          dim,
	}
}

func (idx *FlatIndex) Build(ctx context.Context, vectors []VectorWithID) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	for _, v := range vectors {
		if len(v.Vector) != idx.dim {
			return fmt.Errorf("vector dimension mismatch: expected %d, got %d", idx.dim, len(v.Vector))
		}
		idx.vectors[v.ID] = v
		idx.idList = append(idx.idList, v.ID)
	}

	logger.Info("flat index built", zap.Int("vector_count", len(idx.vectors)))
	return nil
}

func (idx *FlatIndex) Add(vectors ...VectorWithID) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	for _, v := range vectors {
		if len(v.Vector) != idx.dim {
			return fmt.Errorf("vector dimension mismatch")
		}
		if _, exists := idx.vectors[v.ID]; !exists {
			idx.idList = append(idx.idList, v.ID)
		}
		idx.vectors[v.ID] = v
	}
	return nil
}

func (idx *FlatIndex) Remove(ids ...VectorID) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	for _, id := range ids {
		delete(idx.vectors, id)
	}

	newIDList := make([]VectorID, 0, len(idx.idList))
	for _, id := range idx.idList {
		if _, exists := idx.vectors[id]; exists {
			newIDList = append(newIDList, id)
		}
	}
	idx.idList = newIDList

	return nil
}

func (idx *FlatIndex) Search(query Vector, k int, efSearch int) ([]SearchResult, error) {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	if len(query) != idx.dim {
		return nil, fmt.Errorf("query dimension mismatch")
	}

	type distancePair struct {
		id       VectorID
		distance float32
	}

	pairs := make([]distancePair, 0, len(idx.idList))

	for _, id := range idx.idList {
		vec := idx.vectors[id]
		var dist float32

		switch idx.distanceType {
		case DistanceL2:
			dist = L2Distance(query, vec.Vector)
		case DistanceCosine:
			dist = CosineDistance(query, vec.Vector)
		case DistanceInnerProduct:
			dist = -InnerProduct(query, vec.Vector)
		default:
			dist = L2Distance(query, vec.Vector)
		}

		pairs = append(pairs, distancePair{id: id, distance: dist})
	}

	sort.Slice(pairs, func(i, j int) bool {
		return pairs[i].distance < pairs[j].distance
	})

	if k > len(pairs) {
		k = len(pairs)
	}

	results := make([]SearchResult, k)
	for i := 0; i < k; i++ {
		vec := idx.vectors[pairs[i].id]
		results[i] = SearchResult{
			ID:       pairs[i].id,
			Score:    -pairs[i].distance,
			Distance: pairs[i].distance,
			Meta:     vec.Meta,
		}
	}

	return results, nil
}

func (idx *FlatIndex) Size() int {
	idx.mu.RLock()
	defer idx.mu.RUnlock()
	return len(idx.vectors)
}

func (idx *FlatIndex) Save(path string) error {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()

	enc := gob.NewEncoder(file)
	return enc.Encode(struct {
		Vectors      map[VectorID]VectorWithID
		IDList       []VectorID
		DistanceType DistanceType
		Dim          int
	}{
		Vectors:      idx.vectors,
		IDList:       idx.idList,
		DistanceType: idx.distanceType,
		Dim:          idx.dim,
	})
}

func (idx *FlatIndex) Load(path string) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()

	dec := gob.NewDecoder(file)
	var data struct {
		Vectors      map[VectorID]VectorWithID
		IDList       []VectorID
		DistanceType DistanceType
		Dim          int
	}
	if err := dec.Decode(&data); err != nil {
		return err
	}

	idx.vectors = data.Vectors
	idx.idList = data.IDList
	idx.distanceType = data.DistanceType
	idx.dim = data.Dim
	return nil
}

type HNSWNode struct {
	ID        VectorID
	Vector    Vector
	Neighbors [][]VectorID
	Layer     int
	Meta      map[string]interface{}
}

type HNSWIndex struct {
	nodes        map[VectorID]*HNSWNode
	entryPoint   VectorID
	maxLayer     int
	M            int
	efConstruction int
	efSearch     int
	distanceType DistanceType
	dim          int
	mu           sync.RWMutex
	rng          *rand.Rand
}

func NewHNSWIndex(M, efConstruction, efSearch int, distanceType DistanceType, dim int) *HNSWIndex {
	return &HNSWIndex{
		nodes:        make(map[VectorID]*HNSWNode),
		M:            M,
		efConstruction: efConstruction,
		efSearch:     efSearch,
		distanceType: distanceType,
		dim:          dim,
		rng:          rand.New(rand.NewSource(42)),
	}
}

func (idx *HNSWIndex) randomLayer() int {
	layer := 0
	for idx.rng.Float64() < 1.0/math.Exp(1.0/float64(idx.M)) && layer < 16 {
		layer++
	}
	return layer
}

func (idx *HNSWIndex) Build(ctx context.Context, vectors []VectorWithID) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	if len(vectors) == 0 {
		return errors.New("no vectors provided")
	}

	for _, v := range vectors {
		if len(v.Vector) != idx.dim {
			return fmt.Errorf("vector dimension mismatch")
		}
	}

	first := vectors[0]
	idx.nodes[first.ID] = &HNSWNode{
		ID:        first.ID,
		Vector:    first.Vector,
		Neighbors: [][]VectorID{},
		Layer:     0,
		Meta:      first.Meta,
	}
	idx.entryPoint = first.ID
	idx.maxLayer = 0

	for i := 1; i < len(vectors); i++ {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		idx.insertInternal(vectors[i])
	}

	logger.Info("hnsw index built", zap.Int("vector_count", len(idx.nodes)), zap.Int("max_layer", idx.maxLayer))
	return nil
}

func (idx *HNSWIndex) insertInternal(vec VectorWithID) {
	if len(idx.nodes) == 0 {
		idx.nodes[vec.ID] = &HNSWNode{
			ID:        vec.ID,
			Vector:    vec.Vector,
			Neighbors: [][]VectorID{},
			Layer:     0,
			Meta:      vec.Meta,
		}
		idx.entryPoint = vec.ID
		return
	}

	newLayer := idx.randomLayer()
	node := &HNSWNode{
		ID:        vec.ID,
		Vector:    vec.Vector,
		Neighbors: make([][]VectorID, newLayer+1),
		Layer:     newLayer,
		Meta:      vec.Meta,
	}

	entryPoint := idx.nodes[idx.entryPoint]
	currentNode := entryPoint

	for layer := idx.maxLayer; layer > newLayer; layer-- {
		changed := true
		for changed {
			changed = false
			for _, neighborID := range currentNode.Neighbors[layer] {
				neighbor := idx.nodes[neighborID]
				if idx.distance(vec.Vector, neighbor.Vector) < idx.distance(vec.Vector, currentNode.Vector) {
					currentNode = neighbor
					changed = true
				}
			}
		}
	}

	for layer := min(newLayer, idx.maxLayer); layer >= 0; layer-- {
		candidates := idx.searchLayer(vec.Vector, currentNode, layer, idx.efConstruction)
		neighbors := idx.selectNeighbors(candidates, idx.M)

		node.Neighbors[layer] = make([]VectorID, len(neighbors))
		for i, n := range neighbors {
			node.Neighbors[layer][i] = n.ID
			idx.nodes[n.ID].Neighbors[layer] = append(idx.nodes[n.ID].Neighbors[layer], vec.ID)

			if len(idx.nodes[n.ID].Neighbors[layer]) > idx.M*2 {
				idx.shrinkNeighbors(n.ID, layer)
			}
		}

		if len(neighbors) > 0 {
			currentNode = idx.nodes[neighbors[0].ID]
		}
	}

	if newLayer > idx.maxLayer {
		idx.entryPoint = vec.ID
		idx.maxLayer = newLayer
	}

	idx.nodes[vec.ID] = node
}

func (idx *HNSWIndex) distance(a, b Vector) float32 {
	switch idx.distanceType {
	case DistanceCosine:
		return CosineDistance(a, b)
	case DistanceInnerProduct:
		return -InnerProduct(a, b)
	default:
		return L2Distance(a, b)
	}
}

func (idx *HNSWIndex) searchLayer(query Vector, entry *HNSWNode, layer, ef int) []SearchResult {
	visited := make(map[VectorID]bool)
	candidates := &minHeap{}
	results := &maxHeap{}

	dist := idx.distance(query, entry.Vector)
	heap.Push(candidates, heapItem{id: entry.ID, distance: dist})
	heap.Push(results, heapItem{id: entry.ID, distance: dist})
	visited[entry.ID] = true

	for candidates.Len() > 0 {
		current := heap.Pop(candidates).(heapItem)
		farthest := heap.Pop(results).(heapItem)
		heap.Push(results, farthest)

		if current.distance > farthest.distance {
			break
		}

		currentNode := idx.nodes[current.id]
		for _, neighborID := range currentNode.Neighbors[layer] {
			if !visited[neighborID] {
				visited[neighborID] = true
				neighborDist := idx.distance(query, idx.nodes[neighborID].Vector)

				if results.Len() < ef || neighborDist < farthest.distance {
					heap.Push(candidates, heapItem{id: neighborID, distance: neighborDist})
					heap.Push(results, heapItem{id: neighborID, distance: neighborDist})
					if results.Len() > ef {
						heap.Pop(results)
					}
				}
			}
		}
	}

	resultSlice := make([]SearchResult, 0, results.Len())
	for results.Len() > 0 {
		item := heap.Pop(results).(heapItem)
		resultSlice = append(resultSlice, SearchResult{
			ID:       item.id,
			Score:    -item.distance,
			Distance: item.distance,
			Meta:     idx.nodes[item.id].Meta,
		})
	}

	sort.Slice(resultSlice, func(i, j int) bool {
		return resultSlice[i].Distance < resultSlice[j].Distance
	})

	return resultSlice
}

func (idx *HNSWIndex) selectNeighbors(candidates []SearchResult, M int) []SearchResult {
	if len(candidates) <= M {
		return candidates
	}

	sort.Slice(candidates, func(i, j int) bool {
		return candidates[i].Distance < candidates[j].Distance
	})

	return candidates[:M]
}

func (idx *HNSWIndex) shrinkNeighbors(nodeID VectorID, layer int) {
	node := idx.nodes[nodeID]
	neighbors := node.Neighbors[layer]

	type neighborDist struct {
		id   VectorID
		dist float32
	}

	dists := make([]neighborDist, len(neighbors))
	for i, nID := range neighbors {
		dists[i] = neighborDist{
			id:   nID,
			dist: idx.distance(node.Vector, idx.nodes[nID].Vector),
		}
	}

	sort.Slice(dists, func(i, j int) bool {
		return dists[i].dist < dists[j].dist
	})

	kept := make([]VectorID, idx.M)
	for i := 0; i < idx.M; i++ {
		kept[i] = dists[i].id
	}

	node.Neighbors[layer] = kept
}

func (idx *HNSWIndex) Add(vectors ...VectorWithID) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	for _, v := range vectors {
		if len(v.Vector) != idx.dim {
			return fmt.Errorf("vector dimension mismatch")
		}
		idx.insertInternal(v)
	}
	return nil
}

func (idx *HNSWIndex) Remove(ids ...VectorID) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	for _, id := range ids {
		if _, exists := idx.nodes[id]; !exists {
			continue
		}

		node := idx.nodes[id]
		for layer := 0; layer <= node.Layer; layer++ {
			for _, neighborID := range node.Neighbors[layer] {
				neighbor := idx.nodes[neighborID]
				newNeighbors := make([]VectorID, 0, len(neighbor.Neighbors[layer]))
				for _, n := range neighbor.Neighbors[layer] {
					if n != id {
						newNeighbors = append(newNeighbors, n)
					}
				}
				neighbor.Neighbors[layer] = newNeighbors
			}
		}

		delete(idx.nodes, id)

		if idx.entryPoint == id && len(idx.nodes) > 0 {
			for newID := range idx.nodes {
				idx.entryPoint = newID
				break
			}
		}
	}

	return nil
}

func (idx *HNSWIndex) Search(query Vector, k int, efSearch int) ([]SearchResult, error) {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	if len(query) != idx.dim {
		return nil, fmt.Errorf("query dimension mismatch")
	}

	if len(idx.nodes) == 0 {
		return []SearchResult{}, nil
	}

	if efSearch == 0 {
		efSearch = idx.efSearch
	}

	entryPoint := idx.nodes[idx.entryPoint]
	currentNode := entryPoint

	for layer := idx.maxLayer; layer > 0; layer-- {
		changed := true
		for changed {
			changed = false
			if len(currentNode.Neighbors) <= layer {
				break
			}
			for _, neighborID := range currentNode.Neighbors[layer] {
				neighbor := idx.nodes[neighborID]
				if idx.distance(query, neighbor.Vector) < idx.distance(query, currentNode.Vector) {
					currentNode = neighbor
					changed = true
				}
			}
		}
	}

	results := idx.searchLayer(query, currentNode, 0, max(efSearch, k))

	if k > len(results) {
		k = len(results)
	}

	return results[:k], nil
}

func (idx *HNSWIndex) Size() int {
	idx.mu.RLock()
	defer idx.mu.RUnlock()
	return len(idx.nodes)
}

func (idx *HNSWIndex) Save(path string) error {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()

	saveNodes := make(map[VectorID]struct {
		Vector    Vector
		Neighbors [][]VectorID
		Layer     int
		Meta      map[string]interface{}
	})

	for id, node := range idx.nodes {
		saveNodes[id] = struct {
			Vector    Vector
			Neighbors [][]VectorID
			Layer     int
			Meta      map[string]interface{}
		}{
			Vector:    node.Vector,
			Neighbors: node.Neighbors,
			Layer:     node.Layer,
			Meta:      node.Meta,
		}
	}

	enc := gob.NewEncoder(file)
	return enc.Encode(struct {
		Nodes          map[VectorID]struct { Vector Vector; Neighbors [][]VectorID; Layer int; Meta map[string]interface{} }
		EntryPoint     VectorID
		MaxLayer       int
		M              int
		EfConstruction int
		EfSearch       int
		DistanceType   DistanceType
		Dim            int
	}{
		Nodes:          saveNodes,
		EntryPoint:     idx.entryPoint,
		MaxLayer:       idx.maxLayer,
		M:              idx.M,
		EfConstruction: idx.efConstruction,
		EfSearch:       idx.efSearch,
		DistanceType:   idx.distanceType,
		Dim:            idx.dim,
	})
}

func (idx *HNSWIndex) Load(path string) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()

	dec := gob.NewDecoder(file)
	var data struct {
		Nodes          map[VectorID]struct { Vector Vector; Neighbors [][]VectorID; Layer int; Meta map[string]interface{} }
		EntryPoint     VectorID
		MaxLayer       int
		M              int
		EfConstruction int
		EfSearch       int
		DistanceType   DistanceType
		Dim            int
	}
	if err := dec.Decode(&data); err != nil {
		return err
	}

	idx.nodes = make(map[VectorID]*HNSWNode)
	for id, n := range data.Nodes {
		idx.nodes[id] = &HNSWNode{
			ID:        id,
			Vector:    n.Vector,
			Neighbors: n.Neighbors,
			Layer:     n.Layer,
			Meta:      n.Meta,
		}
	}

	idx.entryPoint = data.EntryPoint
	idx.maxLayer = data.MaxLayer
	idx.M = data.M
	idx.efConstruction = data.EfConstruction
	idx.efSearch = data.EfSearch
	idx.distanceType = data.DistanceType
	idx.dim = data.Dim
	return nil
}

type heapItem struct {
	id       VectorID
	distance float32
}

type minHeap []heapItem

func (h minHeap) Len() int            { return len(h) }
func (h minHeap) Less(i, j int) bool  { return h[i].distance < h[j].distance }
func (h minHeap) Swap(i, j int)       { h[i], h[j] = h[j], h[i] }
func (h *minHeap) Push(x interface{}) { *h = append(*h, x.(heapItem)) }
func (h *minHeap) Pop() interface{} {
	old := *h
	n := len(old)
	item := old[n-1]
	*h = old[0 : n-1]
	return item
}

type maxHeap []heapItem

func (h maxHeap) Len() int            { return len(h) }
func (h maxHeap) Less(i, j int) bool  { return h[i].distance > h[j].distance }
func (h maxHeap) Swap(i, j int)       { h[i], h[j] = h[j], h[i] }
func (h *maxHeap) Push(x interface{}) { *h = append(*h, x.(heapItem)) }
func (h *maxHeap) Pop() interface{} {
	old := *h
	n := len(old)
	item := old[n-1]
	*h = old[0 : n-1]
	return item
}

func L2Distance(a, b Vector) float32 {
	var sum float32
	for i := range a {
		diff := a[i] - b[i]
		sum += diff * diff
	}
	return sum
}

func CosineDistance(a, b Vector) float32 {
	var dot, normA, normB float32
	for i := range a {
		dot += a[i] * b[i]
		normA += a[i] * a[i]
		normB += b[i] * b[i]
	}
	if normA == 0 || normB == 0 {
		return 1.0
	}
	return 1.0 - dot/float32(math.Sqrt(float64(normA*normB)))
}

func InnerProduct(a, b Vector) float32 {
	var sum float32
	for i := range a {
		sum += a[i] * b[i]
	}
	return sum
}

func Normalize(v Vector) Vector {
	var norm float32
	for _, x := range v {
		norm += x * x
	}
	if norm == 0 {
		return v
	}
	norm = float32(math.Sqrt(float64(norm)))
	result := make(Vector, len(v))
	for i, x := range v {
		result[i] = x / norm
	}
	return result
}

type IndexBuilder struct {
	indexType   IndexType
	distanceType DistanceType
	dim         int
	params      map[string]interface{}
}

func NewIndexBuilder(indexType IndexType, distanceType DistanceType, dim int) *IndexBuilder {
	return &IndexBuilder{
		indexType:   indexType,
		distanceType: distanceType,
		dim:         dim,
		params:      make(map[string]interface{}),
	}
}

func (b *IndexBuilder) SetParam(key string, value interface{}) *IndexBuilder {
	b.params[key] = value
	return b
}

func (b *IndexBuilder) Build(ctx context.Context, vectors []VectorWithID) (VectorIndex, error) {
	var idx VectorIndex

	switch b.indexType {
	case IndexFlatL2:
		idx = NewFlatIndex(DistanceL2, b.dim)
	case IndexFlatIP:
		idx = NewFlatIndex(DistanceInnerProduct, b.dim)
	case IndexHNSW:
		M := 16
		efConstruction := 100
		efSearch := 50
		if v, ok := b.params["M"].(int); ok {
			M = v
		}
		if v, ok := b.params["efConstruction"].(int); ok {
			efConstruction = v
		}
		if v, ok := b.params["efSearch"].(int); ok {
			efSearch = v
		}
		idx = NewHNSWIndex(M, efConstruction, efSearch, b.distanceType, b.dim)
	default:
		idx = NewFlatIndex(b.distanceType, b.dim)
	}

	if err := idx.Build(ctx, vectors); err != nil {
		return nil, err
	}

	return idx, nil
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}
