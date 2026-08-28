# Risposte alla consegna, punto per punto

Ogni richiesta della consegna è citata, sotto c'è la risposta. Setup e demo nel [README](../README.md), dettaglio tecnico in [DESIGN.md](DESIGN.md).

## La query target

### Perché è complessa

> Richiede **AI** per interpretare "miglioramento" dai testi liberi delle valutazioni

Ogni nota diventa un punteggio da -3 a +3, dato dall'AI con il dizionario di termini come controllo. La difficoltà non è trovare la parola "miglioramento": è capire che "non risponde alla terapia" è l'opposto e che "nessun dolore residuo" è positivo nonostante il "nessun".

> Combina **multiple collections** (schede_valutazione, eventi_calendario, pazienti)

Note da `schede_valutazione` e `diario_trattamenti` (entrambe) in un'unica collection `note_insights`, con paziente, professionista e data copiati dentro. La ricerca legge quella, più `eventi_calendario` e `pazienti`: tre letture su indici, nessuna join.

> Filtra per **timeframe** specifico ("ultimi 3 mesi")

Finestra calcolata all'indietro dalla data di riferimento, estremi inclusi. Il numero di mesi viene dalla domanda ("ultimi 2 mesi", "nell'ultimo mese"), 3 se non è indicato.

> Correla **compliance del paziente** con **progressi clinici**

Il progresso sta in un testo, la compliance in uno stato del calendario. Il sistema li unisce per paziente e li mostra affiancati: punteggio con le frasi che lo provano, e data dell'appuntamento saltato. Serve a trovare il paziente che sta migliorando ma sta sparendo.

### Vincoli operativi

> ⚡ Performance: <2 secondi con 50+ professionisti concorrenti

Rispettato. Misurato con `scripts/load-test.py` sulla demo con Gemini attivo:

| Scenario | Richieste | mediana | 95° perc. | massimo |
|---|---|---|---|---|
| 60 professionisti insieme, domande già note | 300 | 56 ms | 141 ms | 156 ms |
| 100 professionisti insieme, domande già note | 500 | 95 ms | 245 ms | 367 ms |
| 60 professionisti insieme, con domande mai viste | 300 | 121 ms | 1387 ms | 1627 ms |
| 10 domande mai viste nello stesso istante | 10 | 1068 ms | 1264 ms | 1264 ms |

Il caso peggiore è la terza riga, con la chiamata AI dentro la richiesta.

> 💰 Budget limitato per API calls AI

Il costo dipende da quante note vengono scritte e da quante domande **diverse** vengono fatte, non da quante ricerche. Nel test di carico le 500 ricerche sono costate zero chiamate AI.

> 🔄 Integrazione con sistemi esistenti (SpringBoot + MongoDB)

Spring Boot 3.5 e Java 17, gli stessi dello stack esistente. Le collection della consegna vengono solo lette; il sistema ne aggiunge due sue.

> 📊 Accuratezza per decisioni cliniche e gestione pazienti

- ogni risultato porta le frasi delle note che lo giustificano, quindi si verifica invece di fidarsi;
- la risposta elenca anche gli esclusi con il motivo;
- se AI e dizionario si contraddicono su un marcatore esplicito, vince il dizionario: un "non migliora" scritto nel testo non può essere ribaltato dal modello.

## 1. Analisi del Problema

> **Dove sono le informazioni** necessarie per rispondere?

| Cosa serve | Dove sta |
|---|---|
| "dolore lombare" | testo libero di `schede_valutazione` e `diario_trattamenti` |
| "ha mostrato miglioramento" | stesso testo libero, ma è un giudizio, non un campo |
| "negli ultimi 3 mesi" | campo `data` delle note |
| "ha saltato l'ultimo appuntamento" | campo `stato` di `eventi_calendario` |
| nome, età, contatti | `pazienti` |

Le prime due non esistono come dati strutturati e vanno estratte dal testo, le altre sono filtri normali. Da qui la separazione tra quello che si calcola una volta e si salva, e quello che si calcola a ogni ricerca.

> **Come estrarre** "miglioramento" da testi liberi?

Punteggio da -3 a +3 per nota, salvato in `improvement_score`. Lo dà l'AI, a cui viene chiesto un JSON con punteggio e frasi che lo giustificano ([LlmClinicalTextAnalyzer](../src/main/java/com/fisiodesk/smartquery/analysis/LlmClinicalTextAnalyzer.java)); senza AI lo calcola il dizionario ([HeuristicClinicalTextAnalyzer](../src/main/java/com/fisiodesk/smartquery/analysis/HeuristicClinicalTextAnalyzer.java)).

Per un paziente si sommano i punteggi delle sue note nel periodo: somma positiva uguale sta migliorando. Somma e non media, perché due note positive contano più di una.

