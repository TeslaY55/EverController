package me.teslay.evercontroller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import me.teslay.evercontroller.BreakPlace.BreakPlaceLog;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.ChatColor;

public class BreakPlace implements Listener {

    private final EverController plugin;
    private final Connection connection;
    private final String breakPlaceTable;
    private final String serverName;

    // 🔥 DB KAYIT QUEUE
    private final List<BreakPlaceLog> breakPlaceLogQueue = new ArrayList<>();

    // 🔔 DISCORD QUEUE
    private List<BreakPlaceLog> breakPlaceQueue = new ArrayList<>();
    private int breakPlaceIndex = 0;
    private boolean sendBreakPlaceLogsAktif = false;

    public BreakPlace(EverController plugin, Connection connection) {
        this.plugin = plugin;
        this.connection = connection;
        breakPlaceTable = plugin.getConfig().getString("breakPlace.table");
        this.serverName = plugin.getConfig().getString("serverName");

        // ⏱️ 10 SANİYEDE 1 BATCH DB INSERT
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (breakPlaceLogQueue.isEmpty()) return;

            List<BreakPlaceLog> batch = new ArrayList<>(breakPlaceLogQueue);
            breakPlaceLogQueue.clear();

            try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO " + breakPlaceTable +
                " (time, server, type, player, uuid, location, block, equipped, discord) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                for (BreakPlaceLog log : batch) {
                    stmt.setString(1, log.time);
                    stmt.setString(2, log.server);
                    stmt.setString(3, log.type);
                    stmt.setString(4, log.player);
                    stmt.setString(5, log.uuid);
                    stmt.setString(6, log.location);
                    stmt.setString(7, log.block);
                    stmt.setString(8, log.equipped);
                    stmt.setInt(9, log.discord);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                e.printStackTrace();
            }
            cleanBreakPlaceTable();
        }, 0L, 200L); // 200 tick = 10 saniye
    }

    public void flushQueueToDatabase() {
        if (breakPlaceQueue == null || breakPlaceQueue.isEmpty()) return;

        String query = "INSERT INTO " + breakPlaceTable +
                " (time, server, type, player, uuid, location, block, equipped, discord) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            for (BreakPlaceLog log : breakPlaceQueue) {
                stmt.setString(1, log.time);
                stmt.setString(2, log.server);
                stmt.setString(3, log.type);
                stmt.setString(4, log.player);
                stmt.setString(5, log.uuid);
                stmt.setString(6, log.location);
                stmt.setString(7, log.block);
                stmt.setString(8, log.equipped);
                stmt.setInt(9, log.discord);
                stmt.addBatch();
            }
            stmt.executeBatch();
            breakPlaceQueue.clear();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void createBreakPlaceTable() {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS " + breakPlaceTable + " (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "time DATETIME DEFAULT CURRENT_TIMESTAMP," +
                "server TEXT," +
                "type VARCHAR(50)," +
                "player VARCHAR(50)," +
                "uuid TEXT," +
                "location TEXT," +
                "block TEXT," +
                "equipped VARCHAR(50)," +
                "discord TINYINT DEFAULT 0" +
                ") " +
                "CHARACTER SET utf8mb4 " +
                "COLLATE utf8mb4_turkish_ci"
            );
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void cleanBreakPlaceTable() {
        int maxData = plugin.getConfig().getInt("breakPlace.limits.maxData", 50000);
        int limitDay = plugin.getConfig().getInt("breakPlace.limits.limitDay", 5);

        try (Statement stmt = connection.createStatement()) {
            // 1️⃣ limitDay: 5 gün önceki kayıtları sil
            stmt.executeUpdate(
                "DELETE FROM " + breakPlaceTable +
                " WHERE time < NOW() - INTERVAL " + limitDay + " DAY"
            );

            // 2️⃣ maxData: toplam satır sayısı maxData'dan fazla ise en eskiyi sil
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM " + breakPlaceTable);
            if (rs.next()) {
                int rowCount = rs.getInt("count");
                if (rowCount > maxData) {
                    int toDelete = rowCount - maxData;
                    stmt.executeUpdate(
                        "DELETE FROM " + breakPlaceTable +
                        " ORDER BY id ASC LIMIT " + toDelete
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ================= QUEUE =================

    private void queueBreakPlaceLog(Player p, String type,
                                    String location, String block, String equipped) {

        long timestamp = System.currentTimeMillis();

        String time = java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        breakPlaceLogQueue.add(new BreakPlaceLog(
            0,
            0,
            time,
            serverName,
            type,
            p.getName(),
            p.getUniqueId().toString(),
            location,
            block,
            equipped
        ));
    }

    private boolean matchKeywords(String itemName) {
        List<String> keywords = plugin.getConfig().getStringList("breakPlace.keywords");
        String mode = plugin.getConfig().getString("breakPlace.mode", "whitelist").toLowerCase();

        // Liste boşsa → her zaman true dön
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }

        String item = itemName.toUpperCase();

        if (mode.equals("whitelist")) {
            // Whitelist: listede varsa true, yoksa false
            for (String key : keywords) {
                if (item.equals(key.toUpperCase())) {
                    return true;
                }
            }
            return false;
        } else if (mode.equals("blacklist")) {
            // Blacklist: listede varsa false, yoksa true
            for (String key : keywords) {
                if (item.equals(key.toUpperCase())) {
                    return false;
                }
            }
            return true;
        }

        // Eğer mode yanlışsa default olarak whitelist gibi davran
        return false;
    }

    // ================= EVENTS =================

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        if (!plugin.getConfig().getBoolean("breakPlace.enable", true)) return;

        Player p = e.getPlayer();
        Block block = e.getBlock();

        String blockType = block.getType().name();
        if (block.getType() == Material.SPAWNER) {
            BlockState state = block.getState();
            if (state instanceof CreatureSpawner spawner &&
                spawner.getSpawnedType() != null) {
                blockType += ": " + spawner.getSpawnedType().name();
            }
        }

        if (!matchKeywords(blockType)) return;

        Location l = block.getLocation();
        String location = l.getWorld().getName() + " " +
                          l.getBlockX() + " " +
                          l.getBlockY() + " " +
                          l.getBlockZ();

        queueBreakPlaceLog(
            p,
            "break",
            location,
            blockType,
            p.getInventory().getItemInMainHand().getType().name()
        );
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (!plugin.getConfig().getBoolean("breakPlace.enable", true)) return;

        Player p = e.getPlayer();
        Block block = e.getBlock();

        // Spawner bilgisi doğru gelsin diye 1 tick sonra
        Bukkit.getScheduler().runTask(plugin, () -> {
            String blockType = block.getType().name();

            if (block.getType() == Material.SPAWNER) {
                BlockState state = block.getState();
                if (state instanceof CreatureSpawner spawner &&
                    spawner.getSpawnedType() != null) {
                    blockType = "SPAWNER: " + spawner.getSpawnedType().name();
                }
            }

            if (!matchKeywords(blockType)) return;

            Location l = block.getLocation();
            String location = l.getWorld().getName() + " " +
                              l.getBlockX() + " " +
                              l.getBlockY() + " " +
                              l.getBlockZ();

            queueBreakPlaceLog(
                p,
                "place",
                location,
                blockType,
                p.getInventory().getItemInMainHand().getType().name()
            );
        });
    }

    // ================= DISCORD =================

    public void sendBreakPlaceLogs() {
        if (!plugin.getConfig().getBoolean("breakPlace.enable") ||
            !plugin.getConfig().getBoolean("breakPlace.discord.enable") ||
            sendBreakPlaceLogsAktif) return;

        sendBreakPlaceLogsAktif = true;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                breakPlaceQueue.clear();
                breakPlaceIndex = 0;

                List<String> keywords = plugin.getConfig()
                    .getStringList("breakPlace.discord.keywords");

                String query;
                if (!keywords.isEmpty() &&
                    !(keywords.size() == 1 && keywords.get(0).isEmpty())) {
                    String where = keywords.stream()
                        .map(k -> "block LIKE ?")
                        .collect(Collectors.joining(" OR "));
                    query = "SELECT * FROM " + breakPlaceTable +
                            " WHERE discord = 0 AND (" + where + ") ORDER BY id ASC";
                } else {
                    query = "SELECT * FROM " + breakPlaceTable +
                            " WHERE discord = 0 ORDER BY id ASC";
                }

                PreparedStatement stmt = connection.prepareStatement(query);
                for (int i = 0; i < keywords.size(); i++) {
                    stmt.setString(i + 1, "%" + keywords.get(i) + "%");
                }

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    breakPlaceQueue.add(new BreakPlaceLog(
                        rs.getInt("id"),
                        rs.getInt("discord"),
                        rs.getString("time"),
                        rs.getString("server"),
                        rs.getString("type"),
                        rs.getString("player"),
                        rs.getString("uuid"),
                        rs.getString("location"),
                        rs.getString("block"),
                        rs.getString("equipped")
                    ));
                }

                if (!breakPlaceQueue.isEmpty()) {
                    sendBreakPlaceNext(plugin.getConfig()
                        .getString("breakPlace.discord.webhook-url"));
                } else {
                    sendBreakPlaceLogsAktif = false;
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendBreakPlaceLogsAktif = false;
            }
        });
    }

    private void sendBreakPlaceNext(String webhook) {
        if (breakPlaceIndex >= breakPlaceQueue.size()) {
            sendBreakPlaceLogsAktif = false;
            return;
        }

        BreakPlaceLog log = breakPlaceQueue.get(breakPlaceIndex);

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Blok Log Mesajı");
        embed.addProperty("color", log.type.equals("place") ? 65280 : 16711680);

        JsonObject author = new JsonObject();
        author.addProperty(
            "name",
            log.player + " tarafından " + log.type + " yapıldı"
        );
        author.addProperty(
            "icon_url",
            "https://visage.surgeplay.com/head/512/" + log.uuid
        );
        embed.add("author", author);

        JsonArray fields = new JsonArray();
        fields.add(makeField("Sunucu", log.server, true));
        fields.add(makeField("Zaman", String.valueOf(log.time), true));
        fields.add(makeField("Blok", log.block, true));
        fields.add(makeField("Konum", log.location, false));
        fields.add(makeField("Eşya", log.equipped, false));
        embed.add("fields", fields);

        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (sendBreakPlaceDiscord(webhook, payload, log.id)) {
                breakPlaceIndex++;
                sendBreakPlaceNext(webhook);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin,
                    () -> sendBreakPlaceNext(webhook), 100L);
            }
        });
    }

    private JsonObject makeField(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    private boolean sendBreakPlaceDiscord(String webhook, JsonObject payload, int id) {
        try {
            HttpURLConnection conn =
                (HttpURLConnection) new URL(webhook).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes());
            }

            if (conn.getResponseCode() >= 200 &&
                conn.getResponseCode() < 300) {

                try (PreparedStatement stmt = connection.prepareStatement(
                    "UPDATE " + breakPlaceTable +
                    " SET discord = 1 WHERE id = ?")) {
                    stmt.setInt(1, id); // ✅ GERÇEK DB ID
                    stmt.executeUpdate();
                }
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // ================= MODEL =================

    public static class BreakPlaceLog {
        public int id;          // DB ID
        public int discord;     // 0 / 1
        public String time;
        public String server;
        public String type;
        public String player;
        public String uuid;
        public String location;
        public String block;
        public String equipped;

        public BreakPlaceLog(int id, int discord, String time, String server, String type,
                            String player, String uuid,
                            String location, String block, String equipped) {
            this.id = id;
            this.discord = discord;
            this.time = time;
            this.server = server;
            this.type = type;
            this.player = player;
            this.uuid = uuid;
            this.location = location;
            this.block = block;
            this.equipped = equipped;
        }
    }
}
