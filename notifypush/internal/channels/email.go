package channels

import (
	"fmt"
	"net/mail"
	"notifypush/internal/models"
	"time"
)

type EmailChannel struct {
	smtpHost string
	smtpPort int
	username string
	password string
	fromName string
	fromAddr string
}

func NewEmailChannel(smtpHost string, smtpPort int, username, password, fromName, fromAddr string) *EmailChannel {
	return &EmailChannel{
		smtpHost: smtpHost,
		smtpPort: smtpPort,
		username: username,
		password: password,
		fromName: fromName,
		fromAddr: fromAddr,
	}
}

func (c *EmailChannel) GetChannelType() models.ChannelType {
	return models.ChannelTypeEmail
}

func (c *EmailChannel) Send(receiver string, content string, subject string) (*SendResult, error) {
	if receiver == "" {
		return &SendResult{
			Success: false,
			Error:   fmt.Errorf("receiver is empty"),
			Message: "receiver is empty",
		}, nil
	}
	
	_, err := mail.ParseAddress(receiver)
	if err != nil {
		return &SendResult{
			Success: false,
			Error:   fmt.Errorf("invalid email address: %s", err.Error()),
			Message: "invalid email address",
		}, nil
	}
	
	fmt.Printf("[Email] Sending to %s, Subject: %s, Content: %s\n", receiver, subject, content)
	time.Sleep(80 * time.Millisecond)
	
	return &SendResult{
		Success: true,
		Message: "Email sent successfully",
	}, nil
}
