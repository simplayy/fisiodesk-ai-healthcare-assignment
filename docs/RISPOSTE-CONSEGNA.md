# Risposte alla consegna, punto per punto

Questo documento segue la consegna nell'ordine in cui è scritta. Ogni richiesta è riportata come citazione, e sotto c'è la risposta con il riferimento al punto del codice.

Per far partire il sistema e vedere la demo: [README](../README.md). Per il dettaglio tecnico delle scelte: [DESIGN.md](DESIGN.md).

---

## La query target

> *"Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento"*

### Perché è complessa

> Richiede **AI** per interpretare "miglioramento" dai testi liberi delle valutazioni

Ogni nota viene letta e trasformata in un punteggio da -3 a +3. Lo fa l'AI, con il dizionario di termini come controllo e come riserva. Il punto difficile non è trovare la parola "miglioramento", ma capire che "non risponde alla terapia" e "situazione stazionaria" sono l'opposto, e che "nessun dolore residuo" è una buona notizia nonostante il "nessun".

> Combina **multiple collections** (schede_valutazione, eventi_calendario, pazienti)

Le note vengono da `schede_valutazione` e `diario_trattamenti` (entrambe, non solo la prima) e finiscono in un'unica collection `note_insights` con paziente, professionista e data copiati dentro. La ricerca legge quella, più `eventi_calendario` per l'ultimo appuntamento e `pazienti` per l'anagrafica. Sono tre letture mirate su indici, non una join.

> Filtra per **timeframe** specifico ("ultimi 3 mesi")

La finestra si calcola all'indietro dalla data di riferimento, con gli estremi inclusi. Il numero di mesi non è fisso: viene dalla domanda ("ultimi 2 mesi", "nell'ultimo mese"), e se la domanda non lo dice il valore è 3.

> Correla **compliance del paziente** con **progressi clinici**

Sono due mondi diversi: il progresso clinico sta in un testo, la compliance in uno stato del calendario. Il sistema li unisce per paziente, e nella risposta li mostra affiancati: il punteggio di miglioramento con le frasi che lo dimostrano, e la data dell'appuntamento saltato. È esattamente il paziente che sta migliorando ma sta sparendo, quello che vale la pena richiamare.

### Vincoli operativi

> ⚡ Performance: <2 secondi con 50+ professionisti concorrenti

Rispettato. Misurato con `scripts/load-test.py` sulla demo con Gemini attivo:

| Scenario | Richieste | mediana | 95° percentile | massimo |
|---|---|---|---|---|
| 60 professionisti insieme, domande già note | 300 | 56 ms | 141 ms | 156 ms |
| 100 professionisti insieme, domande già note | 500 | 95 ms | 245 ms | 367 ms |
| 60 professionisti insieme, con domande mai viste tra le loro | 300 | 121 ms | 1387 ms | 1627 ms |
| 10 domande mai viste, tutte nello stesso istante | 10 | 1068 ms | 1264 ms | 1264 ms |

Il caso peggiore, cioè tutte domande nuove che devono passare dall'AI dentro la richiesta, resta sotto il limite. Le domande già viste non toccano l'AI e costano decine di millisecondi.

> 💰 Budget limitato per API calls AI

Il costo dipende da quante note vengono scritte e da quante domande **diverse** vengono fatte, non da quante ricerche. Sulla demo attuale: 32 note lette una volta sola, e le 500 ricerche del test di carico sono costate zero chiamate AI, perché i filtri erano già salvati.

> 🔄 Integrazione con sistemi esistenti (SpringBoot + MongoDB)

Spring Boot 3.5 e Java 17, gli stessi di FisioDesk e Dottai. Le collection esistenti vengono lette e mai modificate: il sistema aggiunge due collection sue (`note_insights` e `query_plans`). L'integrazione nel gestionale vero sarebbe l'aggiunta di questi componenti al backend attuale, non un servizio separato.

> 📊 Accuratezza per decisioni cliniche e gestione pazienti

Tre cose, che valgono più della percentuale di accuratezza:

