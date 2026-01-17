package me.teslay.evercontroller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import me.teslay.evercontroller.DropPickup.DropPickupLog;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.block.Block;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.ItemDespawnEvent;

public class DropPickup implements Listener {
    private final EverController plugin;
    private final Connection connection;
    private final String dropPickupTable;
    private final String serverName;
    private final List<DropPickupLog> dropPickupLogQueue = new ArrayList();
    private List<DropPickupLog> dropPickupQueue = new ArrayList();
    private int dropPickupIndex = 0;
    private boolean sendDropPickupLogsAktif = false;
    private final Set<UUID> loggedItems = new HashSet<>();

    public DropPickup(EverController plugin, Connection connection) {
        this.plugin = plugin;
        this.connection = connection;
        this.dropPickupTable = plugin.getConfig().getString("dropPickup.table");
        this.serverName = plugin.getConfig().getString("serverName");
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!this.dropPickupLogQueue.isEmpty()) {
                List<DropPickupLog> batch = new ArrayList(this.dropPickupLogQueue);
                this.dropPickupLogQueue.clear();

               try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO " + dropPickupTable +
                    " (time, server, type, player, uuid, location, item, source, discord) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )) {
                    for (DropPickupLog log : batch) {
                        stmt.setString(1, log.time);
                        stmt.setString(2, log.server);
                        stmt.setString(3, log.type);
                        stmt.setString(4, log.player);
                        stmt.setString(5, log.uuid);
                        stmt.setString(6, log.location);
                        stmt.setString(7, log.item);
                        stmt.setString(8, log.source);
                        stmt.setInt(9, log.discord);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            cleanDropPickupTable();
        }, 0L, 200L);
    }

    public void flushQueueToDatabase() {
        if (dropPickupLogQueue == null || dropPickupLogQueue.isEmpty()) return;

        String query = "INSERT INTO " + dropPickupTable +
                " (time, server, type, player, uuid, item, amount, location, discord) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            for (DropPickupLog log : dropPickupLogQueue) {
                stmt.setString(1, log.time);
                stmt.setString(2, log.server);
                stmt.setString(3, log.type);
                stmt.setString(4, log.player);
                stmt.setString(5, log.uuid);
                stmt.setString(6, log.location);
                stmt.setString(7, log.item);
                stmt.setInt(8, log.id);
                stmt.addBatch();
            }
            stmt.executeBatch();
            dropPickupLogQueue.clear();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void createDropPickupTable() {
        try (Statement stmt = this.connection.createStatement()) {
            String sql =
                "CREATE TABLE IF NOT EXISTS " + this.dropPickupTable + " (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "time DATETIME DEFAULT CURRENT_TIMESTAMP," +
                "server TEXT," +
                "type VARCHAR(50)," +
                "player VARCHAR(50)," +
                "uuid TEXT," +
                "location TEXT," +
                "item TEXT," +
                "source VARCHAR(50)," +
                "discord TINYINT DEFAULT 0" +
                ") " +
                "CHARACTER SET utf8mb4 " +
                "COLLATE utf8mb4_turkish_ci";
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    public void cleanDropPickupTable() {
        int maxData = plugin.getConfig().getInt("dropPickup.limits.maxData", 50000);
        int limitDay = plugin.getConfig().getInt("dropPickup.limits.limitDay", 5);

        try (Statement stmt = connection.createStatement()) {
            // 1️⃣ limitDay: 5 gün önceki kayıtları sil
            stmt.executeUpdate(
                "DELETE FROM " + dropPickupTable +
                " WHERE time < NOW() - INTERVAL " + limitDay + " DAY"
            );

            // 2️⃣ maxData: toplam satır sayısı maxData'dan fazla ise en eskiyi sil
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM " + dropPickupTable);
            if (rs.next()) {
                int rowCount = rs.getInt("count");
                if (rowCount > maxData) {
                    int toDelete = rowCount - maxData;
                    stmt.executeUpdate(
                        "DELETE FROM " + dropPickupTable +
                        " ORDER BY id ASC LIMIT " + toDelete
                    );
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void sendDropPickupLogs() {
        if (this.plugin.getConfig().getBoolean("dropPickup.enable") && this.plugin.getConfig().getBoolean("dropPickup.discord.enable")) {
            if (!this.sendDropPickupLogsAktif) {
                this.sendDropPickupLogsAktif = true;
                Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                    try {
                        this.dropPickupQueue.clear();
                        this.dropPickupIndex = 0;
                        List<String> keywords = this.plugin.getConfig().getStringList("dropPickup.discord.keywords");
                        String query;
                        if (!keywords.isEmpty() && (keywords.size() != 1 || !((String)keywords.get(0)).isEmpty())) {
                            String whereClause = (String)keywords.stream().map((k) -> "item LIKE ?").collect(Collectors.joining(" OR "));
                            query = "SELECT * FROM " + this.dropPickupTable + " WHERE discord = 0 AND (" + whereClause + ") ORDER BY id ASC";
                        } else {
                            query = "SELECT * FROM " + this.dropPickupTable + " WHERE discord = 0 ORDER BY id ASC";
                        }

                        PreparedStatement stmt = this.connection.prepareStatement(query);
                        if (!keywords.isEmpty() && (keywords.size() != 1 || !((String)keywords.get(0)).isEmpty())) {
                            for(int i = 0; i < keywords.size(); ++i) {
                                int var10001 = i + 1;
                                Object var10002 = keywords.get(i);
                                stmt.setString(var10001, "%" + (String)var10002 + "%");
                            }
                        }

                        ResultSet rs = stmt.executeQuery();

                        while (rs.next()) {
                            dropPickupQueue.add(
                                new DropPickupLog(
                                    rs.getInt("id"),
                                    rs.getInt("discord"),
                                    rs.getString("time"),
                                    rs.getString("server"),
                                    rs.getString("type"),
                                    rs.getString("player"),
                                    rs.getString("uuid"),
                                    rs.getString("location"),
                                    rs.getString("item"),
                                    rs.getString("source")   // ✅
                                )
                            );
                        }

                        String webhookUrl = this.plugin.getConfig().getString("dropPickup.discord.webhook-url");
                        if (webhookUrl == null || webhookUrl.isEmpty()) {
                            Bukkit.getConsoleSender().sendMessage(
                                "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                                "Discord webhook URL bulunamadı!"
                            );
                            this.sendDropPickupLogsAktif = false;
                            return;
                        }

                        this.sendDropPickupNext(webhookUrl);
                    } catch (SQLException e) {
                        e.printStackTrace();
                        this.sendDropPickupLogsAktif = false;
                    }

                });
            }
        }
    }

    private void sendDropPickupNext(String webhookUrl) {
        if (this.dropPickupIndex >= this.dropPickupQueue.size()) {
            this.sendDropPickupLogsAktif = false;
        } else {
            DropPickupLog log = (DropPickupLog)this.dropPickupQueue.get(this.dropPickupIndex);
            JsonObject embed = new JsonObject();
            embed.addProperty("title", "Blok Log Mesajı");
            int color = log.type.equals("pickup") ? 0xff00 : 
                    log.type.equals("remove") ? 0x808080 : // remove için gri
                    0xff0000; // diğer durumlar (drop) kırmızı
            embed.addProperty("color", color);
            JsonObject author = new JsonObject();
            String iconUrl = log.uuid != null && !log.player.isEmpty() ? "https://visage.surgeplay.com/head/512/" + log.uuid : "https://visage.surgeplay.com/head/512/X-Steve";
            author.addProperty("name", log.player + " tarafından " + log.type + " yapıldı");
            author.addProperty("icon_url", iconUrl);
            embed.add("author", author);
            JsonArray fieldsArray = new JsonArray();
            JsonObject sunucu = new JsonObject();
            sunucu.addProperty("name", "Sunucu");
            sunucu.addProperty("value", log.server);
            sunucu.addProperty("inline", true);
            fieldsArray.add(sunucu);
            JsonObject zaman = new JsonObject();
            zaman.addProperty("name", "Zaman");
            zaman.addProperty("value", log.time);
            zaman.addProperty("inline", true);
            fieldsArray.add(zaman);
            JsonObject itemObject = new JsonObject();
            itemObject.addProperty("name", "İtem");
            itemObject.addProperty("value", log.item);
            itemObject.addProperty("inline", true);
            fieldsArray.add(itemObject);
            JsonObject konum = new JsonObject();
            konum.addProperty("name", "Konum");
            konum.addProperty("value", log.location);
            konum.addProperty("inline", false);
            fieldsArray.add(konum);
            JsonObject sourceField = new JsonObject();
            sourceField.addProperty("name", "Kaynak");
            sourceField.addProperty("value", log.source);
            sourceField.addProperty("inline", true);
            fieldsArray.add(sourceField);
            embed.add("fields", fieldsArray);
            JsonArray embedsArray = new JsonArray();
            embedsArray.add(embed);
            JsonObject payload = new JsonObject();
            payload.add("embeds", embedsArray);
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                boolean success = this.sendDropPickupDiscord(webhookUrl, payload, log.id);
                if (success) {
                    ++this.dropPickupIndex;
                    this.sendDropPickupNext(webhookUrl);
                } else {
                    Bukkit.getScheduler().runTaskLater(this.plugin, () -> this.sendDropPickupNext(webhookUrl), 100L);
                }

            });
        }
    }

    private boolean sendDropPickupDiscord(String webhookUrl, JsonObject payload, int id) {
        try {
            URL url = new URL(webhookUrl);
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes());
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            conn.disconnect();
            if (responseCode >= 200 && responseCode < 300) {
                try (PreparedStatement updateStmt = this.connection.prepareStatement("UPDATE " + this.dropPickupTable + " SET discord = 1 WHERE id = ?")) {
                    updateStmt.setInt(1, id);
                    updateStmt.executeUpdate();
                }

                return true;
            } else if (responseCode == 429) {
                return false;
            } else {
                Bukkit.getConsoleSender().sendMessage(
                    "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                    "Mesaj gönderilemedi, ID: " + id + " ResponseCode: " + responseCode
                );
                return false;
            }
        } catch (Exception e) {
            Bukkit.getConsoleSender().sendMessage(
                "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                "Mesaj gönderilirken hata oluştu, ID: " + id
            );
            e.printStackTrace();
            return false;
        }
    }

    public void queueDropPickupLog(String playerName, String uuid, String type,
                               String location, String item, String source) {

        String time = java.time.Instant.now()
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        this.dropPickupLogQueue.add(
            new DropPickupLog(
                0,
                0,
                time,
                this.serverName,
                type,
                playerName,
                uuid,
                location,
                item,
                source     // ✅
            )
        );
    }

    private boolean matchKeywords(String itemName) {
        if (itemName == null || itemName.isEmpty()) {
            return false;
        }

        List<String> keywords = plugin.getConfig().getStringList("breakPlace.keywords");
        String mode = plugin.getConfig().getString("breakPlace.mode", "whitelist").toLowerCase();

        // Liste boşsa → her şeyi kabul et
        if (keywords == null || keywords.isEmpty()) {
            return true;
        }

        String item = itemName.toUpperCase().trim();

        if (mode.equals("whitelist")) {
            // Whitelist: listede varsa true, yoksa false
            for (String key : keywords) {
                if (key != null && !key.isEmpty()) {
                    if (item.equals(key.toUpperCase().trim())) {
                        return true;
                    }
                }
            }
            return false;
        } else if (mode.equals("blacklist")) {
            // Blacklist: listede varsa false, yoksa true
            for (String key : keywords) {
                if (key != null && !key.isEmpty()) {
                    if (item.equals(key.toUpperCase().trim())) {
                        return false;
                    }
                }
            }
            return true;
        }

        // Eğer mode yanlışsa default whitelist gibi davran
        return false;
    }

    private String getInventorySource(Inventory inv) {
        if (inv == null) return "UNKNOWN";

        switch (inv.getType()) {
            default: return inv.getType().name();
        }
    }

    private String getBlockSource(Block block) {
        if (block == null) return "UNKNOWN";

        switch (block.getType()) {
            default: return block.getType().name();
        }
    }

    @EventHandler
    public void onInventoryDrop(InventoryClickEvent event) {
        if (!plugin.getConfig().getBoolean("dropPickup.enable", true)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        InventoryView view = event.getView();
        Inventory clickedInv = event.getClickedInventory();

        if (clickedInv == null) return;

        // Oyuncu envanterinden çıkıyor mu?
        if (!clickedInv.equals(player.getInventory())) return;

        Inventory targetInv = view.getTopInventory();
        if (targetInv == null) return;

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType().isAir()) return;

        String itemName = item.getType().name();
        int amount = item.getAmount();

        if (!matchKeywords(itemName)) return;

        Location loc = player.getLocation();
        String location =
                loc.getWorld().getName() + " " +
                loc.getBlockX() + " " +
                loc.getBlockY() + " " +
                loc.getBlockZ();

        String source = getInventorySource(targetInv);

        if (source == "CRAFTING") return;

        queueDropPickupLog(
            player.getName(),
            player.getUniqueId().toString(),
            "drop",
            location,
            itemName + " x" + amount,
            source
        );
    }

    @EventHandler
    public void onInventoryPickup(InventoryClickEvent event) {
        if (!plugin.getConfig().getBoolean("dropPickup.enable", true)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        InventoryView view = event.getView();
        Inventory topInv = view.getTopInventory();      // chest / shulker
        Inventory clickedInv = event.getClickedInventory();

        if (topInv == null || clickedInv == null) return;

        // ❗ Oyuncu envanterine ALIYOR mu?
        if (clickedInv.equals(topInv) &&
            view.getBottomInventory().equals(player.getInventory())) {

            ItemStack item = event.getCurrentItem();
            if (item == null || item.getType().isAir()) return;

            String itemName = item.getType().name();
            int amount = item.getAmount();

            // 🔍 keyword filtresi
            if (!matchKeywords(itemName)) return;

            Location loc = player.getLocation();
            String location =
                    loc.getWorld().getName() + " " +
                    loc.getBlockX() + " " +
                    loc.getBlockY() + " " +
                    loc.getBlockZ();

            String source = getInventorySource(topInv);

            queueDropPickupLog(
                player.getName(),
                player.getUniqueId().toString(),
                "pickup",
                location,
                itemName + " x" + amount,
                source
            );
        }
    }

    @EventHandler
    public void onContainerBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("dropPickup.enable", true)) return;

        Block block = event.getBlock();
        Player player = event.getPlayer();

        if (!(block.getState() instanceof InventoryHolder holder)) return;

        Inventory inv = holder.getInventory();
        if (inv == null) return;

        String uuid = player.getUniqueId().toString();

        Location loc = block.getLocation();
        String location =
                loc.getWorld().getName() + " " +
                loc.getBlockX() + " " +
                loc.getBlockY() + " " +
                loc.getBlockZ();

        String source = getBlockSource(block);

        /* --------------------------------------------------
        1️⃣ Container'ın KENDİSİ DROP OLDU MU?
        -------------------------------------------------- */
        if (event.isDropItems()) {
            Collection<ItemStack> drops =
                    block.getDrops(player.getInventory().getItemInMainHand());

            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) continue;

                queueDropPickupLog(
                        player.getName(),
                        uuid,
                        "drop",
                        location,
                        drop.getType().name() + " x" + drop.getAmount(),
                        source
                );
            }
        }

        /* --------------------------------------------------
        2️⃣ SHULKER BOX ise İÇİNDEKİ ITEM'LERİ GEÇ
        -------------------------------------------------- */
        if (source.endsWith("SHULKER_BOX")) return;

        /* --------------------------------------------------
        3️⃣ Diğer container item içeriklerini logla
        -------------------------------------------------- */
        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType().isAir()) continue;

            String itemName = item.getType().name();
            int amount = item.getAmount();

            if (!matchKeywords(itemName)) continue;

            queueDropPickupLog(
                    player.getName(),
                    uuid,
                    "drop",
                    location,
                    itemName + " x" + amount,
                    source
            );
        }
    }

    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        if (!plugin.getConfig().getBoolean("dropPickup.enable", true)) return;
        if (event.getDrops().isEmpty()) return;

        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller(); // olabilir / olmayabilir

        Location loc = entity.getLocation();
        String location =
                loc.getWorld().getName() + " " +
                loc.getBlockX() + " " +
                loc.getBlockY() + " " +
                loc.getBlockZ();

        String source = "ENTITY_DEATH:" + entity.getType().name();

        for (ItemStack item : event.getDrops()) {
            if (item == null || item.getType().isAir()) continue;
            if (!matchKeywords(item.getType().name())) continue;

            queueDropPickupLog(
                    killer != null ? killer.getName() : "UNKNOWN",
                    killer != null ? killer.getUniqueId().toString() : "UNKNOWN",
                    "drop",
                    location,
                    item.getType().name() + " x" + item.getAmount(),
                    source
            );
        }
    }

    @EventHandler
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (!plugin.getConfig().getBoolean("dropPickup.enable", true)) return;

        Location loc = event.getLocation();
        String baseLocation =
                loc.getWorld().getName() + " " +
                loc.getBlockX() + " " +
                loc.getBlockY() + " " +
                loc.getBlockZ();

        String source = "EXPLOSION:" + event.getEntityType().name();

        for (Block block : event.blockList()) {
            // InventoryHolder şartı kaldırıldı
            ItemStack drop = new ItemStack(block.getType(), 1);

            if (!matchKeywords(drop.getType().name())) continue;

            String blockLocation =
                    block.getWorld().getName() + " " +
                    block.getX() + " " +
                    block.getY() + " " +
                    block.getZ();

            queueDropPickupLog(
                    "UNKNOWN",          // player name
                    "UNKNOWN",          // uuid
                    "drop",
                    blockLocation,
                    drop.getType().name() + " x1",
                    source
            );
        }
    }

    @EventHandler
    public void onItemDespawn(org.bukkit.event.entity.ItemDespawnEvent event) {
        handleItemDeath(event.getEntity(), "DESPAWN");
    }

    @EventHandler
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;

        switch (event.getCause()) {
            case LAVA, FIRE, FIRE_TICK, VOID -> handleItemDeath(item, event.getCause().name());
            default -> {}
        }
    }

    @EventHandler
    public void onItemDamageByBlock(EntityDamageByBlockEvent event) {
        if (!(event.getEntity() instanceof Item item)) return;

        if (event.getDamager().getType() == Material.CACTUS) {
            handleItemDeath(item, "CACTUS");
        }
    }

    private void handleItemDeath(Item itemEntity, String cause) {
        UUID id = itemEntity.getUniqueId();

        // Zaten loglandıysa çık
        if (loggedItems.contains(id)) return;
        loggedItems.add(id);

        if (!plugin.getConfig().getBoolean("dropPickup.enable", true)) return;

        ItemStack item = itemEntity.getItemStack();
        if (item == null || item.getType().isAir()) return;
        if (!matchKeywords(item.getType().name())) return;

        Location loc = itemEntity.getLocation();
        String location = loc.getWorld().getName() + " " +
                        loc.getBlockX() + " " +
                        loc.getBlockY() + " " +
                        loc.getBlockZ();

        queueDropPickupLog(
                "UNKNOWN",
                "UNKNOWN",
                "remove",
                location,
                item.getType().name() + " x" + item.getAmount(),
                cause
        );

        // 5 saniye sonra hafızadan temizle
        Bukkit.getScheduler().runTaskLater(plugin, () -> loggedItems.remove(id), 100L);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        if (this.plugin.getConfig().getBoolean("dropPickup.enable", true)) {
            Player p = e.getPlayer();
            String uuid = p.getUniqueId().toString();
            ItemStack stack = e.getItemDrop().getItemStack();
            String itemName = stack.getType().name();

            if (!matchKeywords(itemName)) return;

            int amount = stack.getAmount();
            Location loc = e.getItemDrop().getLocation();
            String var10000 = loc.getWorld().getName();
            String location = var10000 + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
            queueDropPickupLog(
                p.getName(),
                uuid,
                "drop",
                location,
                itemName + " x" + amount,
                "GROUND"
            );
        }
    }

    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent e) {
        if (this.plugin.getConfig().getBoolean("dropPickup.enable", true)) {
            LivingEntity var3 = e.getEntity();
            if (var3 instanceof Player) {
                Player p = (Player)var3;
                String var9 = p.getUniqueId().toString();
                ItemStack stack = e.getItem().getItemStack();
                String itemName = stack.getType().name();
                String uuid = p.getUniqueId().toString();

                if (!matchKeywords(itemName)) return;
                
                int amount = stack.getAmount();
                Location loc = e.getItem().getLocation();
                String var10000 = loc.getWorld().getName();
                String location = var10000 + " " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
                queueDropPickupLog(
                    p.getName(),
                    uuid,
                    "pickup",
                    location,
                    itemName + " x" + amount,
                    "GROUND"
                );
            }
        }
    }

    static class DropPickupLog {
        int id;        // DB id
        int discord;   // 0 / 1
        String time;
        String server;
        String type;
        String player;
        String uuid;
        String location;
        String item;
        String source;

        DropPickupLog(int id, int discord, String time, String server,
              String type, String player, String uuid,
              String location, String item, String source) {

            this.id = id;
            this.discord = discord;
            this.time = time;
            this.server = server;
            this.type = type;
            this.player = player;
            this.uuid = uuid;
            this.location = location;
            this.item = item;
            this.source = source;
        }
    }
}
