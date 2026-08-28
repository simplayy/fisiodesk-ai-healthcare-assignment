#!/usr/bin/env python3
"""Latency check of the running service under concurrent load.

    scripts/load-test.py load [users] [requests-per-user]   # e.g. load 60 5
    scripts/load-test.py cold                                # new questions, LLM path

"load" simulates N professionals asking questions in parallel over persistent
connections. Questions come from a fixed pool combined with a different
"today" per request, so the 60 s response cache is almost always cold and the
plan is executed for real; plans of the pool are already stored, so no LLM
call is made (this is the steady state of a system where the same questions
recur). Prints client-side and server-side latency percentiles and checks
that every plan came from the LLM (no rule-based fallback).

"cold" fires a set of never-seen questions at once: each one must be planned
by the LLM inside the request. Once planned, a question is stored and no
longer cold: set COLD_TAG=<anything> to make the questions unique again
(the tag is appended to the text). Do not repeat it within a minute on a
free-tier Gemini key (15 requests/min).

Only the standard library is used.
"""
import asyncio
import datetime
import json
import os
import random
import sys
import time

HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8080"))

POOL = [
    "Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento",
    "Pazienti con lombalgia che non mostrano miglioramento negli ultimi 3 mesi",
    "Pazienti con cervicalgia in miglioramento negli ultimi 2 mesi",
    "Chi ha saltato l'ultimo appuntamento?",
    "Chi ha il colpo della strega e sta meglio?",
    "Pazienti con mal di schiena che hanno avuto progressi negli ultimi 6 mesi",
    "Pazienti con problemi alla spalla peggiorati nell'ultimo mese",
    "Pazienti con dolore al ginocchio senza miglioramento negli ultimi 4 mesi che hanno saltato l'ultimo appuntamento",
    "Pazienti con dolore all'anca stazionari negli ultimi 2 mesi",
    "Pazienti con cervicalgia e lombalgia recuperati negli ultimi 3 mesi",
    "Quali pazienti con problemi alla spalla stanno meglio negli ultimi 4 mesi?",
    "Pazienti con cervicalgia guariti negli ultimi 2 mesi",
]
NEW = [
    "Elenca i pazienti con lombalgia peggiorati negli ultimi 5 mesi",
    "Chi soffre di cervicale ed è migliorato nell'ultimo mese?",
    "Pazienti con dolore alla spalla che non hanno fatto progressi negli ultimi 3 mesi",
    "Pazienti con gonalgia recuperati negli ultimi 6 mesi che hanno saltato l'ultimo appuntamento",
    "Pazienti con coxalgia in miglioramento negli ultimi 3 mesi",
    "Mostrami chi ha mal di schiena e ha saltato l'ultimo appuntamento",
    "Pazienti con low back pain stazionari negli ultimi 2 mesi",
    "Chi ha la cervicalgia e sta peggio negli ultimi 4 mesi?",
    "Pazienti con problemi al ginocchio migliorati negli ultimi 2 mesi",
    "Pazienti lombari senza miglioramento negli ultimi 6 mesi che hanno saltato l'ultimo appuntamento",
]
# Daily "today" values over the demo dataset period.
DATES = [(datetime.date(2024, 8, 1) + datetime.timedelta(days=i)).isoformat() for i in range(153)]


async def request(reader, writer, question, reference_date):
    payload = {"question": question}
    if reference_date:
        payload["referenceDate"] = reference_date
    body = json.dumps(payload).encode()
    writer.write(b"POST /api/query HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                 b"Content-Length: %d\r\n\r\n" % len(body) + body)
    start = time.perf_counter()
    await writer.drain()
    head = await reader.readuntil(b"\r\n\r\n")
    headers = {l.split(":")[0].lower(): l.split(":", 1)[1].strip()
               for l in head.decode().split("\r\n")[1:] if ":" in l}
    if "content-length" in headers:
        data = await reader.readexactly(int(headers["content-length"]))
    else:  # chunked
        data = b""
        while True:
            size = int((await reader.readline()).strip().split(b";")[0], 16)
            if size == 0:
                await reader.readline()
                break
            data += await reader.readexactly(size)
            await reader.readline()
    wall = (time.perf_counter() - start) * 1000
    response = json.loads(data)
    return dict(question=question, wall=wall, server=response["meta"]["tookMs"],
                cached=response["meta"]["cached"], planner=response["plan"]["planner"],
                results=len(response["results"]))


async def user(index, per_user, rnd, rows, questions, dates):
    reader, writer = await asyncio.open_connection(HOST, PORT)
    try:
        for _ in range(per_user):
            # without dates (cold mode) each user asks its own never-seen question
            question = rnd.choice(questions) if dates else questions[index]
            rows.append(await request(reader, writer, question,
                                      rnd.choice(dates) if dates else None))
    finally:
        writer.close()


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))]


def report(name, rows):
    for label, subset in (("uncached", [r for r in rows if not r["cached"]]),
                          ("cached", [r for r in rows if r["cached"]])):
        if not subset:
            continue
        wall = [r["wall"] for r in subset]
        line = (f"  {name} {label:8s} n={len(subset):4d}  wall p50={pct(wall, 50):5.0f} "
                f"p95={pct(wall, 95):5.0f} p99={pct(wall, 99):5.0f} max={max(wall):5.0f} ms  "
                f"over 2s={sum(1 for x in wall if x > 2000)}")
        if label == "uncached":
            server = [r["server"] for r in subset]
            line += f"   server-side p50={pct(server, 50):4.0f} p95={pct(server, 95):4.0f} max={max(server):4.0f}"
        print(line)
    fallback = [r for r in rows if r["planner"] != "llm"]
    print(f"  {name} plans not from the LLM: {len(fallback)}")
    return fallback or any(r["wall"] > 2000 for r in rows)


async def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "load"
    rows = []
    if mode == "cold":
        tag = os.environ.get("COLD_TAG")
        questions = [f"{q} [{tag}]" for q in NEW] if tag else NEW
        dates, users, per_user = None, len(questions), 1
    else:
        questions, dates = POOL, DATES
        users = int(sys.argv[2]) if len(sys.argv) > 2 else 60
        per_user = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    rnd = random.Random(int(os.environ.get("SEED", "1")))
    start = time.perf_counter()
    await asyncio.gather(*(user(i, per_user, rnd, rows, questions, dates) for i in range(users)))
    print(f"  {len(rows)} requests from {users} concurrent users in {time.perf_counter() - start:.1f}s")
    if mode == "cold":
        for r in rows:
            print(f"    {r['wall']:5.0f} ms  planner={r['planner']:9s} results={r['results']}  {r['question'][:60]}")
    failed = report(mode + str(users), rows)
    print("FAIL: latency or fallback constraint violated" if failed else "OK: all requests under 2 s, all plans from the LLM")
    sys.exit(1 if failed else 0)


asyncio.run(main())
