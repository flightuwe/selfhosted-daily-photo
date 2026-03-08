# Optional debug stack for Synology/Portainer

## Purpose
Provides a temporary log UI for all project containers (`shp-*`).

## Deploy
1. Portainer -> Stacks -> Add stack
2. Paste `deploy/portainer-debug-stack.yml`
3. Deploy
4. Open `http://<synology-ip>:13380`

## Security note
Dozzle can expose logs for all containers available via Docker socket.
Use only in trusted networks or protect via Synology reverse proxy/auth.

## Disable
Stop/remove the debug stack when not needed.
