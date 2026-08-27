package com.zonlong.beloong.compat.lockdown;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes the per-save template version marker used by
 * {@link LockdownTemplateMigration}.
 *
 * <p>The marker is stored at {@code <save>/data/beloong_template_version.json}.</p>
 */
public final class TemplateVersionStore {
    private static final String FILE_NAME = "beloong_template_version.json";

    private TemplateVersionStore() {
    }

    /**
     * Reads the stored template version.
     *
     * @param saveDir the active save directory
     * @return the stored version, or {@code -1} if the file is missing or invalid
     */
    public static int read(Path saveDir) {
        Path file = saveDir.resolve("data").resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return -1;
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            if (!root.has("version") || !root.get("version").isJsonPrimitive()) {
                return -1;
            }
            return root.get("version").getAsInt();
        } catch (Exception exception) {
            return -1;
        }
    }

    /**
     * Writes the template version marker.
     *
     * @param saveDir the active save directory
     * @param version the version to store
     * @throws IOException if the marker cannot be written
     */
    public static void write(Path saveDir, int version) throws IOException {
        Path dataDir = saveDir.resolve("data");
        Files.createDirectories(dataDir);

        JsonObject root = new JsonObject();
        root.addProperty("version", version);

        Files.writeString(dataDir.resolve(FILE_NAME), root.toString(), StandardCharsets.UTF_8);
    }
}
