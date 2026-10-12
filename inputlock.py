#!/usr/bin/env python3
"""inputlock.py WORD - keep the TV computer's keyboards, mice and touchpads to
itself, so pressing them does nothing on the desktop; the phone is then the
only way in. tvremote starts it when the lock is on and stops it to unlock.

Each device is held with EVIOCGRAB (exclusive: the desktop gets no events);
the kernel lets go the moment this process ends, however it ends. Devices
plugged in later are picked up within 5s. The power, sleep and lid switches
are left alone. Typing WORD on any keyboard ends it (exit 0), as a way back in
without the phone.

Exit 2: no permission to the devices (the user needs the 'input' group).
Standard library only."""
import fcntl, os, select, struct, sys

EVIOCGRAB = 0x40044590
SKIP = {"Power Button", "Sleep Button", "Lid Switch"}
EVENT = struct.Struct("llHHi")  # time (2 longs), type, code, value
EV_KEY = 1
LETTERS = dict(zip([16, 17, 18, 19, 20, 21, 22, 23, 24, 25,
                    30, 31, 32, 33, 34, 35, 36, 37, 38,
                    44, 45, 46, 47, 48, 49, 50],
                   "qwertyuiopasdfghjklzxcvbnm"))


def devices():
    """Event nodes of every keyboard and pointer, from /proc."""
    out, name = [], None
    for line in open("/proc/bus/input/devices"):
        if line.startswith("N: Name="):
            name = line.split("=", 1)[1].strip().strip('"')
        elif line.startswith("H: Handlers="):
            h = line.split("=", 1)[1].split()
            if name not in SKIP and ("kbd" in h or any(x.startswith("mouse") for x in h)):
                out += ["/dev/input/" + x for x in h if x.startswith("event")]
    return out


def main():
    word = sys.argv[1].lower()
    held, typed = {}, ""
    while True:
        for path in devices():
            if path in held.values():
                continue
            try:
                fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
            except PermissionError:
                print("no permission to " + path + " - add this user to the 'input' group",
                      file=sys.stderr)
                sys.exit(2)
            except OSError:
                continue
            try:
                fcntl.ioctl(fd, EVIOCGRAB, 1)
                held[fd] = path
            except OSError:
                os.close(fd)
        ready, _, _ = select.select(list(held), [], [], 5)
        for fd in ready:
            try:
                data = os.read(fd, EVENT.size * 64)
            except OSError:  # unplugged
                os.close(fd)
                del held[fd]
                continue
            for i in range(0, len(data) - EVENT.size + 1, EVENT.size):
                _, _, kind, code, value = EVENT.unpack_from(data, i)
                if kind == EV_KEY and value == 1:
                    typed = (typed + LETTERS.get(code, "#"))[-len(word):]
                    if typed == word:
                        sys.exit(0)


if __name__ == "__main__":
    main()
