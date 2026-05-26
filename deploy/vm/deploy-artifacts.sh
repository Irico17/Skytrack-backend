#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo: sudo ./deploy-artifacts.sh"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

JAR_SRC="$BUNDLE_ROOT/backend/scheduling-core.jar"
FRONTEND_SRC="$BUNDLE_ROOT/frontend/dist"
DATA_SRC="$BUNDLE_ROOT/backend/data"

if [ ! -f "$JAR_SRC" ]; then
  echo "Missing backend jar: $JAR_SRC"
  exit 1
fi
if [ ! -d "$FRONTEND_SRC" ]; then
  echo "Missing frontend dist: $FRONTEND_SRC"
  exit 1
fi
if [ ! -d "$DATA_SRC" ]; then
  echo "Missing backend data: $DATA_SRC"
  exit 1
fi

install -d -m 0755 /opt/skytrack/backend
install -d -m 0755 /opt/skytrack/backend/data
install -d -m 0755 /opt/skytrack/backend/data/results
install -d -m 0755 /var/www/skytrack

install -m 0644 "$JAR_SRC" /opt/skytrack/backend/scheduling-core.jar

rm -rf /var/www/skytrack/*
cp -a "$FRONTEND_SRC/." /var/www/skytrack/

cp -a "$DATA_SRC/." /opt/skytrack/backend/data/
install -d -m 0755 /opt/skytrack/backend/data/results

chown -R skytrack:skytrack /opt/skytrack
chown -R www-data:www-data /var/www/skytrack

systemctl daemon-reload
systemctl enable skytrack-backend
systemctl restart skytrack-backend
nginx -t
systemctl reload nginx

for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8081/actuator/health >/dev/null; then
    echo "Backend health OK"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "Backend health check failed. Logs: journalctl -u skytrack-backend -n 120 --no-pager"
    exit 1
  fi
  sleep 1
done

curl -fsS http://127.0.0.1/ >/dev/null
systemctl --no-pager --full status skytrack-backend || true

echo "Deployment finished. Open: http://1inf54-981-2b.inf.pucp.edu.pe/"
