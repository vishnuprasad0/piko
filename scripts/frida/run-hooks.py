#!/usr/bin/env python3
"""
run-hooks.py - Load dm-hooks.js into the Instagram Frida Gadget and stay resident.

Unlike the `frida` CLI (which exits the moment stdin closes when run from a pipe),
this keeps the session alive for a fixed duration so DM events can be triggered and
the hook output observed. Use for both Windows and Mac.

Usage:
    python scripts/frida/run-hooks.py [--host 127.0.0.1:27043] [--seconds 60]

Then, while it runs, open a DM thread on the device. Hook output prints here.
"""
import argparse
import sys
import time

import frida


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1:27043", help="gadget host:port")
    ap.add_argument("--seconds", type=int, default=60, help="how long to stay attached")
    ap.add_argument("--script", default="scripts/frida/dm-hooks.js", help="path to JS")
    args = ap.parse_args()

    with open(args.script, "r", encoding="utf-8") as f:
        source = f.read()

    print(f"[run-hooks] connecting to gadget at {args.host} ...")
    dev = frida.get_device_manager().add_remote_device(args.host)
    session = dev.attach("Gadget")
    print("[run-hooks] attached. loading dm-hooks.js ...")

    script = session.create_script(source)

    def on_message(message, data):
        if message["type"] == "send":
            print("[send]", message["payload"])
        elif message["type"] == "error":
            # This is what we care about: a JS/native error when a hook fires
            print("[ERROR]", message.get("stack") or message.get("description"))
        else:
            print("[msg]", message)

    script.on("message", on_message)
    script.load()

    print(f"[run-hooks] loaded. Staying attached for {args.seconds}s.")
    print("[run-hooks] >>> Open a DM thread on the device now to fire Hook 1. <<<")
    try:
        time.sleep(args.seconds)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            session.detach()
        except Exception:
            pass
    print("[run-hooks] done.")


if __name__ == "__main__":
    main()
