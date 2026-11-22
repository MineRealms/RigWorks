"""Run a script through the already running local Blender MCP addon."""

import argparse
import json
import socket
from pathlib import Path


def request(command, params=None, timeout=120):
    payload = {"type": command, "params": params or {}}
    with socket.create_connection(("127.0.0.1", 9876), timeout=timeout) as sock:
        sock.settimeout(timeout)
        sock.sendall(json.dumps(payload).encode("utf-8"))
        response = bytearray()
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                raise RuntimeError("Blender closed the connection before its response")
            response.extend(chunk)
            try:
                result = json.loads(response)
            except (json.JSONDecodeError, UnicodeDecodeError):
                continue
            if result.get("status") != "success":
                raise RuntimeError(json.dumps(result, ensure_ascii=True))
            return result.get("result", result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--script", type=Path)
    group.add_argument("--code")
    group.add_argument("--command")
    parser.add_argument("--params", default="{}")
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    if args.script:
        script = args.script.resolve()
        code = "__file__ = " + repr(str(script)) + "\n" + script.read_text(encoding="utf-8")
    else:
        code = args.code
    result = request("execute_code", {"code": code}, args.timeout) if code is not None else request(
        args.command, json.loads(args.params), args.timeout)
    print(json.dumps(result, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
