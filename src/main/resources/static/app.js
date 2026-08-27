(function () {
  "use strict";

  var questionEl = document.getElementById("question");
  var runBtn = document.getElementById("run");
  var resultsEl = document.getElementById("results");
  var planBox = document.getElementById("plan-box");
  var planChips = document.getElementById("plan-chips");
  var statusEl = document.getElementById("status");
  var footerEl = document.getElementById("footer");

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
          item.appendChild(el("div", "evidence-date", src + " · " + fmtDate(ev.data)));
          item.appendChild(el("div", null, ev.excerpt));
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
      body: JSON.stringify({ question: question })
    })
      .then(function (res) {
        return res.json().then(function (body) {
          if (!res.ok) throw new Error(body.error || "Errore del server");
          return body;
        });
      })
      .then(function (data) {
        renderPlan(data.plan);
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

  loadStats();
  setInterval(loadStats, 15000);
})();
