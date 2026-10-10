#!/usr/bin/env bash
# rbb.sh < snippet.clj - run Clojure (stdin) on the media machine inside the
# tvremote namespace, with the server's functions loaded (js, cdp, press, ...).
. "$(dirname "$0")/deploy.env" 2>/dev/null || { echo "No deploy.env - copy deploy.env.example and fill it in"; exit 1; }
ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "$TV_SSH_KEY" "$TV_HOST" \
  'cd ~/tvremote && ~/.local/bin/bb -e "(load-file \"tvremote.clj\") (in-ns (quote tvremote)) (load-string (slurp *in*))"'
