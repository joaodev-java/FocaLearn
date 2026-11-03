package com.focaAI.learningService.domain.entity.dto;


import java.util.List;

public class RequestDto {



    public record RequestBody(List<Content> contents){}
    public record Content(List<Part> parts) {}
    public record Part (String text){}

}
