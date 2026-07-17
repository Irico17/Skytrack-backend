#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo: sudo ./deploy-artifacts.sh"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Raíz del paquete: tar en ~/skytrack-deploy/current/ o carpeta scheduling-core/ del zip.
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PARENT="$(cd "$ROOT/.." && pwd)"

resolve_jar() {
  # Layout tar (redeploy-vm.ps1 / package-local.ps1): current/backend/scheduling-core.jar
  if [ -f "$ROOT/backend/scheduling-core.jar" ]; then
    echo "$ROOT/backend/scheduling-core.jar"
    return 0
  fi
  # Layout zip: scheduling-core/build/libs/*.jar
  local jar
  jar="$(find "$ROOT/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' 2>/dev/null | head -n 1 || true)"
  if [ -n "$jar" ] && [ -f "$jar" ]; then
    echo "$jar"
    return 0
  fi
  # Fallback legacy
  if [ -f "$PARENT/backend/scheduling-core.jar" ]; then
    echo "$PARENT/backend/scheduling-core.jar"
    return 0
  fi
  return 1
}

resolve_frontend() {
  if [ -d "$ROOT/frontend/dist" ]; then
    echo "$ROOT/frontend/dist"
    return 0
  fi
  if [ -d "$PARENT/Skytrack-Frontend/dist" ]; then
    echo "$PARENT/Skytrack-Frontend/dist"
    return 0
  fi
  if [ -d "$PARENT/frontend/dist" ]; then
    echo "$PARENT/frontend/dist"
    return 0
  fi
  return 1
}

resolve_data() {
  if [ -d "$ROOT/backend/data" ]; then
    echo "$ROOT/backend/data"
    return 0
  fi
  if [ -d "$ROOT/data" ]; then
    echo "$ROOT/data"
    return 0
  fi
  if [ -d "$PARENT/backend/data" ]; then
    echo "$PARENT/backend/data"
    return 0
  fi
  return 1
}

JAR_SRC=""
FRONTEND_SRC=""
DATA_SRC=""

if ! JAR_SRC="$(resolve_jar)"; then
  echo "Missing backend jar."
  echo "  Tar layout expected : $ROOT/backend/scheduling-core.jar"
  echo "  Zip layout expected : $ROOT/build/libs/*.jar"
  echo "Build first: cd $ROOT && ./gradlew bootJar -x test"
  exit 1
fi

if ! FRONTEND_SRC="$(resolve_frontend)"; then
  echo "Missing frontend dist."
  echo "  Tar layout expected : $ROOT/frontend/dist/"
  echo "  Zip layout expected : $PARENT/Skytrack-Frontend/dist/"
  exit 1
fi

if ! DATA_SRC="$(resolve_data)"; then
  echo "Missing backend data."
  echo "  Expected: $ROOT/backend/data/ or $ROOT/data/"
  exit 1
fi

echo "Using jar      : $JAR_SRC"
echo "Using frontend : $FRONTEND_SRC"
echo "Using data     : $DATA_SRC"

install -d -m 0755 /opt/skytrack/backend
install -d -m 0755 /opt/skytrack/backend/data
install -d -m 0755 /opt/skytrack/backend/data/results
install -d -m 0755 /var/www/skytrack

install -m 0644 "$JAR_SRC" /opt/skytrack/backend/scheduling-core.jar

if [ -f "$SCRIPT_DIR/skytrack-backend.service" ]; then
  install -m 0644 "$SCRIPT_DIR/skytrack-backend.service" /etc/systemd/system/skytrack-backend.service
fi

if [ -f /etc/skytrack/backend.env ]; then
  # 2 CPUs reales de la VM (antes decía 1 y castraba GA/Tabú y el GC). Xmx768m:
  # heap pico medido ~350 MB; con Xmx1024 el RSS (heap+metaspace+threads) rozaba
  # MemoryMax=1300M del service y el kernel OOM-mataba a java a mitad del ciclo 1
  # (systemd lo reiniciaba vacío y el frontend quedaba con 404 de /status).
  SKYTRACK_JAVA_OPTS='JAVA_OPTS=-Xms192m -Xmx768m -XX:MaxMetaspaceSize=160m -XX:ActiveProcessorCount=2 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -XX:+UseG1GC -XX:MaxGCPauseMillis=250 -XX:+UseStringDeduplication -Djava.util.concurrent.ForkJoinPool.common.parallelism=1 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp -XX:+ExitOnOutOfMemoryError'
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

# Actualizar también la config de nginx en cada redeploy: install-dependencies.sh solo la
# copia la PRIMERA vez, así que cambios posteriores (límites de tamaño/timeouts, etc.) se
# quedaban en el repo sin aplicarse nunca en la VM salvo copia manual.
if [ -f "$SCRIPT_DIR/nginx-skytrack.conf" ]; then
  install -m 0644 "$SCRIPT_DIR/nginx-skytrack.conf" /etc/nginx/sites-available/skytrack
fi

# Dieta de memoria para MySQL (VM de 2 GB): aplicar en redeploy si cambió; el restart
# solo ocurre cuando el archivo es distinto para no cortar conexiones sin motivo.
if [ -f "$SCRIPT_DIR/mysql-skytrack.cnf" ] && [ -d /etc/mysql/mysql.conf.d ]; then
  if ! cmp -s "$SCRIPT_DIR/mysql-skytrack.cnf" /etc/mysql/mysql.conf.d/skytrack.cnf 2>/dev/null; then
    install -m 0644 "$SCRIPT_DIR/mysql-skytrack.cnf" /etc/mysql/mysql.conf.d/skytrack.cnf
    systemctl restart mysql || echo "⚠️  MySQL no pudo reiniciarse; revisa: systemctl status mysql"
  fi
fi

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
