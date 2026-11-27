#!/usr/bin/env python3
"""pointernudge.py - park the TV's mouse pointer at the middle of the top edge,
with one real tiny move there (1 pixel right and back), for each line read on
stdin. tvremote runs it for good and writes a line after it hides the pointer
in a page.

Parking (2026-11-28): a hidden pointer left resting on Netflix's speed button
(bottom right) opened its Playback Speed panel over a paused film. So the
virtual mouse is ABSOLUTE (like a VM's tablet): GNOME maps its range onto the
whole screen, so it can go straight to a point, with no pointer acceleration
to undo. The screen is mirrored, one 1280x720 logical monitor; the size and
the parking point come from the command line (W H X Y), 1280 720 640 10 when
none is given. tvremote's park-point must be the same point.

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
EV_SYN, EV_KEY, EV_ABS = 0, 1, 3
SYN_REPORT, ABS_X, ABS_Y, BTN_LEFT = 0, 0, 1, 0x110
BUS_VIRTUAL = 0x06
UI_SET_EVBIT = 0x40045564
UI_SET_KEYBIT = 0x40045565
UI_SET_ABSBIT = 0x40045567
UI_ABS_SETUP = 0x401C5504  # struct uinput_abs_setup: u16 code, pad, input_absinfo (6 x s32)
UI_DEV_SETUP = 0x405C5503  # struct uinput_setup: input_id (4 x u16), name[80], u32
UI_DEV_CREATE = 0x5501
UI_DEV_DESTROY = 0x5502


def emit(fd, kind, code, value):
    os.write(fd, EVENT.pack(0, 0, kind, code, value))


def make(w, h):
    """The virtual mouse, absolute over a w x h screen. A left button too:
    without one udev does not call it a mouse, and GNOME Shell would ignore
    it."""
    try:
        fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
    except PermissionError:
        print("no permission to /dev/uinput - see the udev rule in TVREMOTE.md",
              file=sys.stderr)
        sys.exit(2)
    fcntl.ioctl(fd, UI_SET_EVBIT, EV_KEY)
    fcntl.ioctl(fd, UI_SET_KEYBIT, BTN_LEFT)
    fcntl.ioctl(fd, UI_SET_EVBIT, EV_ABS)
    for code, top in ((ABS_X, w - 1), (ABS_Y, h - 1)):
        fcntl.ioctl(fd, UI_SET_ABSBIT, code)
        fcntl.ioctl(fd, UI_ABS_SETUP, struct.pack("HH6i", code, 0, 0, 0, top, 0, 0, 0))
    fcntl.ioctl(fd, UI_DEV_SETUP, struct.pack("HHHH80sI", BUS_VIRTUAL, 0, 0, 1, NAME, 0))
    fcntl.ioctl(fd, UI_DEV_CREATE)
    time.sleep(1)  # let GNOME Shell open it before the first nudge
    return fd


def nudge(fd, x, y):
    """To the parking point, by way of 1 pixel to its right: the kernel drops
    an absolute event that repeats the last value, so a pointer already parked
    still gets a real move."""
    for px in (x + 1, x):
        emit(fd, EV_ABS, ABS_X, px)
        emit(fd, EV_ABS, ABS_Y, y)
        emit(fd, EV_SYN, SYN_REPORT, 0)
        time.sleep(0.02)


def main():
    w, h, x, y = (int(a) for a in (sys.argv[1:5] if len(sys.argv) >= 5 else (1280, 720, 640, 10)))
    fd = make(w, h)
    try:
        for _ in sys.stdin:
            nudge(fd, x, y)
    finally:
        fcntl.ioctl(fd, UI_DEV_DESTROY)
        os.close(fd)


if __name__ == "__main__":
    main()
