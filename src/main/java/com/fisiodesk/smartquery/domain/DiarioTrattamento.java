package com.fisiodesk.smartquery.domain;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("diario_trattamenti")
public class DiarioTrattamento implements ClinicalNote {

    @Id
    private String id;

    @Field("paziente_id")
    private ObjectId pazienteId;

    private Instant data;
    private String descrizione;

    @Field("professionista_id")
    private ObjectId professionistaId;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public ObjectId getPazienteId() {
        return pazienteId;
    }

    @Override
    public Instant getData() {
        return data;
    }

    @Override
    public String getDescrizione() {
        return descrizione;
    }

    @Override
    public ObjectId getProfessionistaId() {
        return professionistaId;
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

    public void setDescrizione(String descrizione) {
        this.descrizione = descrizione;
    }

    public void setProfessionistaId(ObjectId professionistaId) {
        this.professionistaId = professionistaId;
    }
}
