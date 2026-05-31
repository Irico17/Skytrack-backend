# Skytrack VM Deploy

Deploy sin Docker ni Git en la VM. La VM publica Nginx en `80/443`, sirve el frontend desde `/var/www/skytrack` y proxy `/api` + `/ws` al backend Spring Boot en `127.0.0.1:8081`.

## Arquitectura VM

- Dominio: `1inf54-981-2b.inf.pucp.edu.pe`.
- IP: `200.16.7.142`.
- Usuario SSH: `1inf54.981.2b`.
- Backend: `skytrack-backend.service`.
- Backend home: `/opt/skytrack/backend`.
- Frontend: `/var/www/skytrack`.
- Config: `/etc/skytrack/backend.env`.
- Perfil recomendado: `container` con MySQL local.
- Perfil alternativo demo: `dev` con H2 en memoria.

## Build Local

Desde `C:\Users\Irico\Documents\DP1\scheduling-core`:

```powershell
.\deploy\vm\package-local.ps1
```

Genera:

```text
deploy\skytrack-vm-deploy.tar.gz
```

El paquete incluye:

- `backend/scheduling-core.jar`
- `backend/data/` con dataset default
- `frontend/dist/`
- `deploy/vm/*.sh`

## Upload

```powershell
scp deploy\skytrack-vm-deploy.tar.gz 1inf54.981.2b@200.16.7.142:~/skytrack-vm-deploy.tar.gz
```

## Primer Deploy Con MySQL

En la VM:

```bash
mkdir -p ~/skytrack-deploy
rm -rf ~/skytrack-deploy/current
mkdir -p ~/skytrack-deploy/current
tar -xzf ~/skytrack-vm-deploy.tar.gz -C ~/skytrack-deploy/current
cd ~/skytrack-deploy/current/deploy/vm
chmod +x *.sh
sudo ./install-dependencies.sh
sudo ./deploy-artifacts.sh
```

`install-dependencies.sh` instala Java 17, Nginx, MySQL, unzip y curl. Tambien crea:

- Base de datos: `scheduling_db`.
- Usuario de app: `scheduling_user`.
- Password de app aleatoria, guardada en `/etc/skytrack/backend.env`.

Si MySQL root pide password:

```bash
MYSQL_ROOT_PASSWORD='password-root-mysql' sudo -E ./install-dependencies.sh
```

Si quieres fijar tambien la password del usuario `scheduling_user`:

```bash
MYSQL_ROOT_PASSWORD='password-root-mysql' SKYTRACK_DB_PASSWORD='password-app' sudo -E ./install-dependencies.sh
```

Usa una password fuerte para `SKYTRACK_DB_PASSWORD`. En MySQL con `validate_password.policy=MEDIUM`, la password debe tener al menos 8 caracteres, mayuscula, minuscula, numero y caracter especial. Ejemplo:

```bash
SKYTRACK_DB_PASSWORD='SkyApp@Secure2026' MYSQL_ROOT_PASSWORD='password-root-mysql' sudo -E ./install-dependencies.sh
```

Este paso se hace solo en el primer deploy con MySQL o cuando quieras regenerar `/etc/skytrack/backend.env` y credenciales. Para redeploys normales de nueva version basta con `deploy-artifacts.sh`.

## MySQL Root Password Perdida

Primero intenta entrar con socket local:

```bash
sudo mysql
```

Si no entra y conoces la password, usa `MYSQL_ROOT_PASSWORD`. Si no la conoces, sigue el procedimiento oficial de MySQL para resetear privilegios. Version corta para Ubuntu/MySQL 8:

```bash
sudo systemctl stop mysql
sudo mkdir -p /var/run/mysqld
sudo chown mysql:mysql /var/run/mysqld
sudo mysqld_safe --skip-grant-tables --skip-networking &
mysql -uroot
```

Dentro de MySQL:

```sql
FLUSH PRIVILEGES;
ALTER USER 'root'@'localhost' IDENTIFIED BY 'nueva-password-root';
EXIT;
```

Luego:

```bash
sudo pkill mysqld_safe || true
sudo pkill mysqld || true
sudo systemctl start mysql
mysql -u root -p'nueva-password-root' -e "SELECT 1;"
SKYTRACK_DB_PASSWORD='password-fuerte-app' MYSQL_ROOT_PASSWORD='nueva-password-root' sudo -E ./install-dependencies.sh
```

Referencias oficiales: `default-privileges` y `resetting-permissions` de MySQL 8.0.

## Deploy Demo Sin MySQL

Solo para demo temporal:

```bash
sudo ./install-demo-no-db.sh
sudo ./deploy-artifacts.sh
```

Esto escribe `SPRING_PROFILES_ACTIVE=dev` y usa H2 en memoria. No conserva persistencia tras reiniciar backend.

## Redeploy De Nueva Version

Desde local:

```powershell
cd C:\Users\Irico\Documents\DP1\scheduling-core
.\deploy\vm\package-local.ps1
scp deploy\skytrack-vm-deploy.tar.gz 1inf54.981.2b@200.16.7.142:~/skytrack-vm-deploy.tar.gz
```

O en una sola corrida desde local:

```powershell
.\deploy\vm\redeploy-vm.ps1
```

Si quieres restaurar los datos default incluidos en el paquete:

```powershell
.\deploy\vm\redeploy-vm.ps1 -OverwriteData
```

En la VM:

```bash
rm -rf ~/skytrack-deploy/current
mkdir -p ~/skytrack-deploy/current
tar -xzf ~/skytrack-vm-deploy.tar.gz -C ~/skytrack-deploy/current
cd ~/skytrack-deploy/current/deploy/vm
chmod +x *.sh
sudo ./deploy-artifacts.sh
```

Por defecto, `deploy-artifacts.sh` preserva los datos estaticos actuales en `/opt/skytrack/backend/data`. Esto evita perder datasets subidos desde el frontend.

Para restaurar los datos default empaquetados:

```bash
SKYTRACK_OVERWRITE_DATA=true sudo -E ./deploy-artifacts.sh
```

## Verificacion

```bash
curl http://127.0.0.1:8081/actuator/health
curl -I http://127.0.0.1/
systemctl status skytrack-backend --no-pager
```

Desde navegador:

```text
http://1inf54-981-2b.inf.pucp.edu.pe/
http://200.16.7.142/
```

## HTTPS

Despues de verificar HTTP y DNS:

```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d 1inf54-981-2b.inf.pucp.edu.pe
```

Si no quieres registrar email:

```bash
sudo certbot --nginx -d 1inf54-981-2b.inf.pucp.edu.pe --register-unsafely-without-email
```

Elige redirect HTTP -> HTTPS cuando Certbot lo pregunte.

## Operaciones Utiles

```bash
sudo systemctl restart skytrack-backend
sudo journalctl -u skytrack-backend -n 200 --no-pager
sudo nginx -t
sudo systemctl reload nginx
sudo cat /etc/skytrack/backend.env
```

Importar manualmente aeropuertos/vuelos actuales a BD:

```bash
curl -X POST http://127.0.0.1:8081/api/data/import
```

## Seguridad

- No publicar `/etc/skytrack/backend.env`; contiene credenciales.
- Backend debe seguir en `127.0.0.1:8081`, no expuesto publicamente.
- Exponer solo 22, 80 y 443.
- Mantener `client_max_body_size 128m` para la carga de datasets.
- Cambiar passwords si se compartieron por chat.