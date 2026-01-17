package me.teslay.evercontroller;

import com.google.gson.JsonObject;

import me.teslay.evercontroller.CommandLogger.CommandLog;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.ChatColor;

public class CommandLogger implements Listener {

    private final EverController plugin;
    private final Connection connection;
    private final String commandTable;
    private final String serverName;

    // 🔥 QUEUE
    private final List<CommandLog> commandLogQueue = new ArrayList<>();

    private List<CommandLog> commandQueue = new ArrayList<>();
    private int commandIndex = 0;
    private boolean sendCommandLogsAktif = false;

    public CommandLogger(EverController plugin, Connection connection) {
        this.plugin = plugin;
        this.connection = connection;
        this.commandTable = plugin.getConfig().getString("command.table");
        this.serverName = plugin.getConfig().getString("serverName");

        // ⏱️ 10 SANİYEDE 1 DB KAYDI
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (commandLogQueue.isEmpty()) return;

            List<CommandLog> batch = new ArrayList<>(commandLogQueue);
            commandLogQueue.clear();

            try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO " + commandTable +
                " (time, server, player, uuid, command, discord) VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                for (CommandLog log : batch) {
                    stmt.setString(1, log.time);
                    stmt.setString(2, log.server);
                    stmt.setString(3, log.player);
                    stmt.setString(4, log.uuid);
                    stmt.setString(5, log.command);
                    stmt.setInt(6, log.discord); // ✅
                    stmt.addBatch();
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            cleanCommandTable();
        }, 0L, 200L); // 200 tick = 10 saniye
    }

    public void flushQueueToDatabase() {
        if (commandLogQueue == null || commandLogQueue.isEmpty()) return;

        String query = "INSERT INTO " + commandTable +
                " (time, server, player, uuid, command, discord) VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            for (CommandLog log : commandLogQueue) {
                stmt.setString(1, log.time);
                stmt.setString(2, log.server);
                stmt.setString(3, log.player);
                stmt.setString(4, log.uuid);
                stmt.setString(5, log.command);
                stmt.setInt(6, log.id);
                stmt.addBatch();
            }
            stmt.executeBatch();
            commandLogQueue.clear();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void createCommandTable() {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + commandTable + " (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "time DATETIME DEFAULT CURRENT_TIMESTAMP," +
                "server TEXT," +
                "player VARCHAR(50)," +
                "uuid TEXT," +
                "command TEXT," +
                "discord TINYINT DEFAULT 0" +
                ") " +
                "CHARACTER SET utf8mb4 " +
                "COLLATE utf8mb4_turkish_ci"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void cleanCommandTable() {
        int maxData = plugin.getConfig().getInt("command.limits.maxData", 50000);
        int limitDay = plugin.getConfig().getInt("command.limits.limitDay", 5);

        try (Statement stmt = connection.createStatement()) {
            // 1️⃣ limitDay: 5 gün önceki kayıtları sil
            stmt.executeUpdate(
                "DELETE FROM " + commandTable +
                " WHERE time < NOW() - INTERVAL " + limitDay + " DAY"
            );

            // 2️⃣ maxData: toplam satır sayısı maxData'dan fazla ise en eskiyi sil
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM " + commandTable);
            if (rs.next()) {
                int rowCount = rs.getInt("count");
                if (rowCount > maxData) {
                    int toDelete = rowCount - maxData;
                    stmt.executeUpdate(
                        "DELETE FROM " + commandTable +
                        " ORDER BY id ASC LIMIT " + toDelete
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 🔥 QUEUE'YA EKLE
    private void queueCommandLog(String player, String uuid, String command) {
        long timestamp = System.currentTimeMillis();

        String time = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        commandLogQueue.add(new CommandLog(
            0,
            0,
            time,
            serverName,
            uuid,
            player,
            command
        ));
    }

    private boolean matchKeywords(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }

        List<String> keywords = plugin.getConfig().getStringList("command.keywords");
        String mode = plugin.getConfig().getString("command.mode", "whitelist").toLowerCase();

        // Liste boşsa → her şeyi kabul et
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }

        String msg = message.toLowerCase().trim();

        if (mode.equals("whitelist")) {
            // Whitelist: listedeki keyword varsa true, yoksa false
            for (String key : keywords) {
                if (key != null && !key.isEmpty()) {
                    if (msg.contains(key.toLowerCase().trim())) {
                        return true;
                    }
                }
            }
            return false;
        } else if (mode.equals("blacklist")) {
            // Blacklist: listedeki keyword varsa false, yoksa true
            for (String key : keywords) {
                if (key != null && !key.isEmpty()) {
                    if (msg.contains(key.toLowerCase().trim())) {
                        return false;
                    }
                }
            }
            return true;
        }

        // Eğer mode yanlışsa default whitelist gibi davran
        return false;
    }

    // ================= EVENTS =================

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (plugin.getConfig().getBoolean("command.enable", true)) {
            if (!matchKeywords(event.getMessage())) return;
            Player p = event.getPlayer();
            queueCommandLog(
                p.getName(),
                p.getUniqueId().toString(),
                event.getMessage()
            );
        }
    }

    @EventHandler
    public void onServerCommand(ServerCommandEvent event) {
        if (plugin.getConfig().getBoolean("command.enable", true)) {
            if (!matchKeywords(event.getCommand())) return;
            CommandSender sender = event.getSender();
            String uuid = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";

            queueCommandLog(
                sender.getName(),
                uuid,
                event.getCommand()
            );
        }
    }

    // ================= DISCORD =================

    public void sendCommandLogs() {
        if (!plugin.getConfig().getBoolean("command.enable")) return;
        if (!plugin.getConfig().getBoolean("command.discord.enable")) return;
        if (sendCommandLogsAktif) return;

        sendCommandLogsAktif = true;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                commandQueue.clear();
                commandIndex = 0;

                List<String> keywords = plugin.getConfig()
                        .getStringList("command.discord.keywords");

                String query;
                if (!keywords.isEmpty() && !(keywords.size() == 1 && keywords.get(0).isEmpty())) {
                    String where = keywords.stream()
                            .map(k -> "command LIKE ?")
                            .collect(Collectors.joining(" OR "));
                    query = "SELECT * FROM " + commandTable +
                            " WHERE discord = 0 AND (" + where + ") ORDER BY id ASC";
                } else {
                    query = "SELECT * FROM " + commandTable +
                            " WHERE discord = 0 ORDER BY id ASC";
                }

                try (PreparedStatement stmt = connection.prepareStatement(query)) {

                    if (!keywords.isEmpty() && !(keywords.size() == 1 && keywords.get(0).isEmpty())) {
                        for (int i = 0; i < keywords.size(); i++) {
                            stmt.setString(i + 1, "%" + keywords.get(i) + "%");
                        }
                    }

                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            commandQueue.add(
                                new CommandLog(
                                    rs.getInt("id"),
                                    rs.getInt("discord"),
                                    rs.getString("time"),
                                    rs.getString("server"),
                                    rs.getString("uuid"),
                                    rs.getString("player"),
                                    rs.getString("command")
                                )
                            );
                        }
                    }
                }

                sendNextCommand(
                    plugin.getConfig().getString("command.discord.webhook-url")
                );

            } catch (SQLException e) {
                e.printStackTrace();
                sendCommandLogsAktif = false;
            }
        });
    }

    private void sendNextCommand(String webhook) {
        if (commandIndex >= commandQueue.size()) {
            sendCommandLogsAktif = false;
            return;
        }

        CommandLog log = commandQueue.get(commandIndex);
        String format = plugin.getConfig().getString("command.discord.message-format", "%command%");
        String message = format
            .replace("%command%", log.command)
            .replace("%player%", log.player)
            .replace("%uuid%", log.uuid)
            .replace("%server%", log.server)
            .replace("%time%", log.time);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (sendDiscord(webhook, message, log.id)) {
                commandIndex++;
                sendNextCommand(webhook);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, () -> sendNextCommand(webhook), 100L);
            }
        });
    }

    private boolean sendDiscord(String webhook, String message, int id) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(webhook).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JsonObject payload = new JsonObject();
            payload.addProperty("content", message);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes());
            }

            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                try (PreparedStatement stmt = connection.prepareStatement(
                    "UPDATE " + commandTable + " SET discord = 1 WHERE id = ?"
                )) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ================= MODEL =================

    static class CommandLog {
        int id;        // DB id
        int discord;   // 0 / 1
        String time;
        String server;
        String uuid;
        String player;
        String command;

        CommandLog(int id, int discord, String time,
                String server, String uuid,
                String player, String command) {

            this.id = id;          // ✅ artık ezilmiyor
            this.discord = discord;
            this.time = time;
            this.server = server;
            this.uuid = uuid;
            this.player = player;
            this.command = command;
        }
    }
}
