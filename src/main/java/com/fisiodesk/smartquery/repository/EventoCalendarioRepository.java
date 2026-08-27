package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.EventoCalendario;
import java.time.Instant;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventoCalendarioRepository extends MongoRepository<EventoCalendario, String> {

    /**
     * Latest calendar event for a patient up to the reference date. "Last
     * appointment" deliberately includes every state: a patient whose most
     * recent event was cancelled or is still "prenotato" did not skip it, so
     * checking the state of this single event answers the question.
     */
    Optional<EventoCalendario> findFirstByPazienteIdAndDataLessThanEqualOrderByDataDesc(
            ObjectId pazienteId, Instant reference);

    /** Most recent event overall, used as the default reference date for the demo dataset. */
    Optional<EventoCalendario> findFirstByOrderByDataDesc();
}
