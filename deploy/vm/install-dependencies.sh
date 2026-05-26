#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo: sudo ./install-dependencies.sh"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="/etc/skytrack/backend.env"
DB_NAME="scheduling_db"
DB_USER="scheduling_user"

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y openjdk-17-jre-headless nginx mysql-server unzip curl openssl

systemctl enable --now mysql
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

DB_PASS=""
if [ -f "$ENV_FILE" ]; then
  DB_PASS="$(grep '^SPRING_DATASOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2- || true)"
fi
if [ -z "$DB_PASS" ]; then
  DB_PASS="$(openssl rand -base64 24 | tr -d '\n')"
fi

mysql <<SQL
CREATE DATABASE IF NOT EXISTS ${DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL

cat > "$ENV_FILE" <<EOF
SPRING_PROFILES_ACTIVE=container
SERVER_PORT=8081
SERVER_ADDRESS=127.0.0.1
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/${DB_NAME}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=${DB_USER}
SPRING_DATASOURCE_PASSWORD=${DB_PASS}
DATA_AIRPORTS_PATH=/opt/skytrack/backend/data/c.1inf54.26.1.v1.Aeropuerto.husos.v1.20250818__estudiantes.txt
DATA_FLIGHTS_PATH=/opt/skytrack/backend/data/planes_vuelo.txt
DATA_SHIPMENTS_DIR=/opt/skytrack/backend/data/_envios_preliminar_
DATA_RESULTS_DIR=/opt/skytrack/backend/data/results
JPA_SHOW_SQL=false
LOGGING_LEVEL_COM_EQUIPO2B=INFO
JAVA_OPTS=-Xms256m -Xmx1200m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError
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

echo "Dependencies and base services are ready. Now run: sudo ./deploy-artifacts.sh"
