package api

import (
	"os"
	"strings"
)

func (s *Server) serverInstanceID() string {
	if host, err := os.Hostname(); err == nil {
		clean := strings.TrimSpace(host)
		if clean != "" {
			return clean
		}
	}
	return "unknown"
}
