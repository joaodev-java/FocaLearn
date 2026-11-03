package com.focaAI.learningService.infraestructure.web;


import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping(path = "/assistant")
@RequiredArgsConstructor

public class AssistantController {



    private final AssistantService assistantService;

    @PostMapping(value = "/desafios", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> desafios(@RequestParam String assunto) {
        return ResponseEntity.ok(assistantService.gerarDesafios(assunto));
    }

}
