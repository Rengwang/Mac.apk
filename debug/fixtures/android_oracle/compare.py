from pathlib import Path
import sys

def read(path):
    result = {}
    for line in Path(path).read_text().splitlines():
        if line.startswith("ORACLE "):
            name, value = line.removeprefix("ORACLE ").split("=", 1)
            if name in result:
                raise ValueError(f"duplicate record: {name}")
            result[name] = value
    return result

mac, android = map(read, sys.argv[1:3])
if android.pop("sdk", None) != "36":
    raise SystemExit("expected API 36")
expected = {"bundle", "intent", "filter", "pattern"}
if set(mac) != expected or set(android) != expected:
    raise SystemExit("missing or unexpected family")
if "--inject-difference" in sys.argv:
    android["pattern"] = "injected"
differences = 0
for name in sorted(expected):
    if mac[name] != android[name]:
        print(f"DIFF {name}: mac={mac[name]} android={android[name]}")
        differences += 1
    else:
        print(f"MATCH {name}: {mac[name]}")
sys.exit(1 if differences else 0)
