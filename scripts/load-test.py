#!/usr/bin/env python3
"""Latency of the running service under concurrent load.

    scripts/load-test.py load [users] [requests-per-user]   # e.g. load 60 5
    scripts/load-test.py cold                               # new questions, LLM path

"load" simulates N professionals asking questions in parallel. Questions come
from a fixed pool combined with a different "today" per request, so the 60 s
answer cache is almost always cold and the plan is executed for real. The first
run also plans the pool's questions with the LLM (one call per distinct
question, then they are stored); from the second run on, the LLM is out of the
picture and what is measured is the query itself.

"cold" fires never-seen questions at once: each one must be planned by the LLM
inside the request. Set COLD_TAG=<anything> to make them new again.

Checks: no request over 2 s, and - when the service runs with AI enabled - no
answer served by the rule-based fallback. Only the standard library is used.
"""
import concurrent.futures
import datetime
import http.client
import json
import os
import random
import sys
import time

HOST = os.environ.get("HOST", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8080"))
TIMEOUT = 30

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


def get(path):
    conn = http.client.HTTPConnection(HOST, PORT, timeout=TIMEOUT)
    try:
        conn.request("GET", path)
        response = conn.getresponse()
        body = response.read()
        if response.status != 200:
            raise RuntimeError(f"GET {path} -> HTTP {response.status}: {body[:200]!r}")
        return json.loads(body)
    finally:
        conn.close()


def ask(conn, question, reference_date):
    payload = {"question": question}
    if reference_date:
        payload["referenceDate"] = reference_date
    start = time.perf_counter()
    conn.request("POST", "/api/query", json.dumps(payload),
                 {"Content-Type": "application/json"})
    response = conn.getresponse()
    body = response.read()
    wall = (time.perf_counter() - start) * 1000
    if response.status != 200:
        raise RuntimeError(f"HTTP {response.status}: {body[:200]!r}")
    answer = json.loads(body)
    return dict(question=question, wall=wall, server=answer["meta"]["tookMs"],
                cached=answer["meta"]["cached"], planner=answer["plan"]["planner"],
                results=len(answer["results"]))


def user(questions, dates, per_user, seed):
    """One professional: a keep-alive connection and a sequence of questions."""
    rnd = random.Random(seed)
    conn = http.client.HTTPConnection(HOST, PORT, timeout=TIMEOUT)
    rows = []
    try:
        for i in range(per_user):
            question = rnd.choice(questions) if dates else questions[seed % len(questions)]
            rows.append(ask(conn, question, rnd.choice(dates) if dates else None))
    finally:
        conn.close()
    return rows


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))]


def report(name, rows, check_llm):
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
    slow = [r for r in rows if r["wall"] > 2000]
    fallback = [r for r in rows if r["planner"] != "llm"] if check_llm else []
    if slow:
        print(f"  {len(slow)} richieste oltre 2 s")
    if fallback:
        print(f"  {len(fallback)} risposte servite dal parser a regole invece che dall'AI")
    return bool(slow or fallback)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "load"
    seed = int(os.environ.get("SEED", "1"))
    try:
        ai_enabled = get("/api/stats")["aiEnabled"]
    except Exception as error:                       # noqa: BLE001 - report and stop
        print(f"Servizio non raggiungibile su {HOST}:{PORT} ({error})")
        return 1

    if mode == "cold":
        tag = os.environ.get("COLD_TAG")
        questions = [f"{q} [{tag}]" for q in NEW] if tag else NEW
        dates, users, per_user = None, len(questions), 1
    else:
        questions, dates = POOL, DATES
        users = int(sys.argv[2]) if len(sys.argv) > 2 else 60
        per_user = int(sys.argv[3]) if len(sys.argv) > 3 else 5

    start = time.perf_counter()
    rows, failures = [], []
    with concurrent.futures.ThreadPoolExecutor(max_workers=users) as pool:
        futures = [pool.submit(user, questions, dates, per_user, seed + i) for i in range(users)]
        for future in concurrent.futures.as_completed(futures):
            try:
                rows.extend(future.result())
            except Exception as error:               # noqa: BLE001 - collected below
                failures.append(str(error))

    print(f"  {len(rows)} richieste da {users} utenti concorrenti in {time.perf_counter() - start:.1f}s"
          + (f" · AI {'attiva' if ai_enabled else 'disattivata'}"))
    for error in failures[:3]:
        print(f"  richiesta fallita: {error}")
    if not rows:
        print("FAIL: nessuna richiesta completata")
        return 1

    if mode == "cold":
        for r in sorted(rows, key=lambda r: r["wall"]):
            print(f"    {r['wall']:5.0f} ms  planner={r['planner']:9s} risultati={r['results']}  {r['question'][:60]}")

    failed = report(mode + str(users), rows, check_llm=ai_enabled) or bool(failures)
    print("FAIL: vincolo di latenza o fallback violato" if failed
          else "OK: tutte le richieste sotto i 2 s"
               + (", tutti i piani dall'AI" if ai_enabled else " (AI disattivata: piani a regole, come previsto)"))
    return 1 if failed else 0


sys.exit(main())
