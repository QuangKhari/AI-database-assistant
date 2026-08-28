# Deployment setup

This repository is prepared for a single Ubuntu server with host Nginx and Docker Compose. The setup does not change DNS, Nginx, or the server automatically.

## Production layout

- Nginx on the Ubuntu host serves `frontend/dist` from `/var/www/ai-database-assistant`.
- Nginx proxies `/api/*` to `127.0.0.1:8080`.
- Docker Compose runs the Spring Boot backend and the System MySQL database.
- MySQL is only reachable through the internal Docker network.
- Mailpit and the sample Target MySQL remain local-development services only.

## Files to customize on the server

1. Copy `.env.production.example` to `.env.production` and replace every placeholder.
2. Replace `example.com` in `deployment/nginx/ai-database-assistant.conf`.
3. Install the Nginx config under `/etc/nginx/sites-available`, enable it, run `nginx -t`, and reload Nginx.
4. Point the domain DNS records to the server before requesting an HTTPS certificate.

Never commit `.env.production`, SSH keys, database passwords, JWT/AES secrets, SMTP passwords, or OpenAI keys.

## First deployment

```bash
sudo install -d -o "$USER" -g "$USER" /opt/ai-database-assistant
git clone <repository-url> /opt/ai-database-assistant
cd /opt/ai-database-assistant
cp .env.production.example .env.production
# Edit .env.production locally on the server.
bash deployment/scripts/deploy.sh
```

Validate `https://<domain>/api/health`, registration, login, password reset, connection ownership, read-only checks, Schema sync, and Chat preview before opening the site publicly.

## HTTPS

After DNS and the HTTP virtual host work, use Certbot's Nginx integration and verify renewal with `certbot renew --dry-run`.

## Backup and rollback

```bash
bash deployment/scripts/backup-system-db.sh
bash deployment/scripts/rollback.sh <previous-tag-or-commit>
```

Schedule the backup script with cron only after testing a manual backup and restore. The rollback script changes application code only; it does not reverse database schema changes.

## Production notes

- Keep `ALLOW_PRIVATE_TARGET_HOSTS=false` for the public application.
- Keep ports 3306/3308/3309, 8025, and 8080 closed to the Internet. Nginx is the only public application entry point.
- `JPA_DDL_AUTO=update` is retained for the course-project MVP. Move to versioned migrations and `validate` before relying on long-lived production data.
- The server does not need PM2 or MongoDB for this project.
