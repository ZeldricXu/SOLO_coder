package utils

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"regexp"
	"strings"
	"time"
	"unicode"

	"github.com/google/uuid"
	"github.com/speps/go-hashids/v2"
	"golang.org/x/crypto/bcrypt"
)

const (
	HashIDSalt = "knowledgebase-salt-2024"
	HashIDMin  = 8
)

func HashPassword(password string) (string, error) {
	bytes, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	return string(bytes), err
}

func CheckPassword(password, hash string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	return err == nil
}

func GenerateToken(length int) (string, error) {
	b := make([]byte, length)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.URLEncoding.EncodeToString(b), nil
}

func GenerateAPIKey() string {
	return "kb_" + uuid.New().String() + uuid.New().String()[:8]
}

func EncodeID(id int64) string {
	hd := hashids.NewData()
	hd.Salt = HashIDSalt
	hd.MinLength = HashIDMin
	h, _ := hashids.NewWithData(hd)
	e, _ := h.Encode([]int{int(id)})
	return e
}

func DecodeID(hash string) (int64, error) {
	hd := hashids.NewData()
	hd.Salt = HashIDSalt
	hd.MinLength = HashIDMin
	h, _ := hashids.NewWithData(hd)
	d, err := h.DecodeWithError(hash)
	if err != nil || len(d) == 0 {
		return 0, fmt.Errorf("invalid id")
	}
	return int64(d[0]), nil
}

func GenerateSlug(title string) string {
	slug := strings.ToLower(title)
	slug = strings.TrimSpace(slug)

	var b strings.Builder
	for _, r := range slug {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			b.WriteRune(r)
		case unicode.IsSpace(r) || r == '-' || r == '_':
			b.WriteRune('-')
		}
	}
	slug = b.String()

	slug = regexp.MustCompile(`-+`).ReplaceAllString(slug, "-")
	slug = strings.Trim(slug, "-")

	if slug == "" {
		slug = uuid.New().String()[:8]
	}
	return slug
}

func FormatTime(t time.Time) string {
	return t.Format("2006-01-02 15:04:05")
}

func CountWords(text string) int64 {
	text = strings.TrimSpace(text)
	if text == "" {
		return 0
	}

	re := regexp.MustCompile(`\s+`)
	cleaned := re.ReplaceAllString(text, " ")
	words := strings.Split(cleaned, " ")

	var chineseCount int
	var otherWords int

	for _, word := range words {
		for _, r := range word {
			if unicode.Is(unicode.Han, r) {
				chineseCount++
			}
		}
		hasOther := false
		for _, r := range word {
			if !unicode.Is(unicode.Han, r) {
				hasOther = true
				break
			}
		}
		if hasOther {
			otherWords++
		}
	}

	return int64(chineseCount + otherWords)
}

func TruncateString(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	runes := []rune(s)
	if len(runes) <= maxLen {
		return s
	}
	return string(runes[:maxLen]) + "..."
}

func InArray[T comparable](item T, arr []T) bool {
	for _, v := range arr {
		if v == item {
			return true
		}
	}
	return false
}