- ogni risultato porta con sé le frasi delle note che lo giustificano, quindi il professionista verifica invece di fidarsi;
- la risposta elenca anche **chi è stato escluso e perché**, che in ambito clinico conta quanto chi è dentro;
- quando l'AI e il dizionario si contraddicono su un marcatore esplicito, vince il dizionario, così un "non migliora" scritto nero su bianco non può essere ribaltato dal modello.

---

## 1. Analisi del Problema

> **Dove sono le informazioni** necessarie per rispondere?

Sparse su quattro collection, ed è questo che rende la query complessa:

| Cosa serve | Dove sta |
|---|---|
| "dolore lombare" | nel testo libero di `schede_valutazione` e `diario_trattamenti` |
| "ha mostrato miglioramento" | nello stesso testo libero, ma è un giudizio, non un campo |
| "negli ultimi 3 mesi" | nel campo `data` delle note |
| "ha saltato l'ultimo appuntamento" | nel campo `stato` di `eventi_calendario` |
| nome, età, contatti da mostrare | in `pazienti` |

Le prime due non esistono come dati strutturati: vanno estratte dal testo. Le altre due sono filtri normali. Da questa differenza nasce tutta l'architettura: quello che è costoso e ambiguo lo calcolo una volta e lo salvo, quello che è un filtro lo calcolo a ogni ricerca.

> **Come estrarre** "miglioramento" da testi liberi?

Ogni nota riceve un punteggio da -3 (netto peggioramento) a +3 (netto miglioramento), salvato nel campo `improvement_score` di `note_insights`. A darlo è l'AI, a cui viene chiesto un JSON con punteggio e frasi che lo giustificano ([LlmClinicalTextAnalyzer](../src/main/java/com/fisiodesk/smartquery/analysis/LlmClinicalTextAnalyzer.java)); senza AI lo calcola il dizionario di termini fisioterapici ([HeuristicClinicalTextAnalyzer](../src/main/java/com/fisiodesk/smartquery/analysis/HeuristicClinicalTextAnalyzer.java)).

Per un paziente il miglioramento nel periodo è la somma dei punteggi delle sue note: se è positiva sta migliorando. Somma e non media, perché due note positive contano più di una.

Il punto delicato sono le negazioni: "non risponde alla terapia" e "situazione stazionaria" contengono parole positive ma dicono il contrario. Il dizionario le riconosce, e riconosce anche il caso opposto, le frasi come "nessun dolore residuo", che contengono una negazione ma sono una buona notizia. Se AI e dizionario si contraddicono sul segno, vince il dizionario, perché quei marcatori sono espliciti nel testo ([TextAnalysisService.merge](../src/main/java/com/fisiodesk/smartquery/analysis/TextAnalysisService.java)).

> **Come identificare** "dolore lombare" tra diagnosi + testi?

Nel dataset non esiste un campo diagnosi: la patologia si desume dal testo. Ogni nota viene etichettata con le aree anatomiche di cui parla (`body_areas`), scelte da un elenco chiuso: `LUMBAR`, `CERVICAL`, `SHOULDER`, `KNEE`, `HIP`, `OTHER`.

I sinonimi sono il problema: nei dati la stessa cosa è scritta "dolore lombare", "lombalgia", "mal di schiena", "rachialgia lombare", "low back pain", "dolore alla bassa schiena", "colpo della strega". Il dizionario li mappa tutti su `LUMBAR` ([ClinicalLexicon](../src/main/java/com/fisiodesk/smartquery/analysis/ClinicalLexicon.java)) e l'AI copre le formulazioni non previste. Sulle aree, in caso di disaccordo vince il dizionario: è un vocabolario chiuso e i modelli piccoli a volte ci mettono dentro aree che nel testo non ci sono.

Una nota conta per la query solo se è etichettata con l'area richiesta, e il punteggio viene sommato solo su quelle note. È il motivo per cui Giuseppe Verdi non compare: migliora, ma sulle note della cervicale.

> **Come definire** "ultimo appuntamento saltato"?

L'appuntamento più recente del paziente con data minore o uguale alla data di riferimento ha `stato` uguale a `no_show`.

Le scelte dietro questa definizione:

