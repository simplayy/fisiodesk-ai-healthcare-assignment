package com.fisiodesk.smartquery.domain;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("eventi_calendario")
public class EventoCalendario {

    public static final String STATO_NO_SHOW = "no_show";
    public static final String STATO_CANCELLATO = "cancellato";

    @Id
    private String id;

    @Field("paziente_id")
    private ObjectId pazienteId;

    private Instant data;
    private String stato;
    private Integer durata;

    @Field("professionista_id")
    private ObjectId professionistaId;

    private String note;

    public String getId() {
        return id;
    }

    public ObjectId getPazienteId() {
        return pazienteId;
    }

    public Instant getData() {
        return data;
    }

    public String getStato() {
        return stato;
    }

    public Integer getDurata() {
        return durata;
    }

    public ObjectId getProfessionistaId() {
        return professionistaId;
    }

    public String getNote() {
        return note;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setPazienteId(ObjectId pazienteId) {
        this.pazienteId = pazienteId;
    }

    public void setData(Instant data) {
        this.data = data;
    }

    public void setStato(String stato) {
        this.stato = stato;
    }

    public void setDurata(Integer durata) {
        this.durata = durata;
    }

    public void setProfessionistaId(ObjectId professionistaId) {
        this.professionistaId = professionistaId;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
