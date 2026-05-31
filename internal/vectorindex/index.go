package vectorindex

import (
	"encoding/json"
	"fmt"
	"math"
	"math/rand"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"
	"streamsql/internal/common/logger"
)

type Vector struct {
	ID       string
	Values   []float32
	Metadata map[string]interface{}
}

type DistanceMetric string

const (
	MetricCosine     DistanceMetric = "cosine"
	MetricEuclidean  DistanceMetric = "euclidean"
	MetricDotProduct DistanceMetric = "dot_product"
	MetricManhattan  DistanceMetric = "manhattan"
)

type SearchResult struct {
	Vector   Vector
	Distance float32
}

type VectorIndex interface {
	Build(vectors []Vector) error
	Add(vector Vector) error
	AddBatch(vectors []Vector) error
	Search(query []float32, k int, efSearch int) ([]SearchResult, error)
	Delete(id string) error
	Size() int
	Save(path string) error
	Load(path string) error
	Name() string
}

type FlatIndex struct {
	vectors     map[string]Vector
	metric      DistanceMetric
	mu          sync.RWMutex
	dimensions  int
}

func NewFlatIndex(dimensions int, metric DistanceMetric) *FlatIndex {
	return &FlatIndex{
		vectors:    make(map[string]Vector),
		metric:     metric,
		dimensions: dimensions,
	}
}

func (idx *FlatIndex) Name() string {
	return "flat"
}

func (idx *FlatIndex) Build(vectors []Vector) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	idx.vectors = make(map[string]Vector)
	for _, v := range vectors {
		if len(v.Values) != idx.dimensions {
			return fmt.Errorf("vector dimension mismatch: expected %d, got %d", idx.dimensions, len(v.Values))
		}
		if v.ID == "" {
			v.ID = uuid.New().String()
		}
		idx.vectors[v.ID] = v
	}
	return nil
}

func (idx *FlatIndex) Add(vector Vector) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	if len(vector.Values) != idx.dimensions {
		return fmt.Errorf("vector dimension mismatch: expected %d, got %d", idx.dimensions, len(vector.Values))
	}
	if vector.ID == "" {
		vector.ID = uuid.New().String()
	}
	idx.vectors[vector.ID] = vector
	return nil
}

func (idx *FlatIndex) AddBatch(vectors []Vector) error {
	for _, v := range vectors {
		if err := idx.Add(v); err != nil {
			return err
		}
	}
	return nil
}

func (idx *FlatIndex) Search(query []float32, k int, _ int) ([]SearchResult, error) {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	if len(query) != idx.dimensions {
		return nil, fmt.Errorf("query dimension mismatch: expected %d, got %d", idx.dimensions, len(query))
	}

	results := make([]SearchResult, 0, len(idx.vectors))
	for _, v := range idx.vectors {
		dist := idx.calculateDistance(query, v.Values)
		results = append(results, SearchResult{
			Vector:   v,
			Distance: dist,
		})
	}

	sort.Slice(results, func(i, j int) bool {
		return results[i].Distance < results[j].Distance
	})

	if k > len(results) {
		k = len(results)
	}

	return results[:k], nil
}

func (idx *FlatIndex) Delete(id string) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()
	delete(idx.vectors, id)
	return nil
}

func (idx *FlatIndex) Size() int {
	idx.mu.RLock()
	defer idx.mu.RUnlock()
	return len(idx.vectors)
}

func (idx *FlatIndex) Save(path string) error {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	data := struct {
		Metric     DistanceMetric `json:"metric"`
		Dimensions int            `json:"dimensions"`
		Vectors    []Vector       `json:"vectors"`
	}{
		Metric:     idx.metric,
		Dimensions: idx.dimensions,
		Vectors:    make([]Vector, 0, len(idx.vectors)),
	}

	for _, v := range idx.vectors {
		data.Vectors = append(data.Vectors, v)
	}

	jsonData, err := json.Marshal(data)
	if err != nil {
		return err
	}

	return os.WriteFile(path, jsonData, 0644)
}

func (idx *FlatIndex) Load(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	var loaded struct {
		Metric     DistanceMetric `json:"metric"`
		Dimensions int            `json:"dimensions"`
		Vectors    []Vector       `json:"vectors"`
	}

	if err := json.Unmarshal(data, &loaded); err != nil {
		return err
	}

	idx.mu.Lock()
	defer idx.mu.Unlock()

	idx.metric = loaded.Metric
	idx.dimensions = loaded.Dimensions
	idx.vectors = make(map[string]Vector)
	for _, v := range loaded.Vectors {
		idx.vectors[v.ID] = v
	}

	return nil
}