Negazioni: "non risponde alla terapia" e "situazione stazionaria" contengono parole positive ma valgono il contrario; "nessun dolore residuo" è il caso opposto. Se AI e dizionario danno segno diverso vince il dizionario, perché quei marcatori sono espliciti ([TextAnalysisService](../src/main/java/com/fisiodesk/smartquery/analysis/TextAnalysisService.java)).

> **Come identificare** "dolore lombare" tra diagnosi + testi?

Non c'è un campo diagnosi: la patologia si desume dal testo. Ogni nota viene etichettata con le aree di cui parla, da un elenco chiuso (`LUMBAR`, `CERVICAL`, `SHOULDER`, `KNEE`, `HIP`, `OTHER`).

Nei dati la stessa cosa è scritta in sette modi: "dolore lombare", "lombalgia", "mal di schiena", "rachialgia lombare", "low back pain", "dolore alla bassa schiena", "colpo della strega". Il dizionario li mappa tutti su `LUMBAR` ([ClinicalLexicon](../src/main/java/com/fisiodesk/smartquery/analysis/ClinicalLexicon.java)), l'AI copre le formulazioni non previste. Sulle aree, in disaccordo, vince il dizionario: è un vocabolario chiuso.

Il punteggio si somma solo sulle note dell'area richiesta. È il motivo per cui Verdi non compare: migliora, ma sulle note della cervicale.

> **Come definire** "ultimo appuntamento saltato"?

L'evento più recente del paziente con `data <= referenceDate` ha `stato == "no_show"`.

- si guarda un solo evento, l'ultimo, non "ha mai saltato";
- `cancellato` non conta: il paziente ha avvisato;
- `prenotato` con data passata non conta: è un dato sporco;
- senza appuntamenti in calendario, il filtro non passa.

Una sola query indicizzata per paziente ([EventoCalendarioRepository](../src/main/java/com/fisiodesk/smartquery/repository/EventoCalendarioRepository.java)).

> **Quale architettura** permette risposte <2 secondi?

Quella in cui l'AI non sta nel percorso della ricerca. I testi si leggono prima, in un job ogni 30 secondi, e il risultato finisce in `note_insights`; la ricerca fa solo letture MongoDB su indici.

L'unico punto in cui l'AI entra in una richiesta è una domanda mai vista, con tempo massimo (1,5 s, 1,8 con Gemini) oltre il quale risponde il parser a regole. Il risultato viene salvato, quindi succede una volta sola per domanda.

> **Come gestire** i costi delle API AI?

- **una chiamata per nota**, una volta sola: se il testo non cambia (hash SHA-256) non viene riletta;
- **una chiamata per domanda distinta**: il filtro non dipende dai dati, si salva in `query_plans` e vale anche dopo un riavvio e su altre istanze;
- **modello economico**, e le note possono usarne uno diverso dalle domande;
- **freno sulle chiamate dell'indexer** (`AI_INDEXER_RPM`) per restare nella quota.

Ordine di grandezza: 1.000 professionisti con 20 note al giorno fanno circa 20.000 chiamate corte al giorno, qualunque sia il numero di ricerche.

> **Cosa succede** se l'AI non funziona?

- **lettura delle note**: errore o timeout e la nota viene letta dal dizionario, marcata `heuristic`. Quando l'AI torna, viene riletta una volta;
- **interpretazione della domanda**: fuori tempo massimo o errore, risponde il parser a regole; se era solo lenta, il risultato viene comunque salvato per dopo;
- **provider che sbaglia in continuazione**: l'indexer si ferma dopo tre note andate male e riprova al giro successivo.

Ogni chiamata ha timeout e un tentativo di ripetizione. Il blocco `explain` dice sempre chi ha lavorato.

## 2. Progettazione della Soluzione

### Data Strategy (Come organizzi i dati per query efficienti)

> Come gestisci i testi liberi per query veloci? (non puoi fare AI su tutto ogni volta)

Il testo diventa una volta sola due dati piccoli: elenco di aree e numero da -3 a +3. La ricerca lavora su quelli. Il testo resta solo come estratto da mostrare come prova.

> Dove e quando processi con AI? (real-time vs batch preprocessing)

In batch: [InsightIndexer](../src/main/java/com/fisiodesk/smartquery/analysis/InsightIndexer.java) parte all'avvio e poi ogni 30 secondi, e manda all'AI solo le note nuove o modificate. L'unica eccezione è l'interpretazione di una domanda mai vista, dentro la richiesta ma con tempo massimo e una volta sola per domanda.

> Come memorizzi/organizzi i risultati? (nuove collections, cache, indici)

