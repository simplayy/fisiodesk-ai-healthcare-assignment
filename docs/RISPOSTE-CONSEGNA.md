# Risposte alla consegna

Le domande della consegna con la risposta sotto. Setup e demo nel [README](../README.md), dettagli tecnici in [DESIGN.md](DESIGN.md).

## 1. Analisi del Problema

**Dove sono le informazioni necessarie per rispondere?**

"Dolore lombare" e "miglioramento" stanno solo nei testi liberi di `schede_valutazione` e `diario_trattamenti`. "Ultimi 3 mesi" nel campo `data` delle note. "Ha saltato l'ultimo appuntamento" nello `stato` di `eventi_calendario`. Nome e contatti in `pazienti`.

**Come estrarre "miglioramento" da testi liberi?**

Do a ogni nota un punteggio da -3 a +3, calcolato una volta sola e salvato in `note_insights`. Lo dà l'AI, senza AI un dizionario di termini fisioterapici. Per paziente sommo i punteggi delle note nel periodo: somma positiva vuol dire che migliora.

La parte delicata sono le negazioni: "non risponde alla terapia" è negativo, "nessun dolore residuo" è positivo. Se AI e dizionario danno segno opposto tengo il dizionario, perché quei marcatori sono scritti nel testo.

**Come identificare "dolore lombare" tra diagnosi + testi?**

Non c'è un campo diagnosi, quindi ogni nota viene etichettata con le aree di cui parla, da un elenco chiuso (`LUMBAR`, `CERVICAL`, `SHOULDER`, `KNEE`, `HIP`, `OTHER`). Nei dati la lombalgia è scritta in sette modi ("mal di schiena", "low back pain", "colpo della strega"...): il dizionario li mappa tutti su `LUMBAR`, l'AI copre le formulazioni non previste. Il miglioramento lo sommo solo sulle note dell'area richiesta.

**Come definire "ultimo appuntamento saltato"?**

L'evento più recente del paziente fino alla data di riferimento ha stato `no_show`. Un ultimo evento `cancellato` non conta, il paziente ha avvisato. Un `prenotato` con data passata nemmeno, è un dato sporco.

La domanda opposta ("che **non** hanno saltato l'ultimo appuntamento") è un filtro a sé, con un campo suo nel piano: altrimenti la negazione verrebbe letta come conferma e la risposta sarebbe esattamente il contrario di quella chiesta.

**Quale architettura permette risposte <2 secondi?**

Quella in cui l'AI non sta nel percorso della ricerca. Legge le note prima, in un job che gira ogni 30 secondi, e salva il risultato. La ricerca fa solo letture MongoDB su indici. L'unica eccezione è una domanda mai vista, che ha un tetto di 1,5 secondi oltre il quale risponde il parser a regole.

**Come gestire i costi delle API AI?**

Una chiamata per nota, e mai più se il testo non cambia (confronto per hash). Una chiamata per domanda distinta, poi il filtro è salvato in `query_plans` e vale anche dopo un riavvio. Il costo cresce con le note scritte, non con le ricerche: nel test di carico 500 ricerche sono costate zero chiamate.

**Cosa succede se l'AI non funziona?**

Prende il suo posto il dizionario, sia sulle note sia sulle domande, e il sistema continua a rispondere. Le note lette col dizionario vengono rilette una volta quando l'AI torna. Ogni risposta dice quale dei due ha lavorato.

