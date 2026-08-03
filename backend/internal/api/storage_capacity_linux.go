//go:build linux

package api

import "golang.org/x/sys/unix"

func filesystemCapacity(path string) (total, free int64) {
	var stat unix.Statfs_t
	if err := unix.Statfs(path, &stat); err != nil {
		return 0, 0
	}
	return int64(stat.Blocks) * int64(stat.Bsize), int64(stat.Bavail) * int64(stat.Bsize)
}
