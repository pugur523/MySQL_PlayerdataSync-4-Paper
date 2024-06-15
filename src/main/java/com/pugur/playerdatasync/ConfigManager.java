package com.pugur.playerdatasync;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class ConfigManager {
    private static YamlConfiguration config;
    private static File configFile;

    public static void init(JavaPlugin plugin) {
        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }

        config = new YamlConfiguration();
        loadConfig(plugin);
    }

    private static void loadConfig(JavaPlugin plugin) {
        try {
            config.load(configFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not read the config.yml file: " + e.getMessage());
        } catch (InvalidConfigurationException e) {
            plugin.getLogger().severe("Invalid configuration in config.yml: " + e.getMessage());
        }
    }

    public static YamlConfiguration getConfig() {
        return config;
    }

    public static void saveConfig() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
