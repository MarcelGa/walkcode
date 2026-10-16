#!/usr/bin/env bash
set -euo pipefail

# Install authorized keys mounted from the repo (tools/testhost/keys/authorized_keys).
if [[ -f /keys/authorized_keys ]]; then
  tr -d '\r' < /keys/authorized_keys > /home/dev/.ssh/authorized_keys
  chown dev:dev /home/dev/.ssh/authorized_keys
  chmod 600 /home/dev/.ssh/authorized_keys
fi

exec /usr/sbin/sshd -D -e
