package dag_test

import (
	"sync"
	"testing"

	"github.com/distributed-task-scheduler/internal/dag"
	"github.com/distributed-task-scheduler/test/testkit"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBuildGraph_NormalPath(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "Extract", "task-1").
		WithNode("b", "Transform", "task-2").
		WithNode("c", "Load", "task-3").
		WithEdge("a", "b").
		WithEdge("b", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)
	assert.Len(t, g.Nodes, 3)
	assert.Len(t, g.Edges, 2)
}

func TestBuildGraph_InvalidEdge(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "Extract", "task-1").
		WithEdge("a", "nonexistent").
		Build()

	_, err := o.BuildGraph(nodes, edges)
	assert.ErrorIs(t, err, dag.ErrNodeNotFound)
}

func TestBuildGraph_EmptyNodes(t *testing.T) {
	o := dag.NewOrchestrator()
	g, err := o.BuildGraph(nil, nil)
	require.NoError(t, err)
	assert.Empty(t, g.Nodes)
}

func TestDetectCycle_NoCycle(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithEdge("a", "b").
		WithEdge("b", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)
	assert.NoError(t, o.DetectCycle(g))
}

func TestDetectCycle_SimpleCycle(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithEdge("a", "b").
		WithEdge("b", "a").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)
	assert.ErrorIs(t, o.DetectCycle(g), dag.ErrCyclicDependency)
}

func TestDetectCycle_ThreeNodeCycle(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithEdge("a", "b").
		WithEdge("b", "c").
		WithEdge("c", "a").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)
	assert.ErrorIs(t, o.DetectCycle(g), dag.ErrCyclicDependency)
}

func TestDetectCycle_SelfLoop(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithEdge("a", "a").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)
	assert.ErrorIs(t, o.DetectCycle(g), dag.ErrCyclicDependency)
}

func TestDetectCycle_DisconnectedWithCycle(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithNode("d", "D", "t4").
		WithEdge("a", "b").
		WithEdge("c", "d").
		WithEdge("d", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)
	assert.ErrorIs(t, o.DetectCycle(g), dag.ErrCyclicDependency)
}

func TestTopologicalSort_LinearChain(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithEdge("a", "b").
		WithEdge("b", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	result, err := o.TopologicalSort(g)
	require.NoError(t, err)
	assert.Equal(t, []string{"a", "b", "c"}, result)
}

func TestTopologicalSort_DiamondDependency(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "Root", "t1").
		WithNode("b", "Left", "t2").
		WithNode("c", "Right", "t3").
		WithNode("d", "Merge", "t4").
		WithEdge("a", "b").
		WithEdge("a", "c").
		WithEdge("b", "d").
		WithEdge("c", "d").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	result, err := o.TopologicalSort(g)
	require.NoError(t, err)

	assert.Equal(t, "a", result[0])
	assert.Equal(t, "d", result[3])

	posB := indexOf(result, "b")
	posC := indexOf(result, "c")
	posA := indexOf(result, "a")
	posD := indexOf(result, "d")
	assert.Less(t, posA, posB)
	assert.Less(t, posA, posC)
	assert.Less(t, posB, posD)
	assert.Less(t, posC, posD)
}

func TestTopologicalSort_Disconnected(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	result, err := o.TopologicalSort(g)
	require.NoError(t, err)
	assert.Len(t, result, 2)
}

func TestTopologicalSort_CyclicReturnsError(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithEdge("a", "b").
		WithEdge("b", "a").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	_, err = o.TopologicalSort(g)
	assert.ErrorIs(t, err, dag.ErrCyclicDependency)
}

func TestGenerateExecutionPlan_Diamond(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "Extract", "t1").
		WithNode("b", "TransformA", "t2").
		WithNode("c", "TransformB", "t3").
		WithNode("d", "Load", "t4").
		WithEdge("a", "b").
		WithEdge("a", "c").
		WithEdge("b", "d").
		WithEdge("c", "d").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	plan, err := o.GenerateExecutionPlan(g)
	require.NoError(t, err)

	assert.Equal(t, 0, plan.Levels["a"])
	assert.Equal(t, 1, plan.Levels["b"])
	assert.Equal(t, 1, plan.Levels["c"])
	assert.Equal(t, 2, plan.Levels["d"])

	assert.Equal(t, []string{"a"}, plan.Parallel[0])
	assert.ElementsMatch(t, []string{"b", "c"}, plan.Parallel[1])
	assert.Equal(t, []string{"d"}, plan.Parallel[2])

	assert.Equal(t, []string{"a"}, plan.Nodes)
}

