package core

import (
	"context"
	"fmt"
	"sync"
)

type ResourcePool struct {
	mu        sync.Mutex
	resources chan struct{}
	used      int
	total     int
}

func NewResourcePool(size int) *ResourcePool {
	return &ResourcePool{
		resources: make(chan struct{}, size),
		total:     size,
	}
}

func (p *ResourcePool) Acquire(ctx context.Context) (struct{}, error) {
	select {
	case p.resources <- struct{}{}:
		p.mu.Lock()
		p.used++
		p.mu.Unlock()
		return struct{}{}, nil
	case <-ctx.Done():
		return struct{}{}, ctx.Err()
	}
}

func (p *ResourcePool) Release() {
	select {
	case <-p.resources:
		p.mu.Lock()
		p.used--
		p.mu.Unlock()
	default:
	}
}

func (p *ResourcePool) Used() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.used
}

func (p *ResourcePool) Total() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.total
}

func (p *ResourcePool) Resize(newSize int) error {
	if newSize <= 0 {
		return fmt.Errorf("pool size must be positive")
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	if newSize == p.total {
		return nil
	}

	newChan := make(chan struct{}, newSize)

	currentUsed := p.used
	if currentUsed > newSize {
		currentUsed = newSize
	}

	for i := 0; i < currentUsed; i++ {
		select {
		case newChan <- struct{}{}:
		default:
		}
	}

	p.resources = newChan
	p.total = newSize

	return nil
}
