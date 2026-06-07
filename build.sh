#!/usr/bin/env bash
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
IMAGE="${IMAGE:-fetch-new}"
VERSION="$(grep -m1 '^version' build.gradle | sed "s/.*'\(.*\)'/\1/")"
PLATFORM="${PLATFORM:-linux/amd64,linux/arm64}"
PUSH=false

# ── Args ──────────────────────────────────────────────────────────────────────
for arg in "$@"; do
  case "$arg" in
    --push)             PUSH=true ;;
    --platform=*)       PLATFORM="${arg#*=}" ;;
    --image=*)          IMAGE="${arg#*=}" ;;
    --help|-h)
      echo "Usage: $0 [--push] [--platform=linux/amd64,linux/arm64] [--image=registry/name]"
      exit 0 ;;
    *) echo "Unknown option: $arg"; exit 1 ;;
  esac
done

TAG_VERSION="${IMAGE}:${VERSION}"
TAG_LATEST="${IMAGE}:latest"

echo "Building  : ${TAG_VERSION}"
echo "Platform  : ${PLATFORM:-<native>}"
echo "Push      : ${PUSH}"
echo

# ── Build ─────────────────────────────────────────────────────────────────────
PLATFORMS_COUNT=$(tr ',' '\n' <<< "$PLATFORM" | wc -l | tr -d ' ')

if [[ "$PLATFORMS_COUNT" -gt 1 ]]; then
  # Multi-platform: buildx requires --push (--load only supports single platform)
  if [[ "$PUSH" != true ]]; then
    echo "ERROR: multi-platform build requires --push (docker buildx --load only supports a single platform)"
    exit 1
  fi
  docker buildx build \
    --platform "$PLATFORM" \
    -t "$TAG_VERSION" \
    -t "$TAG_LATEST" \
    --push \
    .
else
  # Single platform: plain docker build + optional push
  docker buildx build \
    --platform "$PLATFORM" \
    -t "$TAG_VERSION" \
    -t "$TAG_LATEST" \
    --load \
    .

  if [[ "$PUSH" == true ]]; then
    docker push "$TAG_VERSION"
    docker push "$TAG_LATEST"
  fi
fi

echo
echo "Done: ${TAG_VERSION}, ${TAG_LATEST}"
