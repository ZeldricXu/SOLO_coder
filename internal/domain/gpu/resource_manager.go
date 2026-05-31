package gpu

import (
	"container/heap"
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/dataplatform/engine/internal/common/errors"
	"github.com/dataplatform/engine/internal/domain"
	"github.com/google/uuid"
)

type PriorityQueue []*GPUTask

func (pq PriorityQueue) Len() int { return len(pq) }

func (pq PriorityQueue) Less(i, j int) bool {
	if pq[i].Priority != pq[j].Priority {
		return pq[i].Priority > pq[j].Priority
	}
	return pq[i].SubmittedAt.Before(pq[j].SubmittedAt)
}

func (pq PriorityQueue) Swap(i, j int) {
	pq[i], pq[j] = pq[j], pq[i]
}

func (pq *PriorityQueue) Push(x interface{}) {
	*pq = append(*pq, x.(*GPUTask))
}

func (pq *PriorityQueue) Pop() interface{} {
	old := *pq
	n := len(old)
	item := old[n-1]
	*pq = old[0 : n-1]
	return item
}

type GPUResourceManagerImpl struct {
	resources map[string]*GPUResource
	mu        sync.RWMutex
}

func NewGPUResourceManager(nodeID string, deviceIndices []int, vramPerDevice uint64) *GPUResourceManagerImpl {
	rm := &GPUResourceManagerImpl{
		resources: make(map[string]*GPUResource),
	}

	for _, idx := range deviceIndices {
		id := fmt.Sprintf("%s-gpu-%d", nodeID, idx)
		rm.resources[id] = &GPUResource{
			ID:          id,
			NodeID:      nodeID,
			DeviceIndex: idx,
			TotalVRAM:   vramPerDevice,
			UsedVRAM:    0,
			Status:      GPUStatusAvailable,
			Labels:      make(map[string]string),
		}
	}

	return rm
}

func (rm *GPUResourceManagerImpl) Acquire(ctx context.Context, req *GPUResourceRequest) (*GPUResource, error) {
	rm.mu.Lock()
	defer rm.mu.Unlock()

	for _, res := range rm.resources {
		if res.Status != GPUStatusAvailable {
			continue
		}
		availableVRAM := res.TotalVRAM - res.UsedVRAM
		if availableVRAM >= req.MinVRAM {
			res.Status = GPUStatusAllocated
			res.UsedVRAM += req.PreferredVRAM
			return res, nil
		}
	}

	return nil, errors.New(errors.ErrCodeResourceExhausted, "no GPU resources available")
}

func (rm *GPUResourceManagerImpl) Release(ctx context.Context, resourceID string) error {
	rm.mu.Lock()
	defer rm.mu.Unlock()

	res, exists := rm.resources[resourceID]
	if !exists {
		return errors.New(errors.ErrCodeNotFound, "resource not found")
	}

	res.Status = GPUStatusAvailable
	res.UsedVRAM = 0
	return nil
}

func (rm *GPUResourceManagerImpl) List(ctx context.Context) ([]*GPUResource, error) {
	rm.mu.RLock()
	defer rm.mu.RUnlock()

	result := make([]*GPUResource, 0, len(rm.resources))
	for _, res := range rm.resources {
		result = append(result, res)
	}
	return result, nil
}

func (rm *GPUResourceManagerImpl) UpdateStatus(ctx context.Context, resourceID string, status GPUStatus) error {
	rm.mu.Lock()
	defer rm.mu.Unlock()

	res, exists := rm.resources[resourceID]
	if !exists {
		return errors.New(errors.ErrCodeNotFound, "resource not found")
	}

	res.Status = status
	return nil
}

func (rm *GPUResourceManagerImpl) GetAvailableVRAM() uint64 {
	rm.mu.RLock()
	defer rm.mu.RUnlock()

	var total uint64
	for _, res := range rm.resources {
		if res.Status == GPUStatusAvailable {
			total += res.TotalVRAM - res.UsedVRAM
		}
	}
	return total
}

func (rm *GPUResourceManagerImpl) GetAllocatedResources() []*GPUResource {
	rm.mu.RLock()
	defer rm.mu.RUnlock()

	result := make([]*GPUResource, 0)
	for _, res := range rm.resources {
		if res.Status == GPUStatusAllocated {
			result = append(result, res)
		}
	}
	return result
}
