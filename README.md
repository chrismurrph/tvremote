# tvremote

Turn a spare computer plugged into your TV into a Netflix, YouTube and reading
screen you drive from your phone's browser. No app to install, no casting:
the phone opens a web page served by the TV computer, and that page controls
a Chrome window on the TV.

- **Netflix and YouTube**: browse, search, play, pause, skip, volume, full screen.
- **Reading**: send any web page to the TV, scroll it, change the text size,
  follow its links (with breadcrumbs back), play the videos in it.
- **Per person**: each phone says who's holding it, and the TV keeps each
  person's Recent list and place in every show, video and page.

## What you need

- **A spare computer on the TV's HDMI.** Any laptop or mini PC that plays video
  smoothly. It runs Linux with a desktop (developed on Fedora with GNOME) and
  should log in automatically when switched on, since the remote starts at
  login.
- **Google Chrome** on that computer, either the normal package or the Flatpak.
- **[Babashka](https://babashka.org)** (`bb`), the small program the remote runs
  on. `install.sh` installs it for you if it's missing (into `~/.local/bin`).
- **A Netflix account** and/or a Google account for YouTube.
- **Phones on the same Wi-Fi**, with Chrome.

## Install

On the TV computer:

```bash
git clone <this repo> ~/tvremote
cd ~/tvremote
./install.sh
```

`install.sh` creates `config.edn` from `config.example.edn`, sets the remote up
to start whenever you log in, and prints the address for your phone, such as
`http://192.168.1.50:7700/`.

Then open `config.edn` and put your household's names in `:people`, for
example `["Ann" "Bob"]`, and run `./install.sh` again to restart the remote.
The other settings are explained in the file, and you'll rarely need them.

If your computer has a firewall, allow port 7700. On Fedora:
`sudo firewall-cmd --permanent --add-port=7700/tcp && sudo firewall-cmd --reload`.

**First start:** open the address on your phone and press **Start Chrome on
the TV**. Chrome opens full screen on Netflix. With a keyboard on the TV
computer, sign in to Netflix, then to YouTube in the YouTube tab. This
Chrome uses its own profile, so these sign-ins are separate from any other
Chrome you use on that computer. That's needed only once.

To update later: `cd ~/tvremote && git pull && ./install.sh`.

## Pair a phone

1. On the phone, open the address `install.sh` printed.
2. Pick your name. Each phone remembers it; tap the name at the top to change it.
3. Add the page to your home screen (Chrome menu → **Add to Home screen**).
4. Optional, to stop the phone's screen going off while you use the remote:
   in Chrome open `chrome://flags`, search for **Insecure origins treated as
   secure**, enter the address (such as `http://192.168.1.50:7700`), set it to
   **Enabled**, and relaunch Chrome. The remote then says
   "☀ Screen stays on".

## Send a page from your computer

The phone page has a box for pasting a link, but you can also send one from any
computer on the same Wi-Fi. That's handy for scripts, or for asking an AI coding
agent to "put it on the TV". Copy `bigscreen` from this repo onto your `PATH`, set
the address once, and run it:

```bash
export TVREMOTE=http://192.168.1.50:7700     # the address install.sh printed
bigscreen https://example.com/some-article
```

It's one web request (`/act/open?url=...`), so anything that can make one can do
the same.

## Good to know

- **The remote's Chrome belongs to the TV.** It runs dark, with a profile
  called "TV", so it isn't mistaken for anyone's own browser. Don't use it
  for everyday browsing: it can be driven by anyone on your Wi-Fi through the
  phone page, which has no password and will open any web address it is sent,
  and by any program on that computer. Everyday browsing belongs in your
  normal Chrome. Only run the remote on a home network you trust.
- Only one Chrome can run on the TV at a time. If another Chrome is open
  there, the remote takes over: the phone's Start button reads **Take over
  the TV**, and it (or `bigscreen`) closes the other Chrome before starting
  its own.
- The phone wakes the TV computer's screen when you press a button. That
  works on GNOME; on other desktops, set the screen not to blank.
- Everything stays on your network. The remote stores its state in
  `state.edn` beside the program.
- To see what the remote is doing: `journalctl --user -u tvremote`. Every
  button press is logged there.
- Netflix and YouTube change their pages from time to time, and when they do,
  browsing may stop finding titles until the remote is updated.

## The other scripts

`deploy.sh`, `rbb.sh` and `probe.sh` are for developing tvremote from another
computer (copy it to the TV and restart it, or try code against the running
remote). `deploy.env.example` says what they need. You don't need any of them
to use the remote.

`test/` holds checks that run on any computer, without a TV or Chrome: for
example `bb test/state_race.clj` checks that saves made at the same moment
don't lose each other.

## License

MIT - see `LICENSE`.
