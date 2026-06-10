package scheduler

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/tests/fixtures"
	"github.com/stretchr/testify/assert"
)

func topologicalSort(stages []types.StageDefinition) [][]string {
	inDegree := make(map[string]int)
	adjacency := make(map[string][]string)

	for _, stage := range stages {
		if _, exists := inDegree[stage.Name]; !exists {
			inDegree[stage.Name] = 0
		}
		for _, dep := range stage.DependsOn {
			adjacency[dep] = append(adjacency[dep], stage.Name)
			inDegree[stage.Name]++
		}
	}

	var result [][]string
	visited := make(map[string]bool)

	for len(inDegree) > 0 {
		var currentLevel []string
		for name, degree := range inDegree {
			if degree == 0 && !visited[name] {
				currentLevel = append(currentLevel, name)
			}
		}

		if len(currentLevel) == 0 {
			break
		}

		for _, name := range currentLevel {
			visited[name] = true
			delete(inDegree, name)
			for _, neighbor := range adjacency[name] {
				inDegree[neighbor]--
			}
		}

		result = append(result, currentLevel)
	}

	return result
}

func FindReadyStages(stages []types.StageDefinition, completed, failed map[string]bool) []types.StageDefinition {
	var ready []types.StageDefinition

	for _, stage := range stages {
		if completed[stage.Name] || failed[stage.Name] {
			continue
		}

		allDepsCompleted := true
		anyDepFailed := false

		for _, dep := range stage.DependsOn {
			if failed[dep] {
				anyDepFailed = true
				break
			}
			if !completed[dep] {
				allDepsCompleted = false
				break
			}
		}

		if anyDepFailed {
			completed[stage.Name] = true
			continue
		}

		if allDepsCompleted {
			ready = append(ready, stage)
		}
	}

	return ready
}

type executionQueue struct {
	maxConcurrent int
	running       int
	queued        int
	mu            sync.Mutex
	cond          *sync.Cond
}

func newExecutionQueue(max int) *executionQueue {
	q := &executionQueue{
		maxConcurrent: max,
	}
	q.cond = sync.NewCond(&q.mu)
	return q
}

func (q *executionQueue) submit(f func()) {
	q.mu.Lock()
	for q.running >= q.maxConcurrent {
		q.queued++
		q.cond.Wait()
		q.queued--
	}
	q.running++
	q.mu.Unlock()

	go func() {
		defer func() {
			q.mu.Lock()
			q.running--
			q.cond.Signal()
			q.mu.Unlock()
		}()
		f()
	}()
}

func (q *executionQueue) getRunning() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.running
}

func (q *executionQueue) getQueued() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.queued
}

func TestTopologicalSort_Linear(t *testing.T) {
	def := fixtures.GeneratePipelineDefinition(4)
	levels := topologicalSort(def.Stages)

	assert.Equal(t, 4, len(levels), "线性DAG应该有4个层级")
	assert.Equal(t, []string{"stage-0"}, levels[0], "第一级应该是stage-0")
	assert.Equal(t, []string{"stage-1"}, levels[1], "第二级应该是stage-1")
	assert.Equal(t, []string{"stage-2"}, levels[2], "第三级应该是stage-2")
	assert.Equal(t, []string{"stage-3"}, levels[3], "第四级应该是stage-3")
}

func TestTopologicalSort_Parallel(t *testing.T) {
	stages := fixtures.GenerateDAGStages(3)
	levels := topologicalSort(stages)

	assert.Equal(t, 3, len(levels), "并行DAG应该有3个层级")
	assert.Equal(t, []string{"init"}, levels[0], "第一级应该是init")

	assert.Equal(t, 3, len(levels[1]), "第二级应该有3个并行stage")
	assert.Contains(t, levels[1], "parallel-0")
	assert.Contains(t, levels[1], "parallel-1")
	assert.Contains(t, levels[1], "parallel-2")

	assert.Equal(t, []string{"final"}, levels[2], "第三级应该是final")
}

func TestFindReadyStages(t *testing.T) {
	stages := fixtures.GenerateDAGStages(2)

	completed := make(map[string]bool)
	failed := make(map[string]bool)

	ready := FindReadyStages(stages, completed, failed)
	assert.Equal(t, 1, len(ready), "初始状态下只有init就绪")
	assert.Equal(t, "init", ready[0].Name)

	completed["init"] = true
	ready = FindReadyStages(stages, completed, failed)
	assert.Equal(t, 2, len(ready), "init完成后，两个parallel stage就绪")
	assert.Contains(t, []string{ready[0].Name, ready[1].Name}, "parallel-0")
	assert.Contains(t, []string{ready[0].Name, ready[1].Name}, "parallel-1")

	completed["parallel-0"] = true
	ready = FindReadyStages(stages, completed, failed)
	assert.Equal(t, 1, len(ready), "parallel-0完成后，parallel-1仍然就绪")
	assert.Equal(t, "parallel-1", ready[0].Name)

	completed["parallel-1"] = true
	ready = FindReadyStages(stages, completed, failed)
	assert.Equal(t, 1, len(ready), "所有parallel完成后，final就绪")
	assert.Equal(t, "final", ready[0].Name)

	completed["final"] = true
	ready = FindReadyStages(stages, completed, failed)
	assert.Equal(t, 0, len(ready), "所有stage完成后没有就绪的stage")
}

