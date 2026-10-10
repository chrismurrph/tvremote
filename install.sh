#!/usr/bin/env bash
# install.sh - run ON the computer plugged into the TV, from the folder you
# cloned: makes tvremote a user service that starts whenever you log in.
set -e
cd "$(dirname "$0")"
dir=$(pwd)
bb=$(command -v bb) || { echo "Babashka (bb) isn't installed - see https://babashka.org"; exit 1; }
[ -f config.edn ] || { cp config.example.edn config.edn; echo "Made config.edn from config.example.edn - edit it to add your names."; }
mkdir -p ~/.config/systemd/user
cat > ~/.config/systemd/user/tvremote.service <<UNIT
[Unit]
Description=tvremote - phone remote for this TV
After=graphical-session.target

[Service]
ExecStart=$bb $dir/tvremote.clj
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable tvremote >/dev/null 2>&1
systemctl --user restart tvremote
sleep 2
systemctl --user is-active tvremote >/dev/null || { echo "tvremote didn't start - see: journalctl --user -u tvremote"; exit 1; }
port=$(bb -e "(:port (merge {:port 7700} (try (clojure.edn/read-string (slurp \"config.edn\")) (catch Exception _ {}))))")
echo "tvremote is running. On your phone (same Wi-Fi) open: http://$(hostname -I | awk '{print $1}'):$port/"
