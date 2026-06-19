package utils

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"math/big"
	"strings"
	"time"
	"unicode"

	"github.com/golang-jwt/jwt/v4"
	"github.com/google/uuid"
)

func GenerateID() string {
	return uuid.New().String()
}

func HashSHA256(s string) string {
	h := sha256.New()
	h.Write([]byte(s))
	return hex.EncodeToString(h.Sum(nil))
}

func RandomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		num, _ := rand.Int(rand.Reader, big.NewInt(int64(len(letters))))
		b[i] = letters[num.Int64()]
	}
	return string(b)
}

func RandomInt(min, max int) int {
	if min >= max {
		return min
	}
	num, _ := rand.Int(rand.Reader, big.NewInt(int64(max-min+1)))
	return int(num.Int64()) + min
}

func TruncateString(s string, maxLen int) string {
	runes := []rune(s)
	if len(runes) <= maxLen {
		return s
	}
	if maxLen <= 3 {
		return string(runes[:maxLen])
	}
	return string(runes[:maxLen-3]) + "..."
}

func SanitizeFilename(name string) string {
	name = strings.TrimSpace(name)
	invalid := []string{"/", "\\", ":", "*", "?", "\"", "<", ">", "|", "\000"}
	for _, ch := range invalid {
		name = strings.ReplaceAll(name, ch, "_")
	}
	name = strings.Trim(name, ". ")
	if name == "" {
		name = "unnamed"
	}
	return name
}

func PaginateOffset(page, pageSize int) (int, int) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	offset := (page - 1) * pageSize
	return offset, pageSize
}

var pinyinMap = map[rune]string{
	'一': "yi", '二': "er", '三': "san", '四': "si", '五': "wu",
	'中': "zhong", '国': "guo", '人': "ren",
	'文': "wen", '章': "zhang", '字': "zi",
	'搜': "sou", '索': "suo", '引': "yin", '擎': "qing",
	'标': "biao", '名': "ming", '称': "cheng",
	'用': "yong", '户': "hu", '管': "guan", '理': "li",
	'数': "shu", '据': "ju", '信': "xin", '息': "xi",
	'网': "wang", '络': "luo", '电': "dian", '脑': "nao",
	'技': "ji", '术': "shu", '科': "ke", '学': "xue",
	'公': "gong", '司': "si", '企': "qi", '业': "ye",
	'团': "tuan", '队': "dui",
	'问': "wen", '题': "ti", '解': "jie", '决': "jue",
	'新': "xin", '时': "shi", '间': "jian",
	'开': "kai", '发': "fa", '测': "ce", '试': "shi",
	'维': "wei", '护': "hu", '服': "fu", '务': "wu",
	'安': "an", '全': "quan",
	'登': "deng", '录': "lu",
	'修': "xiu", '改': "gai", '删': "shan", '除': "chu",
	'增': "zeng", '移': "yi", '动': "dong",
}

func PinyinOf(s string) string {
	var result strings.Builder
	for _, r := range s {
		if p, ok := pinyinMap[r]; ok {
			result.WriteString(p)
		} else if unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.IsSpace(r) {
			result.WriteRune(r)
		}
	}
	return strings.ToLower(result.String())
}

func EditDistance(s1, s2 string) int {
	r1, r2 := []rune(s1), []rune(s2)
	m, n := len(r1), len(r2)
	if m == 0 {
		return n
	}
	if n == 0 {
		return m
	}
	dp := make([][]int, m+1)
	for i := range dp {
		dp[i] = make([]int, n+1)
	}
	for i := 0; i <= m; i++ {
		dp[i][0] = i
	}
	for j := 0; j <= n; j++ {
		dp[0][j] = j
	}
	for i := 1; i <= m; i++ {
		for j := 1; j <= n; j++ {
			if r1[i-1] == r2[j-1] {
				dp[i][j] = dp[i-1][j-1]
			} else {
				dp[i][j] = int(math.Min(math.Min(float64(dp[i-1][j]), float64(dp[i][j-1])), float64(dp[i-1][j-1]))) + 1
			}
		}
	}
	return dp[m][n]
}

func GenerateJWT(userID, tenantID, secret string, expireHours int) (string, error) {
	claims := jwt.MapClaims{
		"user_id":   userID,
		"tenant_id": tenantID,
		"exp":       time.Now().Add(time.Duration(expireHours) * time.Hour).Unix(),
		"iat":       time.Now().Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(secret))
}

func ParseJWT(tokenStr, secret string) (map[string]interface{}, error) {
	token, err := jwt.Parse(tokenStr, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		return []byte(secret), nil
	})
	if err != nil {
		return nil, err
	}
	if claims, ok := token.Claims.(jwt.MapClaims); ok && token.Valid {
		return claims, nil
	}
	return nil, fmt.Errorf("invalid token")
}
