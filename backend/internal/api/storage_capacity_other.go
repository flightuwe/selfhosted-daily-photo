//go:build !linux

package api

func filesystemCapacity(_ string) (total, free int64) { return 0, 0 }
