package storage

import (
    "fmt"
    "io"
    "mime/multipart"
    "os"
    "path/filepath"
    "time"
)

type LocalStore struct {
    root string
}

func NewLocalStore(root string) (*LocalStore, error) {
    if err := os.MkdirAll(root, 0o755); err != nil {
        return nil, fmt.Errorf("create upload root: %w", err)
    }
    return &LocalStore{root: root}, nil
}

func (s *LocalStore) SavePhoto(day string, userID uint, file multipart.File, ext string) (string, error) {
    if ext == "" {
        ext = ".jpg"
    }

    dir := filepath.Join(s.root, day)
    if err := os.MkdirAll(dir, 0o755); err != nil {
        return "", err
    }

    name := fmt.Sprintf("u%d_%d%s", userID, time.Now().UnixNano(), ext)
    dstPath := filepath.Join(dir, name)

    dst, err := os.Create(dstPath)
    if err != nil {
        return "", err
    }
    defer dst.Close()

    if _, err := io.Copy(dst, file); err != nil {
        return "", err
    }

    rel := filepath.ToSlash(filepath.Join(day, name))
    return rel, nil
}