func (idx *FlatIndex) calculateDistance(a, b []float32) float32 {
	switch idx.metric {
	case MetricCosine:
		return cosineDistance(a, b)
	case MetricEuclidean:
		return euclideanDistance(a, b)
	case MetricDotProduct:
		return -dotProduct(a, b)
	case MetricManhattan:
		return manhattanDistance(a, b)
	default:
		return euclideanDistance(a, b)
	}
}

type HNSWNode struct {
	ID        string
	Vector    []float32
	Neighbors [][]int
}

type HNSWIndex struct {
	nodes       []HNSWNode
	idToIdx     map[string]int
	metric      DistanceMetric
	M           int
	Mmax        int
	EfConstruction int
	entryPoint  int
	dimensions  int
	mu          sync.RWMutex
	rand        *rand.Rand
}

func NewHNSWIndex(dimensions int, metric DistanceMetric, M, efConstruction int) *HNSWIndex {
	return &HNSWIndex{
		nodes:          make([]HNSWNode, 0),
		idToIdx:        make(map[string]int),
		metric:         metric,
		M:              M,
		Mmax:           M * 2,
		EfConstruction: efConstruction,
		dimensions:     dimensions,
		entryPoint:     -1,
		rand:           rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (idx *HNSWIndex) Name() string {
	return "hnsw"
}

func (idx *HNSWIndex) Build(vectors []Vector) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	idx.nodes = make([]HNSWNode, 0)
	idx.idToIdx = make(map[string]int)
	idx.entryPoint = -1

	for _, v := range vectors {
		if len(v.Values) != idx.dimensions {
			return fmt.Errorf("vector dimension mismatch")
		}
		if v.ID == "" {
			v.ID = uuid.New().String()
		}
		idx.addInternal(v)
	}
	return nil
}

func (idx *HNSWIndex) Add(vector Vector) error {
	idx.mu.Lock()
	defer idx.mu.Unlock()

	if len(vector.Values) != idx.dimensions {
		return fmt.Errorf("vector dimension mismatch")
	}
	if vector.ID == "" {
		vector.ID = uuid.New().String()
	}
	idx.addInternal(vector)
	return nil
}

func (idx *HNSWIndex) addInternal(vector Vector) {
	layer := 0
	for idx.rand.Float64() < 0.5 {
		layer++
	}

	node := HNSWNode{
		ID:        vector.ID,
		Vector:    vector.Values,
		Neighbors: make([][]int, layer+1),
	}

	idx.nodes = append(idx.nodes, node)
	nodeIdx := len(idx.nodes) - 1
	idx.idToIdx[vector.ID] = nodeIdx

	if idx.entryPoint == -1 {
		idx.entryPoint = nodeIdx
		return
	}

	entry := idx.entryPoint
	for l := layer + 1; l < len(idx.nodes[entry].Neighbors); l++ {
		entry = idx.searchLayer(vector.Values, entry, 1, l)[0]
	}

	for l := min(layer, len(idx.nodes[entry].Neighbors)-1); l >= 0; l-- {
		candidates := idx.searchLayer(vector.Values, entry, idx.EfConstruction, l)
		neighbors := idx.selectNeighbors(vector.Values, candidates, idx.M, l)

		for _, n := range neighbors {
			idx.nodes[n].Neighbors[l] = append(idx.nodes[n].Neighbors[l], nodeIdx)
			if len(idx.nodes[n].Neighbors[l]) > idx.Mmax {
				idx.nodes[n].Neighbors[l] = idx.selectNeighbors(
					idx.nodes[n].Vector,
					idx.nodes[n].Neighbors[l],
					idx.Mmax,
					l,
				)
			}
		}

		idx.nodes[nodeIdx].Neighbors[l] = neighbors
		entry = candidates[0]
	}

	if layer >= len(idx.nodes[idx.entryPoint].Neighbors) {
		idx.entryPoint = nodeIdx
	}
}

func (idx *HNSWIndex) searchLayer(query []float32, entryPoint int, ef int, layer int) []int {
	visited := make(map[int]bool)
	candidates := []int{entryPoint}
	results := []int{entryPoint}
	visited[entryPoint] = true

	for len(candidates) > 0 {
		best := candidates[0]
		bestDist := idx.distance(query, idx.nodes[best].Vector)
		farthest := results[len(results)-1]
		farthestDist := idx.distance(query, idx.nodes[farthest].Vector)

		if bestDist > farthestDist {
			break
		}

		candidates = candidates[1:]
		for _, neighbor := range idx.nodes[best].Neighbors[layer] {
			if !visited[neighbor] {
				visited[neighbor] = true
				dist := idx.distance(query, idx.nodes[neighbor].Vector)
				if dist < farthestDist || len(results) < ef {
					candidates = append(candidates, neighbor)
					results = append(results, neighbor)
					if len(results) > ef {
						results = results[:ef]
					}
				}
			}
		}
	}

	return results
}

func (idx *HNSWIndex) selectNeighbors(query []float32, candidates []int, M int, _ int) []int {
	type candidateDist struct {
		idx int
		dist float32
	}

	dists := make([]candidateDist, len(candidates))
	for i, c := range candidates {
		dists[i] = candidateDist{
			idx:  c,
			dist: idx.distance(query, idx.nodes[c].Vector),
		}
	}

	sort.Slice(dists, func(i, j int) bool {
		return dists[i].dist < dists[j].dist
	})

	result := make([]int, min(M, len(dists)))
	for i := range result {
		result[i] = dists[i].idx
	}

	return result
}

func (idx *HNSWIndex) AddBatch(vectors []Vector) error {
	for _, v := range vectors {
		if err := idx.Add(v); err != nil {
			return err
		}
	}
	return nil
}

func (idx *HNSWIndex) Search(query []float32, k int, efSearch int) ([]SearchResult, error) {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	if len(query) != idx.dimensions {
		return nil, fmt.Errorf("query dimension mismatch")
	}

	if idx.entryPoint == -1 {
		return []SearchResult{}, nil
	}

	entry := idx.entryPoint
	for l := len(idx.nodes[entry].Neighbors) - 1; l > 0; l-- {
		entry = idx.searchLayer(query, entry, 1, l)[0]
	}

	candidates := idx.searchLayer(query, entry, efSearch, 0)
	results := make([]SearchResult, 0, min(k, len(candidates)))

	for i, c := range candidates {
		if i >= k {
			break
		}
		results = append(results, SearchResult{
			Vector: Vector{
				ID:     idx.nodes[c].ID,
				Values: idx.nodes[c].Vector,
			},
			Distance: idx.distance(query, idx.nodes[c].Vector),
		})
	}

	sort.Slice(results, func(i, j int) bool {
		return results[i].Distance < results[j].Distance
	})

	return results, nil
}

func (idx *HNSWIndex) Delete(id string) error {
	return fmt.Errorf("delete not supported in HNSW index")
}

func (idx *HNSWIndex) Size() int {
	idx.mu.RLock()
	defer idx.mu.RUnlock()
	return len(idx.nodes)
}

func (idx *HNSWIndex) Save(path string) error {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	data := struct {
		Metric         DistanceMetric `json:"metric"`
		Dimensions     int            `json:"dimensions"`
		M              int            `json:"M"`
		EfConstruction int            `json:"ef_construction"`
		Nodes          []HNSWNode     `json:"nodes"`
		EntryPoint     int            `json:"entry_point"`
	}{
		Metric:         idx.metric,
		Dimensions:     idx.dimensions,
		M:              idx.M,
		EfConstruction: idx.EfConstruction,
		Nodes:          idx.nodes,
		EntryPoint:     idx.entryPoint,
	}

	jsonData, err := json.Marshal(data)
	if err != nil {
		return err
	}

	return os.WriteFile(path, jsonData, 0644)
}

func (idx *HNSWIndex) Load(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	var loaded struct {
		Metric         DistanceMetric `json:"metric"`
		Dimensions     int            `json:"dimensions"`
		M              int            `json:"M"`
		EfConstruction int            `json:"ef_construction"`
		Nodes          []HNSWNode     `json:"nodes"`
		EntryPoint     int            `json:"entry_point"`
	}

	if err := json.Unmarshal(data, &loaded); err != nil {
		return err
	}

	idx.mu.Lock()
	defer idx.mu.Unlock()

	idx.metric = loaded.Metric
	idx.dimensions = loaded.Dimensions
	idx.M = loaded.M
	idx.EfConstruction = loaded.EfConstruction
	idx.nodes = loaded.Nodes
	idx.entryPoint = loaded.EntryPoint
	idx.idToIdx = make(map[string]int)
	for i, n := range loaded.Nodes {
		idx.idToIdx[n.ID] = i
	}

	return nil
}

func (idx *HNSWIndex) distance(a, b []float32) float32 {
	switch idx.metric {
	case MetricCosine:
		return cosineDistance(a, b)
	case MetricEuclidean:
		return euclideanDistance(a, b)
	case MetricDotProduct:
		return -dotProduct(a, b)
	case MetricManhattan:
		return manhattanDistance(a, b)
	default:
		return euclideanDistance(a, b)
	}
}

func cosineDistance(a, b []float32) float32 {
	dot := float32(0.0)
	normA := float32(0.0)
	normB := float32(0.0)
	for i := range a {
		dot += a[i] * b[i]
		normA += a[i] * a[i]
		normB += b[i] * b[i]
	}
	if normA == 0 || normB == 0 {
		return 1.0
	}
	return 1.0 - dot/float32(math.Sqrt(float64(normA))*math.Sqrt(float64(normB)))
}

func euclideanDistance(a, b []float32) float32 {
	sum := float32(0.0)
	for i := range a {
		diff := a[i] - b[i]
		sum += diff * diff
	}
	return float32(math.Sqrt(float64(sum)))
}

func dotProduct(a, b []float32) float32 {
	sum := float32(0.0)
	for i := range a {
		sum += a[i] * b[i]
	}
	return sum
}

func manhattanDistance(a, b []float32) float32 {
	sum := float32(0.0)
	for i := range a {
		sum += float32(math.Abs(float64(a[i] - b[i])))
	}
	return sum
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

type IVFIndex struct {
	vectors     []Vector
	centroids   [][]float32
	assignments []int
	metric      DistanceMetric
	nlist       int
	dimensions  int
	built       bool
	mu          sync.RWMutex
}

func NewIVFIndex(dimensions int, metric DistanceMetric, nlist int) *IVFIndex {
	return &IVFIndex{
		vectors:    make([]Vector, 0),
		centroids:  make([][]float32, nlist),
		metric:     metric,
		nlist:      nlist,
		dimensions: dimensions,
	}
}

func (idx *IVFIndex) Name() string {
	return "ivf"
}

func (idx *IVFIndex) Build(vectors []Vector) error {
	if len(vectors) < idx.nlist {
		return fmt.Errorf("not enough vectors to build index")
	}

	idx.mu.Lock()
	defer idx.mu.Unlock()

	idx.vectors = make([]Vector, len(vectors))
	copy(idx.vectors, vectors)

	idx.kmeans()
	idx.assignAll()
	idx.built = true

	return nil
}

func (idx *IVFIndex) kmeans() {
	rand.Seed(time.Now().UnixNano())
	idx.centroids = make([][]float32, idx.nlist)
	for i := range idx.centroids {
		randIdx := rand.Intn(len(idx.vectors))
		idx.centroids[i] = make([]float32, idx.dimensions)
		copy(idx.centroids[i], idx.vectors[randIdx].Values)
	}

	for iter := 0; iter < 100; iter++ {
		assignments := make([]int, len(idx.vectors))
		for i, v := range idx.vectors {
			best := 0
			bestDist := float32(math.MaxFloat32)
			for j, c := range idx.centroids {
				dist := idx.distance(v.Values, c)
				if dist < bestDist {
					bestDist = dist
					best = j
				}
			}
			assignments[i] = best
		}

		newCentroids := make([][]float32, idx.nlist)
		counts := make([]int, idx.nlist)
		for i := range newCentroids {
			newCentroids[i] = make([]float32, idx.dimensions)
		}

		for i, v := range idx.vectors {
			cluster := assignments[i]
			for d := range v.Values {
				newCentroids[cluster][d] += v.Values[d]
			}
			counts[cluster]++
		}

		maxDiff := float32(0.0)
		for i := range newCentroids {
			if counts[i] > 0 {
				for d := range newCentroids[i] {
					newCentroids[i][d] /= float32(counts[i])
				}
				diff := idx.distance(idx.centroids[i], newCentroids[i])
				if diff > maxDiff {
					maxDiff = diff
				}
				idx.centroids[i] = newCentroids[i]
			}
		}

		if maxDiff < 1e-6 {
			break
		}
	}
}

func (idx *IVFIndex) assignAll() {
	idx.assignments = make([]int, len(idx.vectors))
	for i, v := range idx.vectors {
		best := 0
		bestDist := float32(math.MaxFloat32)
		for j, c := range idx.centroids {
			dist := idx.distance(v.Values, c)
			if dist < bestDist {
				bestDist = dist
				best = j
			}
		}
		idx.assignments[i] = best
	}
}

func (idx *IVFIndex) Add(vector Vector) error {
	return fmt.Errorf("dynamic add not supported in IVF index, rebuild required")
}

func (idx *IVFIndex) AddBatch(vectors []Vector) error {
	return fmt.Errorf("dynamic add not supported in IVF index, rebuild required")
}

func (idx *IVFIndex) Search(query []float32, k int, nprobe int) ([]SearchResult, error) {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	if !idx.built {
		return nil, fmt.Errorf("index not built")
	}

	centroidDists := make([]struct {
		idx int
		dist float32
	}, idx.nlist)
	for i, c := range idx.centroids {
		centroidDists[i] = struct {
			idx int
			dist float32
		}{i, idx.distance(query, c)}
	}

	sort.Slice(centroidDists, func(i, j int) bool {
		return centroidDists[i].dist < centroidDists[j].dist
	})

	if nprobe > idx.nlist {
		nprobe = idx.nlist
	}

	candidateClusters := make(map[int]bool)
	for i := 0; i < nprobe; i++ {
		candidateClusters[centroidDists[i].idx] = true
	}

	results := make([]SearchResult, 0)
	for i, v := range idx.vectors {
		if candidateClusters[idx.assignments[i]] {
			results = append(results, SearchResult{
				Vector:   v,
				Distance: idx.distance(query, v.Values),
			})
		}
	}

	sort.Slice(results, func(i, j int) bool {
		return results[i].Distance < results[j].Distance
	})

	if k > len(results) {
		k = len(results)
	}

	return results[:k], nil
}

func (idx *IVFIndex) Delete(id string) error {
	return fmt.Errorf("delete not supported in IVF index")
}

func (idx *IVFIndex) Size() int {
	idx.mu.RLock()
	defer idx.mu.RUnlock()
	return len(idx.vectors)
}

func (idx *IVFIndex) Save(path string) error {
	idx.mu.RLock()
	defer idx.mu.RUnlock()

	data := struct {
		Metric      DistanceMetric `json:"metric"`
		Dimensions  int            `json:"dimensions"`
		Nlist       int            `json:"nlist"`
		Vectors     []Vector       `json:"vectors"`
		Centroids   [][]float32    `json:"centroids"`
		Assignments []int          `json:"assignments"`
		Built       bool           `json:"built"`
	}{
		Metric:      idx.metric,
		Dimensions:  idx.dimensions,
		Nlist:       idx.nlist,
		Vectors:     idx.vectors,
		Centroids:   idx.centroids,
		Assignments: idx.assignments,
		Built:       idx.built,
	}

	jsonData, err := json.Marshal(data)
	if err != nil {
		return err
	}

	return os.WriteFile(path, jsonData, 0644)
}

func (idx *IVFIndex) Load(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}

	var loaded struct {
		Metric      DistanceMetric `json:"metric"`
		Dimensions  int            `json:"dimensions"`
		Nlist       int            `json:"nlist"`
		Vectors     []Vector       `json:"vectors"`
		Centroids   [][]float32    `json:"centroids"`
		Assignments []int          `json:"assignments"`
		Built       bool           `json:"built"`
	}

	if err := json.Unmarshal(data, &loaded); err != nil {
		return err
	}

	idx.mu.Lock()
	defer idx.mu.Unlock()

	idx.metric = loaded.Metric
	idx.dimensions = loaded.Dimensions
	idx.nlist = loaded.Nlist
	idx.vectors = loaded.Vectors
	idx.centroids = loaded.Centroids
	idx.assignments = loaded.Assignments
	idx.built = loaded.Built

	return nil
}

func (idx *IVFIndex) distance(a, b []float32) float32 {
	switch idx.metric {
	case MetricCosine:
		return cosineDistance(a, b)
	case MetricEuclidean:
		return euclideanDistance(a, b)
	case MetricDotProduct:
		return -dotProduct(a, b)
	case MetricManhattan:
		return manhattanDistance(a, b)
	default:
		return euclideanDistance(a, b)
	}
}
