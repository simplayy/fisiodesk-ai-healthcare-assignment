(function () {
  "use strict";

  var questionEl = document.getElementById("question");
  var runBtn = document.getElementById("run");
  var resultsEl = document.getElementById("results");
  var planBox = document.getElementById("plan-box");
  var planChips = document.getElementById("plan-chips");
  var statusEl = document.getElementById("status");
  var footerEl = document.getElementById("footer");
  var professionistaEl = document.getElementById("professionista");
  var explainBox = document.getElementById("explain-box");
  var pipelineEl = document.getElementById("pipeline");
  var cluesEl = document.getElementById("clues");
  var contextEl = document.getElementById("context");
  var excludedBox = document.getElementById("excluded-box");
  var excludedEl = document.getElementById("excluded");
  var excludedSummary = document.getElementById("excluded-summary");
  var explainTotal = document.getElementById("explain-total");
  var clearCacheBtn = document.getElementById("clear-cache");
  var toastEl = document.getElementById("toast");
  var toastTimer = null;

  var FIELD_LABELS = {
    bodyAreas: "area",
    requireImprovement: "con miglioramento",
    requireWorsening: "senza miglioramento / peggiorati",
    missedLastAppointment: "ultimo appuntamento saltato",
    windowMonths: "finestra (mesi)"
  };

  var AREA_LABELS = {
    LUMBAR: "zona lombare",
    CERVICAL: "zona cervicale",
    SHOULDER: "spalla",
    KNEE: "ginocchio",
    HIP: "anca",
    OTHER: "altra area"
  };

  function fmtDate(iso) {
    if (!iso) return "-";
    var d = new Date(iso);
    return d.toLocaleDateString("it-IT", { day: "2-digit", month: "2-digit", year: "numeric" });
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function renderPlan(plan) {
    planChips.innerHTML = "";
    var chips = [];
    (plan.bodyAreas || []).forEach(function (area) {
      chips.push("Area: " + (AREA_LABELS[area] || area));
    });
    if (plan.requireImprovement) chips.push("Con miglioramento clinico");
    if (plan.requireWorsening) chips.push("Senza miglioramento / peggiorati");
    chips.push("Finestra: ultimi " + plan.windowMonths + " mesi");
    if (plan.missedLastAppointment) chips.push("Ultimo appuntamento saltato");
    chips.push("Dal " + fmtDate(plan.from) + " al " + fmtDate(plan.to));
    chips.push("Parser: " + (plan.planner === "llm" ? "AI" : "regole"));
    if (professionistaEl.value) {
      chips.push("Solo pazienti del professionista " + professionistaEl.value.slice(-4));
    }
    chips.forEach(function (label) {
      planChips.appendChild(el("span", "plan-chip", label));
    });
    planBox.classList.remove("hidden");
  }

  function trendBadge(score) {
    if (score > 0) return el("span", "badge trend", "In miglioramento (+" + score + ")");
    if (score < 0) return el("span", "badge trend neg", "Non in miglioramento (" + score + ")");
    return el("span", "badge trend neg", "Andamento non chiaro");
  }

  function toast(message) {
    toastEl.textContent = message;
    toastEl.classList.remove("hidden");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.add("hidden"); }, 4000);
  }

  /* Accent-tolerant highlight of the lexicon terms inside a note excerpt. */
  function accentClass(ch) {
    var map = { a: "aàá", e: "eèé", i: "iìí", o: "oòó", u: "uùú" };
    return map[ch] ? "[" + map[ch] + "]" : ch.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }
  function highlighted(text, terms) {
    var node = document.createElement("div");
    if (!terms || !terms.length) { node.textContent = text; return node; }
    var pattern = terms.filter(Boolean).map(function (t) {
      return t.toLowerCase().split("").map(accentClass).join("");
    }).join("|");
    var re = new RegExp("(" + pattern + ")", "gi");
    var last = 0, m;
    while ((m = re.exec(text)) !== null) {
      node.appendChild(document.createTextNode(text.slice(last, m.index)));
      var mark = document.createElement("mark");
      mark.textContent = m[0];
      node.appendChild(mark);
      last = m.index + m[0].length;
      if (m[0].length === 0) re.lastIndex++;
    }
    node.appendChild(document.createTextNode(text.slice(last)));
    return node;
  }

  function renderExplain(data) {
    var ex = data.explain;
    if (!ex) { explainBox.classList.add("hidden"); return; }

    explainTotal.textContent = data.meta.cached
      ? "risposta servita dalla cache (calcolata in " + data.meta.tookMs + " ms)"
      : "calcolata ora in " + data.meta.tookMs + " ms";

    pipelineEl.innerHTML = "";
    ex.stages.forEach(function (st, i) {
      var isTotal = i === ex.stages.length - 1;
      var li = el("li", "stage" + (isTotal ? " total" : "") + (i === 0 ? " origin-" + ex.planOrigin : ""));
      li.appendChild(el("div", "stage-name", (isTotal ? "" : (i + 1) + ". ") + st.name));
      var ms = el("div", "stage-ms", String(st.ms));
      ms.appendChild(el("small", null, " ms"));
      li.appendChild(ms);
      li.appendChild(el("div", "stage-detail", st.detail));
      pipelineEl.appendChild(li);
    });

    cluesEl.innerHTML = "";
    if (!ex.clues.length) {
      cluesEl.appendChild(el("span", "results-meta", "nessun termine esplicito: l'interpretazione è interamente dell'AI"));
    }
    ex.clues.forEach(function (c) {
      var chip = el("span", "clue" + (c.field === "requireWorsening" ? " neg" : ""));
      chip.appendChild(document.createTextNode("\u201C" + c.term + "\u201D \u2192 "));
      var value = c.field === "bodyAreas" ? (AREA_LABELS[c.value] || c.value)
        : c.field === "windowMonths" ? "ultimi " + c.value + " mesi"
        : FIELD_LABELS[c.field] || c.field;
      chip.appendChild(el("b", null, value));
      cluesEl.appendChild(chip);
    });

    contextEl.innerHTML = "";
    contextEl.appendChild(el("li", null, "\u201COggi\u201D = " + fmtDate(data.meta.referenceDate) + " (" + ex.referenceDateSource + ")"));
    contextEl.appendChild(el("li", null, "Ambito: " + (professionistaEl.value
      ? "solo i pazienti del professionista \u2026" + professionistaEl.value.slice(-4) : "tutti i professionisti")));
    contextEl.appendChild(el("li", null, "Note lette: " + ex.notesInWindow + " nella finestra, " + ex.notesMatchingArea +
      " sull'area richiesta, " + ex.patientsInWindow + " pazienti coinvolti"));
    contextEl.appendChild(el("li", null, "Piano: " + ex.planOriginLabel));

    excludedEl.innerHTML = "";
    if (ex.excluded.length) {
      excludedSummary.textContent = ex.excluded.length + (ex.excluded.length === 1
        ? " paziente con note nel periodo escluso: perché" : " pazienti con note nel periodo esclusi: perché");
      ex.excluded.forEach(function (x) {
        var li = el("li");
        li.appendChild(el("b", null, (x.nome || "") + " " + (x.cognome || x.pazienteId)));
        li.appendChild(el("span", null, " \u2014 " + x.reason));
        excludedEl.appendChild(li);
      });
      excludedBox.classList.remove("hidden");
    } else {
      excludedBox.classList.add("hidden");
    }
    explainBox.classList.remove("hidden");
  }

  function renderResults(data) {
    resultsEl.innerHTML = "";

    var head = el("div", "results-head");
    head.appendChild(el("h2", null, data.results.length === 1
      ? "1 paziente trovato" : data.results.length + " pazienti trovati"));
    head.appendChild(el("span", "results-meta",
      data.meta.tookMs + " ms" + (data.meta.cached ? " · dalla cache" : "")));
    resultsEl.appendChild(head);

    if (data.results.length === 0) {
      resultsEl.appendChild(el("div", "empty",
        "Nessun paziente corrisponde ai criteri della domanda."));
      return;
    }

    data.results.forEach(function (p) {
      var card = el("div", "patient-card");

      var headRow = el("div", "patient-head");
      var left = el("div");
      left.appendChild(el("div", "patient-name", p.nome + " " + p.cognome));
      left.appendChild(el("div", "patient-info",
        (p.eta ? p.eta + " anni · " : "") + (p.telefono || "") +
        (p.email ? " · " + p.email : "")));
      headRow.appendChild(left);

      var badges = el("div", "badges");
      badges.appendChild(trendBadge(p.improvementScore));
      if (p.lastAppointment) {
        if (p.lastAppointment.stato === "no_show") {
          badges.appendChild(el("span", "badge noshow",
            "Assente il " + fmtDate(p.lastAppointment.data)));
        } else {
          badges.appendChild(el("span", "badge",
            "Ultimo appuntamento: " + p.lastAppointment.stato + " (" + fmtDate(p.lastAppointment.data) + ")"));
        }
      }
      headRow.appendChild(badges);
      card.appendChild(headRow);

      if (p.evidence && p.evidence.length) {
        var evBox = el("div", "evidence");
        evBox.appendChild(el("div", "evidence-title",
          "Evidenze cliniche nella finestra temporale"));
        p.evidence.forEach(function (ev) {
          var cls = ev.improvementScore > 0 ? "evidence-item pos"
            : ev.improvementScore < 0 ? "evidence-item neg" : "evidence-item";
          var item = el("div", cls);
          var src = ev.source === "schede_valutazione" ? "Scheda di valutazione" : "Diario trattamenti";
          item.appendChild(el("div", "evidence-date", src + " · " + fmtDate(ev.data) +
            (ev.improvementScore ? " · punteggio " + (ev.improvementScore > 0 ? "+" : "") + ev.improvementScore : "") +
            (ev.analyzer === "llm" ? " · analisi AI" : " · analisi lessicale")));
          item.appendChild(highlighted(ev.excerpt, ev.matchedTerms));
          evBox.appendChild(item);
        });
        card.appendChild(evBox);
      }
      resultsEl.appendChild(card);
    });
  }

  function showError(message) {
    resultsEl.innerHTML = "";
    planBox.classList.add("hidden");
    explainBox.classList.add("hidden");
    resultsEl.appendChild(el("div", "error-box", message));
  }

  function runQuery() {
    var question = questionEl.value.trim();
    if (!question) return;
    runBtn.disabled = true;
    runBtn.textContent = "Cerco…";

    fetch("/api/query", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question: question, professionistaId: professionistaEl.value || null })
    })
      .then(function (res) {
        return res.json().then(function (body) {
          if (!res.ok) throw new Error(body.error || "Errore del server");
          return body;
        });
      })
      .then(function (data) {
        renderPlan(data.plan);
        renderExplain(data);
        renderResults(data);
      })
      .catch(function (err) {
        showError(err.message || "Servizio non raggiungibile");
      })
      .finally(function () {
        runBtn.disabled = false;
        runBtn.textContent = "Cerca";
      });
  }

  function loadProfessionisti() {
    return fetch("/api/professionisti")
      .then(function (res) { return res.json(); })
      .then(function (list) {
        list.forEach(function (p) {
          var opt = document.createElement("option");
          opt.value = p.id;
          opt.textContent = "Professionista …" + p.id.slice(-4) + " (" + p.pazienti + " pazienti)";
          professionistaEl.appendChild(opt);
        });
      })
      .catch(function () { /* the selector simply stays on "all" */ });
  }

  function loadStats() {
    fetch("/api/stats")
      .then(function (res) { return res.json(); })
      .then(function (s) {
        statusEl.textContent = s.notesIndexed + " note cliniche indicizzate";
        footerEl.textContent = "Analisi note: " +
          (s.aiEnabled ? "LLM (" + s.aiModel + ") con fallback a regole" : "motore a regole") +
          " · " + s.analyzedByLlm + " note analizzate da AI, " +
          s.analyzedByHeuristic + " da lessico clinico.";
      })
      .catch(function () {
        statusEl.textContent = "servizio non raggiungibile";
      });
  }

  runBtn.addEventListener("click", runQuery);
  questionEl.addEventListener("keydown", function (e) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      runQuery();
    }
  });
  document.querySelectorAll(".chip[data-q]").forEach(function (chip) {
    chip.addEventListener("click", function () {
      questionEl.value = chip.getAttribute("data-q");
      runQuery();
    });
  });

  clearCacheBtn.addEventListener("click", function () {
    clearCacheBtn.disabled = true;
    fetch("/api/cache", { method: "DELETE" })
      .then(function (res) { return res.json(); })
      .then(function (r) {
        toast("Cache svuotata: " + r.responses + " risposte e " + r.plans + " piani. La prossima domanda passa di nuovo dall'AI.");
        if (questionEl.value.trim() && !explainBox.classList.contains("hidden")) runQuery();
      })
      .catch(function () { toast("Impossibile svuotare la cache"); })
      .finally(function () { clearCacheBtn.disabled = false; });
  });

  professionistaEl.addEventListener("change", function () {
    if (questionEl.value.trim()) runQuery();
  });

  /* Deep link: /?q=<domanda>&professionista=<id> opens the page on that question. */
  function runFromUrl() {
    var params = new URLSearchParams(window.location.search);
    var q = params.get("q");
    if (!q) return;
    questionEl.value = q;
    if (params.get("professionista")) professionistaEl.value = params.get("professionista");
    runQuery();
  }

  loadProfessionisti().then(runFromUrl);
  loadStats();
  setInterval(loadStats, 15000);
})();
