package repository

import (
	"errors"
	"strings"

	"gorm.io/gorm"
)

var (
	ErrNotFound        = errors.New("record not found")
	ErrAlreadyExists   = errors.New("record already exists")
	ErrInvalidArgument = errors.New("invalid argument")
	ErrUnauthorized    = errors.New("unauthorized")
	ErrForbidden       = errors.New("forbidden")
)

func isUniqueViolation(err error, constraintName string) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "unique") &&
		(strings.Contains(msg, strings.ToLower(constraintName)) ||
			constraintName == "")
}

func IsNotFound(err error) bool {
	return errors.Is(err, gorm.ErrRecordNotFound) || errors.Is(err, ErrNotFound)
}

func IsUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "duplicate key") ||
		strings.Contains(msg, "unique constraint") ||
		strings.Contains(msg, "violates unique")
}
