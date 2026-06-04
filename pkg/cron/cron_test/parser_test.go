package cron_test

import (
	"testing"
	"time"

	"github.com/distributed-task-scheduler/pkg/cron"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParse_StandardFiveField(t *testing.T) {
	s, err := cron.Parse("*/5 * * * *")
	require.NoError(t, err)
	assert.NotNil(t, s)
}

func TestParse_SixFieldWithSeconds(t *testing.T) {
	s, err := cron.Parse("0 */5 * * * *")
	require.NoError(t, err)
	assert.NotNil(t, s)
	assert.True(t, s.Second[0])
}

func TestParse_InvalidFieldCount(t *testing.T) {
	_, err := cron.Parse("* * *")
	assert.Error(t, err)

	_, err = cron.Parse("* * * * * * *")
	assert.Error(t, err)
}

func TestParse_EveryMinute(t *testing.T) {
	s, err := cron.Parse("* * * * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 10, 30, 0, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 1, 15, 10, 31, 0, 0, time.UTC), next)
}

func TestParse_SpecificMinute(t *testing.T) {
	s, err := cron.Parse("30 * * * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 1, 15, 10, 30, 0, 0, time.UTC), next)
}

func TestParse_SpecificHour(t *testing.T) {
	s, err := cron.Parse("0 14 * * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 1, 15, 14, 0, 0, 0, time.UTC), next)
}

func TestParse_RangeExpression(t *testing.T) {
	s, err := cron.Parse("0 9-17 * * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 8, 0, 0, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 1, 15, 9, 0, 0, 0, time.UTC), next)

	next2 := s.Next(next)
	assert.Equal(t, time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC), next2)
}

func TestParse_StepExpression(t *testing.T) {
	s, err := cron.Parse("*/15 * * * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 1, 15, 10, 15, 0, 0, time.UTC), next)

	next2 := s.Next(next)
	assert.Equal(t, time.Date(2026, 1, 15, 10, 30, 0, 0, time.UTC), next2)
}

func TestParse_CommaExpression(t *testing.T) {
	s, err := cron.Parse("0 0,12 * * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 6, 0, 0, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 1, 15, 12, 0, 0, 0, time.UTC), next)
}

func TestParse_DayOfWeek(t *testing.T) {
	s, err := cron.Parse("0 0 * * 1")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 10, 0, 0, 0, time.UTC)
	next := s.Next(base)

	assert.Equal(t, time.Monday, next.Weekday())
}

func TestNext_SecondPrecision(t *testing.T) {
	s, err := cron.Parse("0 * * * * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 10, 30, 45, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 1, 15, 10, 31, 0, 0, time.UTC), next)
}

func TestNext_CrossDay(t *testing.T) {
	s, err := cron.Parse("0 0 * * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 23, 59, 59, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 1, 16, 0, 0, 0, 0, time.UTC), next)
}

func TestNext_CrossMonth(t *testing.T) {
	s, err := cron.Parse("0 0 1 * *")
	require.NoError(t, err)

	base := time.Date(2026, 1, 15, 0, 0, 0, 0, time.UTC)
	next := s.Next(base)
	assert.Equal(t, time.Date(2026, 2, 1, 0, 0, 0, 0, time.UTC), next)
}

func TestIsDue(t *testing.T) {
	s, err := cron.Parse("30 10 * * *")
	require.NoError(t, err)

	due := time.Date(2026, 1, 15, 10, 30, 0, 0, time.UTC)
	assert.True(t, s.IsDue(due))

	notDue := time.Date(2026, 1, 15, 10, 31, 0, 0, time.UTC)
	assert.False(t, s.IsDue(notDue))
}

func TestGetShard_Deterministic(t *testing.T) {
	shard1 := cron.GetShard("task-abc", 32)
	shard2 := cron.GetShard("task-abc", 32)
	assert.Equal(t, shard1, shard2)
}

func TestGetShard_WithinRange(t *testing.T) {
	for i := 0; i < 1000; i++ {
		shard := cron.GetShard("task-"+time.Now().String(), 32)
		assert.GreaterOrEqual(t, shard, 0)
		assert.Less(t, shard, 32)
	}
}

func TestGetShard_Distribution(t *testing.T) {
	counts := make(map[int]int)
	for i := 0; i < 1000; i++ {
		shard := cron.GetShard(string(rune(i)), 8)
		counts[shard]++
	}

	for _, count := range counts {
		assert.Greater(t, count, 0, "each shard should receive some tasks")
	}
}

func TestParse_InvalidStep(t *testing.T) {
	_, err := cron.Parse("*/abc * * * *")
	assert.Error(t, err)
}

func TestParse_InvalidRange(t *testing.T) {
	_, err := cron.Parse("0 abc-def * * *")
	assert.Error(t, err)
}

func TestNext_EveryFiveMinutesSequence(t *testing.T) {
	s, err := cron.Parse("*/5 * * * *")
	require.NoError(t, err)

	base := time.Date(2026, 3, 15, 10, 0, 0, 0, time.UTC)
	for i := 0; i < 12; i++ {
		next := s.Next(base)
		assert.True(t, next.After(base))
		diff := next.Sub(base)
		assert.Equal(t, 5*time.Minute, diff)
		base = next
	}
}
