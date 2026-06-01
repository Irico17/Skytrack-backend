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

if [ -f "$SCRIPT_DIR/skytrack-backend.service" ]; then
  install -m 0644 "$SCRIPT_DIR/skytrack-backend.service" /etc/systemd/system/skytrack-backend.service
fi

if [ -f /etc/skytrack/backend.env ]; then
  SKYTRACK_JAVA_OPTS='JAVA_OPTS=-Xms192m -Xmx1024m -XX:ActiveProcessorCount=2 -XX:+UseG1GC -XX:MaxGCPauseMillis=250 -XX:+UseStringDeduplication -Djava.util.concurrent.ForkJoinPool.common.parallelism=1 -XX:+ExitOnOutOfMemoryError'
  if grep -q '^JAVA_OPTS=' /etc/skytrack/backend.env; then
    sed -i "s|^JAVA_OPTS=.*|$SKYTRACK_JAVA_OPTS|" /etc/skytrack/backend.env
  else
    printf '\n%s\n' "$SKYTRACK_JAVA_OPTS" >> /etc/skytrack/backend.env
  fi
fi

rm -rf /var/www/skytrack/*
cp -a "$FRONTEND_SRC/." /var/www/skytrack/

if [ "${SKYTRACK_OVERWRITE_DATA:-false}" = "true" ] \
  || [ ! -f /opt/skytrack/backend/data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt ] \
  || [ ! -f /opt/skytrack/backend/data/planes_vuelo.txt ] \
  || [ ! -d /opt/skytrack/backend/data/_envios_preliminar_ ]; then
  rm -rf /opt/skytrack/backend/data/_envios_preliminar_
  cp -a "$DATA_SRC/." /opt/skytrack/backend/data/
else
  echo "Preserving existing static data in /opt/skytrack/backend/data."
  echo "Set SKYTRACK_OVERWRITE_DATA=true to restore bundled defaults."
fi
install -d -m 0755 /opt/skytrack/backend/data/results

chown -R skytrack:skytrack /opt/skytrack
chown -R www-data:www-data /var/www/skytrack

systemctl daemon-reload
systemctl enable skytrack-backend
systemctl restart skytrack-backend
nginx -t
systemctl reload nginx

echo "Waiting for backend to start (up to 90s)..."
for i in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8081/actuator/health >/dev/null; then
    echo "Backend health OK (${i}s)"
    break
  fi
  if [ "$i" -eq 90 ]; then
    echo "Backend health check failed after 90s. Logs: journalctl -u skytrack-backend -n 120 --no-pager"
    exit 1
  fi
  sleep 1
done

curl -fsS http://127.0.0.1/ >/dev/null
systemctl --no-pager --full status skytrack-backend || true

echo "Deployment finished. Open: http://1inf54-981-2b.inf.pucp.edu.pe/"
