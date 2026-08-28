package com.fisiodesk.smartquery.web;

import com.fisiodesk.smartquery.query.QueryDtos.QueryResponse;
import com.fisiodesk.smartquery.query.QueryFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryFacade queryFacade;

    public QueryController(QueryFacade queryFacade) {
        this.queryFacade = queryFacade;
    }

    public record QueryRequest(
            @NotBlank(message = "La domanda non puo' essere vuota")
            @Size(max = 500, message = "La domanda non puo' superare i 500 caratteri")
            String question,
            /** Optional ISO date (yyyy-MM-dd) used as "today" for relative windows. */
            String referenceDate,
            /** Optional scope: only this professional's patients (ObjectId hex). */
            String professionistaId) {
    }

    @PostMapping("/query")
    public QueryResponse query(@Valid @RequestBody QueryRequest request) {
        return queryFacade.answer(request.question(), request.referenceDate(), request.professionistaId());
    }
}