- **`note_insights`**: un documento per nota, `_id` uguale a `<collection>:<id_nota>`, così rianalizzare è una sovrascrittura. Dentro anche paziente, professionista e data, per filtrare senza join.
- **`query_plans`**: il filtro di ogni domanda, chiave l'hash della domanda normalizzata.
- **Indici**: `data`, `(paziente_id, data)` e `(professionista_id, data)` su `note_insights`; `(paziente_id, data)` su `eventi_calendario`.
- **Cache** in memoria delle risposte per 60 secondi, per la stessa domanda ripetuta.

### Query Processing (Come trasformi la richiesta in risposta)

> Come scomponi la query naturale in operazioni concrete?

In cinque campi ([QueryPlan](../src/main/java/com/fisiodesk/smartquery/nlq/QueryPlan.java)): aree, migliora, peggiora, mesi indietro, ha saltato l'ultimo appuntamento. La query target diventa `LUMBAR`, migliora sì, 3 mesi, ha saltato sì.

Lo produce l'AI, obbligata a rispondere in un formato fisso (JSON schema generato dal record Java), quindi non può inventare campi o valori. In parallelo la domanda passa dal parser a regole e i due risultati si fondono ([PlanGuardrail](../src/main/java/com/fisiodesk/smartquery/nlq/PlanGuardrail.java)): quello che il parser riconosce con certezza rientra, le parafrasi restano all'AI, e tra "migliorati" e "non migliorati" vince il secondo.

> Come combini ricerca su dati strutturati + analisi AI sui testi?

L'analisi AI è già dato strutturato in `note_insights`, quindi è una sequenza di letture ([SmartQueryService](../src/main/java/com/fisiodesk/smartquery/query/SmartQueryService.java)):

1. note del periodo (indice su data, o su professionista e data se la ricerca è ristretta);
2. tengo quelle dell'area richiesta, raggruppate per paziente;
3. sommo i punteggi e applico la condizione di miglioramento;
4. per chi resta, leggo l'ultimo appuntamento e controllo `no_show`;
5. leggo l'anagrafica e costruisco la risposta.

I filtri più selettivi vengono prima, così il calendario si legge solo per i pazienti rimasti.

> Come ranki e presenti i risultati finali al dottore?

Ordine per punteggio di miglioramento decrescente, a parità di punteggio per cognome: in cima chi è migliorato di più, cioè chi conviene richiamare per primo.

Per ogni paziente: nome, età, contatti, data e stato dell'ultimo appuntamento, note che hanno determinato il risultato con le frasi evidenziate. Più il blocco `explain` con l'interpretazione della domanda, i tempi e gli esclusi con il motivo.

### Performance & Scalability (Come mantieni il sistema veloce)

> Come rispetti il vincolo <2s con molti utenti concorrenti?

- nessuna chiamata esterna nel percorso della ricerca, solo letture su indici;
- le domande già viste non toccano l'AI, perché il filtro è in `query_plans` ed è condiviso tra le istanze;
- dieci utenti sulla stessa domanda nuova generano una sola chiamata AI;
- pool di 16 thread per le chiamate AI, così una raffica non si accoda;
- HTTP/1.1 verso il provider, perché con HTTP/2 il client Java serializzava le chiamate concorrenti;
- domande suggerite interpretate già all'avvio.

Numeri nella tabella dei vincoli operativi, sopra.

> Come riduci i costi delle API AI mantenendo l'accuratezza?

Sul costo: una chiamata per nota, una per domanda distinta, modello economico, freno sull'indexer.

Sull'accuratezza: i modelli piccoli sbagliano di più, e la contromisura è la fusione col dizionario. Su 10 domande di controllo il modello locale da 1,5B da solo ne prende 6 (sbaglia "guariti", "sta meglio", una negazione, un'area), col dizionario 10. Con Gemini Flash-Lite erano 10 già da solo. Sulle note vale il contrario: il modello decide il punteggio ma non può cancellare un marcatore esplicito di segno opposto.

> Come gestisci l'aggiornamento dei dati quando arrivano nuove schede?

L'indexer ogni 30 secondi confronta le note con quelle già analizzate: nuova uguale analizzata, modificata riconosciuta dall'hash e rianalizzata, invariata saltata senza costo. Ogni nota viene salvata appena analizzata, quindi un riavvio a metà non fa ricominciare.

Conseguenza: una nota nuova è cercabile entro 30 secondi, non nell'istante. Se servisse l'immediatezza, il passo successivo sono i change stream di MongoDB.

## 3. Implementazione MVP

> Identificare pazienti con dolore lombare (da testi delle valutazioni e trattamenti)

Fatto su entrambe le collection: 32 note indicizzate, 17 da `schede_valutazione` e 15 da `diario_trattamenti`, tutte con almeno un'area riconosciuta (26 lombari, 4 cervicali, 2 spalla).

