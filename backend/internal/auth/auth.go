package auth

import (
    "errors"
    "time"

    "github.com/golang-jwt/jwt/v5"
    "golang.org/x/crypto/bcrypt"
)

type Manager struct {
    secret []byte
    ttl    time.Duration
}

type Claims struct {
    UserID   uint   `json:"userId"`
    Username string `json:"username"`
    IsAdmin  bool   `json:"isAdmin"`
    jwt.RegisteredClaims
}

func NewManager(secret string, ttl time.Duration) *Manager {
    return &Manager{secret: []byte(secret), ttl: ttl}
}

func HashPassword(raw string) (string, error) {
    b, err := bcrypt.GenerateFromPassword([]byte(raw), bcrypt.DefaultCost)
    return string(b), err
}

func CheckPassword(hash, raw string) bool {
    return bcrypt.CompareHashAndPassword([]byte(hash), []byte(raw)) == nil
}

func (m *Manager) Sign(userID uint, username string, isAdmin bool) (string, error) {
    now := time.Now().UTC()
    claims := Claims{
        UserID:   userID,
        Username: username,
        IsAdmin:  isAdmin,
        RegisteredClaims: jwt.RegisteredClaims{
            IssuedAt:  jwt.NewNumericDate(now),
            ExpiresAt: jwt.NewNumericDate(now.Add(m.ttl)),
        },
    }

    token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
    return token.SignedString(m.secret)
}

func (m *Manager) Parse(token string) (*Claims, error) {
    parsed, err := jwt.ParseWithClaims(token, &Claims{}, func(t *jwt.Token) (any, error) {
        return m.secret, nil
    })
    if err != nil {
        return nil, err
    }

    claims, ok := parsed.Claims.(*Claims)
    if !ok || !parsed.Valid {
        return nil, errors.New("invalid token")
    }

    return claims, nil
}
