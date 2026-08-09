package email

import (
	"encoding/base64"
	"strings"
	"testing"
)

func testKey(fill byte) string {
	return base64.StdEncoding.EncodeToString([]byte(strings.Repeat(string(fill), 32)))
}

func TestSecretsEncryptDecryptAndRotate(t *testing.T) {
	oldSecrets, err := NewSecrets(testKey('a'), "")
	if err != nil {
		t.Fatal(err)
	}
	ciphertext, err := oldSecrets.Encrypt("smtp-password", "very-secret")
	if err != nil {
		t.Fatal(err)
	}
	rotated, err := NewSecrets(testKey('b'), testKey('a'))
	if err != nil {
		t.Fatal(err)
	}
	plain, usedOld, err := rotated.Decrypt("smtp-password", ciphertext)
	if err != nil || plain != "very-secret" || !usedOld {
		t.Fatalf("rotation decrypt = %q, %v, %v", plain, usedOld, err)
	}
	if _, _, err := rotated.Decrypt("email-action-token", ciphertext); err == nil {
		t.Fatal("purpose-separated ciphertext decrypted with wrong purpose")
	}
}

func TestSecretsRejectMissingWrongAndUnrelatedKeys(t *testing.T) {
	if _, err := NewSecrets("", ""); err == nil {
		t.Fatal("missing key accepted")
	}
	if _, err := NewSecrets(base64.StdEncoding.EncodeToString([]byte("short")), ""); err == nil {
		t.Fatal("short key accepted")
	}
	one, _ := NewSecrets(testKey('a'), "")
	two, _ := NewSecrets(testKey('b'), "")
	ciphertext, _ := one.Encrypt("smtp-password", "secret")
	if _, _, err := two.Decrypt("smtp-password", ciphertext); err == nil {
		t.Fatal("unrelated key decrypted ciphertext")
	}
}

func TestNormalizeAddress(t *testing.T) {
	display, normalized, err := NormalizeAddress("  Alice.Example@Example.COM  ")
	if err != nil || display != "Alice.Example@Example.COM" || normalized != "alice.example@example.com" {
		t.Fatalf("normalize = %q %q %v", display, normalized, err)
	}
	for _, invalid := range []string{"Display <a@example.com>", "a@example.com\r\nBcc:x@example.com", "missing-at"} {
		if _, _, err := NormalizeAddress(invalid); err == nil {
			t.Fatalf("invalid address accepted: %q", invalid)
		}
	}
}
