#!/usr/bin/env bash
# Walkcode host poller (tmux). Streams pane state transitions on stdout until the
# SSH channel closes. Usage: bash -s -- INTERVAL IDLE_GRACE HEARTBEAT
#
# Lines (tab separated, first field is the type, second the host epoch):
#   S now target session state since hash bell dead cmd title   pane appeared or changed
#   G now target                                                 pane gone
#   R now                                                        first full pass done
#   H now                                                        heartbeat
# state is "active" while the visible screen changes, "idle" once it has not changed
# for IDLE_GRACE seconds. since = epoch of the last observed change (0 = unknown).
# Records are only written on transitions, so an idle host costs one heartbeat line.
iv=${1:-2}; grace=${2:-4}; hb=${3:-30}
# Tab separated. bash's read merges runs of tabs, so every field but the last (title) is
# forced non-empty. (tmux 3.4 escapes control characters such as \x1f in formats.)
fmt=$'#{session_name}:#{window_index}.#{pane_index}\t#{session_name}\t#{?@wc_bell,#{@wc_bell},0}\t#{?pane_dead,1,0}\t#{?pane_current_command,#{pane_current_command},-}\t#{pane_title}'
declare -A H C S K B
last_hb=0; first=1
emit() { printf '%s\n' "$1" || exit 0; }
while :; do
  now=$(date +%s)
  declare -A seen=()
  while IFS=$'\t' read -r tgt sess bell dead cmd title; do
    [[ -z $tgt ]] && continue
    seen[$tgt]=1
    h=$(tmux capture-pane -p -t "$tgt" 2>/dev/null | cksum | cut -d' ' -f1)
    bell=${bell:-0}
    key="$bell|$dead|$cmd"
    if [[ -z ${H[$tgt]+x} ]]; then
      H[$tgt]=$h; C[$tgt]=0; S[$tgt]=idle; K[$tgt]=$key; changed=1
    else
      changed=0
      if [[ $h != "${H[$tgt]}" ]]; then
        H[$tgt]=$h; C[$tgt]=$now
        [[ ${S[$tgt]} == idle ]] && { S[$tgt]=active; changed=1; }
      elif [[ ${S[$tgt]} == active ]] && (( now - C[$tgt] >= grace )); then
        S[$tgt]=idle; changed=1
      fi
      [[ $key != "${K[$tgt]}" ]] && { K[$tgt]=$key; changed=1; }
    fi
    (( changed )) && emit "S	$now	$tgt	$sess	${S[$tgt]}	${C[$tgt]}	$h	$bell	$dead	$cmd	$title"
  done < <(tmux list-panes -a -F "$fmt" 2>/dev/null)
  for tgt in "${!H[@]}"; do
    [[ -z ${seen[$tgt]+x} ]] && { emit "G	$now	$tgt"; unset "H[$tgt]" "C[$tgt]" "S[$tgt]" "K[$tgt]"; }
  done
  unset seen
  (( first )) && { emit "R	$now"; first=0; last_hb=$now; }
  (( now - last_hb >= hb )) && { emit "H	$now"; last_hb=$now; }
  sleep "$iv"
done
