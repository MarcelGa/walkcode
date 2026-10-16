#!/usr/bin/env bash
# Experiment: which agent notification escape sequences reach clients attached
# through tmux, with allow-passthrough off/on. Run inside the test host as dev.
set -u
export TERM=xterm-256color

cat > /tmp/notify.sh <<'EOF'
printf '\ePtmux;\e\e]9;WRAPPED-OSC9\a\e\\'
printf '\e]9;RAW-OSC9\a'
printf '\e]777;notify;Agent;RAW-OSC777\a'
printf '\e]9;4;1;50\a'
printf '\a'
EOF

tmux kill-server 2>/dev/null
tmux new -d -s p -x 80 -y 24 || { echo "tmux new failed"; exit 1; }
tmux set-hook -g alert-bell 'set -wF @wc_bell "#{e|+:#{?@wc_bell,#{@wc_bell},0},1}"'

for mode in off on; do
  tmux set -g allow-passthrough "$mode"
  tmux set -wu -t p @wc_bell 2>/dev/null
  rm -f /tmp/client.log /tmp/ctl.log
  # Keep stdin open; EOF would be forwarded into the pane as Ctrl-D.
  (sleep 6) | script -qfc "tmux attach -t p" /tmp/client.log >/dev/null 2>&1 &
  cp=$!
  (sleep 4) | tmux -C attach -t p > /tmp/ctl.log 2>&1 &
  sleep 1
  tmux send-keys -t p "bash /tmp/notify.sh" Enter
  sleep 2
  kill "$cp" 2>/dev/null
  wait 2>/dev/null
  echo "== allow-passthrough=$mode"
  for log in client ctl; do
    printf '  %-7s' "$log:"
    for s in WRAPPED-OSC9 RAW-OSC9 RAW-OSC777 '9;4;1;50'; do
      if grep -qF "$s" "/tmp/$log.log"; then printf '%s=yes ' "$s"; else printf '%s=no ' "$s"; fi
    done
    echo
  done
  echo "  alert-bell hook fired (attached, current window): $(tmux show -wv -t p @wc_bell 2>/dev/null || echo none)"
done
echo "--- client.log bytes: $(wc -c < /tmp/client.log)"
tmux kill-server
