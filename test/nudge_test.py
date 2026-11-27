#!/usr/bin/env python3
"""pointernudge.py's events, with os.write caught, so no device is made
(2026-11-28). Run: python3 test/nudge_test.py"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import pointernudge as pn
from inputlock import EVENT

checks = fails = 0
def check(ok, what):
    global checks, fails
    checks += 1
    if not ok:
        fails += 1
        print("FAIL:", what)

out = []
real_write, real_sleep = os.write, pn.time.sleep
os.write = lambda fd, b: out.append(EVENT.unpack(b)[2:]) or len(b)
pn.time.sleep = lambda s: None
try:
    pn.nudge(7, 640, 10)
finally:
    os.write, pn.time.sleep = real_write, real_sleep

abs_moves = [(c, v) for k, c, v in out if k == pn.EV_ABS]
check(abs_moves == [(pn.ABS_X, 641), (pn.ABS_Y, 10), (pn.ABS_X, 640), (pn.ABS_Y, 10)],
      "1 pixel right of the parking point, then onto it: %r" % abs_moves)
check(out[-1] == (pn.EV_SYN, pn.SYN_REPORT, 0), "ends with a SYN_REPORT")
check(pn.NAME == b"tvremote nudge", "the name the lock and inputwatch.py skip")

print("1 test, %d checks, %d failing" % (checks, fails))
sys.exit(1 if fails else 0)
