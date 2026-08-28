(function () {
  "use strict";

  var questionEl = document.getElementById("question");
  var runBtn = document.getElementById("run");
  var resultsEl = document.getElementById("results");
  var summaryEl = document.getElementById("summary");
  var summaryCount = document.getElementById("summary-count");
  var summaryPlan = document.getElementById("summary-plan");
  var footerEl = document.getElementById("footer");
  var professionistaEl = document.getElementById("professionista");
  var clearCacheBtn = document.getElementById("clear-cache");
  var explainDialog = document.getElementById("explain");
  var explainOpen = document.getElementById("explain-open");
  var explainClose = document.getElementById("explain-close");
  var explainQuestion = document.getElementById("explain-question");
  var pipelineEl = document.getElementById("pipeline");
  var cluesEl = document.getElementById("clues");
  var contextEl = document.getElementById("context");
  var excludedBlock = document.getElementById("excluded-block");
  var excludedTitle = document.getElementById("excluded-title");
  var excludedEl = document.getElementById("excluded");
  var toastEl = document.getElementById("toast");
  var toastTimer = null;

  var AREA_LABELS = {
    LUMBAR: "zona lombare", CERVICAL: "zona cervicale", SHOULDER: "spalla",
    KNEE: "ginocchio", HIP: "anca", OTHER: "altra area"
  };
  var FIELD_LABELS = {
    requireImprovement: "con miglioramento",
    requireWorsening: "senza miglioramento / peggiorati",
    missedLastAppointment: "ultimo appuntamento saltato"
  };

  function fmtDate(iso) {
    if (!iso) return "-";
    return new Date(iso).toLocaleDateString("it-IT", { day: "2-digit", month: "2-digit", year: "numeric" });
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function toast(message) {
    toastEl.textContent = message;
    toastEl.classList.remove("hidden");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.add("hidden"); }, 4000);
  }

  /* ---------- summary line: "4 pazienti · zona lombare · con miglioramento · ..." ---------- */
  function planParts(plan) {
    var parts = (plan.bodyAreas || []).map(function (a) { return AREA_LABELS[a] || a; });
    if (plan.requireImprovement) parts.push("con miglioramento");
    if (plan.requireWorsening) parts.push("senza miglioramento");
    parts.push("ultimi " + plan.windowMonths + " mesi");
    if (plan.missedLastAppointment) parts.push("ultimo appuntamento saltato");
    return parts;
  }

  function renderSummary(data) {
    var n = data.results.length;
    summaryCount.textContent = n === 0 ? "Nessun paziente" : n === 1 ? "1 paziente" : n + " pazienti";
    summaryPlan.innerHTML = "";
    planParts(data.plan).forEach(function (p, i) {
      if (i > 0) summaryPlan.appendChild(el("i", null, "·"));
      summaryPlan.appendChild(document.createTextNode(p));
    });
    summaryEl.classList.remove("hidden");
  }

  /* ---------- results ---------- */
  function accentClass(ch) {
    var map = { a: "aàá", e: "eèé", i: "iìí", o: "oòó", u: "uùú" };
    return map[ch] ? "[" + map[ch] + "]" : ch.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  /* Accent-tolerant highlight of the decisive terms inside a note excerpt. */
  function highlighted(text, terms) {
    var node = el("div");
    if (!terms || !terms.length) { node.textContent = text; return node; }
    var pattern = terms.filter(Boolean).map(function (t) {
      return t.toLowerCase().split("").map(accentClass).join("");
    }).join("|");
    var re = new RegExp("(" + pattern + ")", "gi");
    var last = 0, m;
    while ((m = re.exec(text)) !== null) {
      if (m[0].length === 0) { re.lastIndex++; continue; }
      node.appendChild(document.createTextNode(text.slice(last, m.index)));
      node.appendChild(el("mark", null, m[0]));
      last = m.index + m[0].length;
    }
    node.appendChild(document.createTextNode(text.slice(last)));
    return node;
  }

  function trendBadge(score) {
    if (score > 0) return el("span", "badge trend", "In miglioramento (+" + score + ")");
    if (score < 0) return el("span", "badge trend neg", "Non in miglioramento (" + score + ")");
    return el("span", "badge trend neg", "Andamento non chiaro");
  }

  function renderResults(data) {
    resultsEl.innerHTML = "";
    if (data.results.length === 0) {
      resultsEl.appendChild(el("div", "empty", "Nessun paziente corrisponde ai criteri della domanda."));
      return;
    }
    data.results.forEach(function (p) {
      var card = el("div", "patient-card");
      var head = el("div", "patient-head");
      var left = el("div");
      left.appendChild(el("div", "patient-name", p.nome + " " + p.cognome));
      left.appendChild(el("div", "patient-info",
        (p.eta ? p.eta + " anni · " : "") + (p.telefono || "") + (p.email ? " · " + p.email : "")));
      head.appendChild(left);
      var badges = el("div", "badges");
      badges.appendChild(trendBadge(p.improvementScore));
      if (p.lastAppointment) {
        badges.appendChild(p.lastAppointment.stato === "no_show"
          ? el("span", "badge noshow", "Assente il " + fmtDate(p.lastAppointment.data))
          : el("span", "badge", "Ultimo appuntamento: " + p.lastAppointment.stato + " (" + fmtDate(p.lastAppointment.data) + ")"));
      }
      head.appendChild(badges);
      card.appendChild(head);

      if (p.evidence && p.evidence.length) {
        var box = el("div", "evidence");
        p.evidence.forEach(function (ev) {
          var item = el("div", "evidence-item" + (ev.improvementScore > 0 ? " pos" : ev.improvementScore < 0 ? " neg" : ""));
          var src = ev.source === "schede_valutazione" ? "Scheda di valutazione" : "Diario trattamenti";
          item.appendChild(el("div", "evidence-date", src + " · " + fmtDate(ev.data)));
          item.appendChild(highlighted(ev.excerpt, ev.matchedTerms));
          box.appendChild(item);
        });
        card.appendChild(box);
      }
      resultsEl.appendChild(card);
    });
  }

  /* ---------- explanation dialog ---------- */
  function renderExplain(data) {
    var ex = data.explain;
    explainOpen.classList.toggle("hidden", !ex);
    if (!ex) return;

    explainQuestion.textContent = "“" + data.question + "” — " +
      (data.meta.cached ? "risposta dalla cache, calcolata in " : "calcolata in ") + data.meta.tookMs + " ms";

    pipelineEl.innerHTML = "";
    ex.stages.forEach(function (st, i) {
      var isTotal = i === ex.stages.length - 1;
      var li = el("li", (isTotal ? "total" : "") + (i === 0 ? " origin-" + ex.planOrigin : ""));
      li.appendChild(el("span", "n", isTotal ? "" : String(i + 1)));
      li.appendChild(el("span", "name", st.name));
      li.appendChild(el("span", "ms", st.ms + " ms"));
      li.appendChild(el("span", "detail", st.detail));
      pipelineEl.appendChild(li);
    });

    cluesEl.innerHTML = "";
    if (!ex.clues.length) {
      cluesEl.appendChild(el("span", "none", "Nessun termine esplicito nella domanda: l'interpretazione è interamente dell'AI."));
    }
    ex.clues.forEach(function (c) {
      var chip = el("span", "clue" + (c.field === "requireWorsening" ? " neg" : ""));
      chip.appendChild(document.createTextNode("“" + c.term + "” → "));
      var value = c.field === "bodyAreas" ? (AREA_LABELS[c.value] || c.value)
        : c.field === "windowMonths" ? "ultimi " + c.value + " mesi"
        : FIELD_LABELS[c.field] || c.field;
      chip.appendChild(el("b", null, value));
      cluesEl.appendChild(chip);
    });

    contextEl.innerHTML = "";
    contextEl.appendChild(el("li", null, "Periodo: dal " + fmtDate(data.plan.from) + " al " + fmtDate(data.plan.to) +
      " (“oggi” = " + ex.referenceDateSource + ")"));
    contextEl.appendChild(el("li", null, "Ambito: " + (professionistaEl.value
      ? "solo i pazienti del professionista …" + professionistaEl.value.slice(-4) : "tutti i professionisti")));
    contextEl.appendChild(el("li", null, "Note lette: " + ex.notesInWindow + " nel periodo, " + ex.notesMatchingArea +
      " sull'area richiesta, " + ex.patientsInWindow + " pazienti coinvolti"));

    excludedEl.innerHTML = "";
    if (ex.excluded.length) {
      excludedTitle.textContent = ex.excluded.length === 1
        ? "1 paziente con note nel periodo escluso" : ex.excluded.length + " pazienti con note nel periodo esclusi";
      ex.excluded.forEach(function (x) {
        var li = el("li");
        li.appendChild(el("b", null, (x.nome || "") + " " + (x.cognome || x.pazienteId)));
        li.appendChild(el("span", null, " — " + x.reason));
        excludedEl.appendChild(li);
      });
      excludedBlock.classList.remove("hidden");
    } else {
      excludedBlock.classList.add("hidden");
    }
  }

  function showError(message) {
    resultsEl.innerHTML = "";
    summaryEl.classList.add("hidden");
    resultsEl.appendChild(el("div", "error-box", message));
  }

  /* ---------- actions ---------- */
  function runQuery() {
    var question = questionEl.value.trim();
    if (!question) return;
    runBtn.disabled = true;
    runBtn.textContent = "Cerco…";
    return fetch("/api/query", {
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
        renderSummary(data);
        renderExplain(data);
        renderResults(data);
      })
      .catch(function (err) { showError(err.message || "Servizio non raggiungibile"); })
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
          opt.textContent = "…" + p.id.slice(-4) + " (" + p.pazienti + " pazienti)";
          professionistaEl.appendChild(opt);
        });
      })
      .catch(function () { /* the selector simply stays on "all" */ });
  }

  function loadStats() {
    fetch("/api/stats")
      .then(function (res) { return res.json(); })
      .then(function (s) {
        footerEl.textContent = s.notesIndexed + " note cliniche indicizzate · " +
          (s.aiEnabled ? "analisi con AI (" + s.aiAnalysisModel + "), " + s.analyzedByLlm + " note" : "analisi a regole") +
          (s.analyzedByHeuristic ? ", " + s.analyzedByHeuristic + " in attesa dell'AI" : "");
      })
      .catch(function () { footerEl.textContent = "servizio non raggiungibile"; });
  }

  runBtn.addEventListener("click", runQuery);
  questionEl.addEventListener("keydown", function (e) {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); runQuery(); }
  });
  document.querySelectorAll(".chip[data-q]").forEach(function (chip) {
    chip.addEventListener("click", function () {
      questionEl.value = chip.getAttribute("data-q");
      runQuery();
    });
  });
  professionistaEl.addEventListener("change", function () {
    if (!summaryEl.classList.contains("hidden")) runQuery();
  });
  function openExplain() {
    explainDialog.showModal();
    explainDialog.focus();
  }
  explainOpen.addEventListener("click", openExplain);
  explainClose.addEventListener("click", function () { explainDialog.close(); });
  explainDialog.addEventListener("click", function (e) {
    if (e.target === explainDialog) explainDialog.close();
  });
  clearCacheBtn.addEventListener("click", function () {
    clearCacheBtn.disabled = true;
    fetch("/api/cache", { method: "DELETE" })
      .then(function (res) { return res.json(); })
      .then(function (r) {
        toast("Cache svuotata (" + r.responses + " risposte, " + r.plans + " piani): la prossima domanda passa di nuovo dall'AI.");
        if (!summaryEl.classList.contains("hidden")) runQuery();
      })
      .catch(function () { toast("Impossibile svuotare la cache"); })
      .finally(function () { clearCacheBtn.disabled = false; });
  });

  /* Deep link: /?q=<domanda>&professionista=<id>[&spiega=1] */
  function runFromUrl() {
    var params = new URLSearchParams(window.location.search);
    var q = params.get("q");
    if (!q) return;
    questionEl.value = q;
    if (params.get("professionista")) professionistaEl.value = params.get("professionista");
    var done = runQuery();
    if (done && params.get("spiega")) done.then(function () { if (!explainOpen.classList.contains("hidden")) openExplain(); });
  }

  loadProfessionisti().then(runFromUrl);
  loadStats();
  setInterval(loadStats, 15000);
})();