> Analizzare "miglioramento" dai testi delle schede_valutazione e diario_trattamenti

Fatto: punteggio per nota, somma per paziente sulle sole note dell'area richiesta. Coperto da `HeuristicClinicalTextAnalyzerTest` e `TextAnalysisServiceTest`, negazioni comprese.

> Filtrare per timeframe "ultimi 3 mesi"

Fatto, e la finestra è mobile: stessa domanda, "ultimo mese" legge 9 note, "ultimi 3 mesi" 24, "ultimi 6 mesi" 30. Cambiando la data di riferimento cambia il risultato: al 31/12/2024 quattro pazienti, al 31/10/2024 nessuno, perché a quella data nessuno aveva ancora saltato l'ultimo appuntamento.

> Identificare chi ha saltato l'ultimo appuntamento nel calendario (collection eventi_calendario)

Fatto: alla domanda "Chi ha saltato l'ultimo appuntamento?" rispondono i cinque con ultimo evento `no_show` (Romano 30/12, Colombo 31/12, Rossi 20/12, Bianchi 22/12, Ferrari 24/12), con Verdi escluso e motivato ("ultimo appuntamento prenotato il 28/12/2024, non saltato").

> Presentare risultati in modo utile per il professionista sanitario

Fatto: lista ordinata per miglioramento, contatti per richiamare, data dell'appuntamento saltato, note di prova con le frasi evidenziate, spiegazione con gli esclusi. Screenshot nel [README](../README.md#3-provalo-la-query-target).

Verifica dell'intero flusso: `scripts/smoke-test.sh` controlla i quattro pazienti attesi, l'assenza dei tre casi negativi, il tempo sotto i 2 secondi, la presenza di `explain` e lo scoping per professionista.

## Deliverable Richiesti

> 1. **Diagramma architettura**

[README, sezione 4](../README.md#4-come-funziona) in versione semplice, [DESIGN.md](DESIGN.md#architettura) in versione dettagliata.

> 2. **GitHub repository** con: codice dell'MVP funzionante, Docker Compose per setup facile, README con istruzioni setup

Questo repository. Setup: `docker compose up -d --build`, un comando, senza chiavi API.

> 3. **Nel README documenta:** come far partire il sistema, 2-3 bullet points sui trade-offs principali, demo della query target funzionante

[Sezione 2](../README.md#2-come-far-partire-il-sistema), [sezione 6](../README.md#6-le-scelte-principali-trade-off), [sezione 3](../README.md#3-provalo-la-query-target) con screenshot, comando `curl` e risultato atteso.

## Note Importanti

> **Linguaggio**: Libera scelta (menziona nel README il perché - considera lo stack FisioDesk)

Java 17 e Spring Boot 3.5, motivato in testa al [README](../README.md): è lo stack di FisioDesk e Dottai, quindi i componenti si aggiungono al backend esistente.

> **AI Provider**: Puoi usare OpenAI, Anthropic, o mock intelligente

Tutti e tre, a scelta all'avvio. Il client parla il protocollo OpenAI, quindi vanno OpenAI, Gemini, OpenRouter, Azure o Ollama in locale cambiando due variabili. Senza chiave lavora il dizionario, che è il mock.

> **Database**: Simula MongoDB con strutture appropriate (in-memory va bene)

MongoDB vero in Docker Compose, con collection e nomi dei campi della consegna.

> **Dati di test**: Coprono Marzo-Dicembre 2024; per "ultimi 3 mesi" usa una data di riferimento esplicita

Data di riferimento esplicita e visibile nella risposta. Ordine: quella della richiesta, poi `REFERENCE_DATE`, poi la data massima nel dataset (31/12/2024). `explain` dice quale è stata usata.

> **Integrazione**: Considera l'architettura SpringBoot esistente nel design

Servizi Spring normali, collection esistenti solo lette, due collection nuove aggiunte. L'indexer è uno `@Scheduled`. Lo scoping per professionista è già previsto e in produzione arriverebbe dal token.

> **Setup**: Docker Compose per facilitare la review

Un comando, nessuna chiave, dati caricati e indicizzati da soli. Verificato da zero: clone in cartella vuota e `docker compose up -d --build`.

> **Focus**: Problem solving e design thinking, non production-readiness

Quello che manca è dichiarato nei [limiti noti](DESIGN.md#limiti-noti): candidati presi dalle note e non dal calendario, note cancellate non riconciliate, domanda senza filtri riconoscibili che restituisce tutti, negazione sul filtro appuntamento non gestita, modello locale su CPU quasi sempre fuori budget. Niente autenticazione né permessi, che nel prodotto arriverebbero dal gestionale.
