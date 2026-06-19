package unit

import (
	"math/rand"
	"testing"

	"github.com/enterprise/knowledgebase/internal/ot"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestInsertApply(t *testing.T) {
	doc := "hello world"

	result, err := ot.Apply(doc, ot.NewInsert(5, " beautiful"))
	require.NoError(t, err)
	assert.Equal(t, "hello beautiful world", result)

	result, err = ot.Apply(doc, ot.NewInsert(0, "start: "))
	require.NoError(t, err)
	assert.Equal(t, "start: hello world", result)

	result, err = ot.Apply(doc, ot.NewInsert(11, " end"))
	require.NoError(t, err)
	assert.Equal(t, "hello world end", result)

	result, err = ot.Apply("", ot.NewInsert(0, "new"))
	require.NoError(t, err)
	assert.Equal(t, "new", result)

	_, err = ot.Apply(doc, ot.NewInsert(100, "bad"))
	assert.Error(t, err)

	_, err = ot.Apply(doc, ot.NewInsert(-1, "bad"))
	assert.Error(t, err)
}

func TestDeleteApply(t *testing.T) {
	doc := "hello beautiful world"
	assert.Equal(t, 21, len([]rune(doc)))

	result, err := ot.Apply(doc, ot.NewDelete(5, 10))
	require.NoError(t, err)
	assert.Equal(t, "hello world", result)

	result, err = ot.Apply(doc, ot.NewDelete(0, 6))
	require.NoError(t, err)
	assert.Equal(t, "beautiful world", result)

	result, err = ot.Apply(doc, ot.NewDelete(15, 6))
	require.NoError(t, err)
	assert.Equal(t, "hello beautiful", result)

	result, err = ot.Apply(doc, ot.NewDelete(0, 0))
	require.NoError(t, err)
	assert.Equal(t, doc, result)

	_, err = ot.Apply(doc, ot.NewDelete(0, 100))
	assert.Error(t, err)

	_, err = ot.Apply(doc, ot.NewDelete(5, -1))
	assert.Error(t, err)
}

func TestRetainApply(t *testing.T) {
	doc := "hello world"

	result, err := ot.Apply(doc, ot.NewRetain(0, 5))
	require.NoError(t, err)
	assert.Equal(t, doc, result)

	result, err = ot.Apply(doc, ot.NewRetain(3, 7))
	require.NoError(t, err)
	assert.Equal(t, doc, result)

	result, err = ot.Apply(doc, ot.NewRetain(0, 0))
	require.NoError(t, err)
	assert.Equal(t, doc, result)
}

func TestInvertOperation(t *testing.T) {
	doc := "hello world"

	insertOp := ot.NewInsert(5, " test")
	result, err := ot.Apply(doc, insertOp)
	require.NoError(t, err)
	assert.Equal(t, "hello test world", result)

	invertedInsert := ot.Invert(insertOp)
	result, err = ot.Apply(result, invertedInsert)
	require.NoError(t, err)
	assert.Equal(t, doc, result)

	deleteOp := ot.NewDelete(5, 6)
	deleteOp.Text = " world"
	result, err = ot.Apply(doc, deleteOp)
	require.NoError(t, err)
	assert.Equal(t, "hello", result)

	invertedDelete := ot.Invert(deleteOp)
	result, err = ot.Apply(result, invertedDelete)
	require.NoError(t, err)
	assert.Equal(t, doc, result)

	retainOp := ot.NewRetain(2, 5)
	invertedRetain := ot.Invert(retainOp)
	assert.Equal(t, ot.Retain, invertedRetain.Type)
	assert.Equal(t, retainOp.Position, invertedRetain.Position)
	assert.Equal(t, retainOp.Length, invertedRetain.Length)
}

func TestTransform_InsertVsInsert(t *testing.T) {
	op1 := ot.NewInsert(3, "AAA")
	op2 := ot.NewInsert(5, "BBB")

	op1Prime, op2Prime := ot.Transform(op1, op2)

	assert.Equal(t, 3, op1Prime.Position)
	assert.Equal(t, 5+3, op2Prime.Position)

	op1 = ot.NewInsert(5, "AAA")
	op2 = ot.NewInsert(3, "BBB")

	op1Prime, op2Prime = ot.Transform(op1, op2)

	assert.Equal(t, 5+3, op1Prime.Position)
	assert.Equal(t, 3, op2Prime.Position)

	op1 = ot.NewInsert(4, "AAA")
	op2 = ot.NewInsert(4, "BBB")

	op1Prime, op2Prime = ot.Transform(op1, op2)

	assert.Equal(t, 4, op1Prime.Position)
	assert.Equal(t, 4+3, op2Prime.Position)
}

func TestTransform_InsertVsDelete(t *testing.T) {
	op1 := ot.NewInsert(2, "AAA")
	op2 := ot.NewDelete(5, 3)

	op1Prime, op2Prime := ot.Transform(op1, op2)

	assert.Equal(t, 2, op1Prime.Position)
	assert.Equal(t, 5+3, op2Prime.Position)

	op1 = ot.NewInsert(8, "AAA")
	op2 = ot.NewDelete(5, 3)

	op1Prime, op2Prime = ot.Transform(op1, op2)

	assert.Equal(t, 8, op1Prime.Position)
	assert.Equal(t, 5, op2Prime.Position)
}

func TestTransform_DeleteVsDelete(t *testing.T) {
	op1 := ot.NewDelete(0, 3)
	op2 := ot.NewDelete(5, 3)

	op1Prime, op2Prime := ot.Transform(op1, op2)

	assert.Equal(t, 0, op1Prime.Position)
	assert.Equal(t, 3, op1Prime.Length)
	assert.Equal(t, 2, op2Prime.Position)
	assert.Equal(t, 3, op2Prime.Length)

	op1 = ot.NewDelete(5, 3)
	op2 = ot.NewDelete(0, 3)

	op1Prime, op2Prime = ot.Transform(op1, op2)

	assert.Equal(t, 2, op1Prime.Position)
	assert.Equal(t, 3, op1Prime.Length)
	assert.Equal(t, 0, op2Prime.Position)
	assert.Equal(t, 3, op2Prime.Length)

	op1 = ot.NewDelete(2, 5)
	op2 = ot.NewDelete(3, 4)

	op1Prime, op2Prime = ot.Transform(op1, op2)

	assert.Equal(t, 2, op1Prime.Position)
	assert.Equal(t, 2, op2Prime.Position)
}

func TestTransform_RetainVsAny(t *testing.T) {
	op1 := ot.NewRetain(0, 10)
	op2 := ot.NewInsert(5, "AAA")

	op1Prime, op2Prime := ot.Transform(op1, op2)

	assert.NotNil(t, op1Prime)
	assert.NotNil(t, op2Prime)

	op1 = ot.NewRetain(0, 10)
	op2 = ot.NewDelete(3, 4)

	op1Prime, op2Prime = ot.Transform(op1, op2)

	assert.NotNil(t, op1Prime)
	assert.NotNil(t, op2Prime)

	op1 = ot.NewRetain(0, 10)
	op2 = ot.NewRetain(2, 5)

	op1Prime, op2Prime = ot.Transform(op1, op2)

	assert.NotNil(t, op1Prime)
	assert.NotNil(t, op2Prime)
	assert.Equal(t, 0, op1Prime.Position)
	assert.Equal(t, 2, op2Prime.Position)
}

func TestConvergence_Simple(t *testing.T) {
	doc := "hello world"

	opsA := []*ot.Operation{ot.NewInsert(5, " Alice")}
	opsB := []*ot.Operation{ot.NewInsert(11, "!")}

	converges, docA, docB, err := ot.Converges(doc, opsA, opsB)

	require.NoError(t, err)
	assert.True(t, converges, "documents should converge")
	assert.Equal(t, docA, docB)
	assert.Equal(t, "hello Alice world!", docA)
}

func TestConvergence_TwoInserts(t *testing.T) {
	doc := "ABC"
	opsA := []*ot.Operation{ot.NewInsert(0, "X")}
	opsB := []*ot.Operation{ot.NewInsert(3, "Y")}

	converges, docA, docB, err := ot.Converges(doc, opsA, opsB)
	require.NoError(t, err)
	assert.True(t, converges)
	assert.Equal(t, docA, docB)
	assert.Equal(t, "XABCY", docA)
}

func TestConvergence_InsertAndDelete(t *testing.T) {
	doc := "hello world"
	opsA := []*ot.Operation{ot.NewInsert(0, "prefix: ")}
	opsB := []*ot.Operation{ot.NewDelete(0, 6)}

	converges, docA, docB, err := ot.Converges(doc, opsA, opsB)
	require.NoError(t, err)
	assert.True(t, converges)
	assert.Equal(t, docA, docB)
}

func TestOT_FuzzRandomOperations(t *testing.T) {
	seed := int64(42)
	rng := rand.New(rand.NewSource(seed))

	successCount := 0
	totalRuns := 100
	actualRuns := 0

	for run := 0; run < totalRuns; run++ {
		docLen := rng.Intn(51) + 20
		docRunes := make([]rune, docLen)
		for i := range docRunes {
			docRunes[i] = rune('a' + rng.Intn(26))
		}
		initialDoc := string(docRunes)

		opsA := make([]*ot.Operation, 0)
		opsB := make([]*ot.Operation, 0)

		docA := initialDoc
		numOpsA := rng.Intn(3) + 1
		for i := 0; i < numOpsA; i++ {
			op := ot.RandomOperation(docA, rng)
			applied, err := ot.Apply(docA, op)
			if err != nil {
				continue
			}
			docA = applied
			opsA = append(opsA, op)
		}

		docB := initialDoc
		numOpsB := rng.Intn(3) + 1
		for i := 0; i < numOpsB; i++ {
			op := ot.RandomOperation(docB, rng)
			applied, err := ot.Apply(docB, op)
			if err != nil {
				continue
			}
			docB = applied
			opsB = append(opsB, op)
		}

		if len(opsA) == 0 || len(opsB) == 0 {
			continue
		}

		converges, finalA, finalB, err := ot.Converges(initialDoc, opsA, opsB)
		if err != nil {
			continue
		}
		actualRuns++
		if converges && finalA == finalB {
			successCount++
		}
	}

	t.Logf("Fuzz test completed: %d/%d successful runs (actual valid runs: %d)", successCount, totalRuns, actualRuns)
	assert.GreaterOrEqual(t, successCount, 50, "at least 50% of runs should converge successfully")
}

func TestComposeOperations(t *testing.T) {
	ops := []*ot.Operation{
		ot.NewInsert(0, "hello"),
		ot.NewInsert(5, " world"),
	}

	composed, err := ot.Compose(ops)
	require.NoError(t, err)
	assert.Len(t, composed, 1)
	assert.Equal(t, "hello world", composed[0].Text)

	op1 := ot.NewDelete(0, 3)
	op1.Text = "abc"
	op2 := ot.NewDelete(0, 2)
	op2.Text = "de"
	ops = []*ot.Operation{op1, op2}

	composed, err = ot.Compose(ops)
	require.NoError(t, err)
	assert.Len(t, composed, 1)
	assert.Equal(t, 0, composed[0].Position)
	assert.Equal(t, 5, composed[0].Length)

	ops = []*ot.Operation{
		ot.NewRetain(0, 5),
		ot.NewRetain(5, 3),
	}

	composed, err = ot.Compose(ops)
	require.NoError(t, err)
	assert.Len(t, composed, 1)
	assert.Equal(t, 0, composed[0].Position)
	assert.Equal(t, 8, composed[0].Length)

	single := []*ot.Operation{ot.NewInsert(0, "test")}
	composed, err = ot.Compose(single)
	require.NoError(t, err)
	assert.Len(t, composed, 1)
}

func TestOT_ConcurrentEditsSameParagraph(t *testing.T) {
	doc := "这是一段测试文本，用于验证并发编辑功能。"

	opsA := []*ot.Operation{
		ot.NewInsert(10, "非常好的"),
	}
	opsB := []*ot.Operation{
		ot.NewInsert(len([]rune(doc))-1, "完美"),
	}

	converges, docA, docB, err := ot.Converges(doc, opsA, opsB)

	require.NoError(t, err)
	assert.True(t, converges)
	assert.Equal(t, docA, docB)

	runesA := []rune(docA)
	runesB := []rune(docB)
	assert.Equal(t, len(runesA), len(runesB))
	for i := range runesA {
		assert.Equal(t, runesA[i], runesB[i], "rune at position %d differs", i)
	}
}

func TestOT_MultipleOperations_Sequential(t *testing.T) {
	doc := "initial"

	opsA := []*ot.Operation{
		ot.NewInsert(0, "prefix-"),
	}
	opsB := []*ot.Operation{
		ot.NewInsert(7, "-middle-"),
	}

	converges, finalA, finalB, err := ot.Converges(doc, opsA, opsB)
	require.NoError(t, err)
	assert.True(t, converges)
	assert.Equal(t, finalA, finalB)
	t.Logf("Converged result: %q", finalA)
}
