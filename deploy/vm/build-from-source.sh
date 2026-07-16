#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEPLOY_ROOT="$(cd "$BACKEND_ROOT/.." && pwd)"
FRONTEND_ROOT="$DEPLOY_ROOT/Skytrack-Frontend"

build_backend=false
build_frontend=false

usage() {
  cat <<EOF
Usage: ./build-from-source.sh [--backend] [--frontend] [--all]

Builds scheduling-core.jar and/or Skytrack-Frontend/dist from source.
Expects sibling folders:
  $DEPLOY_ROOT/scheduling-core/
  $DEPLOY_ROOT/Skytrack-Frontend/

Examples:
  ./build-from-source.sh --all
  ./build-from-source.sh --backend
  ./build-from-source.sh --frontend
EOF
}

if [ $# -eq 0 ]; then
  build_backend=true
  build_frontend=true
else
  while [ $# -gt 0 ]; do
    case "$1" in
      --backend) build_backend=true ;;
      --frontend) build_frontend=true ;;
      --all) build_backend=true; build_frontend=true ;;
      -h|--help) usage; exit 0 ;;
      *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
    shift
  done
fi

if [ "$build_backend" = true ]; then
  if [ ! -d "$BACKEND_ROOT" ]; then
    echo "Missing backend source: $BACKEND_ROOT"
    exit 1
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "Java not found. Install JDK 17 (openjdk-17-jdk)."
    exit 1
  fi
  echo "Building backend jar..."
  pushd "$BACKEND_ROOT" >/dev/null
  chmod +x gradlew
  ./gradlew bootJar -x test
  popd >/dev/null
  echo "Backend jar ready under: $BACKEND_ROOT/build/libs/"
fi

if [ "$build_frontend" = true ]; then
  if [ ! -d "$FRONTEND_ROOT" ]; then
    echo "Missing frontend source: $FRONTEND_ROOT"
    exit 1
  fi
  if ! command -v npm >/dev/null 2>&1; then
    echo "npm not found. Install Node.js 18+ and npm."
    exit 1
  fi
  echo "Building frontend dist..."
  pushd "$FRONTEND_ROOT" >/dev/null
  if [ ! -d node_modules ] || [ ! -x node_modules/.bin/vite ]; then
    echo "Installing npm dependencies..."
    npm ci
  else
    echo "Using bundled node_modules (skip npm ci)."
  fi
  npm run build
  popd >/dev/null
  echo "Frontend dist ready: $FRONTEND_ROOT/dist/"
fi

echo "Build from source finished."
