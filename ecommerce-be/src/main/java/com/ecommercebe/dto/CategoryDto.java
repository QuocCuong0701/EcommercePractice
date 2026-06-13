package com.ecommercebe.dto;

import lombok.Value;

import java.util.UUID;

@Value
public class CategoryDto {
    UUID id;
    String name;
    String slug;
}
