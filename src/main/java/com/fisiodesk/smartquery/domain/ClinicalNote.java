package com.fisiodesk.smartquery.domain;

import java.time.Instant;
import org.bson.types.ObjectId;

/** Common shape of schede_valutazione and diario_trattamenti. */
public interface ClinicalNote {

    String getId();

    ObjectId getPazienteId();

    Instant getData();

    String getDescrizione();

    ObjectId getProfessionistaId();
}
