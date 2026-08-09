package email

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"strings"

	"golang.org/x/crypto/hkdf"
)

var ErrMasterKeyUnavailable = errors.New("email master key unavailable")

type Secrets struct {
	current  []byte
	previous []byte
}

func NewSecrets(currentB64, previousB64 string) (*Secrets, error) {
	current, err := decodeMasterKey(currentB64)
	if err != nil {
		return nil, err
	}
	var previous []byte
	if strings.TrimSpace(previousB64) != "" {
		previous, err = decodeMasterKey(previousB64)
		if err != nil {
			return nil, fmt.Errorf("previous email master key: %w", err)
		}
	}
	return &Secrets{current: current, previous: previous}, nil
}

func decodeMasterKey(value string) ([]byte, error) {
	if strings.TrimSpace(value) == "" {
		return nil, ErrMasterKeyUnavailable
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(value))
	if err != nil || len(raw) != 32 {
		return nil, errors.New("EMAIL_MASTER_KEY_B64 must encode exactly 32 bytes")
	}
	return raw, nil
}

func (s *Secrets) Encrypt(purpose, plaintext string) (string, error) {
	if s == nil || len(s.current) != 32 {
		return "", ErrMasterKeyUnavailable
	}
	key, err := deriveKey(s.current, purpose)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	sealed := gcm.Seal(nil, nonce, []byte(plaintext), []byte(purpose))
	payload := append(nonce, sealed...)
	return "v1." + base64.RawURLEncoding.EncodeToString(payload), nil
}

// Decrypt returns whether an old key was used so callers can rotate values lazily.
func (s *Secrets) Decrypt(purpose, ciphertext string) (string, bool, error) {
	if s == nil || len(s.current) != 32 {
		return "", false, ErrMasterKeyUnavailable
	}
	for index, master := range [][]byte{s.current, s.previous} {
		if len(master) == 0 {
			continue
		}
		plain, err := decryptWith(master, purpose, ciphertext)
		if err == nil {
			return plain, index == 1, nil
		}
	}
	return "", false, errors.New("email secret cannot be decrypted")
}

func (s *Secrets) IPHash(ip string) string {
	if s == nil || len(s.current) != 32 {
		return ""
	}
	key, _ := deriveKey(s.current, "rate-limit-ip")
	mac := hmac.New(sha256.New, key)
	_, _ = mac.Write([]byte(strings.TrimSpace(ip)))
	return fmt.Sprintf("%x", mac.Sum(nil))
}

func deriveKey(master []byte, purpose string) ([]byte, error) {
	reader := hkdf.New(sha256.New, master, []byte("daily-email-v1"), []byte(purpose))
	key := make([]byte, 32)
	_, err := io.ReadFull(reader, key)
	return key, err
}

func decryptWith(master []byte, purpose, ciphertext string) (string, error) {
	if !strings.HasPrefix(ciphertext, "v1.") {
		return "", errors.New("unsupported ciphertext version")
	}
	payload, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(ciphertext, "v1."))
	if err != nil {
		return "", err
	}
	key, err := deriveKey(master, purpose)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil || len(payload) < gcm.NonceSize() {
		return "", errors.New("invalid email secret")
	}
	plain, err := gcm.Open(nil, payload[:gcm.NonceSize()], payload[gcm.NonceSize():], []byte(purpose))
	return string(plain), err
}
