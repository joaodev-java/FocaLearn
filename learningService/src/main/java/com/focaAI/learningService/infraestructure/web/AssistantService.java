package com.focaAI.learningService.infraestructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.focaAI.learningService.domain.entity.dto.RequestDto;
import com.focaAI.learningService.infraestructure.web.configuration.AiRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AssistantService {

    private static final Set<Integer> RETENTAVEIS = Set.of(429, 500, 503, 504);
    private static final int TENTATIVAS_POR_MODELO = 2;
    private static final long COOLDOWN_MS = 30_000;

    private final Map<String, Long> indisponivelAte = new ConcurrentHashMap<>();

    private static final Logger log =
            LoggerFactory.getLogger(AssistantService.class);
    private final AiRateLimiter aiRateLimiter;

    private final String apiKeyGroq;
    private final String baseUrlGroq;
    private final List<String> modelsGroq;
    private final String apiKeyGemini;
    private final String baseUrlGemini;
    private final List<String> modelsGemini;




    private final RestClient restClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PROMPT_DESAFIOS = """
       Você é um professor que cria desafios educacionais em português do Brasil.

        Gere EXATAMENTE 15 desafios sobre o assunto informado ao final, nem mais nem menos, organizados em 3 módulos de 5 desafios cada. A dificuldade sobe de forma gradual, do primeiro ao último: o módulo 1 é facil, o módulo 2 é medio e o módulo 3 é dificil. Dentro de cada módulo, o quinto desafio fecha o bloco e é o mais difícil dele. O primeiro desafio responde diretamente à dúvida principal do aluno. Cada módulo cobre um bloco de conceitos diferente e coerente, sem repetir conceitos de outro módulo. Evite perguntas repetidas e informações incorretas. Se o assunto for vago, siga a interpretação mais comum dele.

        ORDEM E TIPOS (siga a tabela, desafio por desafio):
        Assuntos de programação:
        Módulo 1 (facil): 1 escolha | 2 ordenar | 3 complete | 4 escolha | 5 bug
        Módulo 2 (medio): 6 escolha | 7 ordenar | 8 complete | 9 bug | 10 escolha
        Módulo 3 (dificil): 11 escolha | 12 ordenar | 13 complete | 14 bug | 15 central-investigacao
        Outros assuntos (sem código): 15 "escolha", sendo 1 a 5 facil, 6 a 10 medio e 11 a 15 dificil, em ordem crescente.
        A dificuldade vale também para o conteúdo do tipo: no bug facil o erro é evidente; no bug dificil é sutil e exige leitura atenta. No ordenar facil as linhas são poucas e diretas; no dificil há mais passos encadeados.

        REGRAS:
        - Retorne somente JSON válido e compacto (sem indentação), sem Markdown ou texto adicional.
        - Use uma trilha com título curto.
        - "modulos" tem exatamente 3 objetos, em ordem, com "titulo" (no máximo 4 palavras, nomeia o bloco de conceitos sem entregar respostas) e "descricao" (1 frase curta dizendo o que o aluno vai dominar).
        - "desafios" é uma lista única com os 15 itens: os desafios 1 a 5 pertencem ao módulo 1, 6 a 10 ao módulo 2 e 11 a 15 ao módulo 3.
        
        REGRAS OBRIGATÓRIAS PARA TODOS OS 15 DESAFIOS:
            
             - TODOS os desafios, independentemente do tipo, devem conter:
               "tipo", "titulo", "dificuldade", "dica" e "explicacao".
            
             - Nunca omita esses campos.
             - Nunca utilize null ou strings vazias nesses campos.
             - "dificuldade" deve ser EXATAMENTE:
               "facil", "medio" ou "dificil".
            
             - Cada desafio deve ser um objeto JSON independente.
             - Nunca repita nomes de propriedades dentro do mesmo objeto.
             - Nunca omita os dois-pontos após nomes de propriedades.
             - Não gere comentários dentro do JSON.
             - Não acrescente campos que não foram solicitados.
        - "titulo": no máximo 5 palavras. "dica": 1 frase que orienta sem revelar a resposta. "explicacao": no máximo 2 frases.
        - Não invente fatos nem repita o mesmo conceito.
        - "tipo" deve ser exatamente "escolha", "ordenar", "complete", "bug" ou "central-investigacao".
        

        ESCOLHA:
        Campos: "pergunta", "codigo", "opcoes", "correta".
        - Exatamente 4 opções plausíveis, de tamanho semelhante, apenas uma correta.
        - "correta": índice inteiro de 0 a 3. Varie a posição da correta ao longo dos 15 desafios.
        - Não use "todas as anteriores" ou "nenhuma das anteriores".
        - "codigo" é vazio, exceto quando um trecho for necessário.

        ORDENAR (somente programação):
        Campos: "enunciado", "linhas".
        - De 4 a 8 linhas, com indentação em espaços.
        - "linhas" está na ordem correta e só pode existir UMA sequência válida: cada linha depende da anterior. Não use classes com campos, construtor e métodos, porque a ordem entre eles é livre.
        - As linhas contêm somente o código, sem numeração, letras, marcadores ou qualquer prefixo (nada de "1.", "a)" ou "-"). O app embaralha e numera as posições.
        - "linhas" deve possuir EXATAMENTE entre 4 e 8 elementos.
        - Cada elemento representa uma única instrução de código.
        - Não divida uma instrução em várias linhas desnecessariamente.
        
        COMPLETE (somente programação):
        Campos: "pergunta", "codigo", "opcoes", "correta".
        - "codigo" tem de 4 a 8 linhas, com indentação em espaços e uma quebra de linha JSON simples entre elas.
        - Exatamente UMA lacuna, marcada com ___ (três underscores), no lugar de um trecho curto: palavra-chave, nome, operador ou expressão.
        - "opcoes": 4 itens curtos e de tamanho parecido; só um completa corretamente. Os 3 erros são confusões reais de iniciantes.
        - "correta": índice de 0 a 3. Varie a posição.
        - "pergunta" diz o que o código deve fazer, em uma frase.
        - O trecho que falta é inferível pelo contexto.
        - "titulo" é obrigatório, mesmo neste tipo.
        - O título deve descrever brevemente o objetivo do desafio.
        - Nunca omita "titulo", "dica" ou "explicacao".

        BUG (somente programação):
        Campos: "linhas", "linhaComBug", "correcao", "erradas".
        - "linhas": de 5 a 8 linhas, uma por item, com indentação em espaços.
        - Conte as linhas antes de retornar o JSON.
        - "linhas" deve conter EXATAMENTE entre 5 e 8 elementos.
        - Não considere linhas vazias como elementos.
        - Nunca ultrapasse 8 elementos.
        - Exatamente UM erro típico de iniciante (limite de laço, "=" no lugar de "==", operador ou variável errados). Evite ponto e vírgula e erro de digitação.
        - "linhaComBug": índice (a partir de 0) da linha errada. As demais estão corretas.
        - "correcao": a linha errada reescrita corretamente, com a mesma indentação.
        - "erradas": 2 linhas plausíveis, parecidas com "correcao", que NÃO corrigem o erro.

        CENTRAL-INVESTIGACAO (somente programação):
        Campos: "pergunta", "pistas", "causas", "correta".
        - "pergunta" descreve um incidente ou sintoma a investigar, sem revelar a causa.
        - "pistas": 4 ou 5 objetos com "id" (único), "nome" (curto), "tipo" ("log", "codigo", "config" ou "texto"), "conteudo" (evidência concreta, no máximo 4 linhas curtas) e "relevante" (true ou false).
        - Pelo menos 2 pistas relevantes e pelo menos 1 de despiste. O despiste parece plausível, mas não contradiz os fatos.
        - "causas": exatamente 4 hipóteses plausíveis; só uma explica o conjunto das evidências.
        - "correta": índice de 0 a 3 da hipótese certa. Varie a posição.
        - Não revele a causa no título, na pergunta nem nos nomes das pistas.
        - "explicacao" justifica a causa usando as pistas.
        - "dica" é obrigatória e deve orientar a investigação sem revelar a causa.
        - "explicacao" deve justificar a hipótese correta utilizando as pistas.
        - "titulo" deve ser curto e não revelar a causa.

        FORMATO DA RESPOSTA:
        {"trilha":"Título do tema","modulos":[{"titulo":"Título do módulo","descricao":"O que o aluno vai dominar"}],"desafios":[{"tipo":"escolha","titulo":"Título","dificuldade":"facil","pergunta":"Pergunta","codigo":"","opcoes":["A","B","C","D"],"correta":0,"dica":"Dica","explicacao":"Explicação"}]}
        O exemplo mostra só a estrutura: "modulos" completo com os 3 módulos e "desafios" com um item de amostra. Gere os 15 desafios completos, cada um com os campos do seu tipo, sem campos extras.
        VALIDAÇÃO FINAL OBRIGATÓRIA:
            
        Antes de retornar a resposta:
        
        1. Verifique se o JSON pode ser interpretado por um parser JSON padrão.
        2. Verifique se existem exatamente 15 desafios.
        3. Verifique se todos possuem titulo, tipo, dificuldade, dica e explicacao.
        4. Ordenar: entre 4 e 8 elementos no array linhas.
        5. Bug: entre 5 e 8 elementos no array linhas.
        6. Complete: exatamente uma ocorrência de ___ no código.
        7. Não utilize arrays de linhas quando o campo codigo deve ser string.         8. Não coloque vírgulas entre o nome da propriedade e os dois-pontos.
        9. Retorne somente o JSON.""";



    public AssistantService(
                            AiRateLimiter aiRateLimiter,
                            @Value("${groq.api.key}") String apiKeyGroq,
                            @Value("${groq.baseurl}") String baseUrlGroq,
                            @Value("${groq.models}") List<String> modelsGroq,
                            @Value("${googleai.api.key}") String apiKeyGemini,
                            @Value("${gemini.baseurl}") String baseUrlGemini,
                            @Value("${gemini.models}") List<String> modelsGemini
                            //@Value("${anthropic.api-key}") String anthropicKey,
                            //@Value("${anthropic.model}") String anthropicModel
                            ) {
        this.aiRateLimiter = aiRateLimiter;
        this.apiKeyGroq = apiKeyGroq;
        this.baseUrlGroq = baseUrlGroq;
        this.modelsGroq = modelsGroq;

        this.apiKeyGemini = apiKeyGemini;
        this.baseUrlGemini = baseUrlGemini;
        this.modelsGemini = modelsGemini;

        //this.anthropicKey = anthropicKey;
        //this.anthropicModel = anthropicModel;x

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }


    /*public String callChatCliente(String prompt, String message) {

        String promptFull = prompt.concat(message);
        var requestBody = new RequestDto.RequestBody(
                List.of(
                        new RequestDto.Content(
                                List.of(
                                        new RequestDto.Part(promptFull)
                                )
                        )
                )
        );

        RuntimeException ultimoErro = null;

        for (String model : models) {
            if (!disponivel(model)) continue;
            for (int tentativa = 0; tentativa < TENTATIVAS_POR_MODELO; tentativa++) {
                try {
                    return chamarGemini(model, requestBody);
                } catch (HttpStatusCodeException e) {
                    int status = e.getStatusCode().value();
                    ultimoErro = e;
                    if (status == 404) {              // modelo indisponível: próximo modelo
                        marcarIndisponivel(model);
                        break;
                    }
                    if (!RETENTAVEIS.contains(status)) {
                        throw new RuntimeException("Error calling Gemini API", e);
                    }
                    ultimoErro = e;
                    marcarIndisponivel(model);
                } catch (RestClientException e) {     // timeout, conexão, leitura
                    ultimoErro = e;
                    marcarIndisponivel(model);
                    break;                            // timeout: não adianta repetir, vai para o próximo modelo
                }
                esperar(tentativa);
            }
        }
        if(ultimoErro == null){
            // Todos os modelos estão indisponíveis, chama o primeiro novamente
            return chamarGemini(models.get(0), requestBody);
        }
        throw new RuntimeException("All Gemini models failed", ultimoErro);

        /*try {
            return chamarClaude(promptFull);
        } catch (RestClientException e) {
            throw new RuntimeException("All providers failed", e);
        }
    }*/

    // CallChat Groq

    public String callChatCliente(String prompt, String message) {
        String promptFull = prompt
                + "\n\nAssunto do aluno: "
                + message
                + "\n\nGere os desafios sobre esse assunto seguindo "
                + "todas as regras e o formato JSON especificados.";

        try {
            return tentarGroq(promptFull);
        } catch (RuntimeException e) {
            return tentarGemini(promptFull);
        }
    }

    private String tentarGroq(String prompt) {
        RuntimeException ultimoErro = null;

        for (String model : modelsGroq) {
            if (!disponivel(model)) {
                continue;
            }

            if (!aiRateLimiter.podeUsarGroq()) {
                break;
            }

            try {
                aiRateLimiter.registrarRequisicao();
                return chamarGroq(prompt, model);

            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();

                if (status == 429 || status >= 500 && status <= 504) {
                    ultimoErro = new RuntimeException(
                            "Falha no modelo Groq: " + model, e
                    );
                    marcarIndisponivel(model);
                    continue;
                }

                throw new RuntimeException(
                        "Erro HTTP do Groq: " + status, e
                );

            } catch (RestClientException e) {
                ultimoErro = new RuntimeException(
                        "Falha de conexão com o Groq: " + model, e
                );
                marcarIndisponivel(model);

            } catch (JsonProcessingException e) {
                ultimoErro = new RuntimeException(
                        "JSON inválido retornado pelo Groq", e
                );
            }
        }

        throw new RuntimeException(
                "Nenhum modelo Groq disponível.", ultimoErro
        );
    }

    private String tentarGemini(String prompt) {
        var requestBody = new RequestDto.RequestBody(
                List.of(
                        new RequestDto.Content(
                                List.of(
                                        new RequestDto.Part(prompt)
                                )
                        )
                )
        );

        RuntimeException ultimoErro = null;

        for (String model : modelsGemini) {
            if (!disponivel(model)) continue;
            for (int tentativa = 0; tentativa < TENTATIVAS_POR_MODELO; tentativa++) {
                try {
                    return chamarGemini(model, requestBody);
                } catch (HttpStatusCodeException e) {
                    int status = e.getStatusCode().value();
                    ultimoErro = e;
                    //Se a chama for barrada, marca indisponível e passa para o próximo modelo
                    if (status == 429 || (status >= 500 && status <= 504)) {
                        ultimoErro = new RuntimeException(
                                "Falha no modelo Gemini: " + model, e
                        );
                        marcarIndisponivel(model);
                        break;
                    }
                    if (!RETENTAVEIS.contains(status)) {
                        throw new RuntimeException("Error calling Gemini API", e);
                    }
                    ultimoErro = e;
                    marcarIndisponivel(model);
                } catch (RestClientException e) {     // timeout, conexão, leitura
                    ultimoErro = e;
                    marcarIndisponivel(model);
                    break;                            // timeout: não adianta repetir, vai para o próximo modelo
                }
                esperar(tentativa);
            }
        }
        if(ultimoErro == null){
            // Todos os modelos estão indisponíveis, chama o primeiro novamente
            return chamarGemini(modelsGemini.get(0), requestBody);
        }
        throw new RuntimeException("All Gemini models failed", ultimoErro);

    }





    public String gerarDesafios(String assunto) {

        String promptAtual = PROMPT_DESAFIOS;

        for (int tentativa = 1; tentativa <= 2; tentativa++) {
            try {
                String resposta = callChatCliente(promptAtual, assunto);

                log.info("RESPOSTA BRUTA DA IA: {}", resposta);

                String json = limparJson(resposta);

                log.info("JSON APÓS LIMPEZA: {}", json);

                JsonNode raiz;

                // 1. Tenta interpretar o JSON
                try {
                    raiz = mapper.readTree(json);
                } catch (JsonProcessingException e) {

                    log.warn(
                            "JSON inválido na tentativa {}: {}",
                            tentativa,
                            e.getOriginalMessage()
                    );

                    promptAtual = PROMPT_DESAFIOS
                            + "\n\nATENÇÃO: A geração anterior produziu JSON inválido. "
                            + "Revise rigorosamente a sintaxe JSON. "
                            + "Toda propriedade deve seguir o formato \"nome\": valor. "
                            + "Não omita dois-pontos, não repita propriedades "
                            + "e não gere vírgulas indevidas. "
                            + "Retorne somente um JSON válido.";

                    continue;
                }

                // 2. Verifica a estrutura principal
                JsonNode desafios = raiz.path("desafios");

                if (!raiz.isObject() || !desafios.isArray()) {

                    log.warn(
                            "Estrutura inválida na tentativa {}. "
                                    + "Esperado um objeto contendo um array 'desafios'.",
                            tentativa
                    );

                    promptAtual = PROMPT_DESAFIOS
                            + "\n\nATENÇÃO: A resposta anterior não respeitou "
                            + "a estrutura JSON exigida. "
                            + "Retorne um objeto com a propriedade 'desafios' "
                            + "contendo um array de desafios.";

                    continue;
                }

                log.info("QUANTIDADE DE DESAFIOS: {}", desafios.size());

                // 3. Exibe os desafios recebidos
                for (JsonNode desafio : desafios) {

                    String tipo = desafio.path("tipo").asText("");

                    log.info("DESAFIO: {}", desafio);
                    log.info("TIPO: {}", tipo);

                    switch (tipo) {
                        case "escolha" -> {
                            log.info("OPÇÕES: {}", desafio.path("opcoes"));
                            log.info("CORRETA: {}", desafio.path("correta"));
                        }

                        case "central-investigacao" -> {
                            log.info("PISTAS: {}", desafio.path("pistas"));
                            log.info("CAUSAS: {}", desafio.path("causas"));
                            log.info("CORRETA: {}", desafio.path("correta"));
                        }

                        case "complete", "ordenar", "bug" ->
                                log.info("DADOS DO DESAFIO: {}", desafio);

                        default ->
                                log.warn("Tipo de desafio desconhecido: {}", tipo);
                    }
                }

                // 4. Valida e repara individualmente os desafios

                ArrayNode validos = mapper.createArrayNode();
                if (desafios.isArray()) {
                    for (JsonNode original : desafios) {
                        if (!original.isObject()) continue;
                        ObjectNode d = ((ObjectNode) original).deepCopy();
                        reparar(d);
                        String motivo = motivoInvalido(d);
                        if (motivo == null) {
                            validos.add(d);
                        } else {
                            String bruto = d.toString();
                            log.warn("Desafio descartado [{}] '{}': {} | {}",
                                    d.path("tipo").asText(), d.path("titulo").asText(), motivo,
                                    bruto.substring(0, Math.min(bruto.length(), 700)));
                        }
                    }
                }
                // 5. Retorna se atingiu o mínimo necessário
                if (validos.size() >= MINIMO) {

                    ((ObjectNode) raiz).set("desafios", validos);

                    log.info(
                            "Geração concluída. {} desafios válidos de {} recebidos.",
                            validos.size(),
                            desafios.size()
                    );

                    return embaralhar(raiz);
                }

                log.warn(
                        "Tentativa {}: somente {} desafios válidos de {}. Mínimo exigido: {}.",
                        tentativa,
                        validos.size(),
                        desafios.size(),
                        MINIMO
                );

                // 6. Prepara uma nova tentativa com instruções adicionais
                promptAtual = PROMPT_DESAFIOS
                        + "\n\nATENÇÃO: A geração anterior apresentou desafios "
                        + "que não passaram pela validação. "
                        + "Revise cuidadosamente todos os requisitos de cada tipo. "
                        + "Não ultrapasse os limites de linhas dos códigos. "
                        + "Em desafios complete, utilize exatamente uma lacuna. "
                        + "Todos os desafios devem possuir titulo, dica e explicacao. "
                        + "Confira a estrutura e a sintaxe do JSON antes de responder. "
                        + "Retorne somente o JSON solicitado.";

            } catch (Exception e) {

                log.error(
                        "Falha na tentativa {} ao gerar desafios",
                        tentativa,
                        e
                );
            }
        }

        throw new RuntimeException(
                "Não foi possível gerar desafios após 2 tentativas"
        );
    }

    /** Conserta o que dá para consertar antes de validar. */
    private void reparar(ObjectNode d) {
        // dica ausente não torna o desafio injogável
        if (d.path("dica").asText().isBlank()) {
            d.put("dica", "Releia o enunciado com atenção e elimine o que não faz sentido.");
        }

        // "linhas" como texto único (ou 1 item com quebras de linha): separa por linha
        JsonNode linhas = d.path("linhas");
        if (linhas.isTextual()) {
            d.set("linhas", paraArray(linhas.asText()));
        } else if (linhas.isArray() && linhas.size() == 1) {
            String unica = linhas.get(0).asText();
            if (unica.contains("\n") || unica.contains("\\n")) {
                d.set("linhas", paraArray(unica));
            }
        }

        // bug: tira das "erradas" o que for igual à linha com bug, à correção ou repetido
        if ("bug".equals(d.path("tipo").asText())
                && d.path("linhas").isArray()
                && d.path("erradas").isArray()
                && d.path("linhaComBug").canConvertToInt()) {
            int i = d.path("linhaComBug").asInt(-1);
            if (i >= 0 && i < d.path("linhas").size()) {
                String original = d.path("linhas").get(i).asText().trim();
                String correcao = d.path("correcao").asText("").trim();
                Set<String> vistos = new HashSet<>(Arrays.asList(original, correcao));
                ArrayNode limpas = mapper.createArrayNode();
                for (JsonNode e : d.path("erradas")) {
                    String t = e.asText().trim();
                    if (!t.isBlank() && vistos.add(t)) limpas.add(e.asText());
                }
                while (limpas.size() > 2) limpas.remove(limpas.size() - 1);
                d.set("erradas", limpas);
            }
        }
    }

    private ArrayNode paraArray(String texto) {
        List<String> lista = new ArrayList<>();
        for (String p : texto.replace("\\n", "\n").split("\\R", -1)) lista.add(p.stripTrailing());
        while (!lista.isEmpty() && lista.get(lista.size() - 1).isBlank()) lista.remove(lista.size() - 1);
        ArrayNode a = mapper.createArrayNode();
        lista.forEach(a::add);
        return a;
    }


    private boolean invalidar(String motivo) {
        log.warn("Desafio rejeitado: {}", motivo);
        return false;
    }

    private static final int MINIMO = 9;

    private String motivoInvalido(JsonNode d) {
        String tipo = d.path("tipo").asText("");

        if (d.path("titulo").asText().isBlank()) return "sem título";
        if (d.path("dica").asText().isBlank()) return "sem dica";
        if (d.path("explicacao").asText().isBlank()) return "sem explicação";

        String dificuldade = d.path("dificuldade").asText("");

        if (!Set.of("facil", "medio", "dificil").contains(dificuldade)) {
            return "dificuldade inválida: " + dificuldade;
        }

        switch (tipo) {
            case "escolha" -> {
                if (d.path("pergunta").asText().isBlank()) return "sem pergunta";
                return motivoOpcoes(d);
            }
            case "complete" -> {
                if (d.path("pergunta").asText().isBlank()) return "sem pergunta";
                String codigo = d.path("codigo").asText();
                long lacunas = java.util.regex.Pattern.compile("_{3,}").matcher(codigo).results().count();
                if (lacunas != 1) return "o código precisa ter exatamente 1 lacuna (___), tem " + lacunas;
                return motivoOpcoes(d);
            }
            case "ordenar" -> {
                String enunciado = d.path("enunciado")
                        .asText(d.path("pergunta").asText());

                if (enunciado.isBlank()) return "sem enunciado";

                JsonNode linhas = d.path("linhas");

                if (!linhas.isArray() || linhas.size() < 4 || linhas.size() > 8) {
                    return "ordenar deve ter de 4 a 8 linhas";
                }

                return null;
            }
            case "bug" -> {
                JsonNode linhas = d.path("linhas");
                JsonNode erradas = d.path("erradas");
                String correcao = d.path("correcao").asText("").trim();

                if (!linhas.isArray() || linhas.size() < 5 || linhas.size() > 8) {
                    return "bug deve ter de 5 a 8 linhas";
                }

                if (!d.path("linhaComBug").canConvertToInt()) {
                    return "linhaComBug não é número";
                }

                int indice = d.path("linhaComBug").asInt(-1);

                if (indice < 0 || indice >= linhas.size()) {
                    return "linhaComBug fora do intervalo";
                }

                if (correcao.isBlank()) return "sem correcao";

                if (!erradas.isArray() || erradas.size() != 2) {
                    return "erradas deve ter 2 itens";
                }

                String original = linhas.get(indice).asText().trim();

                if (correcao.equals(original)) {
                    return "correcao igual à linha com bug";
                }

                Set<String> distintas = new HashSet<>();
                distintas.add(correcao);

                for (JsonNode e : erradas) {
                    String t = e.asText().trim();

                    if (t.equals(original)) {
                        return "uma errada é a própria linha com bug";
                    }

                    if (!distintas.add(t)) {
                        return "opções de correção repetidas";
                    }
                }

                return null;
            }
            case "central-investigacao" -> {
                JsonNode pistas = d.path("pistas");
                JsonNode causas = d.path("causas");

                if (d.path("pergunta").asText().isBlank()) return "sem pergunta";
                if (!pistas.isArray() || pistas.size() < 4 || pistas.size() > 6) return "pistas deve ter de 4 a 6 itens";
                if (!causas.isArray() || causas.size() != 4) return "causas deve ter 4 itens";
                if (!d.path("correta").canConvertToInt()) return "correta não é número";
                int correta = d.path("correta").asInt(-1);
                if (correta < 0 || correta >= causas.size()) return "correta fora do intervalo";

                Set<String> ids = new HashSet<>();
                int relevantes = 0;
                for (JsonNode p : pistas) {
                    if (p.path("id").asText().isBlank() || !ids.add(p.path("id").asText())) return "pista sem id ou com id repetido";
                    if (p.path("nome").asText().isBlank() || p.path("conteudo").asText().isBlank()) return "pista sem nome ou conteúdo";
                    if (!p.path("relevante").isBoolean()) return "pista sem 'relevante' booleano";
                    if (p.path("relevante").asBoolean()) relevantes++;
                }
                if (relevantes < 2) return "menos de 2 pistas relevantes";
                if (relevantes == pistas.size()) return "nenhuma pista de despiste";
                return null;
            }
            default -> {
                return "tipo desconhecido: " + tipo;
            }
        }
    }



    private String motivoOpcoes(JsonNode d) {
        JsonNode opcoes = d.path("opcoes");
        if (!opcoes.isArray() || opcoes.size() != 4) return "opcoes deve ter 4 itens";
        if (!d.path("correta").canConvertToInt()) return "correta não é número";
        int correta = d.path("correta").asInt(-1);
        if (correta < 0 || correta >= 4) return "correta fora do intervalo";

        Set<String> textos = new HashSet<>();
        for (JsonNode o : opcoes) {
            if (o.asText().isBlank() || !textos.add(o.asText().trim())) return "opções vazias ou repetidas";
        }
        return null;
    }




    private String embaralhar(JsonNode raiz) throws JsonProcessingException {
        ArrayNode desafios = (ArrayNode) raiz.path("desafios");

        for (int j = 0; j < desafios.size(); j++) {
            ObjectNode desafio = (ObjectNode) desafios.get(j);

            // Embaralha somente desafios com alternativas
            if (!desafio.path("opcoes").isArray()
                    || !desafio.path("correta").canConvertToInt()) {
                continue;
            }

            ArrayNode opcoesOriginais = (ArrayNode) desafio.path("opcoes");

            if (opcoesOriginais.size() != 4) {
                continue;
            }

            int certa = desafio.path("correta").asInt();

            if (certa < 0 || certa >= opcoesOriginais.size()) {
                continue;
            }

            List<Integer> ordem = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(ordem);

            ArrayNode novasOpcoes = mapper.createArrayNode();
            int novaCerta = -1;

            for (int i = 0; i < ordem.size(); i++) {
                int origem = ordem.get(i);

                novasOpcoes.add(opcoesOriginais.get(origem));

                if (origem == certa) {
                    novaCerta = i;
                }
            }

            desafio.set("opcoes", novasOpcoes);
            desafio.put("correta", novaCerta);
        }

        return mapper.writeValueAsString(raiz);
    }


    private String limparJson(String texto) {
        int ini = texto.indexOf('{');
        int fim = texto.lastIndexOf('}');
        if (ini < 0 || fim <= ini) return texto;
        return texto.substring(ini, fim + 1);
    }

    private String chamarGroq(String prompt, String model) throws JsonProcessingException {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of(
                                "role", "user",
                                "content", prompt
                        )
                ),
                "temperature", 0.2,
                "max_completion_tokens", 8500,
                "reasoning_effort", "low"
        );

        String response = restClient
                .post()
                .uri(baseUrlGroq + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKeyGroq)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        JsonNode raiz = mapper.readTree(response);
        JsonNode uso = raiz.path("usage");
        log.info("TOKENS modelo={} entrada={} saida={} raciocinio={} total={} motivo={}",
                model,
                uso.path("prompt_tokens").asInt(),
                uso.path("completion_tokens").asInt(),
                uso.path("completion_tokens_details").path("reasoning_tokens").asInt(),
                uso.path("total_tokens").asInt(),
                raiz.at("/choices/0/finish_reason").asText());


        String finishReason = raiz
                .at("/choices/0/finish_reason")
                .asText();

        if ("length".equals(finishReason)) {
            throw new RuntimeException(
                    "A resposta da IA atingiu o limite de tokens."
            );
        }

        return extrairTexto(
                response,
                "/choices/0/message/content"
        );

    }

    private String chamarGemini(String model, RequestDto.RequestBody body) {
        String json = restClient
                .post()
                .uri(baseUrlGemini + "/models/{model}:generateContent", model)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-goog-api-key", apiKeyGemini)
                .header("Api-Revision", "2026-05-20")
                .body(body)
                .retrieve()
                .body(String.class);

        return extrairTexto(json, "/candidates/0/content/parts/0/text");
    }

    //Fallback (claudeAI)
    /* private String chamarClaude(String prompt) {
        Map<String, Object> body = Map.of(
                "model", anthropicModel,
                "max_tokens", 2048,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        String json = restClient
                .post()
                .uri("https://api.anthropic.com/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", anthropicKey)
                .header("anthropic-version", "2023-06-01")
                .body(body)
                .retrieve()
                .body(String.class);

        return extrairTexto(json, "/content/0/text");
    }*/

    private void esperar(int tentativa) {
        try {
            Thread.sleep((long) (Math.pow(2, tentativa) * 1000 + Math.random() * 500));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting to retry", e);
        }
    }

    private boolean disponivel(String model) {
        return indisponivelAte.getOrDefault(model, 0L) < System.currentTimeMillis();
    }

    private void marcarIndisponivel(String model) {
        indisponivelAte.put(model, System.currentTimeMillis() + COOLDOWN_MS);
    }

    private String extrairTexto(String json, String caminho) {
        try {
            String texto = mapper.readTree(json).at(caminho).asText();
            if (texto.isBlank()) {
                throw new RuntimeException("Resposta sem texto: " + json);
            }
            return texto;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Resposta inválida da API", e);
        }
    }
}
