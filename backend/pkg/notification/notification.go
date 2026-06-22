package notification

import (
	"meeting-system/internal/model"
	"meeting-system/pkg/database"

	"github.com/google/uuid"
)

type NotificationChannel string

const (
	ChannelWeChat   NotificationChannel = "wechat"
	ChannelDingTalk NotificationChannel = "dingtalk"
	ChannelFeishu   NotificationChannel = "feishu"
	ChannelEmail    NotificationChannel = "email"
)

type NotificationType string

const (
	TypeBookingConfirm NotificationType = "booking_confirm"
	TypeUpcomingRemind NotificationType = "upcoming_remind"
	TypeMinutesRelease NotificationType = "minutes_release"
	TypeTodoAssign     NotificationType = "todo_assign"
)

func SendNotification(userID uuid.UUID, notifType NotificationType, title, content string, bookingID *uuid.UUID, channels []NotificationChannel) error {
	channelStr := ""
	for i, ch := range channels {
		if i > 0 {
			channelStr += ","
		}
		channelStr += string(ch)
	}

	notification := model.Notification{
		ID:        uuid.New(),
		UserID:    userID,
		Type:      string(notifType),
		Title:     title,
		Content:   content,
		Channels:  channelStr,
		Status:    "unread",
		BookingID: bookingID,
	}

	return database.DB.Create(&notification).Error
}

func SendBookingConfirmation(userID uuid.UUID, title, content string, bookingID uuid.UUID) {
	SendNotification(userID, TypeBookingConfirm, title, content, &bookingID,
		[]NotificationChannel{ChannelWeChat, ChannelEmail})
}

func SendUpcomingReminder(userID uuid.UUID, title, content string, bookingID uuid.UUID) {
	SendNotification(userID, TypeUpcomingRemind, title, content, &bookingID,
		[]NotificationChannel{ChannelWeChat, ChannelDingTalk})
}

func SendMinutesRelease(userID uuid.UUID, title, content string, bookingID uuid.UUID) {
	SendNotification(userID, TypeMinutesRelease, title, content, &bookingID,
		[]NotificationChannel{ChannelEmail, ChannelFeishu})
}

func SendTodoAssign(userID uuid.UUID, title, content string) {
	SendNotification(userID, TypeTodoAssign, title, content, nil,
		[]NotificationChannel{ChannelWeChat, ChannelEmail})
}

func SendWeChatMessage(userID uuid.UUID, content string) error {
	return nil
}

func SendDingTalkMessage(userID uuid.UUID, content string) error {
	return nil
}

func SendFeishuMessage(userID uuid.UUID, content string) error {
	return nil
}

func SendEmail(userID uuid.UUID, subject, content string) error {
	return nil
}
