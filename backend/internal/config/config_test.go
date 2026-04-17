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
