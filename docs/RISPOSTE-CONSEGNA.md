# Risposte alle domande della consegna

La consegna pone una serie di domande precise nelle sezioni "Analisi del problema", "Progettazione della soluzione" e "Implementazione MVP". Qui rispondo a tutte, nell'ordine in cui sono scritte, con il riferimento al punto del codice dove la cosa è implementata.

Il quadro generale è nel [README](../README.md), il dettaglio tecnico delle scelte in [DESIGN.md](DESIGN.md).

---

## 1. Analisi del problema

### Dove sono le informazioni necessarie per rispondere?

Sparse su quattro collection, ed è questo che rende la query complessa:

| Cosa serve | Dove sta |
|---|---|
| "dolore lombare" | nel testo libero di `schede_valutazione` e `diario_trattamenti` |
| "ha mostrato miglioramento" | nello stesso testo libero, ma è un giudizio, non un campo |
| "negli ultimi 3 mesi" | nel campo `data` delle note |
| "ha saltato l'ultimo appuntamento" | nel campo `stato` di `eventi_calendario` |
| nome, età, contatti da mostrare | in `pazienti` |

Le prime due informazioni non esistono come dati strutturati: vanno estratte dal testo. Le altre due sono filtri normali. Da questa differenza nasce tutta l'architettura: quello che è costoso e ambiguo lo calcolo una volta e lo salvo, quello che è un filtro lo calcolo a ogni ricerca.

### Come estrarre "miglioramento" da testi liberi?

Ogni nota riceve un punteggio da -3 (netto peggioramento) a +3 (netto miglioramento), salvato nel campo `improvement_score` di `note_insights`. A darlo è l'AI, a cui viene chiesto un JSON con punteggio e frasi che lo giustificano ([LlmClinicalTextAnalyzer](../src/main/java/com/fisiodesk/smartquery/analysis/LlmClinicalTextAnalyzer.java)); senza AI lo calcola un dizionario di termini fisioterapici ([HeuristicClinicalTextAnalyzer](../src/main/java/com/fisiodesk/smartquery/analysis/HeuristicClinicalTextAnalyzer.java)).

Per un paziente il miglioramento nel periodo è la somma dei punteggi delle sue note: se è positiva sta migliorando. Somma e non media, perché due note positive contano più di una.

Il punto delicato sono le negazioni: "non risponde alla terapia" e "situazione stazionaria" contengono parole positive ma dicono il contrario. Il dizionario le riconosce e ha anche il caso opposto, le frasi come "nessun dolore residuo", che contengono una negazione ma sono una buona notizia. Se AI e dizionario si contraddicono sul segno, vince il dizionario, perché quei marcatori sono espliciti nel testo ([TextAnalysisService.merge](../src/main/java/com/fisiodesk/smartquery/analysis/TextAnalysisService.java)).

### Come identificare "dolore lombare" tra diagnosi + testi?

Nel dataset non esiste un campo diagnosi: la patologia si desume dal testo. Ogni nota viene quindi etichettata con le aree anatomiche di cui parla (`body_areas`), scelte da un elenco chiuso: `LUMBAR`, `CERVICAL`, `SHOULDER`, `KNEE`, `HIP`, `OTHER`.

I sinonimi sono il problema: nei dati la stessa cosa è scritta "dolore lombare", "lombalgia", "mal di schiena", "rachialgia lombare", "low back pain", "dolore alla bassa schiena", "colpo della strega". Il dizionario li mappa tutti su `LUMBAR` ([ClinicalLexicon](../src/main/java/com/fisiodesk/smartquery/analysis/ClinicalLexicon.java)) e l'AI copre le formulazioni non previste. Sulle aree, quando i due sono in disaccordo, vince il dizionario: è un vocabolario chiuso e i modelli piccoli a volte ci mettono dentro aree che nel testo non ci sono.

Una nota conta per la query solo se è etichettata con l'area richiesta, e il punteggio di miglioramento viene sommato solo su quelle note. È il motivo per cui Giuseppe Verdi non compare: migliora, ma sulle note della cervicale.

### Come definire "ultimo appuntamento saltato"?

L'appuntamento più recente del paziente con data minore o uguale alla data di riferimento ha `stato` uguale a `no_show`.

Le scelte dietro questa definizione:

- si guarda **un solo** evento, l'ultimo, non "ha mai saltato": la domanda parla dell'ultimo appuntamento;
- un ultimo evento `cancellato` non conta come saltato, perché il paziente ha avvisato;
- un ultimo evento `prenotato` con data passata non conta: è un dato sporco, non una prova di assenza;
- un paziente senza appuntamenti in calendario non passa il filtro.

