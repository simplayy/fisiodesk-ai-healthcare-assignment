# Note di design

Approfondimento delle scelte dietro l'MVP; il quadro generale è nel [README](../README.md).

## Scomposizione del problema

La query target mescola quattro sotto-problemi con nature diverse:

| Sotto-problema | Natura | Dove si risolve |
|---|---|---|
| "dolore lombare" tra sinonimi e testo libero | comprensione del testo | indicizzazione (batch) |
| "hanno mostrato miglioramento" | comprensione del testo, direzionale | indicizzazione (batch) |
| "negli ultimi 3 mesi" | filtro temporale | query (real-time) |
| "hanno saltato l'ultimo appuntamento" | dato strutturato | query (real-time) |

I primi due sono costosi e ambigui: vanno risolti una volta per nota e memorizzati. Gli ultimi due sono filtri banali su dati strutturati: vanno risolti a ogni query. Tutta l'architettura discende da questa separazione.

## La collection `note_insights`

Un documento per nota clinica analizzata:

```javascript
{
  _id: "schede_valutazione:607f...9105",  // upsert idempotente
  note_id, source_collection,
  paziente_id, professionista_id, data,   // denormalizzati per query dirette
  text_hash: "sha256...",                 // rilevamento modifiche
  body_areas: ["LUMBAR"],
  improvement_score: 2,                   // -3..+3
  excerpt, evidence: ["+netto miglioramento", ...],
  analyzer: "llm" | "heuristic",
  analyzed_at
}
```

Indice composto `(paziente_id, data)`; la query tipica filtra per finestra temporale e area e raggruppa per paziente. Con volumi reali si aggiunge il filtro `professionista_id` allo stesso indice (i dati sono già denormalizzati per farlo).

## Semantica delle condizioni

- **Area anatomica**: una nota "conta" per la query se è taggata con l'area richiesta. Il miglioramento è calcolato **solo sulle note dell'area richiesta**: un paziente che migliora della cervicalgia non soddisfa una query sulla lombalgia, anche se "migliora" in generale.
- **Miglioramento nel periodo**: somma degli `improvement_score` delle note in finestra > 0, con almeno una nota positiva. Anna Ferrari (tutte note negative/stazionarie) resta fuori anche se ha tante note lombari.
- **Ultimo appuntamento saltato**: l'evento più recente con `data <= referenceDate` ha `stato == "no_show"`. Scelte esplicite: un ultimo evento `cancellato` non è un "salto" (il paziente ha avvisato); un ultimo evento `prenotato` con data passata è un dato sporco che non trattiamo come no-show.

## Analizzatore deterministico

Lessico fisioterapico italiano (sinonimi per area + marcatori di miglioramento/peggioramento) con tre livelli di matching, in ordine:

1. **Frasi positive esplicite** che contengono parole di negazione: "nessun dolore", "senza limitazioni".
2. **Negazioni esplicite di miglioramento**: "non migliora", "non risponde", "nessun miglioramento".
3. **Stem generici** ("miglior", "recuper", "peggior", "stazionar"…) con guardia di negazione: un termine positivo preceduto entro 12 caratteri da "non/nessun/senza" non conta.

Il punteggio è la somma dei match (clamp a ±3) e ogni match finisce nel campo `evidence`: il professionista vede *perché* un paziente è in lista. Sul dataset demo questo analizzatore da solo produce il risultato atteso esatto della query target.

## Integrazione LLM

- **Protocollo**: chat-completions OpenAI-compatibile → lo stesso client funziona con OpenAI, OpenRouter, Azure o Ollama on-premise (rilevante in sanità: i testi clinici possono non uscire dall'infrastruttura).
- **Dove**: (1) analisi nota in fase di indicizzazione; (2) traduzione domanda→piano, unica chiamata per query, prompt corto, su testo non clinico.
- **Budget**: il costo è O(note scritte), non O(query). 1.000 professionisti × 20 note/giorno ≈ 20k chiamate corte/giorno, batchabili e con modello economico; le query sono gratis lato AI.
- **Guasti**: timeout stretti, fallback deterministico per chiamata, upgrade automatico (una tantum) delle note analizzate col fallback quando l'LLM torna su. Nessun circuito manuale da riarmare.

## Percorso di crescita (non implementato, per la call)

- **Freshness**: sostituire il polling a 30s con MongoDB change streams sulle collections sorgente.
- **Vector search**: aggiungere un embedding per nota in `note_insights` e usare l'indice vettoriale di Atlas per query aperte ("pazienti con quadro simile a…"); la struttura non cambia.
- **Multi-tenancy**: filtro `professionista_id` in tutte le query + indici dedicati; già predisposto nei dati denormalizzati.
- **Valutazione qualità**: golden set di note etichettate dai clinici, confronto periodico analizzatore vs LLM vs umano prima di allargare i casi d'uso.