Quanto costa in precisione l'ho misurato: sulle 28 domande già interpretate dall'AI, il parser a regole produce lo stesso filtro in 24 casi. Negli altri la risposta è più larga del dovuto, mai opposta. Il dettaglio è in [DESIGN.md](DESIGN.md#quando-scatta-il-fallback-e-quanto-cambia-la-risposta).

## 2. Progettazione della Soluzione

### Data Strategy (Come organizzi i dati per query efficienti)

**Come gestisci i testi liberi per query veloci? (non puoi fare AI su tutto ogni volta)**

Li trasformo una volta sola in due dati piccoli, aree anatomiche e punteggio, e cerco su quelli. Il testo resta solo come estratto da mostrare al professionista.

**Dove e quando processi con AI? (real-time vs batch preprocessing)**

In batch. [InsightIndexer](../src/main/java/com/fisiodesk/smartquery/analysis/InsightIndexer.java) parte all'avvio e poi ogni 30 secondi, e manda all'AI solo le note nuove o modificate. In tempo reale c'è solo l'interpretazione di una domanda mai vista.

**Come memorizzi/organizzi i risultati? (nuove collections, cache, indici)**

Due collection nuove: `note_insights` (un documento per nota, `_id` uguale a `collection:id_nota`, così rianalizzare è una sovrascrittura) e `query_plans` (il filtro di ogni domanda). Indici su `data`, `(paziente_id, data)` e `(professionista_id, data)`, più `(paziente_id, data)` su `eventi_calendario`. Cache in memoria delle risposte per 60 secondi.

### Query Processing (Come trasformi la richiesta in risposta)

**Come scomponi la query naturale in operazioni concrete?**

In cinque campi: aree, migliora, peggiora, mesi indietro, ha saltato l'ultimo appuntamento. La query target diventa `LUMBAR` / migliora / 3 mesi / saltato. Lo produce l'AI vincolata a un JSON schema, fusa col parser a regole: quello che il parser riconosce con certezza rientra sempre, perché i modelli piccoli a volte perdono un pezzo della domanda.

**Come combini ricerca su dati strutturati + analisi AI sui testi?**

L'analisi AI è già diventata dato strutturato, quindi è una sequenza di letture: note del periodo, filtro sull'area, somma dei punteggi per paziente, ultimo appuntamento, anagrafica. I filtri più selettivi vengono prima, così il calendario lo leggo solo per i pazienti rimasti.

**Come ranki e presenti i risultati finali al dottore?**

Ordine per punteggio di miglioramento decrescente: in cima chi conviene richiamare per primo. Per ognuno mostro contatti, data dell'appuntamento saltato e le note che lo dimostrano, con le frasi decisive evidenziate. In più un blocco `explain` con l'interpretazione della domanda, i tempi e i pazienti esclusi con il motivo.

### Performance & Scalability (Come mantieni il sistema veloce)

**Come rispetti il vincolo <2s con molti utenti concorrenti?**

Nessuna chiamata esterna nella ricerca, solo letture su indici. Le domande già viste non toccano l'AI. Dieci utenti sulla stessa domanda nuova generano una sola chiamata. Misurato con `scripts/load-test.py` sulla demo con Gemini:

| Scenario | Richieste | mediana | 95° perc. | massimo |
|---|---|---|---|---|
| 60 professionisti insieme, domande già note | 300 | 56 ms | 141 ms | 156 ms |
| 100 professionisti insieme, domande già note | 500 | 95 ms | 245 ms | 367 ms |
| 60 professionisti insieme, con domande mai viste | 300 | 121 ms | 1387 ms | 1627 ms |
| 10 domande mai viste nello stesso istante | 10 | 1068 ms | 1264 ms | 1264 ms |

**Come riduci i costi delle API AI mantenendo l'accuratezza?**

Sul costo le leve sono quelle di sopra. Sull'accuratezza il problema è che risparmiare porta a modelli piccoli, che sbagliano di più: la fusione col dizionario recupera quei casi. Su 10 domande di controllo il modello locale da 1,5B da solo ne prende 6, col dizionario 10. Con Gemini Flash-Lite erano già 10 da solo.

**Come gestisci l'aggiornamento dei dati quando arrivano nuove schede?**

L'indexer confronta l'hash del testo: nota nuova o modificata la rianalizza, invariata la salta senza costo. Una nota appena scritta è cercabile entro 30 secondi. Per averla subito servirebbero i change stream di MongoDB.

## 3. Implementazione MVP

- **Identificare pazienti con dolore lombare (da testi delle valutazioni e trattamenti)**: fatto su entrambe le collection. Delle 32 note indicizzate, 17 vengono dalle schede e 15 dal diario, tutte con un'area riconosciuta.
- **Analizzare "miglioramento" dai testi**: fatto, con i test `HeuristicClinicalTextAnalyzerTest` e `TextAnalysisServiceTest`, negazioni comprese.
- **Filtrare per timeframe "ultimi 3 mesi"**: fatto, e la finestra è mobile. Stessa domanda: "ultimo mese" legge 9 note, "ultimi 3 mesi" 24, "ultimi 6 mesi" 30. Al 31/12/2024 la query target dà quattro pazienti, al 31/10/2024 nessuno.
- **Identificare chi ha saltato l'ultimo appuntamento**: fatto. "Chi ha saltato l'ultimo appuntamento?" restituisce i cinque con ultimo evento `no_show` ed esclude Verdi con la motivazione "ultimo appuntamento prenotato il 28/12/2024, non saltato".
- **Presentare risultati in modo utile**: lista ordinata per miglioramento, con contatti, appuntamento saltato, note di prova e spiegazione degli esclusi. Screenshot nel [README](../README.md#3-provalo-la-query-target).

`scripts/smoke-test.sh` verifica tutto il flusso: i quattro pazienti attesi, l'assenza dei tre casi negativi, il tempo sotto i 2 secondi e lo scoping per professionista.

## Deliverable Richiesti

1. **Diagramma architettura**: nel [README](../README.md#4-come-funziona), versione dettagliata in [DESIGN.md](DESIGN.md#architettura).
2. **GitHub repository**: questo, con il codice, `docker-compose.yml` e il README. Setup con un comando, senza chiavi API.
3. **Nel README**: [come far partire il sistema](../README.md#2-come-far-partire-il-sistema), [tre trade-off](../README.md#6-le-scelte-principali-trade-off), [demo della query target](../README.md#3-provalo-la-query-target) con screenshot e comando.

Il linguaggio è Java 17 con Spring Boot, come chiedeva la nota sulla libera scelta: è lo stack di FisioDesk e Dottai, quindi questi componenti si aggiungono al backend esistente. Quello che manca perché è un MVP è elencato nei [limiti noti](DESIGN.md#cosa-non-fa).
