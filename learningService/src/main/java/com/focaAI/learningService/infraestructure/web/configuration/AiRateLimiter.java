package com.focaAI.learningService.infraestructure.web.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

@Component
public class AiRateLimiter {

    private final Deque<Instant> requisicoes = new ArrayDeque<>();
    private final int limitePorMinuto;

    public AiRateLimiter(
            @Value("${groq.rate-limit.per-minute}") int limitePorMinuto) {
        this.limitePorMinuto = limitePorMinuto;
    }

    public synchronized boolean podeUsarGroq() {
        Instant agora = Instant.now();
        Instant limite = agora.minusSeconds(60);

        while (!requisicoes.isEmpty()
                && !requisicoes.peekFirst().isAfter(limite)) {
            requisicoes.pollFirst();
        }

        return requisicoes.size() < limitePorMinuto;
    }

    public synchronized void registrarRequisicao() {
        requisicoes.addLast(Instant.now());
    }
}
