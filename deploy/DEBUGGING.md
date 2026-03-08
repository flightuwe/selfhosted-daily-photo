# Debug profile in main stack

Dozzle is integrated directly in `deploy/portainer-stack.yml` and controlled via Compose profile `debug`.

## Enable debug in Portainer
1. Open your stack -> Editor
2. Add env var:
   - `COMPOSE_PROFILES=debug`
3. Update stack
4. Open `http://<synology-ip>:13380`

## Disable debug
1. Remove env var `COMPOSE_PROFILES` (or set empty)
2. Update stack

## Security note
Dozzle reads Docker logs via `/var/run/docker.sock`.
Use only in trusted networks or protect via Synology reverse proxy/auth.
