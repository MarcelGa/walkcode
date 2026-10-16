#!/usr/bin/env bash
# Install Walkcode tmux settings for the current user (what the app will do at host setup).
# Usage: install-tmux-conf.sh < walkcode.tmux.conf
set -euo pipefail
mkdir -p ~/.config/walkcode
tr -d '\r' > ~/.config/walkcode/tmux.conf
line='source-file -q ~/.config/walkcode/tmux.conf'
touch ~/.tmux.conf
grep -qxF "$line" ~/.tmux.conf || printf '\n# Walkcode\n%s\n' "$line" >> ~/.tmux.conf
# Apply to a running server, if any.
if tmux ls >/dev/null 2>&1; then tmux source-file ~/.config/walkcode/tmux.conf; fi
echo "installed"
