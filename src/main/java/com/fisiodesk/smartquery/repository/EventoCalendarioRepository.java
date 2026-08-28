package com.fisiodesk.smartquery.repository;

import com.fisiodesk.smartquery.domain.EventoCalendario;
import java.time.Instant;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventoCalendarioRepository extends MongoRepository<EventoCalendario, String> {

    /** Latest event of a patient up to the reference date, any state: its state tells whether the last appointment was skipped. */
    Optional<EventoCalendario> findFirstByPazienteIdAndDataLessThanEqualOrderByDataDesc(
            ObjectId pazienteId, Instant reference);

    /** Same, restricted to one professional: with scoping, a colleague's appointment is not "the last one". */
    Optional<EventoCalendario> findFirstByPazienteIdAndProfessionistaIdAndDataLessThanEqualOrderByDataDesc(
            ObjectId pazienteId, ObjectId professionistaId, Instant reference);

    /** Most recent event overall, used as the default reference date for the demo dataset. */
    Optional<EventoCalendario> findFirstByOrderByDataDesc();
}
