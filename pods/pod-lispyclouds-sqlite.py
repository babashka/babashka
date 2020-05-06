#!/usr/bin/env python3

# To run this:

# Install Python3 and SQLite3
# Create a virtualenv: python3 -m venv ~/.virtualenvs/babashka
# Switch to it: source ~/.virtualenvs/babashka/bin/activate
# Run: pip install bcoding # to install the bencode lib
# Create a new db: sqlite3 /tmp/babashka.db "CREATE TABLE foo (foo int);"
# Can be tested as:
# pods/pod-lispyclouds-sqlite.py <<< $(bb -e '(bencode/write-bencode System/out {"op" "invoke" "var" "pod.lispy-clouds.sqlite/execute!" "id" 1 "args" "[\"insert into foo values(1)\"]"})')

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
                    "namespaces": [{"name": "pod.lispy-clouds.sqlite",
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

            if var == "pod.lispy-clouds.sqlite/execute!":
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
