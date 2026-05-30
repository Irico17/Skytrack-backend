# Skytrack VM deploy

This deploy does not require Docker or Git on the VM.

Architecture:
- Nginx listens on public port 80.
- React/Vite build is served from `/var/www/skytrack`.
- Spring Boot runs as `skytrack-backend.service` on `127.0.0.1:8081`.
- Nginx proxies `/api/` and `/ws/` to the backend.
- MySQL runs locally and is used by the Spring `container` profile.
- Existing Tomcat on port 8080 is not touched.

## 1. Build the deployment zip locally

From `C:\Users\Irico\Documents\DP1\scheduling-core`:

```powershell
.\deploy\vm\package-local.ps1
```

This creates:

```text
deploy\skytrack-vm-deploy.tar.gz
```

## 2. Upload to the VM

Use the VM password only when SSH/SCP asks for it. Do not paste it into shared chats or scripts.

```powershell
scp deploy\skytrack-vm-deploy.tar.gz 1inf54.981.2b@200.16.7.142:~/skytrack-vm-deploy.tar.gz
```

If Windows asks whether to trust the host fingerprint, type `yes` after verifying it is the university VM.

## 3. Install packages and deploy on the VM

Connect:

```powershell
ssh 1inf54.981.2b@200.16.7.142
```

Then on the VM:

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

For a demo-only deployment without MySQL, use:

```bash
sudo ./install-demo-no-db.sh
sudo ./deploy-artifacts.sh
```

## 4. Verify

On the VM:

```bash
curl http://127.0.0.1:8081/actuator/health
curl -I http://127.0.0.1/
systemctl status skytrack-backend --no-pager
```

From your browser:

```text
http://1inf54-981-2b.inf.pucp.edu.pe/
http://200.16.7.142/
```

## 5. Useful operations

Restart backend:

```bash
sudo systemctl restart skytrack-backend
```

View backend logs:

```bash
sudo journalctl -u skytrack-backend -n 200 --no-pager
```

Reload Nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

Redeploy a new version:

```bash
rm -rf ~/skytrack-deploy/current
mkdir -p ~/skytrack-deploy/current
tar -xzf ~/skytrack-vm-deploy.tar.gz -C ~/skytrack-deploy/current
cd ~/skytrack-deploy/current/deploy/vm
sudo ./deploy-artifacts.sh
```

## 6. Optional TLS

After HTTP works and DNS resolves correctly:

```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d 1inf54-981-2b.inf.pucp.edu.pe
```

## 7. Security checklist

- Change the VM password after deployment because it was shared in chat.
- Keep backend bound to `127.0.0.1`; do not expose port 8081 publicly.
- Only ports 22, 80 and 443 should be externally reachable.
- Store DB credentials only in `/etc/skytrack/backend.env` with permissions `0640`.
- Update packages periodically with `sudo apt-get update && sudo apt-get upgrade`.