- si guarda **un solo** evento, l'ultimo, non "ha mai saltato": la domanda parla dell'ultimo appuntamento;
- un ultimo evento `cancellato` non conta come saltato, perché il paziente ha avvisato;
- un ultimo evento `prenotato` con data passata non conta: è un dato sporco, non una prova di assenza;
- un paziente senza appuntamenti in calendario non passa il filtro.

Implementato con una singola query indicizzata per paziente, in [EventoCalendarioRepository](../src/main/java/com/fisiodesk/smartquery/repository/EventoCalendarioRepository.java).

> **Quale architettura** permette risposte <2 secondi?

Quella in cui l'AI non è nel percorso della ricerca. Il lavoro pesante, cioè leggere i testi, avviene prima, in un job che gira ogni 30 secondi e salva i risultati in `note_insights`. Quando arriva una domanda, il sistema fa solo letture MongoDB su indici.

Resta un punto in cui l'AI potrebbe entrare in una richiesta: capire una domanda mai vista. Anche lì è sotto controllo, perché ha un tempo massimo (1,5 secondi, 1,8 con Gemini) oltre il quale risponde il parser a regole, e perché il risultato viene salvato: la stessa domanda non passa più dall'AI.

> **Come gestire** i costi delle API AI?

Il costo è proporzionale al numero di note scritte e al numero di domande diverse, non al numero di ricerche:

- **una chiamata per nota**, una volta sola. Se la nota non cambia non viene più riletta, e per accorgersene si confronta l'hash SHA-256 del testo.
- **una chiamata per domanda distinta**. Il filtro ricavato da una domanda non dipende dai dati, quindi viene salvato in `query_plans` e riusato per sempre, anche dopo un riavvio e su altre istanze.
- **modello economico**: nella demo `gemini-3.5-flash-lite`, e le note possono usare un modello diverso dalle domande.
- **freno sulle chiamate dell'indexer** (`AI_INDEXER_RPM`), per restare dentro la quota del provider senza prendere errori.

Ordine di grandezza: 1.000 professionisti che scrivono 20 note al giorno fanno circa 20.000 chiamate corte al giorno, indipendentemente da quante ricerche facciano.

> **Cosa succede** se l'AI non funziona?

Non si ferma niente. Ogni punto in cui interviene l'AI ha una via alternativa:

- **lettura delle note**: se la chiamata va in errore o in timeout, la nota viene letta dal dizionario e salvata con l'etichetta `heuristic`. Quando l'AI torna disponibile, quelle note vengono rilette una volta sola.
- **interpretazione della domanda**: se l'AI tarda oltre il tempo massimo o dà errore, risponde il parser a regole. Se la chiamata era solo lenta, il suo risultato viene comunque salvato per la volta dopo.
- **il provider risponde male a ripetizione**: l'indexer si ferma dopo tre note andate male e riprova al giro successivo, invece di bruciare chiamate ogni 30 secondi.

Ogni chiamata ha timeout e un tentativo di ripetizione. In ogni risposta il blocco `explain` dice chi ha lavorato, quindi il professionista sa sempre se ha davanti un'interpretazione dell'AI o del parser.

---

## 2. Progettazione della Soluzione

### Data Strategy (Come organizzi i dati per query efficienti)

> Come gestisci i testi liberi per query veloci? (non puoi fare AI su tutto ogni volta)

I testi vengono trasformati una volta sola in due dati strutturati e piccoli: un elenco di aree anatomiche e un numero da -3 a +3. La ricerca lavora su quelli, non sul testo. Il testo originale resta, ma solo come estratto da mostrare al professionista come prova.

> Dove e quando processi con AI? (real-time vs batch preprocessing)

In batch, non in tempo reale. Un job schedulato ([InsightIndexer](../src/main/java/com/fisiodesk/smartquery/analysis/InsightIndexer.java)) parte all'avvio e poi ogni 30 secondi, confronta le note esistenti con quelle già analizzate e manda all'AI solo quelle nuove o modificate.

L'unica eccezione è l'interpretazione di una domanda mai vista, che avviene dentro la richiesta perché serve subito, ma con un tempo massimo e una sola volta per domanda.

> Come memorizzi/organizzi i risultati? (nuove collections, cache, indici)

