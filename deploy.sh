#!/usr/bin/env bash
# Copy tvremote to the TV computer (deploy.env) and restart its user service.
# For working on tvremote from another computer; on the TV itself use install.sh.
set -e
cd "$(dirname "$0")"
# Refuse to deploy a broken phone page or server: a syntax error in the page's
# script leaves the phone dead with nothing in the server log (2026-10-09).
node -e 'const h=require("fs").readFileSync("tvremote.html","utf8");new Function(h.match(/<script>([\s\S]*)<\/script>/)[1])' \
  || { echo "tvremote.html: the page script doesn't parse - not deploying"; exit 1; }
bb -e '(load-file "tvremote.clj")' >/dev/null \
  || { echo "tvremote.clj doesn't load - not deploying"; exit 1; }
. ./deploy.env 2>/dev/null || { echo "No deploy.env - copy deploy.env.example and fill it in"; exit 1; }
H=$TV_HOST
O="-o BatchMode=yes -o IdentitiesOnly=yes -i $TV_SSH_KEY"
S="ssh $O"
$S $H 'mkdir -p ~/tvremote ~/.local/bin ~/.config/systemd/user'
[ "$($S $H '~/.local/bin/bb --version 2>/dev/null')" = "$(bb --version)" ] || \
  scp $O "$(which bb)" $H:.local/bin/bb
scp $O tvremote.clj tvremote.html $H:tvremote/
[ -f config.edn ] && scp $O config.edn $H:tvremote/
scp $O tvremote.service $H:.config/systemd/user/
$S $H 'export XDG_RUNTIME_DIR=/run/user/$(id -u); systemctl --user daemon-reload; systemctl --user enable tvremote >/dev/null 2>&1; systemctl --user restart tvremote; sleep 2; systemctl --user is-active tvremote'
