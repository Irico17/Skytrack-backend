#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo: sudo ./install-demo-no-db.sh"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="/etc/skytrack/backend.env"

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y openjdk-17-jre-headless nginx unzip curl

systemctl enable --now nginx

if ! id -u skytrack >/dev/null 2>&1; then
  useradd --system --home /opt/skytrack --shell /usr/sbin/nologin skytrack
fi

install -d -m 0755 /opt/skytrack/backend
install -d -m 0755 /opt/skytrack/backend/data
install -d -m 0755 /opt/skytrack/backend/data/results
install -d -m 0755 /opt/skytrack/backend/logs
install -d -m 0755 /var/www/skytrack
install -d -m 0750 /etc/skytrack

cat > "$ENV_FILE" <<EOF
SPRING_PROFILES_ACTIVE=dev
SERVER_PORT=8081
SERVER_ADDRESS=127.0.0.1
DATA_AIRPORTS_PATH=/opt/skytrack/backend/data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt
DATA_FLIGHTS_PATH=/opt/skytrack/backend/data/planes_vuelo.txt
DATA_SHIPMENTS_DIR=/opt/skytrack/backend/data/_envios_preliminar_
DATA_RESULTS_DIR=/opt/skytrack/backend/data/results
MAX_UPLOAD_FILE_SIZE=25MB
MAX_UPLOAD_REQUEST_SIZE=128MB
JPA_SHOW_SQL=false
LOGGING_LEVEL_COM_EQUIPO2B=INFO
JAVA_OPTS=-Xms192m -Xmx1024m -XX:ActiveProcessorCount=2 -XX:+UseG1GC -XX:MaxGCPauseMillis=250 -XX:+UseStringDeduplication -Djava.util.concurrent.ForkJoinPool.common.parallelism=1 -XX:+ExitOnOutOfMemoryError
EOF
chown root:skytrack "$ENV_FILE"
chmod 0640 "$ENV_FILE"

install -m 0644 "$SCRIPT_DIR/skytrack-backend.service" /etc/systemd/system/skytrack-backend.service
install -m 0644 "$SCRIPT_DIR/nginx-skytrack.conf" /etc/nginx/sites-available/skytrack
ln -sf /etc/nginx/sites-available/skytrack /etc/nginx/sites-enabled/skytrack
rm -f /etc/nginx/sites-enabled/default

chown -R skytrack:skytrack /opt/skytrack
chown -R www-data:www-data /var/www/skytrack

systemctl daemon-reload
nginx -t
systemctl reload nginx

echo "Demo dependencies ready without MySQL. Now run: sudo ./deploy-artifacts.sh"