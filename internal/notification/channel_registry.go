package notification

import (
	"fmt"
	"sync"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type ChannelRegistry struct {
	channels map[contracts.ChannelType]contracts.NotificationChannel
	mu       sync.RWMutex
}

func NewChannelRegistry() *ChannelRegistry {
	return &ChannelRegistry{
		channels: make(map[contracts.ChannelType]contracts.NotificationChannel),
	}
}

func (r *ChannelRegistry) Register(channel contracts.NotificationChannel) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.channels[channel.GetType()] = channel
}

func (r *ChannelRegistry) Get(channelType contracts.ChannelType) (contracts.NotificationChannel, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	channel, exists := r.channels[channelType]
	if !exists {
		return nil, fmt.Errorf("channel not registered: %s", channelType)
	}
	return channel, nil
}

func (r *ChannelRegistry) List() []contracts.ChannelType {
	r.mu.RLock()
	defer r.mu.RUnlock()

	channels := make([]contracts.ChannelType, 0, len(r.channels))
	for ch := range r.channels {
		channels = append(channels, ch)
	}
	return channels
}
