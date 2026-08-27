package com.fisiodesk.smartquery.domain;

import java.time.Instant;
import org.bson.types.ObjectId;

/**
 * Common shape shared by schede_valutazione and diario_trattamenti: both are
 * free-text clinical notes tied to a patient and a professional. The analysis
 * pipeline treats them uniformly and only keeps track of the source collection.
 */
public interface ClinicalNote {

    String getId();

    ObjectId getPazienteId();

    Instant getData();

    String getDescrizione();

    ObjectId getProfessionistaId();
}
