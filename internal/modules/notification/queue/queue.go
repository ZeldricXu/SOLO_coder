package queue

import (
	"container/heap"
	"sync"
	"time"

	"notificationplatform/internal/common/models"
)

type PriorityItem struct {
	Notification *models.NotificationRecord
	Priority     int
	AddedAt      time.Time
	Index        int
}

type PriorityQueue []*PriorityItem

func (pq PriorityQueue) Len() int { return len(pq) }

func (pq PriorityQueue) Less(i, j int) bool {
	if pq[i].Priority != pq[j].Priority {
		return pq[i].Priority > pq[j].Priority
	}
	return pq[i].AddedAt.Before(pq[j].AddedAt)
}

func (pq PriorityQueue) Swap(i, j int) {
	pq[i], pq[j] = pq[j], pq[i]
	pq[i].Index = i
	pq[j].Index = j
}

func (pq *PriorityQueue) Push(x interface{}) {
	n := len(*pq)
	item := x.(*PriorityItem)
	item.Index = n
	*pq = append(*pq, item)
}

func (pq *PriorityQueue) Pop() interface{} {
	old := *pq
	n := len(old)
	item := old[n-1]
	old[n-1] = nil
	item.Index = -1
	*pq = old[0 : n-1]
	return item
}

func (pq *PriorityQueue) Peek() *PriorityItem {
	if len(*pq) == 0 {
		return nil
	}
	return (*pq)[0]
}

type QueueManager struct {
	pq          PriorityQueue
	mu          sync.Mutex
	cond        *sync.Cond
	maxSize     int
	closed      bool
	metrics     *QueueMetrics
}

type QueueMetrics struct {
	TotalEnqueued int64
	TotalDequeued int64
	TotalDropped  int64
	CurrentSize   int
	HighPriority  int64
	NormalPriority int64
	LowPriority   int64
}

func NewQueueManager(maxSize int) *QueueManager {
	qm := &QueueManager{
		pq:      make(PriorityQueue, 0, maxSize),
		maxSize: maxSize,
		metrics: &QueueMetrics{},
	}
	qm.cond = sync.NewCond(&qm.mu)
	heap.Init(&qm.pq)
	return qm
}

func (qm *QueueManager) Enqueue(notification *models.NotificationRecord) bool {
	qm.mu.Lock()
	defer qm.mu.Unlock()

	if qm.closed {
		return false
	}

	if qm.pq.Len() >= qm.maxSize {
		qm.metrics.TotalDropped++
		return false
	}

	item := &PriorityItem{
		Notification: notification,
		Priority:     notification.Priority,
		AddedAt:      time.Now(),
	}

	heap.Push(&qm.pq, item)

	qm.metrics.TotalEnqueued++
	qm.metrics.CurrentSize = qm.pq.Len()

	switch notification.Priority {
	case int(models.PriorityHigh), int(models.PriorityCritical):
		qm.metrics.HighPriority++
	case int(models.PriorityNormal), int(models.PriorityMedium):
		qm.metrics.NormalPriority++
	default:
		qm.metrics.LowPriority++
	}

	qm.cond.Signal()
	return true
}

func (qm *QueueManager) Dequeue(blocking bool) *models.NotificationRecord {
	qm.mu.Lock()
	defer qm.mu.Unlock()

	if blocking {
		for qm.pq.Len() == 0 && !qm.closed {
			qm.cond.Wait()
		}
	}

	if qm.pq.Len() == 0 {
		return nil
	}

	item := heap.Pop(&qm.pq).(*PriorityItem)

	qm.metrics.TotalDequeued++
	qm.metrics.CurrentSize = qm.pq.Len()

	return item.Notification
}

func (qm *QueueManager) DequeueWithTimeout(timeout time.Duration) *models.NotificationRecord {
	deadline := time.Now().Add(timeout)
	pollInterval := 100 * time.Millisecond

	for time.Now().Before(deadline) {
		notif := qm.Dequeue(false)
		if notif != nil {
			return notif
		}

		remaining := time.Until(deadline)
		if remaining <= 0 {
			break
		}

		sleepDuration := pollInterval
		if remaining < pollInterval {
			sleepDuration = remaining
		}
		time.Sleep(sleepDuration)
	}

	return qm.Dequeue(false)
}

func (qm *QueueManager) Len() int {
	qm.mu.Lock()
	defer qm.mu.Unlock()
	return qm.pq.Len()
}

func (qm *QueueManager) Close() {
	qm.mu.Lock()
	defer qm.mu.Unlock()

	qm.closed = true
	qm.cond.Broadcast()
}

func (qm *QueueManager) IsClosed() bool {
	qm.mu.Lock()
	defer qm.mu.Unlock()
	return qm.closed
}

func (qm *QueueManager) GetMetrics() QueueMetrics {
	qm.mu.Lock()
	defer qm.mu.Unlock()
	return *qm.metrics
}

func (qm *QueueManager) Clear() {
	qm.mu.Lock()
	defer qm.mu.Unlock()

	qm.pq = make(PriorityQueue, 0, qm.maxSize)
	qm.metrics.CurrentSize = 0
	heap.Init(&qm.pq)
}
