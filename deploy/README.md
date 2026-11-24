# HTTPS backend on a Linux server

This setup runs the existing Express API in Docker, bound only to the server's
loopback address. Host Nginx serves the public domain. Certbot obtains and
renews the Let's Encrypt certificate. Replace `api.example.com` with a domain
you control; it is a placeholder, not a working endpoint.

## Prerequisites

- A Linux server with Docker Compose, Nginx, snapd and a public IPv4 or IPv6
  address. Point the domain's A/AAAA record to that server. If an AAAA record
  exists, IPv6 must reach the same Nginx server.
- Allow inbound TCP ports 80 and 443 in the server and provider firewalls.
  Let's Encrypt HTTP-01 validation needs port 80.
- Configure `backend/.env` for `APP_MODE=connected` and the same Firebase
  project as `app/google-services.json`. Keep secrets out of Git.
- Set `FIREBASE_ADMIN_KEY_PATH` to the absolute path of the Firebase Admin
  credential file on the Linux server. The Compose service mounts it read-only.

In your domain provider's DNS panel, add an `A` record with host/name `api`
and value equal to the Linux server's public IPv4 address. This makes
`api.your-domain.com` point to that server. Add an `AAAA` record only if the
server has working public IPv6. Wait until DNS resolves to the server before
requesting a certificate. A purchased domain by itself is not a server.

## Start the backend

From the repository root on the Linux server:

```sh
export FIREBASE_ADMIN_KEY_PATH=/absolute/private/path/firebase-admin.json
docker compose -f deploy/compose.https.yaml up -d --build
curl --fail http://127.0.0.1:3001/health
```

The container port is reachable from Nginx on the same host, but port 3001
is not published to the public network.

## Configure Nginx and issue the certificate

Copy `deploy/nginx/nimbus-bank.conf.example` to the host's Nginx site
configuration, replace `api.example.com` with the real domain, and enable the
site. On a Debian/Ubuntu host using the standard `sites-available` layout:

```sh
sudo cp deploy/nginx/nimbus-bank.conf.example /etc/nginx/sites-available/nimbus-bank
sudo nano /etc/nginx/sites-available/nimbus-bank
sudo ln -s /etc/nginx/sites-available/nimbus-bank /etc/nginx/sites-enabled/nimbus-bank
sudo nginx -t
sudo systemctl reload nginx
```

Install Certbot with its Nginx plugin using the official Certbot instructions
for your Linux distribution. On Ubuntu with snapd installed, the commands are:

```sh
sudo snap install --classic certbot
sudo ln -s /snap/bin/certbot /usr/local/bin/certbot
```

Then issue and verify the certificate:

```sh
sudo certbot --nginx -d api.example.com --redirect
sudo certbot renew --dry-run
curl --fail https://api.example.com/health
```

Use the real domain in both commands. Certbot edits the Nginx site to add TLS
and the HTTP-to-HTTPS redirect, and its scheduled renewal checks keep the
certificate current. Verify the resulting Nginx configuration with
`sudo nginx -t` after issuance.

## Point Android at HTTPS

In the local, Git-ignored `local.properties`, set:

```properties
backendBaseUrl=https://api.example.com
```

Replace the placeholder with the real domain and rebuild the app. Do not add
`/v1` to the URL. The physical phone must reach the domain over the Internet.
`/health` confirms the HTTP process is reachable; test a signed-in API flow
separately because `/health` does not validate Firebase or other providers.