- **Nuova collection `note_insights`**: un documento per nota, con `_id` costruito come `<collection>:<id_nota>`, così rianalizzare una nota è una sovrascrittura e non crea duplicati. Dentro ci sono anche paziente, professionista e data copiati dalla nota, per filtrare senza join.
- **Nuova collection `query_plans`**: il filtro ricavato da ogni domanda, con chiave l'hash della domanda normalizzata.
- **Indici**: su `note_insights` per data, per (paziente, data) e per (professionista, data); su `eventi_calendario` per (paziente, data), che rende la ricerca dell'ultimo appuntamento una lettura diretta e non una scansione.
- **Cache in memoria** delle risposte complete per 60 secondi, per assorbire la stessa domanda ripetuta o il refresh della pagina.

### Query Processing (Come trasformi la richiesta in risposta)

> Come scomponi la query naturale in operazioni concrete?

La domanda diventa un oggetto con cinque campi ([QueryPlan](../src/main/java/com/fisiodesk/smartquery/nlq/QueryPlan.java)): aree anatomiche, migliora sì/no, peggiora sì/no, quanti mesi indietro guardare, ha saltato l'ultimo appuntamento sì/no. La query target diventa: `LUMBAR`, migliora sì, 3 mesi, ha saltato sì.

A produrlo è l'AI, obbligata a rispondere in un formato fisso (JSON schema generato dal record Java), quindi non può inventare campi o valori fuori elenco. In parallelo la stessa domanda passa dal parser a regole e i due risultati vengono fusi ([PlanGuardrail](../src/main/java/com/fisiodesk/smartquery/nlq/PlanGuardrail.java)): quello che il parser riconosce con certezza viene rimesso dentro, perché i modelli piccoli a volte perdono un pezzo della domanda; le parafrasi restano all'AI. In caso di contraddizione tra "migliorati" e "non migliorati" vince il secondo, che è come si leggono le negazioni.

> Come combini ricerca su dati strutturati + analisi AI sui testi?

L'analisi AI dei testi è già diventata dato strutturato dentro `note_insights`, quindi la combinazione è una normale sequenza di letture ([SmartQueryService](../src/main/java/com/fisiodesk/smartquery/query/SmartQueryService.java)):

1. prendo le note del periodo (una query, con indice su data, oppure su professionista e data se la ricerca è ristretta a un professionista);
2. tengo solo quelle etichettate con l'area richiesta e le raggruppo per paziente;
3. per ogni paziente sommo i punteggi e controllo la condizione di miglioramento;
4. per chi la supera, leggo l'ultimo appuntamento e controllo se è `no_show`;
5. per chi resta, leggo l'anagrafica e costruisco la risposta.

I filtri più selettivi vengono prima, così le letture del calendario si fanno solo sui pazienti rimasti.

> Come ranki e presenti i risultati finali al dottore?

Ordinamento per punteggio di miglioramento decrescente, a parità di punteggio per cognome: in cima chi è migliorato di più, che è il paziente per cui vale di più fare una telefonata di recupero.

Per ogni paziente la risposta contiene nome, età, contatti (servono per richiamarlo), data e stato dell'ultimo appuntamento, e le note che hanno determinato il risultato con le frasi decisive evidenziate. Non è solo una lista di nomi: il professionista vede la prova clinica di quello che il sistema afferma.

In più ogni risposta porta un blocco `explain` con come è stata capita la domanda, i tempi di ogni passaggio, e l'elenco dei pazienti scartati con il motivo ("nessun miglioramento nell'area richiesta, punteggio -7 su 4 note", "ultimo appuntamento prenotato il 28/12/2024, non saltato").

### Performance & Scalability (Come mantieni il sistema veloce)

> Come rispetti il vincolo <2s con molti utenti concorrenti?

- Nel percorso della ricerca non ci sono chiamate esterne: solo letture MongoDB su indici.
- Le domande già viste non toccano mai l'AI, perché il filtro è salvato in `query_plans` ed è condiviso tra tutte le istanze.
- Se dieci utenti fanno la stessa domanda nuova insieme, parte una sola chiamata all'AI e tutti aspettano quella.
- Le chiamate all'AI hanno un pool ampio (16), così una raffica di domande nuove non si mette in coda.
- La connessione verso il provider è forzata a HTTP/1.1, perché con HTTP/2 il client Java le accodava tutte su una connessione e le chiamate concorrenti diventavano seriali.
- Le domande suggerite dell'interfaccia vengono interpretate già all'avvio, quindi il primo clic non paga niente.

