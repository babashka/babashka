#!/usr/bin/env python3

import json
import sqlite3
import sys

from bcoding import bencode, bdecode


def read():
    return dict(bdecode(sys.stdin.buffer))


def write(obj):
    sys.stdout.buffer.write(bencode(obj))
    sys.stdout.flush()

def debug(*msg):
    with open("/tmp/debug.log", "a") as f:
        f.write(str(msg) + "\n")

def main():
    while True:
        msg = read()
        debug("msg", msg)

        op = msg["op"]

        if op == "describe":
            write(
                {
                    "format": "json",
                    "namespaces": [{"name": "pod.lispyclouds.sqlite",
                                    "vars": [{"name": "execute!"}]}]}
            )
        elif op == "invoke":
            var = msg["var"]
            id = msg["id"]
            args = json.loads(msg["args"])
            debug(args)
            conn = sqlite3.connect("/tmp/babashka.db")
            c = conn.cursor()

            result = None

            if var == "pod.lispyclouds.sqlite/execute!":
                try:
                    result = c.execute(*args)
                except Exception as e:
                    debug(e)

            value = json.dumps(result.fetchall())
            debug("value", value)

            write({"value": value, "id": id, "status": ["done"]})

            conn.commit()
            conn.close()

if __name__ == "__main__":
    main()
