#!/usr/bin/env python3
"""
Compare the answers of the C++ conversion (engine/test/conversion-dump.cpp) with those of the conversion server of
version 1, on the same requests: whole sentences cut into bunsetsu (/api/segment-convert) and the candidates of one
bunsetsu after the text before it (/api/segment-candidates).

Usage: compare_cpp_java.py <rows .tsv> <server URL> <conversion-dump> <weights> <tokenizer.json> [count=400]

The rows are those of the rank measurement (reading, right text, place, count, model text, context). Both sides
should use the same model, Anthy with the same dictionaries, and an empty history.
"""

import json
import subprocess
import sys
import urllib.request


def main() -> None:
    rows_path, url, dump, weights, tokenizer = sys.argv[1:6]
    count = int(sys.argv[6]) if len(sys.argv) > 6 else 400
    rows = [l.rstrip("\n").split("\t") for l in open(rows_path, encoding="utf-8")][:count]
    requests = []
    for r in rows:
        reading, context = r[0], r[5]
        requests.append({"path": "/api/segment-candidates", "request": {"input": reading, "context": context, "n": 5}})
        requests.append({"path": "/api/segment-convert", "request": {"input": reading, "context": context, "n": 5}})

    java, java_ms = [], 0.0
    import time
    for q in requests:
        body = json.dumps(q["request"]).encode()
        t0 = time.time()
        req = urllib.request.Request(url + q["path"], data=body, method="POST")
        java.append(json.loads(urllib.request.urlopen(req, timeout=60).read()))
        java_ms += (time.time() - t0) * 1000

    proc = subprocess.run([dump, weights, tokenizer], input="\n".join(json.dumps(q, ensure_ascii=False)
                                                                     for q in requests) + "\n",
                          capture_output=True, text=True, check=True)
    cpp = [json.loads(l) for l in proc.stdout.splitlines()]
    cpp_ms = sum(c["ms"] for c in cpp)

    same, differ = 0, []
    for q, j, c in zip(requests, java, cpp):
        a = c["answer"]
        if q["path"] == "/api/segment-candidates":
            j = {"candidates": j["candidates"], "output": j["output"]}
        if a == j:
            same += 1
        else:
            differ.append((q, j, a))
    print(f"{same} of {len(requests)} answers are the same; java {java_ms / len(requests):.1f} ms per request "
          f"(over HTTP), C++ {cpp_ms / len(requests):.1f} ms")
    for q, j, a in differ[:10]:
        print("DIFFER", q["path"], q["request"]["input"])
        print("  java:", json.dumps(j, ensure_ascii=False)[:300])
        print("  c++ :", json.dumps(a, ensure_ascii=False)[:300])


if __name__ == "__main__":
    main()
