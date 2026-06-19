package scheduler

import (
	"container/list"
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/df1-96/experiment/internal/models"
)

type PriorityQueue struct {
	mu          sync.RWMutex
	queues      [4]*list.List
	taskIndex   map[int64]*list.Element
	lengths     [4]int
	totalLength int
}

func NewPriorityQueue() *PriorityQueue {
	pq := &PriorityQueue{
		taskIndex: make(map[int64]*list.Element),
	}
	for i := 0; i < 4; i++ {
		pq.queues[i] = list.New()
	}
	return pq
}

func (pq *PriorityQueue) Enqueue(task *models.Task, deadline *time.Time) (*QueuedTask, error) {
	pq.mu.Lock()
	defer pq.mu.Unlock()

	if task == nil {
		return nil, fmt.Errorf("task cannot be nil")
	}
	if _, exists := pq.taskIndex[task.ID]; exists {
		return nil, fmt.Errorf("task %d already in queue", task.ID)
	}

	priority := pq.normalizePriority(task.Priority)

	qt := &QueuedTask{
		Task:        task,
		EnqueueTime: time.Now(),
		Deadline:    deadline,
		CancelChan:  make(chan struct{}, 1),
	}

	var elem *list.Element
	if priority == int(PriorityCritical) {
		elem = pq.queues[priority].PushFront(qt)
	} else {
		elem = pq.queues[priority].PushBack(qt)
	}

	pq.taskIndex[task.ID] = elem
	pq.lengths[priority]++
	pq.totalLength++

	return qt, nil
}

func (pq *PriorityQueue) Dequeue(ctx context.Context) (*QueuedTask, error) {
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
			task, err := pq.tryDequeue()
			if err != nil {
				return nil, err
			}
			if task != nil {
				return task, nil
			}
			time.Sleep(10 * time.Millisecond)
		}
	}
}

func (pq *PriorityQueue) TryDequeue() (*QueuedTask, error) {
	pq.mu.Lock()
	defer pq.mu.Unlock()

	return pq.dequeueInternal()
}

func (pq *PriorityQueue) tryDequeue() (*QueuedTask, error) {
	pq.mu.Lock()
	defer pq.mu.Unlock()

	return pq.dequeueInternal()
}

func (pq *PriorityQueue) dequeueInternal() (*QueuedTask, error) {
	for i := 0; i < 4; i++ {
		for pq.queues[i].Len() > 0 {
			front := pq.queues[i].Front()
			qt := front.Value.(*QueuedTask)

			if pq.isExpired(qt) {
				pq.removeElement(front, i)
				close(qt.CancelChan)
				continue
			}

			pq.removeElement(front, i)
			return qt, nil
		}
	}
	return nil, nil
}

func (pq *PriorityQueue) DequeueWithTimeout(timeout time.Duration) (*QueuedTask, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return pq.Dequeue(ctx)
}

func (pq *PriorityQueue) Cancel(taskID int64) bool {
	pq.mu.Lock()
	defer pq.mu.Unlock()

	elem, exists := pq.taskIndex[taskID]
	if !exists {
		return false
	}

	qt := elem.Value.(*QueuedTask)
	priority := pq.normalizePriority(qt.Task.Priority)

	pq.removeElement(elem, priority)
	select {
	case <-qt.CancelChan:
	default:
		close(qt.CancelChan)
	}

	return true
}

func (pq *PriorityQueue) Contains(taskID int64) bool {
	pq.mu.RLock()
	defer pq.mu.RUnlock()
	_, exists := pq.taskIndex[taskID]
	return exists
}

func (pq *PriorityQueue) Length() int {
	pq.mu.RLock()
	defer pq.mu.RUnlock()
	return pq.totalLength
}

func (pq *PriorityQueue) LengthByPriority(priority int) int {
	pq.mu.RLock()
	defer pq.mu.RUnlock()
	p := pq.normalizePriority(priority)
	return pq.lengths[p]
}

func (pq *PriorityQueue) Peek() (*QueuedTask, error) {
	pq.mu.RLock()
	defer pq.mu.RUnlock()

	for i := 0; i < 4; i++ {
		for pq.queues[i].Len() > 0 {
			front := pq.queues[i].Front()
			qt := front.Value.(*QueuedTask)
			if !pq.isExpired(qt) {
				return qt, nil
			}
		}
	}
	return nil, nil
}

