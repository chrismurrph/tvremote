#!/usr/bin/env bash
# Update the TV computer (deploy.env) to what's on GitHub and restart tvremote.
# The TV runs from a git clone of this repo (~/tvremote), so there is no copy
# to keep in step: a change reaches the TV by being pushed, then pulled here.
# First set-up on a TV computer is install.sh, run there.
set -e
cd "$(dirname "$0")"
# Refuse a broken phone page or server: a syntax error in the page's script
# leaves the phone dead with nothing in the server log (2026-10-09).
node -e 'const h=require("fs").readFileSync("tvremote.html","utf8");new Function(h.match(/<script>([\s\S]*)<\/script>/)[1])' \
  || { echo "tvremote.html: the page script doesn't parse - not deploying"; exit 1; }
bb -e '(load-file "tvremote.clj")' >/dev/null \
  || { echo "tvremote.clj doesn't load - not deploying"; exit 1; }
. ./deploy.env 2>/dev/null || { echo "No deploy.env - copy deploy.env.example and fill it in"; exit 1; }
# The TV can only pull what GitHub has, so this folder must match it.
git fetch -q origin
if [ -n "$(git status --porcelain)" ] || [ "$(git rev-parse HEAD)" != "$(git rev-parse @{u})" ]; then
  echo "This folder differs from GitHub, and the TV runs what's on GitHub - push first."; exit 1
fi
ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "$TV_SSH_KEY" "$TV_HOST" '
  cd ~/tvremote && git pull -q --ff-only
  export XDG_RUNTIME_DIR=/run/user/$(id -u)
  systemctl --user restart tvremote; sleep 2
  systemctl --user is-active tvremote && git log -1 --format="TV now runs %h %s"'
