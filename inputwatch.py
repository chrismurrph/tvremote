#!/usr/bin/env python3
"""inputwatch.py - say when the TV computer's own keyboard or mouse is used,
without taking them: prints a line each time (at most one a second), which
tvremote reads to tell the phone "used at the TV". Devices plugged in later
are picked up within 5s. While inputlock.py holds the devices this sees
nothing, which is right: presses then do nothing at the TV either.

Exit 2: no permission to the devices (the user needs the 'input' group).
Standard library only; the device list is inputlock.py's."""
import os, select, sys, time
from inputlock import devices, EVENT

EV_KEY, EV_REL, EV_ABS = 1, 2, 3


def main():
    held, last = {}, 0.0
    while True:
        for path in devices():
            if path in held.values():
                continue
            try:
                held[os.open(path, os.O_RDONLY | os.O_NONBLOCK)] = path
            except PermissionError:
                print("no permission to " + path + " - add this user to the 'input' group",
                      file=sys.stderr)
                sys.exit(2)
            except OSError:
                continue
        ready, _, _ = select.select(list(held), [], [], 5)
        used = False
        for fd in ready:
            try:
                data = os.read(fd, EVENT.size * 64)
            except OSError:  # unplugged
                os.close(fd)
                del held[fd]
                continue
            for i in range(0, len(data) - EVENT.size + 1, EVENT.size):
                kind = EVENT.unpack_from(data, i)[2]
                if kind in (EV_KEY, EV_REL, EV_ABS):
                    used = True
        if used and time.time() - last >= 1:
            last = time.time()
            print("used", flush=True)


if __name__ == "__main__":
    main()
