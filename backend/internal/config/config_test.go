package config

import "testing"

func TestResolveAppVersion(t *testing.T) {
	tests := []struct {
		name       string
		configured string
		build      string
		want       string
	}{
		{name: "uses build when configured missing", configured: "", build: "srv-300.1", want: "srv-300.1"},
		{name: "uses build when configured dev", configured: "dev", build: "srv-300.1", want: "srv-300.1"},
		{name: "uses build when configured migration placeholder", configured: "migration-prep", build: "srv-300.1", want: "srv-300.1"},
		{name: "keeps configured semantic version", configured: "0.4.28", build: "srv-300.1", want: "0.4.28"},
		{name: "keeps configured runtime version", configured: "srv-248.1", build: "srv-300.1", want: "srv-248.1"},
		{name: "falls back to configured when build missing", configured: "migration-prep", build: "", want: "migration-prep"},
		{name: "falls back to dev when both missing", configured: "", build: "", want: "dev"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got := ResolveAppVersion(tc.configured, tc.build)
			if got != tc.want {
				t.Fatalf("ResolveAppVersion(%q, %q) = %q, want %q", tc.configured, tc.build, got, tc.want)
			}
		})
	}
}

func TestLoadDistributionSecurityPolicy(t *testing.T) {
	t.Setenv("ALLOW_INSECURE_DISTRIBUTION_URLS", "true")
	t.Setenv("DISTRIBUTION_PRIVATE_HOST_ALLOWLIST", "internal.example,10.20.10.0/24")
	t.Setenv("DISTRIBUTION_MANIFEST_MAX_BYTES", "2048")
	t.Setenv("DISTRIBUTION_APK_MAX_BYTES", "4096")

	cfg := Load()
	if !cfg.AllowInsecureDistributionURLs {
		t.Fatal("insecure distribution URL policy was not loaded")
	}
	if len(cfg.DistributionPrivateHostAllowlist) != 2 {
		t.Fatalf("private allowlist = %#v", cfg.DistributionPrivateHostAllowlist)
	}
	if cfg.DistributionManifestMaxBytes != 2048 || cfg.DistributionAPKMaxBytes != 4096 {
		t.Fatalf("distribution limits = manifest:%d apk:%d", cfg.DistributionManifestMaxBytes, cfg.DistributionAPKMaxBytes)
	}
}
