package com.vojtechruzicka.lights.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads/saves {@link AppConfig} as JSON under {@code config/config.json}. */
public final class ConfigStore {

    private static final Path DIR = Path.of("config");
    private static final Path FILE = DIR.resolve("config.json");

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public boolean exists() {
        return Files.exists(FILE);
    }

    public Path path() {
        return FILE;
    }

    public AppConfig loadOrDefaults() throws IOException {
        if (!Files.exists(FILE)) {
            return AppConfig.defaults();
        }
        return mapper.readValue(Files.readString(FILE), AppConfig.class);
    }

    public void save(AppConfig cfg) throws IOException {
        Files.createDirectories(DIR);
        Files.writeString(FILE, mapper.writeValueAsString(cfg));
    }
}
