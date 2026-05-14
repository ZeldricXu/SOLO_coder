package channels

import (
	"notifypush/internal/models"
)

type SendResult struct {
	Success  bool
	Error    error
	Message  string
}

type Channel interface {
	Send(receiver string, content string, subject string) (*SendResult, error)
	GetChannelType() models.ChannelType
}

type ChannelRegistry struct {
	channels map[models.ChannelType]Channel
}

func NewChannelRegistry() *ChannelRegistry {
	return &ChannelRegistry{
		channels: make(map[models.ChannelType]Channel),
	}
}

func (r *ChannelRegistry) Register(channelType models.ChannelType, channel Channel) {
	r.channels[channelType] = channel
}

func (r *ChannelRegistry) Get(channelType models.ChannelType) (Channel, bool) {
	channel, exists := r.channels[channelType]
	return channel, exists
}