func TestFindReadyStages_FailedDependency(t *testing.T) {
	stages := fixtures.GenerateDAGStages(2)

	completed := make(map[string]bool)
	failed := make(map[string]bool)

	completed["init"] = true
	failed["parallel-0"] = true

	ready := FindReadyStages(stages, completed, failed)
	assert.Equal(t, 1, len(ready), "parallel-0失败后，parallel-1仍然就绪")
	assert.Equal(t, "parallel-1", ready[0].Name)

	completed["parallel-1"] = true
	ready = FindReadyStages(stages, completed, failed)
	assert.Equal(t, 0, len(ready), "有依赖失败时，final应该被标记为已完成而不执行")
	assert.True(t, completed["final"], "final应该被标记为已完成")
}

func TestTopologicalSort_EmptyStages(t *testing.T) {
	var stages []types.StageDefinition
	levels := topologicalSort(stages)
	assert.Equal(t, 0, len(levels), "空stage列表应该返回空结果")

	completed := make(map[string]bool)
	failed := make(map[string]bool)
	ready := FindReadyStages(stages, completed, failed)
	assert.Equal(t, 0, len(ready), "空stage列表应该没有就绪的stage")
}

func TestTopologicalSort_SingleStage(t *testing.T) {
	stages := []types.StageDefinition{
		{
			Name:     "single",
			Type:     types.StageTypeBuild,
			Commands: []string{"echo single"},
		},
	}

	levels := topologicalSort(stages)
	assert.Equal(t, 1, len(levels), "单个stage应该有1个层级")
	assert.Equal(t, []string{"single"}, levels[0])

	completed := make(map[string]bool)
	failed := make(map[string]bool)
	ready := FindReadyStages(stages, completed, failed)
	assert.Equal(t, 1, len(ready), "单个stage应该就绪")
	assert.Equal(t, "single", ready[0].Name)
}

func TestConcurrentStageExecution(t *testing.T) {
	maxConcurrent := 2
	queue := newExecutionQueue(maxConcurrent)

	var runningCount int
	var maxRunning int
	var mu sync.Mutex
	var wg sync.WaitGroup
	var startWg sync.WaitGroup

	executionCount := 4
	wg.Add(executionCount)
	startWg.Add(1)

	started := make(chan struct{}, executionCount)
	completed := make(chan struct{}, executionCount)

	for i := 0; i < executionCount; i++ {
		go func(idx int) {
			defer wg.Done()

			queue.submit(func() {
				mu.Lock()
				runningCount++
				if runningCount > maxRunning {
					maxRunning = runningCount
				}
				currentRunning := runningCount
				mu.Unlock()

				started <- struct{}{}

				if idx == 0 {
					startWg.Wait()
				}

				time.Sleep(50 * time.Millisecond)

				mu.Lock()
				runningCount--
				mu.Unlock()

				completed <- struct{}{}

				assert.LessOrEqual(t, currentRunning, maxConcurrent, "并发执行数不应超过max_concurrent")
			})
		}(i)
	}

	for i := 0; i < maxConcurrent; i++ {
		<-started
	}

	time.Sleep(20 * time.Millisecond)
	assert.Equal(t, maxConcurrent, queue.getRunning(), "前2个执行应该在运行")
	assert.Equal(t, executionCount-maxConcurrent, queue.getQueued(), "后2个执行应该排队")

	startWg.Done()

	for i := 0; i < executionCount; i++ {
		<-completed
	}

	wg.Wait()

	time.Sleep(10 * time.Millisecond)

	assert.Equal(t, maxConcurrent, maxRunning, "最大并发数应该等于max_concurrent")
	assert.Equal(t, 0, queue.getRunning(), "所有执行完成后没有运行中的任务")
	assert.Equal(t, 0, queue.getQueued(), "所有执行完成后没有排队的任务")
}

func TestExecutionCancellation(t *testing.T) {
	scheduler := &Scheduler{
		runningExecs: make(map[types.ID]context.CancelFunc),
	}

	execID := types.ID("test-exec-1")
	ctx, cancel := context.WithCancel(context.Background())

	scheduler.mu.Lock()
	scheduler.runningExecs[execID] = cancel
	scheduler.mu.Unlock()

	scheduler.mu.RLock()
	_, exists := scheduler.runningExecs[execID]
	scheduler.mu.RUnlock()
	assert.True(t, exists, "执行应该在运行中")

	cancelled := make(chan bool, 1)
	go func() {
		select {
		case <-ctx.Done():
			cancelled <- true
		case <-time.After(1 * time.Second):
			cancelled <- false
		}
	}()

	scheduler.mu.Lock()
	if cancelFn, ok := scheduler.runningExecs[execID]; ok {
		cancelFn()
		delete(scheduler.runningExecs, execID)
	}
	scheduler.mu.Unlock()

	result := <-cancelled
	assert.True(t, result, "执行应该被取消")

	scheduler.mu.RLock()
	_, exists = scheduler.runningExecs[execID]
	scheduler.mu.RUnlock()
	assert.False(t, exists, "执行应该从运行列表中移除")
}
