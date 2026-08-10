#!/usr/bin/env bash
set -euo pipefail

# Reproducible, secret-free toolchain for vm-daily-build-01.
# Runner registration is deliberately a separate approval step.

NODE_VERSION="22.23.2"
NODE_SHA256="d60acfe00a2932254bb0ad20e01b0d74397a0875595de719654b214f4b03f307"
GO_VERSION="1.26.5"
GO_SHA256="5c2c3b16caefa1d968a94c1daca04a7ca301a496d9b086e17ad77bb81393f053"
GRADLE_VERSION="8.2.1"
GRADLE_SHA256="03ec176d388f2aa99defcadc3ac6adf8dd2bce5145a129659537c0874dea5ad1"
ANDROID_TOOLS_VERSION="15859902"
ANDROID_TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
FORGEJO_RUNNER_VERSION="6.3.1"
FORGEJO_RUNNER_SHA256="fb27a4c722210044030aaf09211eb7fc5d5c497a238be00e032b2f5ffa6da6c0"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root." >&2
  exit 1
fi

work_dir="$(mktemp -d /var/tmp/daily-runner-provision.XXXXXX)"
trap 'rm -rf -- "${work_dir}"' EXIT

verify() {
  local expected="$1"
  local file="$2"
  printf '%s  %s\n' "${expected}" "${file}" | sha256sum --check --status
}

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install --yes --no-install-recommends \
  ca-certificates curl git jq unzip xz-utils build-essential \
  openjdk-17-jdk-headless qemu-guest-agent nftables

node_archive="${work_dir}/node.tar.xz"
curl --fail --location --retry 3 \
  "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
  --output "${node_archive}"
verify "${NODE_SHA256}" "${node_archive}"
mkdir -p "/opt/node-v${NODE_VERSION}"
tar --extract --xz --file "${node_archive}" --strip-components=1 --directory "/opt/node-v${NODE_VERSION}"
ln -sfn "/opt/node-v${NODE_VERSION}/bin/node" /usr/local/bin/node
ln -sfn "/opt/node-v${NODE_VERSION}/bin/npm" /usr/local/bin/npm
ln -sfn "/opt/node-v${NODE_VERSION}/bin/npx" /usr/local/bin/npx

go_archive="${work_dir}/go.tar.gz"
curl --fail --location --retry 3 \
  "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" \
  --output "${go_archive}"
verify "${GO_SHA256}" "${go_archive}"
mkdir -p "/opt/go-${GO_VERSION}"
tar --extract --gzip --file "${go_archive}" --strip-components=1 --directory "/opt/go-${GO_VERSION}"
ln -sfn "/opt/go-${GO_VERSION}/bin/go" /usr/local/bin/go
ln -sfn "/opt/go-${GO_VERSION}/bin/gofmt" /usr/local/bin/gofmt

gradle_archive="${work_dir}/gradle.zip"
curl --fail --location --retry 3 \
  "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
  --output "${gradle_archive}"
verify "${GRADLE_SHA256}" "${gradle_archive}"
unzip -q -o "${gradle_archive}" -d /opt
ln -sfn "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle

android_archive="${work_dir}/android-command-line-tools.zip"
curl --fail --location --retry 3 \
  "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_TOOLS_VERSION}_latest.zip" \
  --output "${android_archive}"
verify "${ANDROID_TOOLS_SHA256}" "${android_archive}"
mkdir -p /opt/android-sdk/cmdline-tools/latest
unzip -q -o "${android_archive}" -d "${work_dir}/android-tools"
cp -a "${work_dir}/android-tools/cmdline-tools/." /opt/android-sdk/cmdline-tools/latest/
set +o pipefail
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses >/dev/null
license_status="${PIPESTATUS[1]}"
set -o pipefail
if [[ "${license_status}" -ne 0 ]]; then
  echo "Android SDK license acceptance failed with exit code ${license_status}." >&2
  exit "${license_status}"
fi
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"
chmod -R a+rX /opt/android-sdk

runner_binary="${work_dir}/forgejo-runner"
curl --fail --location --retry 3 \
  "https://code.forgejo.org/forgejo/runner/releases/download/v${FORGEJO_RUNNER_VERSION}/forgejo-runner-${FORGEJO_RUNNER_VERSION}-linux-amd64" \
  --output "${runner_binary}"
verify "${FORGEJO_RUNNER_SHA256}" "${runner_binary}"
install -o root -g root -m 0755 "${runner_binary}" /usr/local/bin/forgejo-runner

systemctl enable --now qemu-guest-agent nftables

java -version
node --version
npm --version
go version
gradle --version | sed -n '1,8p'
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --list_installed
forgejo-runner --version
