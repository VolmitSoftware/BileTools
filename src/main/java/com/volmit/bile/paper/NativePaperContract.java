package com.volmit.bile.paper;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.InvalidPluginException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public record NativePaperContract(String name, boolean requested, boolean bootstrapper, boolean loader,
                                  boolean runtimeBootstrap, boolean runtimeClasspath) {
    public static NativePaperContract read(File file) throws IOException, InvalidPluginException {
        try (ZipFile archive = new ZipFile(file)) {
            ZipEntry entry = archive.getEntry("paper-plugin.yml");
            if (entry == null) {
                return new NativePaperContract("", false, false, false, false, false);
            }
            try (InputStream stream = archive.getInputStream(entry)) {
                return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    static NativePaperContract parse(String descriptor) throws InvalidPluginException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(descriptor);
        } catch (InvalidConfigurationException exception) {
            throw new InvalidPluginException("Invalid paper-plugin.yml", exception);
        }
        return new NativePaperContract(yaml.getString("name", ""), flag(yaml, "runtime-load"),
                !yaml.getString("bootstrapper", "").isBlank(), !yaml.getString("loader", "").isBlank(),
                flag(yaml, "runtime-bootstrap"), flag(yaml, "runtime-classpath"));
    }

    public void validate() throws InvalidPluginException {
        if (name.isBlank() || !requested) {
            throw new InvalidPluginException("Native Paper runtime loading requires biletools.runtime-load: true in paper-plugin.yml");
        }
        if (bootstrapper && !runtimeBootstrap) {
            throw new InvalidPluginException("Native Paper bootstrapper requires biletools.runtime-bootstrap: true; startup registry mutations require a restart");
        }
        if (loader && !runtimeClasspath) {
            throw new InvalidPluginException("Native Paper classpath loader requires biletools.runtime-classpath: true to declare repeatable runtime classpath setup");
        }
    }

    private static boolean flag(YamlConfiguration yaml, String key) throws InvalidPluginException {
        String path = "biletools." + key;
        Object value = yaml.get(path);
        if (value != null && !(value instanceof Boolean)) {
            throw new InvalidPluginException(path + " must be a boolean");
        }
        return Boolean.TRUE.equals(value);
    }
}
