package me.teslay.evercontroller;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public class EverController extends JavaPlugin implements Listener {
    private Connection connection;
  
    private CommandLogger commandLogger;
  
    private PlayerMessage playerMessage;
  
    private DropPickup dropPickup;
  
    private BreakPlace breakPlace;
  
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs(); 

        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
            Bukkit.getConsoleSender().sendMessage(
                "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                "Config.yml bulunamadı, yeni bir tane oluşturuldu!"
            );
            Bukkit.getConsoleSender().sendMessage(
                "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                "Lütfen config.yml dosyasını düzenleyip MySQL bilgilerini girin."
            );
            getServer().getPluginManager().disablePlugin((Plugin)this);
            return;
        } 

        saveDefaultConfig();
        connectDatabase();

        this.breakPlace = new BreakPlace(this, this.connection);
        this.breakPlace.createBreakPlaceTable();
        this.dropPickup = new DropPickup(this, this.connection);
        this.dropPickup.createDropPickupTable();
        this.commandLogger = new CommandLogger(this, this.connection);
        this.commandLogger.createCommandTable();
        this.playerMessage = new PlayerMessage(this, this.connection);
        this.playerMessage.createPlayerMessageTable();

        getServer().getPluginManager().registerEvents(this.commandLogger, (Plugin)this);
        getServer().getPluginManager().registerEvents(this.playerMessage, (Plugin)this);
        getServer().getPluginManager().registerEvents(this.dropPickup, (Plugin)this);
        getServer().getPluginManager().registerEvents(this.breakPlace, (Plugin)this);

        Bukkit.getScheduler().runTaskTimerAsynchronously((Plugin)this, () -> {
            this.breakPlace.sendBreakPlaceLogs();
            this.dropPickup.sendDropPickupLogs();
            this.playerMessage.sendPlayerMessageLogs();
            this.commandLogger.sendCommandLogs();
        }, 0L, 60L);

        Bukkit.getPluginManager().registerEvents(this, (Plugin)this);
        Bukkit.getConsoleSender().sendMessage(
            "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
            "EverController başarıyla aktif edildi!"
        );
    }
  
    public void onDisable() {
        if (this.connection != null)
            try {
            this.connection.close();
            } catch (SQLException e) {
            e.printStackTrace();
        }  
        Bukkit.getConsoleSender().sendMessage(
            "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
            "EverController başarıyla kapatıldı!"
        );
    }
  
    FileConfiguration config = getConfig();
  
    String breakPlaceTable = this.config.getString("breakPlace.table");
    String dropPickupTable = this.config.getString("dropPickup.table");
    String playerMessageTable = this.config.getString("playerMessage.table");
    String commandTable = this.config.getString("command.table");
    String serverName = this.config.getString("serverName");

    private void connectDatabase() {
        FileConfiguration config = getConfig();
        String host = config.getString("mysql.host");
        String database = config.getString("mysql.database");
        String username = config.getString("mysql.username");
        String password = config.getString("mysql.password");
        int port = config.getInt("mysql.port");
        try {
            Connection tempConnection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/?useSSL=false", username, password);
            Statement stmt = tempConnection.createStatement();
        try {
            stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database + "`");
            if (stmt != null)
            stmt.close(); 
        } catch (Throwable throwable) {
            if (stmt != null)
            try {
                stmt.close();
            } catch (Throwable throwable1) {
                throwable.addSuppressed(throwable1);
            }  
            throw throwable;
        } 
            tempConnection.close();
            try {
                this.connection = DriverManager.getConnection(
                    "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false",
                    username,
                    password
                );
            } catch (SQLException e) {

                String msg = e.getMessage();

                if (msg != null && msg.toLowerCase().contains("access denied for user")) {
                    // SADECE YETKİ / ŞİFRE HATASI
                    Bukkit.getConsoleSender().sendMessage(
                        ChatColor.GREEN + "[EverController] " + ChatColor.RESET +
                        ChatColor.RED + "config.yml üzerinde belirtilen MySQL kullanıcı adı veya şifre hatalı!"
                    );
                } else {
                    // DİĞER MYSQL HATALARI
                    Bukkit.getConsoleSender().sendMessage(
                        ChatColor.GREEN + "[EverController] " + ChatColor.RESET +
                        ChatColor.RED + "MySQL bağlantısı kurulamadı: " + msg
                    );
                }

                // Pluginin devam etmemesi gerekiyorsa
                Bukkit.getPluginManager().disablePlugin(this);
            }
            Bukkit.getConsoleSender().sendMessage(
                "[" + ChatColor.GREEN + "EverController" + ChatColor.RESET + "] " + ChatColor.YELLOW +
                "EverController başarıyla MySQL'a bağlandı!"
            );
    } catch (SQLException e) {
        e.printStackTrace();
        } 
    }
}