# Local SSH + tmux host for Walkcode end-to-end testing.
# The Android emulator reaches it at 10.0.2.2:2222.
FROM ubuntu:24.04

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      openssh-server tmux bash-completion ca-certificates curl less procps locales \
 && locale-gen en_US.UTF-8 \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8

RUN useradd -m -s /bin/bash dev \
 && usermod -p '*' dev \
 && mkdir -p /run/sshd /home/dev/.ssh /home/dev/src/tollbff /home/dev/src/dmbff /home/dev/infra/gw \
 && chown -R dev:dev /home/dev \
 && chmod 700 /home/dev/.ssh \
 && ssh-keygen -A \
 && sed -i -E 's/^#?PasswordAuthentication .*/PasswordAuthentication no/; s/^#?PubkeyAuthentication .*/PubkeyAuthentication yes/' /etc/ssh/sshd_config

COPY fake-agent /usr/local/bin/fake-agent
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /usr/local/bin/fake-agent /entrypoint.sh

EXPOSE 22
ENTRYPOINT ["/entrypoint.sh"]
