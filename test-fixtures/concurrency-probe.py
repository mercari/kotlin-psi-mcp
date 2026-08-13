#!/usr/bin/env python3
"""
Concurrency test for the PSI MCP server (see test-fixtures/README.md § Concurrency).

How it works:
First, sends N identical module-search calls sequentially, then all at once from a
thread pool concurrently, and compares how long each batch takes.

Expected outcome:
If the server serialized tool execution the two batches would take about the same time;
in practice the concurrent batch is much faster, because tool work runs inside IntelliJ
read actions, which run in parallel.
(For example, @Synchronized selection read in PsiMcpServerManager does not block parallel
calls.)

Details:
module-search is used because every call has to reach a real read action or the test
measures nothing — it walks the module graph inside runReadAction via its real
`query` parameter, and stays fast on any project size. Do not swap in find-symbols
with `query`: its parameter is `symbol_name`, and a wrong parameter returns an
instant error before any read action, so you would be timing only HTTP plumbing.

The calls are driven from one process with threads (not N separate curl processes),
so we measure the server rather than process-spawn overhead. Use a high call count
(64-128); a single call, or a low count, cannot reveal the difference.

Usage:
    python3 concurrency-probe.py [call_count]     # call_count defaults to 64

Requires the server on :51234 serving an indexed project (audit-sample is the
standard fixture — confirm with check-sync-status).
"""
import json
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

MODULE_SEARCH_URL = "http://127.0.0.1:51234/api/tools/module-search"
REQUEST_BODY = json.dumps({"query": "a"}).encode()  # module-search's real parameter
DEFAULT_CALL_COUNT = 64
WARMUP_CALLS = 5


def as_millis(seconds):
    return seconds * 1000

def send_request():
    """POST one module-search call and return its raw response body."""
    request = urllib.request.Request(
        MODULE_SEARCH_URL, data=REQUEST_BODY, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request) as response:
        return response.read()

def time_one_call():
    """Return how long a single call takes, in seconds."""
    started_at = time.perf_counter()
    send_request()
    return time.perf_counter() - started_at


def fetch_result():
    """Return the tool's parsed result, exiting if the call wasn't a real success.

    A wrong tool or parameter still answers HTTP 200, so without this check the test
    could silently time error responses that never touch a read action. The returned
    result is also printed at the end, as evidence the timings measured real work.
    """
    body = send_request()
    try:
        result = json.loads(json.loads(body)["result"])
    except (ValueError, KeyError, TypeError):
        result = None
    if not (isinstance(result, dict) and result.get("success") is True):
        sys.exit(f"probe is not measuring a successful call — check the tool/parameters: {body[:200]!r}")
    return result


def describe(durations):
    """Summarise a batch of call durations as fastest / median / slowest."""
    ordered = sorted(durations)
    fastest = as_millis(ordered[0])
    median = as_millis(ordered[len(ordered) // 2])
    slowest = as_millis(ordered[-1])
    return f"fastest={fastest:.1f} median={median:.1f} slowest={slowest:.1f} ms"


def sequential_run(call_count):
    """Time `call_count` calls sequentially; return (total_seconds, per_call_durations)."""
    started_at = time.perf_counter()
    durations = [time_one_call() for _ in range(call_count)]
    return time.perf_counter() - started_at, durations


def concurrent_run(call_count):
    """Time `call_count` calls fired concurrently; return (total_seconds, per_call_durations)."""
    with ThreadPoolExecutor(max_workers=call_count) as pool:
        started_at = time.perf_counter()
        durations = list(pool.map(lambda _: time_one_call(), range(call_count)))
        return time.perf_counter() - started_at, durations


def main():
    call_count = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CALL_COUNT

    result = fetch_result()  # validates the call succeeds; shown at the end as proof of real work
    for _ in range(WARMUP_CALLS):  # let class loading and workspace-model caches warm up
        time_one_call()

    print(f"single call:        {as_millis(time_one_call()):.1f} ms")

    sequential_total, sequential = sequential_run(call_count)
    concurrent_total, concurrent = concurrent_run(call_count)

    print(f"\ncall count = {call_count}")
    print(f"sequential: {as_millis(sequential_total):7.1f} ms   per call {describe(sequential)}")
    print(f"concurrent: {as_millis(concurrent_total):7.1f} ms   per call {describe(concurrent)}")
    print(f"\nspeedup (sequential / concurrent): {sequential_total / concurrent_total:.2f}x")
    print(
        "if the server serialized calls, the concurrent batch would take about as long "
        f"as the sequential one ({as_millis(sequential_total):.0f} ms)"
    )

    modules = [match.get("name") for match in result.get("matches", [])]
    print(f"\nresult measured: success={result.get('success')} "
          f"totalMatches={result.get('totalMatches')} modules={modules}")


if __name__ == "__main__":
    main()
