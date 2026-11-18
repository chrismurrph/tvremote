#!/usr/bin/env python3
"""pointernudge.py - give the TV's mouse pointer one real tiny move (1 pixel
right and back) for each line read on stdin. tvremote runs it for good and
writes a line after it hides the pointer in a page.

Why (2026-11-19): the TV's Chrome runs on Wayland (GNOME). A page's
cursor:none reaches the screen only when the pointer really moves: GNOME Shell
draws the pointer and asks Chrome for its image only then. Chrome's own
made-up moves (CDP) never reach GNOME Shell, and the lock (inputlock.py)
stops the real mouse. So this makes a virtual mouse through uinput, which
GNOME Shell treats like any mouse. The lock and inputwatch.py skip it by its
name, "tvremote nudge".

Long-lived, not one device per nudge: a new device takes GNOME Shell a moment
to open, so a nudge sent straight after making it would be lost, and a device
coming and going every few seconds is churn for the desktop.

Exit 2: no permission to /dev/uinput (see the udev rule in TVREMOTE.md).
Standard library only, like inputlock.py."""
import fcntl, os, struct, sys, time

from inputlock import EVENT

NAME = b"tvremote nudge"
EV_SYN, EV_KEY, EV_REL = 0, 1, 2
SYN_REPORT, REL_X, REL_Y, BTN_LEFT = 0, 0, 1, 0x110
BUS_VIRTUAL = 0x06
UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_SET_RELBIT = 0x40045566
UI_DEV_SETUP = 0x405C5503  # struct uinput_setup: input_id (4 x u16), name[80], u32
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502


def emit(fd, kind, code, value):
    os.write(fd, EVENT.pack(0, 0, kind, code, value))


def make():
    """The virtual mouse. A left button too: without one udev does not call
    it a mouse, and GNOME Shell would ignore it."""
    try:
        fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
    except PermissionError:
        print("no permission to /dev/uinput - see the udev rule in TVREMOTE.md",
              file=sys.stderr)
        sys.exit(2)
    fcntl.ioctl(fd, UI_SET_EVBIT, EV_KEY)
    fcntl.ioctl(fd, UI_SET_KEYBIT, BTN_LEFT)
    fcntl.ioctl(fd, UI_SET_EVBIT, EV_REL)
    fcntl.ioctl(fd, UI_SET_RELBIT, REL_X)
    fcntl.ioctl(fd, UI_SET_RELBIT, REL_Y)
    fcntl.ioctl(fd, UI_DEV_SETUP, struct.pack("HHHH80sI", BUS_VIRTUAL, 0, 0, 1, NAME, 0))
    fcntl.ioctl(fd, UI_DEV_CREATE)
    time.sleep(1)  # let GNOME Shell open it before the first nudge
    return fd


def nudge(fd):
    for dx in (1, -1):
        emit(fd, EV_REL, REL_X, dx)
        emit(fd, EV_SYN, SYN_REPORT, 0)
        time.sleep(0.02)


def main():
    fd = make()
    try:
        for _ in sys.stdin:
            nudge(fd)
    finally:
        fcntl.ioctl(fd, UI_DEV_DESTROY)
        os.close(fd)


if __name__ == "__main__":
    main()