I numeri misurati sono nella tabella dei vincoli operativi, sopra.

> Come riduci i costi delle API AI mantenendo l'accuratezza?

Le leve sul costo sono quelle già dette: una chiamata per nota, una per domanda distinta, modello economico, freno sulle chiamate dell'indexer.

Sull'accuratezza, ridurre i costi porta a usare modelli piccoli, che sbagliano più spesso. La contromisura è la fusione con il dizionario: su un set di 10 domande di controllo il modello locale da 1,5 miliardi di parametri da solo ne interpreta bene 6 (sbaglia "guariti", "sta meglio", una negazione, un'area), con il controllo del dizionario tutte e 10. Con un modello più capace il controllo non toglie niente: con Gemini Flash-Lite le 10 domande erano già giuste da sole.

Sulle note vale il principio opposto: il modello decide il punteggio, ma non può cancellare un marcatore esplicito di segno contrario trovato nel testo.

> Come gestisci l'aggiornamento dei dati quando arrivano nuove schede?

L'indexer gira ogni 30 secondi e confronta le note con quelle già analizzate. Una nota nuova viene analizzata; una nota modificata viene riconosciuta perché l'hash del testo non corrisponde più e viene rianalizzata; una nota invariata viene saltata senza costo. Ogni nota viene salvata appena analizzata, quindi un riavvio a metà non fa ricominciare da capo.

Conseguenza da dichiarare: una nota appena scritta diventa cercabile entro 30 secondi, non nell'istante. Per una ricerca clinica è accettabile. Se servisse l'immediatezza, il passo successivo sono i change stream di MongoDB, che notificano la scrittura invece di farla cercare.

---

## 3. Implementazione MVP

> **Il sistema deve:**

> Identificare pazienti con dolore lombare (da testi delle valutazioni e trattamenti)

Fatto, su entrambe le collection: delle 32 note indicizzate, 17 vengono da `schede_valutazione` e 15 da `diario_trattamenti`, e tutte hanno almeno un'area riconosciuta (26 lombari, 4 cervicali, 2 spalla). Verificato da `HeuristicClinicalTextAnalyzerTest`, che contiene i sinonimi presenti nei dati.

> Analizzare "miglioramento" dai testi delle schede_valutazione e diario_trattamenti

Fatto: punteggio per nota, somma per paziente sulle sole note dell'area richiesta. Verificato da `HeuristicClinicalTextAnalyzerTest` e `TextAnalysisServiceTest`, compresi i casi con negazione.

> Filtrare per timeframe "ultimi 3 mesi"

Fatto, e la finestra è davvero mobile: con la stessa domanda, "ultimo mese" legge 9 note, "ultimi 3 mesi" ne legge 24, "ultimi 6 mesi" 30. Cambiando la data di riferimento cambia anche il risultato: al 31/12/2024 la query target dà quattro pazienti, al 31/10/2024 nessuno, perché a quella data nessuno aveva ancora saltato l'ultimo appuntamento.

> Identificare chi ha saltato l'ultimo appuntamento nel calendario (collection eventi_calendario)

Fatto: alla domanda "Chi ha saltato l'ultimo appuntamento?" il sistema risponde con i cinque pazienti il cui ultimo evento è `no_show` (Romano 30/12, Colombo 31/12, Rossi 20/12, Bianchi 22/12, Ferrari 24/12) ed esclude Verdi con la motivazione "ultimo appuntamento prenotato il 28/12/2024, non saltato".

> Presentare risultati in modo utile per il professionista sanitario

Fatto: lista ordinata per miglioramento, con contatti per richiamare il paziente, data dell'appuntamento saltato, note di prova con le frasi evidenziate, e la spiegazione di come è nata la risposta compresi gli esclusi. Si vede nello screenshot nel [README](../README.md#3-provalo-la-query-target).

