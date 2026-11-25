#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
source scripts/cloud-env.sh

tools_dir=/workspace/.ai-assistant-toolchain
maven_version=3.9.16
maven_dir="$tools_dir/apache-maven-$maven_version"
mkdir -p "$tools_dir" "$MAVEN_USER_HOME"

case "$(uname -m)" in
    x86_64)
        jdk_arch=x64
        jdk_sha=bb8a93f9ae6e7a693b2b721e85dbeff26ae1a77447985a8d7afcdd9c7cf6222e ;;
    aarch64)
        jdk_arch=aarch64
        jdk_sha=8c72816a6b308842d5ed1f13e5826b69c567da4784f7238d7e800efa71fe54b1 ;;
    *) printf 'Unsupported architecture: %s\n' "$(uname -m)" >&2; exit 1 ;;
esac

install_archive() (
    local url=$1 checksum_command=$2 checksum=$3 destination=$4
    local staging
    staging=$(mktemp -d "$tools_dir/download.XXXXXX")
    trap 'rm -f -- "$staging/archive.tar.gz"; if [[ -d "$staging/extracted" ]]; then rm -r -- "$staging/extracted"; fi; rmdir -- "$staging"' EXIT
    curl --fail --silent --show-error --location --retry 2 --output "$staging/archive.tar.gz" "$url"
    printf '%s  %s\n' "$checksum" "$staging/archive.tar.gz" | "$checksum_command" --check
    mkdir "$staging/extracted"
    tar -xzf "$staging/archive.tar.gz" -C "$staging/extracted" --strip-components=1
    mv -T -- "$staging/extracted" "$destination"
)

if [[ ! -x "$JAVA_HOME/bin/javac" ]]; then
    install_archive "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jdk_${jdk_arch}_linux_hotspot_21.0.13.1_2.tar.gz" sha256sum "$jdk_sha" "$JAVA_HOME"
fi
if [[ ! -x "$maven_dir/bin/mvn" ]]; then
    install_archive "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$maven_version/apache-maven-$maven_version-bin.tar.gz" sha512sum \
        831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6 \
        "$maven_dir"
fi

javac -version
mvn --version
