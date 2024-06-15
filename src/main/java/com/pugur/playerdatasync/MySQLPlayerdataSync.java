package com.pugur.playerdatasync;

import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MySQLPlayerdataSync extends JavaPlugin implements Listener {

    private static String JDBC_URL;
    private static String USER;
    private static String PASSWORD;
    public static final Logger logger = LoggerFactory.getLogger("Paper Playerdata Sync");
    private static String SERVER_NAME;
    private static Map<Object, Integer> attempts = new HashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public static void delayTask(Runnable task, long delay, TimeUnit timeUnit) {
        scheduler.schedule(task, delay, timeUnit);
    }

    @Override
    public void onEnable() {
        ConfigManager.init(this);
        YamlConfiguration config = ConfigManager.getConfig();
        JDBC_URL = config.getString("jdbc.url");
        USER = config.getString("jdbc.user");
        PASSWORD = config.getString("jdbc.password");

        if (JDBC_URL == null || USER == null || PASSWORD == null || JDBC_URL.isEmpty() || USER.isEmpty() || PASSWORD.isEmpty()) {
            getLogger().info("データベース設定が見つかりませんでした。プラグインを無効化します。");
            this.setEnabled(false);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        createTable();
        getLevelName();
        logger.info("server name is : " + SERVER_NAME);
        logger.info("Paper Playerdata Sync is enabled🔥");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            savePlayerDataToDatabase(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        loadPlayerDataFromDatabase(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        savePlayerDataToDatabase(event.getPlayer());
    }

    public void savePlayerDataToDatabase(Player player) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            boolean crash_flag = false;
            String check = "SELECT crash_flag FROM playerdata WHERE uuid = ?";
            try (PreparedStatement s1 = connection.prepareStatement(check)) {
                s1.setString(1, player.getUniqueId().toString());
                try (ResultSet resultSet = s1.executeQuery()) {
                    if (resultSet.next()) {
                        crash_flag = resultSet.getBoolean("crash_flag");
                    }
                }
            }

            if (!crash_flag) {
                String saving = "UPDATE playerdata SET saving_flag = ? WHERE uuid = ?";
                try (PreparedStatement s = connection.prepareStatement(saving)) {
                    s.setBoolean(1, true);
                    s.setString(2, player.getUniqueId().toString());
                    s.executeUpdate();
                }

                String sql = "INSERT INTO playerdata (uuid, inventory, enderitems, selecteditemslot, conn_flag, saving_flag) " +
                        "VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "inventory = VALUES(inventory), enderitems = VALUES(enderitems), selecteditemslot = VALUES(selecteditemslot), conn_flag = VALUES(conn_flag), saving_flag = VALUES(saving_flag)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, player.getUniqueId().toString());
                    statement.setString(2, getPlayerInventory(player));
                    statement.setString(3, getPlayerEnderitems(player));
                    statement.setInt(4, player.getInventory().getHeldItemSlot());
                    statement.setBoolean(5, false);
                    statement.setBoolean(6, false);
                    statement.executeUpdate();
                    logger.info("🔥 " + player.getName() + " saved playerdata to mysql");
                }
            }
        } catch (SQLException e) {
            logger.error("Error while saving player data to mysql: " + e.getMessage());
        }
    }

    public void loadPlayerDataFromDatabase(Player player) {
        final Integer counter = attempts.getOrDefault(player.getUniqueId().toString(), 0);
        attempts.put(player.getUniqueId().toString(), counter + 1);
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            String sql = "SELECT inventory, enderitems, selecteditemslot, conn_flag, saving_flag, rollback_flag, crash_flag, lastserver, rollbackserver FROM playerdata WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, player.getUniqueId().toString());

                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        boolean conn_flag = resultSet.getBoolean("conn_flag");
                        boolean saving_flag = resultSet.getBoolean("saving_flag");
                        boolean rollback_flag = resultSet.getBoolean("rollback_flag");
                        boolean crash_flag = resultSet.getBoolean("crash_flag");
                        String lastServer = resultSet.getString("lastserver");
                        String rollbackServer = resultSet.getString("rollbackserver");

                        if (crash_flag) {
                            if (lastServer.equals(SERVER_NAME)) {
                                crashRestore(player, lastServer);
                            } else {
                                crashSkip(player, lastServer);
                            }
                        } else if (rollback_flag) {
                            if (rollbackServer.equals(SERVER_NAME)) {
                                rollbackRestore(player, rollbackServer);
                            } else {
                                rollbackSkip(player, rollbackServer);
                            }
                        } else if (conn_flag) {
                            logger.info("conn_flag is true");
                            if (counter < 100) {
                                delayTask(() -> loadPlayerDataFromDatabase(player), 25, TimeUnit.MILLISECONDS);
                            } else {
                                if (saving_flag) {
                                    logger.info("saving_flag is true");
                                    Component formattedMessage = Component.text().append(
                                            Component.text("Paper プレイヤーデータ共有 : ", NamedTextColor.AQUA)).append(
                                            Component.text("失敗", NamedTextColor.DARK_RED)).build();
                                    Component formattedMessage2 = Component.text().append(
                                            Component.text(lastServer, NamedTextColor.DARK_RED)).append(
                                            Component.text("に入りなおしてください", NamedTextColor.WHITE)).build();
                                    player.sendMessage(formattedMessage);
                                    player.sendMessage(formattedMessage2);
                                    String crashSet = "UPDATE playerdata SET crash_flag = true WHERE uuid = ?";
                                    try (PreparedStatement s = connection.prepareStatement(crashSet)) {
                                        s.setString(1, player.getUniqueId().toString());
                                        s.executeUpdate();
                                    }
                                } else {
                                    if (lastServer.equals(SERVER_NAME)) {
                                        crashRestore(player, lastServer);
                                    } else {
                                        crashSkip(player, lastServer);
                                    }
                                }
                                attempts.put(player.getUniqueId().toString(), 0);
                            }
                        } else {
                            setPlayerInventory(player, resultSet.getString("inventory"));
                            setPlayerEnderitems(player, resultSet.getString("enderitems"));
                            player.getInventory().setHeldItemSlot(resultSet.getInt("selecteditemslot"));
                            String connSet = "UPDATE playerdata SET conn_flag = ?, lastserver = ? WHERE uuid = ?";
                            try (PreparedStatement s = connection.prepareStatement(connSet)) {
                                s.setBoolean(1, true);
                                s.setString(2, SERVER_NAME);
                                s.setString(3, player.getUniqueId().toString());
                                s.executeUpdate();
                            }

                            logger.info("🔥 " + player.getName() + " loaded playerdata from mysql");
                            Component formattedMessage3 = Component.text().append(
                                    Component.text("Paper プレイヤーデータ共有 : ", NamedTextColor.AQUA)).append(
                                    Component.text("完了", NamedTextColor.GREEN)).build();
                            player.sendMessage(formattedMessage3);
                        }
                    } else {
                        String registerPlayer = "INSERT INTO playerdata (uuid, conn_flag, lastserver) VALUES (?, ?, ?)";
                        try (PreparedStatement s2 = connection.prepareStatement(registerPlayer)) {
                            s2.setString(1, player.getUniqueId().toString());
                            s2.setBoolean(2, true);
                            s2.setString(3, SERVER_NAME);
                            s2.executeUpdate();
                        }
                        logger.info("🔥 " + player.getName() + " was registered to mysql");
                    }
                }
            }
        } catch (SQLException | InterruptedException e) {
            logger.error("Error while loading player data from database: " + e.getMessage());
            Component formattedMessage4 = Component.text().append(
                    Component.text("Paper プレイヤーデータ共有 : ", NamedTextColor.AQUA)).append(
                    Component.text("失敗(SQL Exception | InterruptedException)", NamedTextColor.DARK_RED)).build();
            player.sendMessage(formattedMessage4);
        }
    }

    public String getPlayerInventory(Player player) {
        Inventory inventory = player.getInventory();
        ItemStack[] itemStack = inventory.getContents();
        ReadWriteNBT nbt = NBT.itemStackArrayToNBT(itemStack);
        String inventoryString = String.valueOf(nbt);
        inventoryString = inventoryString.replaceAll("Slot:36", "Slot:100")
                .replaceAll("Slot:37", "Slot:101")
                .replaceAll("Slot:38", "Slot:102")
                .replaceAll("Slot:39", "Slot:103")
                .replaceAll("Slot:40", "Slot:-106");
        return formatString(inventoryString);
    }

    public String getPlayerEnderitems(Player player) {
        Inventory enderitems = player.getEnderChest();
        ItemStack[] itemStack = enderitems.getContents();
        ReadWriteNBT nbt = NBT.itemStackArrayToNBT(itemStack);
        String enderitemsString = String.valueOf(nbt);
        return formatString(enderitemsString);
    }

    public String formatString(String string) {
        String formattedString;
        try {
            String beginStr = "[";
            int beginIndex = string.indexOf(beginStr);
            String endStr = ",size";
            int endIndex = string.indexOf(endStr);
            formattedString = string.substring(beginIndex, endIndex);
        } catch (Exception ignored) {
            formattedString = "[]";
        }
        return formattedString;
    }

    public void setPlayerInventory(Player player, String inventoryString) {
        player.getInventory().clear();
        inventoryString = inventoryString.replaceAll("Slot:100", "Slot:36")
                .replaceAll("Slot:101", "Slot:37")
                .replaceAll("Slot:102", "Slot:38")
                .replaceAll("Slot:103", "Slot:39")
                .replaceAll("Slot:-106", "Slot:40");
        inventoryString = "{items:" + inventoryString + ",size:41}";
        ReadWriteNBT nbt = NBT.parseNBT(inventoryString);
        ItemStack[] itemStack = NBT.itemStackArrayFromNBT(nbt);
        Inventory inventory = player.getInventory();
        inventory.setContents(Objects.requireNonNull(itemStack));
    }

    public void setPlayerEnderitems(Player player, String enderitemsString) {
        player.getEnderChest().clear();
        enderitemsString = "{items:" + enderitemsString + ",size:27}";
        ReadWriteNBT nbt = NBT.parseNBT(enderitemsString);
        ItemStack[] itemStack = NBT.itemStackArrayFromNBT(nbt);
        Inventory enderChest = player.getEnderChest();
        enderChest.setContents(Objects.requireNonNull(itemStack));
    }

    public void crashSkip(Player player, String lastServer) {
        Component formattedMessage = Component.text().append
                        (Component.text("前回インベントリのセーブに失敗しています。\n", NamedTextColor.RED)).append
                        (Component.text(lastServer + "に入り直してください", NamedTextColor.GREEN))
                .build();
        player.sendMessage(formattedMessage);
        logger.info(player.getName() + " skipped loading playerdata");
    }

    public void crashRestore(Player player, String lastServer) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            Component formattedMessage = Component.text().append
                            (Component.text("最後に" + lastServer + "に保存されたインベントリを復元しました", NamedTextColor.DARK_GREEN))
                    .build();
            player.sendMessage(formattedMessage);
            String sql = "UPDATE playerdata SET crash_flag = false, saving_flag = false, conn_flag = true WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, player.getUniqueId().toString());
                statement.executeUpdate();
                logger.info(player.getName() + " restored playerdata(crash)");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void rollbackSkip(Player player, String rollbackServer) throws InterruptedException {
        Component formattedMessage = Component.text().append
                        (Component.text("サバイバルワールドのロールバックを行いました。\n", NamedTextColor.LIGHT_PURPLE)).append
                        (Component.text(rollbackServer + "に入ってください", NamedTextColor.GREEN))
                .build();
        player.sendMessage(formattedMessage);
        logger.info(player.getName() + " skipped to load playerdata(rollback)");
    }

    public void rollbackRestore(Player player, String rollbackServer) {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            Component formattedMessage = Component.text().append
                            (Component.text("バックアップ時に" + rollbackServer + "に保存されたインベントリを復元しました", NamedTextColor.DARK_GREEN))
                    .build();
            player.sendMessage(formattedMessage);
            String sql = "UPDATE playerdata SET rollback_flag = false WHERE uuid = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, player.getUniqueId().toString());
                statement.executeUpdate();
                logger.info(player.getName() + " restored playerdata(rollback)");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void createTable() {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            String sql = "CREATE TABLE IF NOT EXISTS playerdata (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "inventory LONGTEXT, " +
                    "enderitems LONGTEXT, " +
                    "selecteditemslot INT, " +
                    "conn_flag BOOLEAN DEFAULT FALSE, " + //サーバーに参加中の時にtrueになる
                    "saving_flag BOOLEAN DEFAULT FALSE, " + //サーバーから退出時に、insert queryが完了するまでtrueになる
                    "rollback_flag BOOLEAN DEFAULT FALSE, " + //discord bot側からrollbackコマンドを実行した際にtrueになる
                    //todo 複数サーバーの同時ロールバック時にどうするか決めて、pythonのほうを改変しないといけないと思う。すでにこのflagがtrueならrollbackserverの更新をしないなど
                    "crash_flag BOOLEAN DEFAULT FALSE, " + //これはロードの失敗時にtrueになり、savingを行わないためだけに使用される。loadは関係ない。crushRestoreでfalseになる
                    "lastserver VARCHAR(256), " + //最後に接続していたサバイバルワールドの名前が記録される
                    "rollbackserver VARCHAR(256) " + //最後にロールバックされたサーバーが記録される
                    ")";
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
            logger.info("🔥 MySQL Data Table was Created Successfully");
        } catch (SQLException e) {
            logger.error("Error while creating MySQL playerdata table on database: " + e.getMessage());
        }
    }

    public static void getLevelName() {
        List<World> worlds = Bukkit.getWorlds();
        SERVER_NAME= worlds.get(0).getName();
    }
}
