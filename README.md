# FisioDesk – Query intelligenti sui dati clinici

Sistema di query in linguaggio naturale sui dati clinici di FisioDesk. Un professionista scrive una domanda come:

> *"Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento"*

e ottiene in meno di 100 ms la lista dei pazienti corrispondenti, con le evidenze cliniche (estratti delle schede e del diario trattamenti) che giustificano ogni risultato.

**Stack**: Java 17 · Spring Boot 3.5 · Spring AI 1.1 · Gradle · MongoDB — allineato allo stack esistente di FisioDesk/Dottai, così i componenti descritti qui si integrano nel backend attuale senza introdurre un nuovo runtime (Spring Boot 3.5 è la linea che l'azienda usa oggi; Spring AI 1.1 è la versione che la supporta, la 2.0 richiede Boot 4). Frontend demo in HTML/JS statico servito dalla stessa applicazione.

---

## Avvio rapido

Prerequisiti: Docker + Docker Compose.

```bash
git clone <repository-url>
cd fisiodesk-ai-healthcare-assignment
docker compose up -d --build
```

Servizi disponibili:

| Servizio | URL |
|---|---|
| Web UI + API | http://localhost:8080 |
| API query | `POST http://localhost:8080/api/query` (opzionale `professionistaId` per lo scoping) |
| Stato indicizzazione | `GET http://localhost:8080/api/stats` |
| MongoDB | `localhost:27017` |
| Mongo Express | http://localhost:8081 |

Al primo avvio l'applicazione indicizza le note cliniche (qualche secondo in modalità deterministica); lo stato è visibile su `/api/stats` e nell'header della UI.

### Demo della query target

Dalla UI (http://localhost:8080) la query target è già precompilata: basta premere **Cerca**. Da terminale:

```bash
curl -s -X POST http://localhost:8080/api/query \
  -H 'Content-Type: application/json' \
  -d '{"question":"Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'\''ultimo appuntamento"}'
```

Risultato atteso sul dataset demo: **Roberto Romano, Marco Colombo, Mario Rossi, Laura Bianchi** (in ordine di forza del miglioramento). Anna Ferrari è esclusa (lombalgia ma nessun miglioramento), Giuseppe Verdi è escluso (migliora ma sul rachide cervicale, e non ha saltato l'ultimo appuntamento), Francesca Ricci è esclusa (nessun dato recente).

Altre domande supportate, per mostrare che non è un sistema a query fissa:

```
Pazienti con lombalgia che non mostrano miglioramento negli ultimi 3 mesi   -> Anna Ferrari
Pazienti con cervicalgia in miglioramento negli ultimi 2 mesi               -> Giuseppe Verdi
Chi ha saltato l'ultimo appuntamento?                                       -> tutti i no-show recenti
```

Nota sulle date: il dataset copre marzo–dicembre 2024, quindi come "oggi" viene usata la data dell'ultimo evento in calendario (31/12/2024). È sovrascrivibile con la variabile `REFERENCE_DATE` o col campo `referenceDate` della richiesta.

### Modalità AI con LLM locale (opzionale)

Di default il sistema usa l'analizzatore deterministico (nessuna dipendenza esterna, risultati riproducibili). Per usare un LLM vero ci sono due strade:

**LLM locale via Ollama** (nessuna API key, tutto on-premise):

```bash
docker compose -f docker-compose.yml -f docker-compose.ai.yml up -d --build
```

Il primo avvio scarica il modello (`qwen2.5:1.5b`, ~1 GB). L'indexer ri-analizza le note in background con l'LLM mantenendo il servizio disponibile; anche l'interpretazione della domanda passa all'LLM.

Nota sulla latenza: su CPU il planning LLM di una domanda *nuova* costa qualche secondo; i piani sono cachati 30 minuti, quindi le domande ricorrenti restano sotto i 100 ms. Il vincolo <2s per 50+ utenti concorrenti è garantito dal design (nessuna analisi di testo a runtime, solo letture indicizzate): l'unica parte variabile è la singola chiamata di planning, che in produzione userebbe un modello hosted (~300-500 ms) o il parser a regole.

**LLM hosted via Google Gemini** (endpoint OpenAI-compatibile, nessun modello da scaricare):

```bash
cp .env.example .env    # e inserire GEMINI_API_KEY
docker compose -f docker-compose.yml -f docker-compose.gemini.yml up -d --build
```

Modello di default `gemini-3.5-flash-lite`: pianifica una domanda nuova in ~0,8 s, quindi resta dentro il budget del planner (1,5 s) anche a freddo. Il codice è lo stesso della modalità Ollama, cambia solo la configurazione.

Verifica del vincolo di latenza (server a 4 core condiviso con MongoDB e con il generatore di carico, `scripts/load-test.py`, cache delle risposte quasi sempre fredda, tutti i piani dall'LLM, nessun fallback a regole):

| Scenario | Richieste | p50 | p95 | max |
|---|---|---|---|---|
| 60 utenti concorrenti | 300 | 158 ms | 391 ms | 599 ms |
| 100 utenti concorrenti | 500 | 199 ms | 865 ms | 1296 ms |
| 60 utenti, 1200 richieste sostenute | 1200 | 127 ms | 359 ms | 867 ms |
| 10 domande *mai viste* nello stesso istante (planning LLM dentro la richiesta) | 10 | 1150 ms | 1350 ms | 1350 ms |

Le quote Gemini sono per modello (piano gratuito: 15 richieste/min ciascuno). L'overlay quindi analizza le note con un secondo modello (`gemini-3.1-flash-lite`) ritmato a 12 chiamate/min: l'indicizzazione in background non consuma mai la quota del planner interattivo. Con il piano gratuito il planner LLM copre fino a 15 domande *nuove e distinte* al minuto (le domande già viste non costano chiamate); oltre serve il piano a pagamento del provider, non modifiche al codice.

**Altri provider** (qualunque endpoint compatibile OpenAI, via Spring AI):

```bash
AI_ENABLED=true AI_API_KEY=sk-... AI_MODEL=gpt-4o-mini docker compose up -d
# altri endpoint: AI_BASE_URL=https://openrouter.ai/api  (senza /v1)
```

In tutti i casi l'output del modello è vincolato a uno schema JSON (structured output), le chiamate hanno timeout e retry configurati (`spring.ai.retry.*`, `spring.http.client.*`) e, se l'LLM non risponde, il sistema degrada automaticamente sull'analizzatore deterministico: il servizio non si ferma mai per un problema del provider AI.

---

## Architettura

```mermaid
flowchart LR
    subgraph client [Professionista]
        UI[Web UI / API client]
    end

    subgraph app [Spring Boot - fisiodesk-smart-query]
        QP[Query Planning<br/>LLM + guardrail lessicale<br/>budget 1.5s, fallback a regole]
        EX[Query Executor<br/>solo letture indicizzate]
        CACHE[(Cache risposte<br/>60s in-memory)]
        IDX[Insight Indexer<br/>batch incrementale]
        AN[Text Analysis<br/>LLM con fallback a lessico]
    end

    subgraph mongo [MongoDB]
        SV[(schede_valutazione)]
        DT[(diario_trattamenti)]
        EC[(eventi_calendario)]
        PZ[(pazienti)]
        NI[(note_insights<br/>pre-calcolata)]
        QPL[(query_plans<br/>piani memorizzati)]
    end

    LLM[LLM via Spring AI<br/>Ollama locale, Gemini o altro endpoint OpenAI-compatibile]

    UI -->|POST /api/query| QP --> EX
    QP <--> QPL
    EX --> CACHE
    EX --> NI
    EX --> EC
    EX --> PZ
    IDX --> SV
    IDX --> DT
    IDX --> AN
    AN -.->|solo in fase di indicizzazione| LLM
    QP -.->|solo per domande mai viste| LLM
    AN --> NI
```

### L'idea centrale: indicizzare, non ri-analizzare

Il vincolo <2s con 50+ professionisti concorrenti e il budget AI limitato portano alla stessa conclusione: **l'AI non può stare nel percorso di risposta della query sui testi**. La pipeline è quindi divisa in due:

1. **Indicizzazione (batch, incrementale)** — un job periodico confronta `schede_valutazione` e `diario_trattamenti` con la collection `note_insights`: ogni nota nuova o modificata (hash SHA-256 del testo) viene analizzata **una sola volta** e il risultato persistito: aree anatomiche citate (gestendo i sinonimi: "lombalgia", "mal di schiena", "low back pain", "colpo della strega" → `LUMBAR`), segnale di miglioramento (da −3 a +3, con gestione delle negazioni: "non risponde bene alla terapia" non è un miglioramento), evidenze testuali. Il costo AI è proporzionale alle note scritte, non alle query eseguite.

2. **Query (real-time)** — la domanda viene tradotta in un piano strutturato (aree, finestra temporale, miglioramento richiesto, filtro no-show). Il piano lo produce l'LLM (output vincolato a JSON schema) fuso con i marcatori espliciti del parser a regole — il modello capisce le parafrasi, il lessico garantisce che i termini espliciti non vadano persi — oppure, senza LLM, il solo parser a regole. Un piano non dipende dai dati, quindi viene calcolato una volta per domanda e memorizzato in `query_plans`. Il piano viene poi eseguito con normali query MongoDB indicizzate su `note_insights` + `eventi_calendario` + `pazienti`: nessuna analisi di testo a runtime. Una cache in-memory (60s) assorbe le domande ripetute.

"Ultimo appuntamento saltato" è definito così: l'evento più recente del paziente con data ≤ data di riferimento ha stato `no_show`. Un ultimo evento `cancellato` o `prenotato` non conta come saltato.

### Cosa succede se l'AI non funziona

Ogni punto che usa l'LLM (interpretazione domanda, analisi note) ha un fallback deterministico basato su un lessico fisioterapico italiano con gestione delle negazioni. Le chiamate hanno timeout e un retry (Spring AI); esaurito quello, il fallback scatta per timeout, errori del provider o risposte non valide. Quando l'LLM torna disponibile, l'indexer ri-analizza (una volta sola) le note classificate col fallback.

---

## Trade-off principali

- **Pre-calcolo batch invece di AI a runtime** — la query resta sotto i 100 ms e il costo AI è per-nota invece che per-query; in cambio una nota nuova diventa cercabile al ciclo di indicizzazione successivo (default 30s), non istantaneamente. Per un gestionale clinico è un compromesso accettabile; in produzione il trigger diventerebbe un evento sul salvataggio della nota (change stream / coda).
- **Budget di latenza sul planner invece di attendere sempre l'LLM** — la prima richiesta di una domanda mai vista può essere interpretata dal parser a regole (più grezzo) se l'LLM è lento; in cambio il tempo di risposta è garantito e il piano LLM viene riusato da tutte le richieste successive. Il piano usato è sempre esposto nella risposta, quindi l'utente vede come è stata interpretata la domanda.
- **LLM piccolo e locale + guardrail lessicale, invece di un modello grande** — un modello da 1.5B su CPU gira su qualunque macchina e tiene i testi clinici in casa, ma sbaglia ~1 domanda su 3 su marcatori espliciti ("guariti", negazioni); la fusione con il parser a regole recupera esattamente quei casi. Con un modello hosted (o Qwen3 8B su GPU) il guardrail resta innocuo e il modello fa più lavoro da solo.
- **Classificazione a punteggio su lessico + LLM opzionale invece di embeddings/vector search** — per la query target servono segnali *direzionali* (migliora / non migliora) e di *area anatomica*, che un classificatore per nota cattura in modo spiegabile (ogni risultato mostra i termini che l'hanno motivato — importante per decisioni cliniche). La vector search di MongoDB Atlas diventa utile al crescere del corpus e delle query aperte ("pazienti simili a…"), e si innesta nella stessa pipeline: stessi documenti `note_insights`, in più un embedding per nota.
- **Aggregazione in application-layer invece di pipeline Mongo monolitica** — con collections separate la correlazione insights↔calendario↔anagrafica è fatta nel service con query indicizzate mirate: più leggibile e testabile di un unico `$lookup` a tre stadi, a costo di qualche round-trip in più (irrilevante a questi volumi; con dataset molto più grandi il candidato naturale è una `$lookup` singola o una vista materializzata per professionista).

## Struttura del progetto

```
├── docker-compose.yml          # MongoDB + seed dati + app + Mongo Express
├── docker-compose.ai.yml       # overlay opzionale: LLM locale via Ollama
├── docker-compose.gemini.yml   # overlay opzionale: LLM hosted via Gemini (chiave in .env)
├── Dockerfile                  # build multi-stage (Gradle -> JRE)
├── scripts/                    # smoke-test.sh (query target), load-test.py (latenza sotto carico)
├── data/                       # dataset demo (marzo-dicembre 2024)
├── src/main/java/com/fisiodesk/smartquery/
│   ├── analysis/               # lessico clinico, analizzatori, indexer batch
│   ├── nlq/                    # domanda -> piano: planner LLM/regole, guardrail, budget, plan store
│   ├── query/                  # esecuzione piano, cache, DTO
│   ├── web/                    # REST controller + error handling
│   └── domain/ repository/     # mapping collections MongoDB
├── src/main/resources/static/  # web UI demo
└── src/test/java/              # unit test (analizzatore, parser, esecuzione)
```

## Configurazione

| Variabile | Default | Significato |
|---|---|---|
| `MONGO_URI` | `mongodb://localhost:27017/fisiodesk` | connessione MongoDB (nel compose punta al container) |
| `REFERENCE_DATE` | *(vuoto → ultimo evento in calendario)* | "oggi" per le finestre relative, `yyyy-MM-dd` |
| `AI_ENABLED` | `false` | `true` per usare l'LLM (planner + analisi note) |
| `AI_BASE_URL` | `https://api.openai.com` | endpoint OpenAI-compatibile, senza `/v1` (es. `http://ollama:11434`) |
| `AI_COMPLETIONS_PATH` | `/v1/chat/completions` | path delle chat completions, per provider con prefisso diverso (Gemini: `/chat/completions` su `.../v1beta/openai`) |
| `AI_API_KEY` | `not-needed` | chiave del provider (ignorata da Ollama) |
| `AI_MODEL` | `gpt-4o-mini` | modello (es. `qwen2.5:1.5b` con Ollama, `gemini-3.5-flash-lite` con Gemini) |
| `AI_ANALYSIS_MODEL` | *(vuoto → `AI_MODEL`)* | modello separato per l'analisi delle note, per non condividere la quota con il planner |
| `AI_INDEXER_RPM` | `0` (nessun ritmo) | massimo di chiamate LLM al minuto dell'indexer, da tenere sotto la quota del provider |
| `GEMINI_API_KEY` | – | chiave Gemini, letta da `.env` dall'overlay `docker-compose.gemini.yml` |
| `AI_TIMEOUT_MS` | `20000` | timeout di lettura di una chiamata LLM |
| `AI_PLAN_TIMEOUT_MS` | `1500` (`1800` nell'overlay Gemini) | budget di attesa del planner LLM in una richiesta utente: obiettivo di risposta meno il tempo di esecuzione |

## Troubleshooting

- **Domande nuove servite dal piano a regole (`planner: heuristic`)**: il planner LLM ha superato il budget (`AI_PLAN_TIMEOUT_MS`) o il provider ha risposto 429; il log riporta la durata di ogni chiamata (`LLM planned '...' in N ms`) e gli errori del provider. Sul piano gratuito Gemini il limite è 15 domande nuove/min per modello.

- **`fisiodesk-mongodb` esce con `ReplicaSetNoPrimary` dopo un ricreate del container**: è un limite noto dell'immagine Atlas Local quando riusa un volume con una configurazione di replica set precedente. Reset: `docker compose down -v && docker compose up -d --build` (i dati demo vengono re-importati e ri-indicizzati automaticamente).
- **Stato dell'indicizzazione**: `curl localhost:8080/api/stats`; log applicativi: `docker logs fisiodesk-smart-query`.
- **Modalità AI locale lenta al primo avvio**: il download del modello è visibile con `docker logs fisiodesk-ollama-pull`; la prima analisi delle note su CPU richiede qualche minuto (`/api/stats` mostra `analyzedByLlm` crescere), nel frattempo le query rispondono con le analisi deterministiche.

## Sviluppo locale

```bash
# solo il database
docker compose up -d mongodb mongodb-seed

# applicazione in locale
./gradlew bootRun

# test
./gradlew test
```

## API

`POST /api/query`

```json
{ "question": "…", "referenceDate": "2024-12-31", "professionistaId": "507f1f77bcf86cd799439021" }
```

Risposta: piano interpretato (trasparenza su come è stata capita la domanda), risultati ordinati per forza del miglioramento con evidenze per nota, metadati (latenza, cache, data di riferimento) e un blocco `explain`. `referenceDate` e `professionistaId` sono opzionali.

**Spiegazione del risultato (`explain`).** Ogni risposta racconta come è stata prodotta, a partire da ciò che la pipeline ha fatto davvero e senza chiamate AI aggiuntive: origine del piano (AI in questa richiesta, piano memorizzato, parser a regole per timeout/AI assente), parole della domanda riconosciute dal lessico e come sono state lette (`"dolore lombare" → LUMBAR`), tempi per tappa (interpretazione, ricerca note, valutazione pazienti, totale) con i conteggi (note nella finestra, note sull'area, pazienti coinvolti), da dove viene la data di riferimento, e **ogni paziente con note nel periodo che è stato escluso, con il motivo** ("nessun miglioramento nell'area richiesta (punteggio -4 su 2 note)", "ultimo appuntamento completato il 20/12/2024, non saltato", "nessuna nota sull'area richiesta"). La UI lo apre in un popup dal link "Come è nato il risultato" accanto al conteggio, ed evidenzia nelle note i termini che hanno pesato. Link diretto a una domanda: `/?q=<domanda>&professionista=<id>&spiega=1`.

`DELETE /api/cache` — svuota le risposte in cache (60 s) e i piani memorizzati: la domanda successiva ripassa dall'AI (link "Svuota cache" nella barra in alto della UI, accanto al selettore del professionista). Le analisi delle note non sono una cache e non vengono toccate.

**Scoping per professionista.** Con `professionistaId` la ricerca considera solo le note scritte da quel professionista: ognuno vede i propri pazienti (sul dataset: `…9021` → Rossi, Bianchi, Romano; `…9023` → Colombo; `…9022` → nessuno per la query target). Senza, la vista è amministrativa. In produzione l'id arriva dal token dell'utente autenticato, non dal body: qui è esplicito per la demo e la UI offre un selettore.

`GET /api/professionisti` — professionisti presenti nei dati (per il selettore della UI). `GET /api/stats` — note indicizzate e analizzatore usato. `GET /actuator/health` — healthcheck.

## Riferimenti

- [Spring AI 1.1 – OpenAI chat e structured outputs](https://docs.spring.io/spring-ai/reference/1.1/api/chat/openai-chat.html) · [ChatClient](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)
- [Spring AI 2.0 / Spring Boot 4 – perché qui resta la linea 1.1](https://www.herodevs.com/blog-posts/spring-ai-2-0-is-coming-soon-your-boot-4-0-migration-does-not-have-to-start-tomorrow)
- [Ollama – structured outputs](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)
- [MongoDB Atlas Local](https://www.mongodb.com/docs/atlas/cli/current/atlas-cli-deploy-docker/)