Verifica automatica di tutto il flusso: `scripts/smoke-test.sh` controlla che la query target restituisca esattamente Rossi, Bianchi, Romano e Colombo, che i tre casi negativi non compaiano, che la risposta stia sotto i 2 secondi, che il blocco `explain` ci sia, e che ogni professionista veda solo i propri pazienti.

---

## Deliverable Richiesti

> 1. **Diagramma architettura** - Schema chiaro della tua soluzione

Nel [README, sezione 4](../README.md#4-come-funziona), in versione semplice (due momenti: prima in background, poi quando si cerca). Versione dettagliata con tutti i componenti in [DESIGN.md](DESIGN.md#architettura).

> 2. **GitHub repository** con: codice dell'MVP funzionante, Docker Compose per setup facile, README con istruzioni setup

Questo repository. Il setup è `docker compose up -d --build`, un comando solo, senza chiavi API. Le istruzioni sono nella [sezione 2 del README](../README.md#2-come-far-partire-il-sistema).

> 3. **Nel README documenta:** come far partire il sistema, 2-3 bullet points sui trade-offs principali, demo della query target funzionante

- Come far partire: [sezione 2](../README.md#2-come-far-partire-il-sistema).
- Tre trade-off: [sezione 6](../README.md#6-le-scelte-principali-trade-off).
- Demo della query target: [sezione 3](../README.md#3-provalo-la-query-target), con screenshot dell'interfaccia, comando `curl` e risultato atteso.

---

## Note Importanti

> **Linguaggio**: Libera scelta (menziona nel README il perché - considera lo stack FisioDesk)

Java 17 e Spring Boot 3.5, dichiarato in testa al [README](../README.md): è lo stack di FisioDesk e Dottai, quindi questi componenti si aggiungono al backend esistente senza introdurre un altro runtime da mantenere.

> **AI Provider**: Puoi usare OpenAI, Anthropic, o mock intelligente

Tutti e tre, a scelta di chi avvia. Il client parla il protocollo delle API OpenAI, quindi funziona con OpenAI, Gemini, OpenRouter, Azure o un modello locale con Ollama, cambiando due variabili d'ambiente. Senza chiave lavora il dizionario di termini, che è il "mock intelligente".

> **Database**: Simula MongoDB con strutture appropriate (in-memory va bene)

MongoDB vero dentro Docker Compose, non simulato, con le collection e i nomi dei campi della consegna (`paziente_id`, `professionista_id`, `data`, `descrizione`, `stato`).

> **Dati di test**: Coprono Marzo-Dicembre 2024; per "ultimi 3 mesi" usa una data di riferimento esplicita

La data di riferimento è esplicita e visibile nella risposta. L'ordine è: quella indicata nella richiesta, poi la variabile `REFERENCE_DATE`, poi la data dell'ultimo evento in calendario (31/12/2024), che è la "data massima nel dataset" suggerita dalla consegna. Il blocco `explain` dice sempre quale delle tre è stata usata.

> **Integrazione**: Considera l'architettura SpringBoot esistente nel design

I componenti sono servizi Spring normali, le collection esistenti vengono solo lette, e le due collection nuove si aggiungono senza toccare le altre. L'indexer è uno `@Scheduled`, quindi in produzione diventerebbe un job dell'applicazione o un processo separato senza cambiare il codice. Lo scoping per professionista è già previsto: nel prodotto vero l'identità arriverebbe dal token dell'utente invece che dalla richiesta.

> **Setup**: Docker Compose per facilitare la review

Un comando, nessuna chiave, dati caricati e indicizzati da soli. Verificato partendo da zero: clone del repository in una cartella vuota e `docker compose up -d --build`.

> **Focus**: Problem solving e design thinking, non production-readiness

Per onestà, quello che manca perché è un MVP è scritto nei [limiti noti](DESIGN.md#limiti-noti): i candidati partono sempre dalle note e non dal calendario, le note cancellate restano indicizzate, una domanda senza filtri riconoscibili restituisce tutti i pazienti del periodo, la negazione sul filtro dell'appuntamento non è gestita, e con il modello locale su CPU l'interpretazione di una domanda nuova supera quasi sempre il tempo massimo. Non ci sono autenticazione né permessi, perché nel prodotto arriverebbero dal gestionale.