func (pq *PriorityQueue) Promote(taskID int64, newPriority int) error {
	pq.mu.Lock()
	defer pq.mu.Unlock()

	elem, exists := pq.taskIndex[taskID]
	if !exists {
		return fmt.Errorf("task %d not found in queue", taskID)
	}

	qt := elem.Value.(*QueuedTask)
	oldPriority := pq.normalizePriority(qt.Task.Priority)
	newP := pq.normalizePriority(newPriority)

	if oldPriority == newP {
		return nil
	}

	pq.removeElement(elem, oldPriority)

	qt.Task.Priority = newP

	var newElem *list.Element
	if newP == int(PriorityCritical) {
		newElem = pq.queues[newP].PushFront(qt)
	} else {
		newElem = pq.queues[newP].PushBack(qt)
	}

	pq.taskIndex[taskID] = newElem
	pq.lengths[newP]++
	pq.totalLength++

	return nil
}

func (pq *PriorityQueue) Demote(taskID int64, newPriority int) error {
	return pq.Promote(taskID, newPriority)
}

func (pq *PriorityQueue) Clear() {
	pq.mu.Lock()
	defer pq.mu.Unlock()

	for i := 0; i < 4; i++ {
		for e := pq.queues[i].Front(); e != nil; e = e.Next() {
			qt := e.Value.(*QueuedTask)
			select {
			case <-qt.CancelChan:
			default:
				close(qt.CancelChan)
			}
		}
		pq.queues[i].Init()
		pq.lengths[i] = 0
	}
	pq.taskIndex = make(map[int64]*list.Element)
	pq.totalLength = 0
}

func (pq *PriorityQueue) Snapshot() []*QueuedTask {
	pq.mu.RLock()
	defer pq.mu.RUnlock()

	result := make([]*QueuedTask, 0, pq.totalLength)
	for i := 0; i < 4; i++ {
		for e := pq.queues[i].Front(); e != nil; e = e.Next() {
			qt := e.Value.(*QueuedTask)
			if !pq.isExpired(qt) {
				result = append(result, qt)
			}
		}
	}
	return result
}

func (pq *PriorityQueue) CleanExpired() int {
	pq.mu.Lock()
	defer pq.mu.Unlock()

	count := 0
	for i := 0; i < 4; i++ {
		var next *list.Element
		for e := pq.queues[i].Front(); e != nil; e = next {
			next = e.Next()
			qt := e.Value.(*QueuedTask)
			if pq.isExpired(qt) {
				pq.removeElement(e, i)
				close(qt.CancelChan)
				count++
			}
		}
	}
	return count
}

func (pq *PriorityQueue) WaitForTask(ctx context.Context) (*QueuedTask, error) {
	return pq.Dequeue(ctx)
}

func (pq *PriorityQueue) removeElement(elem *list.Element, priority int) {
	pq.queues[priority].Remove(elem)
	delete(pq.taskIndex, elem.Value.(*QueuedTask).Task.ID)
	pq.lengths[priority]--
	pq.totalLength--
}

func (pq *PriorityQueue) isExpired(qt *QueuedTask) bool {
	if qt.Deadline == nil {
		return false
	}
	return time.Now().After(*qt.Deadline)
}

func (pq *PriorityQueue) normalizePriority(priority int) int {
	if priority < int(PriorityCritical) {
		return int(PriorityCritical)
	}
	if priority > int(PriorityLow) {
		return int(PriorityLow)
	}
	return priority
}

func (pq *PriorityQueue) GetWaitTime(taskID int64) (time.Duration, error) {
	pq.mu.RLock()
	defer pq.mu.RUnlock()

	elem, exists := pq.taskIndex[taskID]
	if !exists {
		return 0, fmt.Errorf("task %d not found in queue", taskID)
	}

	qt := elem.Value.(*QueuedTask)
	return time.Since(qt.EnqueueTime), nil
}

func (pq *PriorityQueue) GetPosition(taskID int64) (int, int, error) {
	pq.mu.RLock()
	defer pq.mu.RUnlock()

	elem, exists := pq.taskIndex[taskID]
	if !exists {
		return -1, -1, fmt.Errorf("task %d not found in queue", taskID)
	}

	qt := elem.Value.(*QueuedTask)
	priority := pq.normalizePriority(qt.Task.Priority)

	position := 0
	for e := pq.queues[priority].Front(); e != nil; e = e.Next() {
		if e == elem {
			break
		}
		position++
	}

	globalPosition := 0
	for i := 0; i < priority; i++ {
		globalPosition += pq.lengths[i]
	}
	globalPosition += position

	return position, globalPosition, nil
}

func (pq *PriorityQueue) Iterate(fn func(*QueuedTask) bool) {
	pq.mu.RLock()
	defer pq.mu.RUnlock()

	for i := 0; i < 4; i++ {
		for e := pq.queues[i].Front(); e != nil; e = e.Next() {
			qt := e.Value.(*QueuedTask)
			if !fn(qt) {
				return
			}
		}
	}
}
