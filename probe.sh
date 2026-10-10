#!/usr/bin/env bash
# probe.sh 'JS EXPR' - evaluate JS in the TV's Netflix tab, print the JSON result.
. "$(dirname "$0")/deploy.env" 2>/dev/null || { echo "No deploy.env - copy deploy.env.example and fill it in"; exit 1; }
ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "$TV_SSH_KEY" "$TV_HOST" \
  "cd ~/tvremote && ~/.local/bin/bb -e '(load-file \"tvremote.clj\") (println (cheshire.core/generate-string (tvremote/js (tvremote/tv-target) (slurp *in*))))'" <<<"$1"
