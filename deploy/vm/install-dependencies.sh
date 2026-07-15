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
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"

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
  # Generates a password that satisfies MySQL validate_password MEDIUM policy:
  # uppercase, lowercase, digit, special char + 24 random hex chars
  DB_PASS="${SKYTRACK_DB_PASSWORD:-Sky@$(openssl rand -hex 12 | tr -d '\n')1A}"
fi

SQL_FILE="$(mktemp)"
DB_PASS_SQL="$(printf "%s" "$DB_PASS" | sed "s/'/''/g")"
cat > "$SQL_FILE" <<SQL
CREATE DATABASE IF NOT EXISTS ${DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS_SQL}';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS_SQL}';
GRANT ALL PRIVILEGES ON ${DB_NAME}.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL

run_mysql_root_script() {
  if [ -n "$MYSQL_ROOT_PASSWORD" ]; then
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot < "$SQL_FILE"
    return $?
  fi

  mysql -uroot < "$SQL_FILE" 2>/tmp/skytrack-mysql.err && return 0
  mysql < "$SQL_FILE" 2>/tmp/skytrack-mysql.err && return 0
  return 1
}

if ! run_mysql_root_script; then
  echo "Could not connect to MySQL as root."
  echo "If you know the MySQL root password, run: MYSQL_ROOT_PASSWORD='your-password' sudo -E ./install-dependencies.sh"
  echo "If the password is unknown, reset it following the official MySQL procedure, then rerun this script."
  cat /tmp/skytrack-mysql.err 2>/dev/null || true
  rm -f "$SQL_FILE"
  exit 1
fi
rm -f "$SQL_FILE"

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

echo "Dependencies and base services are ready. Now run: sudo ./deploy-artifacts.sh"
