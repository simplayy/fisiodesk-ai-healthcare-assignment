package com.fisiodesk.smartquery.domain;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Document("pazienti")
public class Paziente {

    @Id
    private String id;
    private String nome;
    private String cognome;
    private Integer eta;
    private String telefono;
    private String email;

    @Field("professionista_principale")
    private ObjectId professionistaPrincipale;

    @Field("data_registrazione")
    private Instant dataRegistrazione;

    private String stato;

    public String getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getCognome() {
        return cognome;
    }

    public Integer getEta() {
        return eta;
    }

    public String getTelefono() {
        return telefono;
    }

    public String getEmail() {
        return email;
    }

    public ObjectId getProfessionistaPrincipale() {
        return professionistaPrincipale;
    }

    public Instant getDataRegistrazione() {
        return dataRegistrazione;
    }

    public String getStato() {
        return stato;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public void setCognome(String cognome) {
        this.cognome = cognome;
    }

    public void setEta(Integer eta) {
        this.eta = eta;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setProfessionistaPrincipale(ObjectId professionistaPrincipale) {
        this.professionistaPrincipale = professionistaPrincipale;
    }

    public void setDataRegistrazione(Instant dataRegistrazione) {
        this.dataRegistrazione = dataRegistrazione;
    }

    public void setStato(String stato) {
        this.stato = stato;
    }
}