func TestGenerateExecutionPlan_CyclicRejected(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithEdge("a", "b").
		WithEdge("b", "a").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	_, err = o.GenerateExecutionPlan(g)
	assert.ErrorIs(t, err, dag.ErrCyclicDependency)
}

func TestGetDependencies(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithEdge("a", "c").
		WithEdge("b", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	deps := o.GetDependencies(g, "c")
	assert.ElementsMatch(t, []string{"a", "b"}, deps)

	depsA := o.GetDependencies(g, "a")
	assert.Empty(t, depsA)
}

func TestGetDependents(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithEdge("a", "b").
		WithEdge("a", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	deps := o.GetDependents(g, "a")
	assert.ElementsMatch(t, []string{"b", "c"}, deps)

	depsB := o.GetDependents(g, "b")
	assert.Empty(t, depsB)
}

func TestGetRootNodes(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithEdge("a", "c").
		WithEdge("b", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	roots := o.GetRootNodes(g)
	assert.ElementsMatch(t, []string{"a", "b"}, roots)
}

func TestGetLeafNodes(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithEdge("a", "c").
		WithEdge("b", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	leaves := o.GetLeafNodes(g)
	assert.Equal(t, []string{"c"}, leaves)
}

func TestGetCriticalPath(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithNode("d", "D", "t4").
		WithEdge("a", "b").
		WithEdge("a", "c").
		WithEdge("b", "d").
		WithEdge("c", "d").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	weights := map[string]int{"a": 2, "b": 5, "c": 3, "d": 1}
	path, cost := o.GetCriticalPath(g, weights)

	assert.Equal(t, 8, cost)
	assert.Equal(t, []string{"a", "b", "d"}, path)
}

func TestValidateDAG_EmptyGraph(t *testing.T) {
	o := dag.NewOrchestrator()
	g := &dag.Graph{Nodes: map[string]*dag.Node{}, Edges: nil}
	assert.ErrorIs(t, o.ValidateDAG(g), dag.ErrInvalidGraph)
}

func TestConcurrentDAGOperations(t *testing.T) {
	o := dag.NewOrchestrator()
	nodes, edges := testkit.NewDAGBuilder().
		WithNode("a", "A", "t1").
		WithNode("b", "B", "t2").
		WithNode("c", "C", "t3").
		WithEdge("a", "b").
		WithEdge("b", "c").
		Build()

	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			plan, err := o.GenerateExecutionPlan(g)
			assert.NoError(t, err)
			assert.Equal(t, []string{"a", "b", "c"}, plan.Nodes)
		}()
	}
	wg.Wait()
}

func TestLargeDAG(t *testing.T) {
	o := dag.NewOrchestrator()
	b := testkit.NewDAGBuilder()

	for i := 0; i < 50; i++ {
		nodeID := string(rune('a' + i%26)) + string(rune('0'+i/26))
		b.WithNode(nodeID, nodeID, "task-"+nodeID)
		if i > 0 {
			prevID := string(rune('a' + (i-1)%26)) + string(rune('0'+(i-1)/26))
			b.WithEdge(prevID, nodeID)
		}
	}

	nodes, edges := b.Build()
	g, err := o.BuildGraph(nodes, edges)
	require.NoError(t, err)

	plan, err := o.GenerateExecutionPlan(g)
	require.NoError(t, err)
	assert.Len(t, plan.Nodes, 50)

	for i := 0; i < len(plan.Nodes)-1; i++ {
		levelI := plan.Levels[plan.Nodes[i]]
		levelJ := plan.Levels[plan.Nodes[i+1]]
		assert.LessOrEqual(t, levelI, levelJ)
	}
}

func indexOf(slice []string, item string) int {
	for i, v := range slice {
		if v == item {
			return i
		}
	}
	return -1
}
