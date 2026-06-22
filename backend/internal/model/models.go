package model

import (
	"time"

	"github.com/google/uuid"
	"gorm.io/gorm"
)

type User struct {
	ID           uuid.UUID `gorm:"type:uuid;primaryKey"`
	Name         string    `gorm:"size:100;not null"`
	Email        string    `gorm:"size:255;uniqueIndex;not null"`
	Phone        string    `gorm:"size:20"`
	Department   string    `gorm:"size:100"`
	Avatar       string    `gorm:"size:500"`
	Role         string    `gorm:"size:20;default:user"`
	WeChatID     string    `gorm:"size:100"`
	DingTalkID   string    `gorm:"size:100"`
	FeishuID     string    `gorm:"size:100"`
	PasswordHash string    `gorm:"size:255"`
	CreatedAt    time.Time
	UpdatedAt    time.Time
	DeletedAt    gorm.DeletedAt `gorm:"index"`
}

type Room struct {
	ID            uuid.UUID `gorm:"type:uuid;primaryKey"`
	Name          string    `gorm:"size:100;not null"`
	Floor         int       `gorm:"not null"`
	Capacity      int       `gorm:"not null"`
	Equipment     string    `gorm:"type:text"`
	Description   string    `gorm:"size:500"`
	Status        string    `gorm:"size:20;default:active"`
	NeedApproval  bool      `gorm:"default:false"`
	ApproverID    *uuid.UUID `gorm:"type:uuid"`
	Location      string    `gorm:"size:200"`
	CreatedAt     time.Time
	UpdatedAt     time.Time
	DeletedAt     gorm.DeletedAt `gorm:"index"`
}

type Booking struct {
	ID            uuid.UUID `gorm:"type:uuid;primaryKey"`
	RoomID        uuid.UUID `gorm:"type:uuid;not null;index"`
	UserID        uuid.UUID `gorm:"type:uuid;not null;index"`
	Title         string    `gorm:"size:200;not null"`
	Description   string    `gorm:"type:text"`
	StartTime     time.Time `gorm:"not null;index"`
	EndTime       time.Time `gorm:"not null;index"`
	Status        string    `gorm:"size:20;default:confirmed"`
	RecurringRule string    `gorm:"size:100"`
	RecurringID   *uuid.UUID `gorm:"type:uuid;index"`
	Attendees     string    `gorm:"type:text"`
	ApprovalStatus string   `gorm:"size:20;default:approved"`
	ApproverID    *uuid.UUID `gorm:"type:uuid"`
	ApprovedAt    *time.Time
	RejectReason  string    `gorm:"size:500"`
	CreatedAt     time.Time
	UpdatedAt     time.Time
	DeletedAt     gorm.DeletedAt `gorm:"index"`
	Room          Room      `gorm:"foreignKey:RoomID"`
	User          User      `gorm:"foreignKey:UserID"`
}

type MeetingDoc struct {
	ID         uuid.UUID `gorm:"type:uuid;primaryKey"`
	BookingID  uuid.UUID `gorm:"type:uuid;not null;uniqueIndex"`
	Agenda     string    `gorm:"type:text"`
	Content    string    `gorm:"type:text"`
	Summary    string    `gorm:"size:1000"`
	IsArchived bool      `gorm:"default:false"`
	ArchivedAt *time.Time
	CreatedAt  time.Time
	UpdatedAt  time.Time
	Booking    Booking `gorm:"foreignKey:BookingID"`
}

type Todo struct {
	ID          uuid.UUID `gorm:"type:uuid;primaryKey"`
	DocID       uuid.UUID `gorm:"type:uuid;not null;index"`
	BookingID   uuid.UUID `gorm:"type:uuid;not null;index"`
	Content     string    `gorm:"size:500;not null"`
	AssigneeID  uuid.UUID `gorm:"type:uuid;not null;index"`
	Status      string    `gorm:"size:20;default:pending"`
	DueDate     *time.Time
	Priority    int       `gorm:"default:1"`
	CreatedAt   time.Time
	UpdatedAt   time.Time
	Assignee    User `gorm:"foreignKey:AssigneeID"`
}

type CheckIn struct {
	ID         uuid.UUID `gorm:"type:uuid;primaryKey"`
	BookingID  uuid.UUID `gorm:"type:uuid;not null;index"`
	UserID     uuid.UUID `gorm:"type:uuid;not null;index"`
	CheckInAt  time.Time `gorm:"not null"`
	QRCode     string    `gorm:"size:100"`
	Status     string    `gorm:"size:20;default:checked_in"`
	CreatedAt  time.Time
	Booking    Booking `gorm:"foreignKey:BookingID"`
	User       User    `gorm:"foreignKey:UserID"`
}

type Notification struct {
	ID         uuid.UUID `gorm:"type:uuid;primaryKey"`
	UserID     uuid.UUID `gorm:"type:uuid;not null;index"`
	Type       string    `gorm:"size:50;not null"`
	Title      string    `gorm:"size:200;not null"`
	Content    string    `gorm:"type:text"`
	Channels   string    `gorm:"size:200"`
	Status     string    `gorm:"size:20;default:unread"`
	BookingID  *uuid.UUID `gorm:"type:uuid;index"`
	CreatedAt  time.Time
	ReadAt     *time.Time
	User       User `gorm:"foreignKey:UserID"`
}

type NotificationPreference struct {
	ID             uuid.UUID `gorm:"type:uuid;primaryKey"`
	UserID         uuid.UUID `gorm:"type:uuid;not null;uniqueIndex"`
	BookingConfirm bool      `gorm:"default:true"`
	UpcomingRemind bool      `gorm:"default:true"`
	MinutesRelease bool      `gorm:"default:true"`
	TodoAssign     bool      `gorm:"default:true"`
	Channels       string    `gorm:"size:200;default:'wechat,email'"`
	CreatedAt      time.Time
	UpdatedAt      time.Time
	User           User `gorm:"foreignKey:UserID"`
}

type QRCodeToken struct {
	ID         uuid.UUID `gorm:"type:uuid;primaryKey"`
	BookingID  uuid.UUID `gorm:"type:uuid;not null;index"`
	Token      string    `gorm:"size:100;not null;uniqueIndex"`
	ExpiresAt  time.Time `gorm:"not null"`
	CreatedAt  time.Time
	Booking    Booking `gorm:"foreignKey:BookingID"`
}