Implementato con una singola query indicizzata per paziente, in [EventoCalendarioRepository](../src/main/java/com/fisiodesk/smartquery/repository/EventoCalendarioRepository.java).

### Quale architettura permette risposte sotto i 2 secondi?

Quella in cui l'AI non è nel percorso della ricerca. Il lavoro pesante (leggere i testi) avviene prima, in un job che gira ogni 30 secondi e salva i risultati nella collection `note_insights`. Quando arriva una domanda, il sistema fa solo letture MongoDB su indici: note del periodo, ultimo appuntamento, anagrafica.

Resta un punto in cui l'AI potrebbe entrare in una richiesta: capire una domanda mai vista prima. Anche lì è sotto controllo, perché ha un tempo massimo (1,5 secondi, 1,8 con Gemini) oltre il quale risponde il parser a regole, e perché il risultato viene salvato: la stessa domanda non passa più dall'AI.

Misurato: la ricerca costa qualche decina di millisecondi, e con 100 utenti che cercano contemporaneamente la richiesta più lenta è stata 1296 ms (tabella in [DESIGN.md](DESIGN.md#misure-di-carico)).

### Come gestire i costi delle API AI?

Il costo è proporzionale al numero di note scritte e al numero di domande diverse, non al numero di ricerche. Nel concreto:

- **una chiamata per nota**, una volta sola. Se la nota non cambia non viene più riletta, e per accorgersene si confronta l'hash SHA-256 del testo.
- **una chiamata per domanda distinta**. Il filtro ricavato da una domanda non dipende dai dati, quindi viene salvato nella collection `query_plans` e riusato per sempre, anche dopo un riavvio e su altre istanze.
- **modello economico**: nella demo `gemini-3.5-flash-lite`, e le note possono usare un modello diverso dalle domande.
- **freno sulle chiamate dell'indexer** (`AI_INDEXER_RPM`), per restare dentro la quota del provider senza prendere errori.

Ordine di grandezza: 1.000 professionisti che scrivono 20 note al giorno fanno circa 20.000 chiamate corte al giorno, indipendentemente da quante ricerche facciano.

### Cosa succede se l'AI non funziona?

Non si ferma niente. Ogni punto in cui interviene l'AI ha una via alternativa:

- **lettura delle note**: se la chiamata va in errore o in timeout, la nota viene letta dal dizionario di termini e salvata con l'etichetta `heuristic`. Quando l'AI torna disponibile, quelle note vengono rilette una volta sola.
- **interpretazione della domanda**: se l'AI tarda oltre il tempo massimo o dà errore, risponde il parser a regole. Se la chiamata era solo lenta, il suo risultato viene comunque salvato per la volta dopo.
- **il provider risponde male a ripetizione**: l'indexer si ferma dopo tre note andate male e riprova al giro successivo, invece di bruciare chiamate ogni 30 secondi.

Ogni chiamata ha timeout e un tentativo di ripetizione. In ogni risposta il blocco `explain` dice chi ha lavorato, quindi il professionista sa sempre se ha davanti un'interpretazione dell'AI o del parser.

---

## 2. Progettazione della soluzione

### Data strategy

**Come gestisci i testi liberi per query veloci? (non puoi fare AI su tutto ogni volta)**

I testi vengono trasformati una volta sola in due dati strutturati e piccoli: un elenco di aree anatomiche e un numero da -3 a +3. La ricerca lavora su quelli, non sul testo. Il testo originale resta, ma solo come estratto da mostrare al professionista come prova.

**Dove e quando processi con AI? (real-time vs batch preprocessing)**

In batch, non in tempo reale. Un job schedulato ([InsightIndexer](../src/main/java/com/fisiodesk/smartquery/analysis/InsightIndexer.java)) parte all'avvio e poi ogni 30 secondi, confronta le note esistenti con quelle già analizzate e manda all'AI solo quelle nuove o modificate.

L'unica eccezione è l'interpretazione di una domanda mai vista, che avviene dentro la richiesta perché serve subito, ma con un tempo massimo e una sola volta per domanda.

**Come memorizzi/organizzi i risultati? (nuove collections, cache, indici)**

- **Nuova collection `note_insights`**: un documento per nota, con `_id` costruito come `<collection>:<id_nota>`, così rianalizzare una nota è una sovrascrittura e non crea duplicati. Dentro ci sono anche paziente, professionista e data copiati dalla nota, per poter filtrare senza join.
- **Nuova collection `query_plans`**: il filtro ricavato da ogni domanda, con chiave l'hash della domanda normalizzata.
- **Indici**: su `note_insights` per data, per (paziente, data) e per (professionista, data); su `eventi_calendario` per (paziente, data), che rende la ricerca dell'ultimo appuntamento una lettura diretta e non una scansione.
- **Cache in memoria** delle risposte complete per 60 secondi, per assorbire la stessa domanda ripetuta o il refresh della pagina.

### Query processing

**Come scomponi la query naturale in operazioni concrete?**

La domanda diventa un oggetto con cinque campi ([QueryPlan](../src/main/java/com/fisiodesk/smartquery/nlq/QueryPlan.java)): aree anatomiche, migliora sì/no, peggiora sì/no, quanti mesi indietro guardare, ha saltato l'ultimo appuntamento sì/no.

La query target diventa: `LUMBAR`, migliora sì, 3 mesi, ha saltato sì.

A produrlo è l'AI, obbligata a rispondere in un formato fisso (JSON schema generato dal record Java), quindi non può inventare campi o valori fuori elenco. In parallelo la stessa domanda passa dal parser a regole, e i due risultati vengono fusi ([PlanGuardrail](../src/main/java/com/fisiodesk/smartquery/nlq/PlanGuardrail.java)): quello che il parser riconosce con certezza viene rimesso dentro, perché i modelli piccoli a volte perdono un pezzo della domanda; il resto, cioè le parafrasi, resta all'AI. In caso di contraddizione tra "migliorati" e "non migliorati" vince il secondo, che è come si leggono le negazioni.

**Come combini ricerca su dati strutturati + analisi AI sui testi?**

L'analisi AI dei testi è già diventata dato strutturato dentro `note_insights`, quindi la combinazione è una normale sequenza di letture ([SmartQueryService](../src/main/java/com/fisiodesk/smartquery/query/SmartQueryService.java)):

1. prendo le note del periodo (una query, con indice su data, oppure su professionista e data se la ricerca è ristretta a un professionista);
2. tengo solo quelle etichettate con l'area richiesta e le raggruppo per paziente;
3. per ogni paziente sommo i punteggi e controllo la condizione di miglioramento;
4. per chi la supera, leggo l'ultimo appuntamento e controllo se è `no_show`;
5. per chi resta, leggo l'anagrafica e costruisco la risposta.

I filtri più selettivi vengono prima, così le letture del calendario si fanno solo sui pazienti rimasti.

**Come ranki e presenti i risultati finali al dottore?**

Ordinamento per punteggio di miglioramento decrescente, a parità di punteggio per cognome: in cima chi è migliorato di più, che è il paziente per cui vale di più fare una telefonata di recupero.

Per ogni paziente la risposta contiene nome, età, contatti (servono per richiamarlo), data e stato dell'ultimo appuntamento, e le note che hanno determinato il risultato con le frasi decisive evidenziate. Non è solo una lista di nomi: il professionista vede la prova clinica di quello che il sistema afferma.

In più ogni risposta porta un blocco `explain` con come è stata capita la domanda, i tempi di ogni passaggio, e soprattutto **l'elenco dei pazienti scartati con il motivo** ("nessun miglioramento nell'area richiesta, punteggio -8 su 4 note", "ultimo appuntamento completato il 20/12/2024, non saltato"). In ambito clinico chi è stato escluso conta quanto chi è dentro.

### Performance e scalabilità

**Come rispetti il vincolo sotto i 2 secondi con molti utenti concorrenti?**

- Nel percorso della ricerca non ci sono chiamate esterne: solo letture MongoDB su indici, che sul dataset costano qualche decina di millisecondi.
- Le domande già viste non toccano mai l'AI, perché il filtro è salvato in `query_plans` ed è condiviso tra tutte le istanze.
- Se dieci utenti fanno la stessa domanda nuova insieme, parte una sola chiamata all'AI e tutti aspettano quella.
- Le chiamate all'AI hanno un pool ampio (16), così una raffica di domande nuove non si mette in coda.
- La connessione verso il provider è forzata a HTTP/1.1, perché con HTTP/2 il client Java le accodava tutte su una connessione e le chiamate concorrenti diventavano seriali.
- Le domande suggerite dell'interfaccia vengono interpretate già all'avvio, quindi il primo clic non paga niente.

Misurato con `scripts/load-test.py`: 60 utenti concorrenti, massimo 599 ms; 100 utenti concorrenti, massimo 1296 ms; 10 domande mai viste tutte insieme, quindi tutte con l'AI dentro la richiesta, massimo 1350 ms.

**Come riduci i costi delle API AI mantenendo l'accuratezza?**

Le leve sul costo sono quelle già dette: una chiamata per nota, una per domanda distinta, modello economico, freno sulle chiamate dell'indexer.

Sull'accuratezza, la riduzione dei costi porta a usare modelli piccoli, che sbagliano più spesso. La contromisura è la fusione con il dizionario: su un set di 10 domande di controllo il modello locale da 1,5 miliardi di parametri da solo ne interpreta bene 6 (sbaglia "guariti", "sta meglio", una negazione, un'area), con il controllo del dizionario tutte e 10. Con un modello più capace il controllo non toglie niente: con Gemini Flash-Lite le 10 domande erano già giuste da sole.

Sulle note vale lo stesso principio al contrario: il modello decide il punteggio, ma non può cancellare un marcatore esplicito di segno opposto trovato nel testo.

**Come gestisci l'aggiornamento dei dati quando arrivano nuove schede?**

L'indexer gira ogni 30 secondi e confronta le note con quelle già analizzate. Una nota nuova viene analizzata; una nota modificata viene riconosciuta perché l'hash del testo non corrisponde più e viene rianalizzata; una nota invariata viene saltata senza costo. Ogni nota viene salvata appena analizzata, quindi un riavvio a metà non fa ricominciare da capo.

Conseguenza da dichiarare: una nota appena scritta diventa cercabile entro 30 secondi, non nell'istante. Per un caso d'uso di ricerca clinica è accettabile. Il passo successivo, se servisse l'immediatezza, sono i change stream di MongoDB, che notificano la scrittura invece di farla cercare.

---

## 3. Implementazione MVP

Il sistema deve:

| Requisito | Come è realizzato | Come si verifica |
|---|---|---|
| Identificare pazienti con dolore lombare dai testi di valutazioni e trattamenti | etichettatura delle aree su entrambe le collection, con i sinonimi | `./gradlew test` (`HeuristicClinicalTextAnalyzerTest`) e la risposta della query target |
| Analizzare "miglioramento" dai testi di `schede_valutazione` e `diario_trattamenti` | punteggio da -3 a +3 per nota, somma per paziente, negazioni gestite | `HeuristicClinicalTextAnalyzerTest`, `TextAnalysisServiceTest` |
| Filtrare per timeframe "ultimi 3 mesi" | finestra calcolata dalla data di riferimento, estremi inclusi | `SmartQueryServiceTest`, e Francesca Ricci esclusa perché ha solo dati di giugno |
| Identificare chi ha saltato l'ultimo appuntamento in `eventi_calendario` | ultimo evento fino alla data di riferimento con stato `no_show` | `SmartQueryServiceTest`, e Giuseppe Verdi escluso perché si è presentato |
| Presentare i risultati in modo utile al professionista | lista ordinata per miglioramento, con contatti, ultimo appuntamento, note di prova ed esclusi motivati | interfaccia su http://localhost:8080 e blocco `explain` nella risposta |

Verifica automatica end to end: `scripts/smoke-test.sh` controlla che la query target restituisca esattamente Rossi, Bianchi, Romano e Colombo, che i tre casi negativi non compaiano, che la risposta stia sotto i 2 secondi, che il blocco `explain` ci sia, e che ogni professionista veda solo i propri pazienti.

---

## Deliverable richiesti

| Richiesto | Dove |
|---|---|
| Diagramma architettura | nel [README](../README.md#4-come-funziona), versione semplice; versione dettagliata in [DESIGN.md](DESIGN.md#architettura) |
| Codice dell'MVP funzionante | questo repository |
| Docker Compose per setup facile | `docker-compose.yml`, un comando solo, senza chiavi API |
| README con istruzioni di setup | [sezione 2 del README](../README.md#2-come-far-partire-il-sistema) |
| Come far partire il sistema | [sezione 2 del README](../README.md#2-come-far-partire-il-sistema) |
| 2-3 trade-off principali | [sezione 6 del README](../README.md#6-le-scelte-principali-trade-off) |
| Demo della query target funzionante | [sezione 3 del README](../README.md#3-provalo-la-query-target), con screenshot e comando |
| Motivo della scelta del linguaggio | in testa al [README](../README.md): Java e Spring Boot perché è lo stack di FisioDesk e Dottai |

## Cosa non ho fatto, e perché

Per onestà, visto che la consegna parla di MVP e non di sistema pronto alla produzione, i limiti noti sono raccolti in [DESIGN.md](DESIGN.md#limiti-noti): i candidati partono sempre dalle note e non dal calendario, le note cancellate restano indicizzate, una domanda senza filtri riconoscibili restituisce tutti i pazienti del periodo, la negazione sul filtro dell'appuntamento non è gestita, e con il modello locale su CPU l'interpretazione di una domanda nuova supera quasi sempre il tempo massimo. Non ci sono autenticazione né permessi, perché nel prodotto vero arriverebbero dal gestionale.
