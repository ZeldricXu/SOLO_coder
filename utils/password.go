package utils

import (
	"golang.org/x/crypto/bcrypt"
)

type PasswordUtils struct {
	cost int
}

func NewPasswordUtils(cost int) *PasswordUtils {
	if cost <= 0 {
		cost = bcrypt.DefaultCost
	}
	return &PasswordUtils{cost: cost}
}

func (p *PasswordUtils) HashPassword(password string) (string, error) {
	hashed, err := bcrypt.GenerateFromPassword([]byte(password), p.cost)
	if err != nil {
		return "", err
	}
	return string(hashed), nil
}

func (p *PasswordUtils) VerifyPassword(password, hashedPassword string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hashedPassword), []byte(password))
	return err == nil
}

func (p *PasswordUtils) ValidatePasswordStrength(password string, minLength int) bool {
	if len(password) < minLength {
		return false
	}
	return true
}
