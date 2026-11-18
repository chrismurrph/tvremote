#!/usr/bin/env python3
"""Which input devices inputlock.py holds (and inputwatch.py listens to),
from a made-up /proc/bus/input/devices (2026-11-19). No real devices are
opened. Run: python3 test/devices_test.py"""
import io, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import inputlock

PROC = '''I: Bus=0019 Vendor=0000 Product=0001 Version=0000
N: Name="Power Button"
H: Handlers=kbd event0

I: Bus=0011 Vendor=0001 Product=0001 Version=ab41
N: Name="AT Translated Set 2 keyboard"
H: Handlers=sysrq kbd leds event3

I: Bus=0003 Vendor=046d Product=c077 Version=0111
N: Name="Logitech USB Optical Mouse"
H: Handlers=mouse0 event5

I: Bus=0018 Vendor=06cb Product=7e7e Version=0100
N: Name="SYNA Touchpad"
H: Handlers=mouse1 event6

I: Bus=0000 Vendor=0000 Product=0000 Version=0000
N: Name="Video Bus"
H: Handlers=kbd event7

I: Bus=0019 Vendor=0000 Product=0006 Version=0000
N: Name="HDA Intel PCH Headphone"
H: Handlers=event8

I: Bus=0006 Vendor=0000 Product=0000 Version=0000
N: Name="tvremote nudge"
H: Handlers=mouse2 event9
'''

tests = checks = failed = 0


def check(ok, what):
    global checks, failed
    checks += 1
    if not ok:
        failed += 1
        print("FAIL:", what)


def with_proc(text):
    inputlock.open = lambda path, *a, **k: io.StringIO(text)
    try:
        return inputlock.devices()
    finally:
        del inputlock.open


tests += 1
d = with_proc(PROC)
check("/dev/input/event3" in d, "keyboard held")
check("/dev/input/event5" in d, "mouse held")
check("/dev/input/event6" in d, "touchpad held")
check("/dev/input/event7" in d, "other kbd handlers held")
check("/dev/input/event0" not in d, "power button left alone")
check("/dev/input/event8" not in d, "no kbd or mouse handler: left alone")

tests += 1
check("/dev/input/event9" not in d, "tvremote's own nudge pointer is never held or watched")

print(f"{tests} tests, {checks} checks, {failed} failing")
sys.exit(1 if failed else 0)
