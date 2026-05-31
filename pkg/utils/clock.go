package utils

import "time"

type RealClock struct{}

func NewRealClock() *RealClock {
	return &RealClock{}
}

func (c *RealClock) Now() time.Time {
	return time.Now().UTC()
}

func (c *RealClock) After(d time.Duration) <-chan time.Time {
	return time.After(d)
}

type MockClock struct {
	now time.Time
}

func NewMockClock(t time.Time) *MockClock {
	return &MockClock{now: t}
}

func (c *MockClock) Now() time.Time {
	return c.now
}

func (c *MockClock) Set(t time.Time) {
	c.now = t
}

func (c *MockClock) After(d time.Duration) <-chan time.Time {
	ch := make(chan time.Time, 1)
	ch <- c.now.Add(d)
	return ch
}
