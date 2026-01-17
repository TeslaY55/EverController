package me.teslay.evercontroller;

import com.google.gson.JsonObject;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.ChatColor;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.print.DocFlavor.STRING;

public class PlayerMessage implements Listener {

    private final EverController plugin;
    private final Connection connection;
    private final String playerMessageTable;
    private final String serverName;

    // 🔥 DB KAYIT QUEUE
    private final List<PlayerMessageLog> playerMessageLogQueue = new ArrayList<>();

    private final List<PlayerMessageLog> messageQueue = new ArrayList<>();
    private int queueIndex = 0;
    private boolean sending = false;

    public PlayerMessage(EverController plugin, Connection connection) {
        this.plugin = plugin;
        this.connection = connection;
        this.playerMessageTable = plugin.getConfig().getString("playerMessage.table");
        this.serverName = plugin.getConfig().getString("serverName");

        // ⏱️ 10 SANİYEDE 1 BATCH DB INSERT
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (playerMessageLogQueue.isEmpty()) return;

            List<PlayerMessageLog> batch = new ArrayList<>(playerMessageLogQueue);
            playerMessageLogQueue.clear();

            try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO " + playerMessageTable +
                " (time, server, player, uuid, message, discord) VALUES (?, ?, ?, ?, ?, ?)"
            )) {
                for (PlayerMessageLog log : batch) {
                    stmt.setString(1, log.time);
                    stmt.setString(2, log.server);
                    stmt.setString(3, log.player);
                    stmt.setString(4, log.uuid);
                    stmt.setString(5, log.message);
                    stmt.setInt(6, log.discord); // ✅
                    stmt.addBatch();
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            cleanPlayerMessageTable();
        }, 0L, 200L); // 200 tick = 10 saniye
    }

    public void flushQueueToDatabase() {
        if (playerMessageLogQueue == null || playerMessageLogQueue.isEmpty()) return;

        String query = "INSERT INTO " + playerMessageTable + " (time, server, player, uuid, message, discord) VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            for (PlayerMessageLog log : playerMessageLogQueue) {
                stmt.setString(1, log.time);
                stmt.setString(2, serverName);
                stmt.setString(3, log.player);
                stmt.setString(4, log.uuid);
                stmt.setString(5, log.message);
                stmt.setInt(6, log.id);
                stmt.addBatch();
            }
            stmt.executeBatch();
            playerMessageLogQueue.clear();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void createPlayerMessageTable() {
        String sql =
                "CREATE TABLE IF NOT EXISTS " + playerMessageTable + " (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY," +
                        "time DATETIME DEFAULT CURRENT_TIMESTAMP," +
                        "server TEXT," +
                        "player VARCHAR(50)," +
                        "uuid TEXT," +
                        "message TEXT," +
                        "discord TINYINT DEFAULT 0" +
                        ") " +
                        "CHARACTER SET utf8mb4 " +
                        "COLLATE utf8mb4_turkish_ci";
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void cleanPlayerMessageTable() {
        int maxData = plugin.getConfig().getInt("playerMessage.limits.maxData", 50000);
        int limitDay = plugin.getConfig().getInt("playerMessage.limits.limitDay", 5);

        try (Statement stmt = connection.createStatement()) {
            // 1️⃣ limitDay: 5 gün önceki kayıtları sil
            stmt.executeUpdate(
                "DELETE FROM " + playerMessageTable +
                " WHERE time < NOW() - INTERVAL " + limitDay + " DAY"
            );

            // 2️⃣ maxData: toplam satır sayısı maxData'dan fazla ise en eskiyi sil
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM " + playerMessageTable);
            if (rs.next()) {
                int rowCount = rs.getInt("count");
                if (rowCount > maxData) {
                    int toDelete = rowCount - maxData;
                    stmt.executeUpdate(
                        "DELETE FROM " + playerMessageTable +
                        " ORDER BY id ASC LIMIT " + toDelete
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void sendPlayerMessageLogs() {
        if (!plugin.getConfig().getBoolean("playerMessage.enable")) return;
        if (!plugin.getConfig().getBoolean("playerMessage.discord.enable")) return;
        if (sending) return;

        sending = true;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            messageQueue.clear();
            queueIndex = 0;

            List<String> keywords = plugin.getConfig().getStringList("playerMessage.discord.keywords");

            String query;
            if (!keywords.isEmpty() && !(keywords.size() == 1 && keywords.get(0).isEmpty())) {
                String where = keywords.stream()
                        .map(k -> "message LIKE ?")
                        .collect(Collectors.joining(" OR "));
                query = "SELECT * FROM " + playerMessageTable +
                        " WHERE discord = 0 AND (" + where + ") ORDER BY id ASC";
            } else {
                query = "SELECT * FROM " + playerMessageTable +
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
                        messageQueue.add(
                            new PlayerMessageLog(
                                rs.getInt("id"),        // ✅ DB id
                                rs.getInt("discord"),   // ✅ flag
                                rs.getString("time"),
                                rs.getString("server"),
                                rs.getString("uuid"),
                                rs.getString("player"),
                                rs.getString("message")
                            )
                        );
                    }
                }

                String webhook = plugin.getConfig().getString("playerMessage.discord.webhook-url");
                if (webhook == null || webhook.isEmpty()) {
                    Bukkit.getConsoleSender().sendMessage(
                        "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                        "Discord webhook URL bulunamadı!"
                    );
                    sending = false;
                    return;
                }

                sendNext(webhook);

            } catch (SQLException e) {
                e.printStackTrace();
                sending = false;
            }
        });
    }

    private void sendNext(String webhookUrl) {
        if (queueIndex >= messageQueue.size()) {
            sending = false;
            return;
        }

        PlayerMessageLog log = messageQueue.get(queueIndex);
        String format = plugin.getConfig().getString(
                "playerMessage.discord.message-format",
                "%player%: %message%"
        );

        String content = format
                .replace("%message%", log.message)
                .replace("%player%", log.player)
                .replace("%time%", String.valueOf(log.time))
                .replace("%uuid%", log.uuid)
                .replace("%server%", log.server);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = sendDiscord(webhookUrl, content, log.id);
            if (success) {
                queueIndex++;
                sendNext(webhookUrl);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin,
                        () -> sendNext(webhookUrl), 100L);
            }
        });
    }

    private boolean sendDiscord(String webhookUrl, String message, int id) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            JsonObject json = new JsonObject();
            json.addProperty("content", message);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.toString().getBytes());
            }

            int code = conn.getResponseCode();
            conn.disconnect();

            if (code >= 200 && code < 300) {
                try (PreparedStatement stmt = connection.prepareStatement(
                        "UPDATE " + playerMessageTable + " SET discord = 1 WHERE id = ?")) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
                return true;
            }

            if (code == 429) {
                return false;
            }

            Bukkit.getConsoleSender().sendMessage(
                "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                "Discord gönderim hatası | ID: " + id + " Code: " + code
            );
            return false;

        } catch (Exception e) {
            Bukkit.getConsoleSender().sendMessage(
                "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                "Discord gönderim exception | ID: " + id
            );
            e.printStackTrace();
            return false;
        }
    }

    private void queuePlayerMessageLog(Player p, String uuid, String message) {

        long timestamp = System.currentTimeMillis();

        String time = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        playerMessageLogQueue.add(
            new PlayerMessageLog(
                0,              // id yok (AUTO_INCREMENT)
                0,              // discord = gönderilmedi
                time,
                serverName,
                uuid,
                p.getName(),
                message
            )
        );
    }

    private boolean matchKeywords(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }

        List<String> keywords = plugin.getConfig().getStringList("playerMessage.keywords");
        String mode = plugin.getConfig().getString("playerMessage.mode", "whitelist").toLowerCase();

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

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (plugin.getConfig().getBoolean("playerMessage.enable", true)) {
            Player p = event.getPlayer();
            UUID uuid = p.getUniqueId();
            if (!matchKeywords(event.getMessage())) return;
            queuePlayerMessageLog(
                p, uuid.toString(), event.getMessage()
            );
        }
    }

    private static class PlayerMessageLog {
        int id;        // DB id
        int discord;   // 0 / 1
        String time;
        String server;
        String uuid;
        String player;
        String message;

        PlayerMessageLog(int id, int discord, String time,
                        String server, String uuid,
                        String player, String message) {

            this.id = id;          // ✅ artık ezilmiyor
            this.discord = discord;
            this.time = time;
            this.server = server;
            this.uuid = uuid;
            this.player = player;
            this.message = message;
        }
    }
}
