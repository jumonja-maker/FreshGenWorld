package dev.freshgenerator.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Reads bundled data from the ACTUAL resolved game JAR. No downloaded templates or API stubs.
 * <p>When the mod's own generated pack is on the classpath as well (the test runtime: build/resources/main comes before
 * the game JAR), a path the pack also defines - data/minecraft/worldgen/world_preset/normal.json - resolved to the mod's
 * copy first. The game's own entry (inside a JAR) is preferred; a directory entry is used only when no JAR has the path. */
public final class VanillaAssets {
    private final Map<String, String> fingerprints = new TreeMap<>();
    public boolean exists(String path) { return resource(path) != null; }
    public JsonElement read(String path) throws IOException {
        URL url = resource(path);
        if (url == null) throw new FileNotFoundException("Missing Minecraft 26.3 data: " + path);
        try (InputStream in = url.openStream()) {
            byte[] bytes = in.readAllBytes();
            try { fingerprints.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        }
    }
    /** The game's entry for a path: a JAR entry first, else the first match. */
    private static URL resource(String path) {
        try {
            Enumeration<URL> all = VanillaAssets.class.getClassLoader().getResources(path);
            URL first = null;
            while (all.hasMoreElements()) {
                URL u = all.nextElement();
                if (first == null) first = u;
                if ("jar".equals(u.getProtocol())) return u;
            }
            return first;
        } catch (IOException e) {
            return VanillaAssets.class.getResource("/" + path);
        }
    }
    public Map<String, String> fingerprints() { return Collections.unmodifiableMap(fingerprints); }
    public static String path(String registry, String id) {
        String[] parts = id.split(":", 2);
        if (parts.length != 2 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException(id);
        return "data/" + parts[0] + "/worldgen/" + registry + "/" + parts[1] + ".json";
    }
}
