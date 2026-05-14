package channels

import (
	"fmt"
	"notifypush/internal/models"
	"time"
)

type AppPushChannel struct {
	provider    string
	apiKey      string
	projectID   string
}

func NewAppPushChannel(provider, apiKey, projectID string) *AppPushChannel {
	return &AppPushChannel{
		provider:   provider,
		apiKey:     apiKey,
		projectID:  projectID,
	}
}

func (c *AppPushChannel) GetChannelType() models.ChannelType {
	return models.ChannelTypeApp
}

func (c *AppPushChannel) Send(receiver string, content string, subject string) (*SendResult, error) {
	if receiver == "" {
		return &SendResult{
			Success: false,
			Error:   fmt.Errorf("receiver token is empty"),
			Message: "receiver token is empty",
		}, nil
	}
	
	fmt.Printf("[AppPush] Sending to %s, Title: %s, Content: %s\n", receiver, subject, content)
	time.Sleep(60 * time.Millisecond)
	
	return &SendResult{
		Success: true,
		Message: "App push sent successfully",
	}, nil
}
