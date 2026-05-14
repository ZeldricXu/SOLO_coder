package channels

import (
	"fmt"
	"notifypush/internal/models"
	"time"
)

type SMSChannel struct {
	provider  string
	apiKey    string
	signName  string
	templateCode string
}

func NewSMSChannel(provider, apiKey, signName, templateCode string) *SMSChannel {
	return &SMSChannel{
		provider:     provider,
		apiKey:       apiKey,
		signName:     signName,
		templateCode: templateCode,
	}
}

func (c *SMSChannel) GetChannelType() models.ChannelType {
	return models.ChannelTypeSMS
}

func (c *SMSChannel) Send(receiver string, content string, subject string) (*SendResult, error) {
	if receiver == "" {
		return &SendResult{
			Success: false,
			Error:   fmt.Errorf("receiver is empty"),
			Message: "receiver is empty",
		}, nil
	}
	
	if len(receiver) != 11 {
		return &SendResult{
			Success: false,
			Error:   fmt.Errorf("invalid phone number format"),
			Message: "invalid phone number format",
		}, nil
	}
	
	fmt.Printf("[SMS] Sending to %s: %s\n", receiver, content)
	time.Sleep(50 * time.Millisecond)
	
	return &SendResult{
		Success: true,
		Message: "SMS sent successfully",
	}, nil
}
