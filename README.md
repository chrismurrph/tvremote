# tvremote

Use your phone as a remote for Netflix, YouTube and web pages on your TV. It
runs on a spare computer plugged into the TV, and there's nothing to install on
the phone: it opens a web page served by that computer, and the page controls a
Chrome window on the TV. No casting, no app store.

- **Netflix and YouTube**: browse, search, play, pause, skip, volume, full screen.
- **Reading**: send any web page to the TV, scroll it, change the text size,
  follow its links (with breadcrumbs back), play the videos in it.
- **Per person**: each phone says who's holding it, and the TV keeps each
  person's Recent list and place in every show, video and page.
- **Easy to share**: the TV shows a QR code that gets the next phone the remote.

## What you need

- **A spare computer on the TV's HDMI.** Any laptop or mini PC that plays video
  smoothly. It runs Linux with a desktop (developed on Fedora with GNOME) and
  should log in automatically when switched on, since the remote starts at
  login.
- **Google Chrome** on that computer, either the normal package or the Flatpak.
- **[Babashka](https://babashka.org)** (`bb`), the small program the remote runs
  on. `install.sh` installs it for you if it's missing (into `~/.local/bin`).
- **A Netflix account** and/or a Google account for YouTube.
- **Phones on the same Wi-Fi**: Android, with Chrome or Samsung Internet. iPhones
  should work too, in Safari, but haven't been tried yet.

## Install

On the TV computer:

```bash
git clone https://github.com/chrismurrph/tvremote ~/tvremote
cd ~/tvremote
./install.sh
```

`install.sh` creates `config.edn` from `config.example.edn`, sets the remote up
to start whenever you log in, and prints the address for your phone, such as
`http://192.168.1.50:7700/` (and a QR code of it, if `qrencode` is installed).

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

## Get the remote onto a phone

1. Open the remote's address on the phone **in its own browser** (see the next
   section for why). Easiest: on a phone that already has the remote, open
   **⋯ More** and press **Show a QR code on the TV**, then scan the TV with
   the new phone's camera. A camera scan always opens the real browser. The
   first phone can scan the QR code `install.sh` printed, or type the address.
2. Pick your name. Each phone remembers it; tap the name at the top to change it.
3. Put the remote on your home screen (next section). It's worth it: from
   then on the remote is one tap away, like an app.
4. Optional, Android with Chrome only, to stop the phone's screen going off
   while you use the remote: in Chrome open `chrome://flags`, search for **Insecure origins treated as
   secure**, enter the address (such as `http://192.168.1.50:7700`), set it to
   **Enabled**, and relaunch Chrome. The remote then says
   "☀ Screen stays on". There's no way to do this on an iPhone, so its
   screen goes off as usual.

## Put the remote on your home screen

The remote is a web page, but once it's on your home screen it opens like an
app: its own icon ("TV Remote", a TV with a red play button), full screen, no
address bar.

**First, make sure you're in the real browser.** If you opened the address
from a link in Messenger, WhatsApp, Facebook, Instagram, Gmail or another app,
it's showing in that app's own built-in browser. There, the home-screen option
is missing, and the name you pick isn't remembered once you move to the real
browser. Open the app's menu (usually ⋯ or ⋮) and choose **Open in Chrome**,
**Open in browser** or **Open in Safari**. Or type the address into the
browser yourself.

Then:

- **Android, Chrome:** the ⋮ menu at the top right → **Add to Home screen**
  (in newer versions it may say **Install app**, or be under Add to Home
  screen → **Install**).
- **Android, Samsung Internet:** the ☰ menu at the bottom right → **Add page
  to** → **Home screen**.
- **iPhone, Safari:** the Share button (the square with the up arrow) →
  **Add to Home Screen**. Chrome on iPhone has the same option under its
  Share button, on iOS 16.4 or later.

Pick your name again if the remote asks: each browser keeps its own copy, and
the home-screen icon uses the browser you added it from.

## Lock the TV computer's keyboard and mouse (optional)

With small children around, the TV computer's own keyboard and mouse can undo
what the phones set up: closing Chrome, changing the volume, putting the
computer to sleep. With the lock, pressing them does nothing and only the
phones work the TV.

It's off unless you turn it on. Once, on the TV computer:

```bash
sudo usermod -aG input $USER     # lets the remote take hold of the keyboard and mouse
```

Log out and back in (or restart), set `:input-lock true` in `config.edn`, and
run `./install.sh` again. **🔒 Lock them** then appears under **⋯ More** on the
phone. While locked, the power button does nothing too (holding it down still
forces the computer off). To unlock without a phone, type the `:unlock-word`
from `config.edn` (`tvremote` unless you change it) on the keyboard. If the
remote stops for any reason, the lock lifts by itself.

## Send a page from your computer

The phone can open a link on the TV (under **⋯ More**), but you can also send
one from any computer on the same Wi-Fi. That's handy for scripts, or for asking an AI coding
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
computer (update the TV's copy from GitHub and restart it, or try code against
the running remote). `deploy.env.example` says what they need. You don't need any of them
to use the remote.

`test/` holds checks that run on any computer, without a TV or Chrome: for
example `bb test/state_race.clj` checks that saves made at the same moment
don't lose each other.

## License

MIT - see `LICENSE`.
