package com.meows.lifeplugin;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class LifePlugin extends JavaPlugin implements Listener {

    private FileConfiguration playersConfig;
    private File playersFile;
    private static final long TWELVE_HOURS = 12 * 60 * 60 * 1000; // 12 часов в миллисекундах
    private static final long TWENTY_FOUR_HOURS = 24 * 60 * 60 * 1000; // 24 часа в миллисекундах

    @Override
    public void onEnable() {
        try {
            getServer().getPluginManager().registerEvents(this, this);
            saveDefaultConfig();
            createPlayersConfig();
            getLogger().info("\u001B[32mПлагин успешно загружен!\u001B[0m");
        } catch (Exception e) {
            getLogger().severe("§cОшибка при включении LifePlugin!"); // Красный цвет для ошибки
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (playersConfig != null) {
            savePlayersConfig();
            getLogger().info("\u001B[32mПлагин успешно выключен!\u001B[0m");
        }
    }

    private void createPlayersConfig() {
        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            try {
                playersFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("§cНе удалось создать файл players.yml!");
                e.printStackTrace();
            }
        }
        // Инициализируем конфигурацию перед сохранением
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);

        // Если файл был только что создан, сохраняем пустую конфигурацию
        if (playersFile.exists() && playersFile.length() == 0) {
            savePlayersConfig();
        }
    }

    private void savePlayersConfig() {
        try {
            playersConfig.save(playersFile);
        } catch (IOException e) {
            getLogger().severe("§cНе удалось сохранить файл players.yml!");
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID playerId = player.getUniqueId();

        // Получаем количество жизней игрока
        int lives = playersConfig.getInt("players." + playerId + ".lives", getConfig().getInt("lives", 2));
        lives--;

        if (lives > 0) {
            // Если жизни остались, просто обновляем их
            player.sendMessage("§cОсталось жизней: §6" + lives);
            playersConfig.set("players." + playerId + ".lives", lives);
            playersConfig.set("players." + playerId + ".lastDeathTime", System.currentTimeMillis());
            savePlayersConfig();
        } else {
            // Если жизни закончились, откладываем возрождение и бан
            // Сохраняем значения конфига перед лямбда-выражением, чтобы избежать проблем с
            // контекстом
            final long banTimeMillis = getConfig().getLong("ban-time", 86400L) * 1000L;
            final String deathMessage = getConfig().getString("death-message", "Вы потеряли все жизни! Бан на сутки.");

            Bukkit.getScheduler().runTask(this, () -> {
                // Проверяем, есть ли у игрока точка спавна (кровать)
                Location respawnLocation = player.getBedSpawnLocation();
                if (respawnLocation != null) {
                    // Если точка спавна есть, возрождаем на ней
                    player.spigot().respawn();
                    player.teleport(respawnLocation);
                } else {
                    // Если нет, возрождаем на мировом спавне
                    player.spigot().respawn(); // Это вызовет стандартное возрождение
                }

                // Очищаем инвентарь перед баном
                player.getInventory().clear();
                // player.getEnderChest().clear();

                // Воспроизведение звука для всех
                Bukkit.getOnlinePlayers().forEach(
                        p -> p.playSound(p.getLocation(), "minecraft:entity.player.death", 1.0f, 1.0f));

                // Время бана
                long unbanTime = System.currentTimeMillis() + banTimeMillis;

                // Добавляем бан в список
                Bukkit.getBanList(BanList.Type.NAME).addBan(player.getName(),
                        deathMessage,
                        new java.util.Date(unbanTime), null);
                player.kickPlayer("Вы потеряли все жизни! Попробуйте зайти позже");

                // Сохраняем время бана и убираем жизни игрока
                playersConfig.set("players." + playerId + ".unbanTime", unbanTime);
                playersConfig.set("players." + playerId + ".lives", null);
                savePlayersConfig();
            });
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Проверяем, есть ли информация о бане игрока
        if (playersConfig.contains("players." + playerId + ".unbanTime")) {
            long unbanTime = playersConfig.getLong("players." + playerId + ".unbanTime", 0);
            long currentTime = System.currentTimeMillis();

            // Если бан закончился
            if (currentTime >= unbanTime) {
                // Убираем игрока из черного списка
                Bukkit.getBanList(BanList.Type.NAME).pardon(player.getName());
                playersConfig.set("players." + playerId + ".lastDeathTime", currentTime); // Обновляем время последней
                                                                                          // смерти после разбана

                // Очищаем инвентарь игрока
                // player.getInventory().clear();
                // player.getEnderChest().clear();

                // Проверяем статус смерти
                if (player.isDead()) {
                    player.spigot().respawn(); // Переспавниваем игрока, если он мертв
                }

                // Восстанавливаем здоровье и насыщение
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);

                // Устанавливаем новое количество жизней
                int defaultLives = getConfig().getInt("lives", 2);
                playersConfig.set("players." + playerId + ".lives", defaultLives);
                playersConfig.set("players." + playerId + ".lastDeathTime", null);
                playersConfig.set("players." + playerId + ".unbanTime", null);
                savePlayersConfig();

                // Информируем игрока о восстановлении жизней
                player.sendMessage("§aВаши жизни восстановлены, и ваш инвентарь очищен");

            }
        }

        // Если жизней у игрока еще нет в конфиге, добавляем их с дефолтным значением
        if (!playersConfig.contains("players." + playerId + ".lives")) {
            int defaultLives = getConfig().getInt("lives", 2);
            playersConfig.set("players." + playerId + ".lives", defaultLives);
            savePlayersConfig();
            player.sendMessage("§aДобро пожаловать! Вам выдано §6" + defaultLives + " §aжизней");
        } else {
            // Проверяем, прошел ли 1 день с последней смерти, чтобы восстановить жизни
            int lives = playersConfig.getInt("players." + playerId + ".lives");
            long lastDeathTime = playersConfig.getLong("players." + playerId + ".lastDeathTime", 0);
            long timeSinceLastDeath = System.currentTimeMillis() - lastDeathTime;

            if (lives < getConfig().getInt("lives", 2)
                    && timeSinceLastDeath >= getConfig().getLong("recovery-time") * 1000L) { // 12 часов
                int restoredLives = getConfig().getInt("lives", 2);
                playersConfig.set("players." + playerId + ".lives", restoredLives);
                playersConfig.set("players." + playerId + ".lastDeathTime", null);
                savePlayersConfig();
                player.sendMessage("§aВаши жизни восстановлены до §6" + restoredLives + "§a!");
            } else {
                // Если жизни не восстанавливались, просто показываем текущее количество
                player.sendMessage("§aУ вас осталось §6" + lives + " §aжизни");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        long currentTime = System.currentTimeMillis(); // Получаем текущее время
        // Команда /lifeplugin
        if (command.getName().equalsIgnoreCase("lifeplugin")) {
            // Для администраторов (изменение жизней)
            if (args.length == 3 && args[0].equalsIgnoreCase("life")) {
                if (sender.hasPermission("lifeplugin.setlives")) {
                    Player targetPlayer = Bukkit.getPlayer(args[1]);
                    int lives;

                    try {
                        lives = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cКоличество жизней должно быть числом!");
                        return true;
                    }

                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        UUID playerId = targetPlayer.getUniqueId();
                        playersConfig.set("players." + playerId + ".lives", lives);
                        playersConfig.set("players." + playerId + ".lastDeathTime", currentTime); // Обновляем время
                                                                                                  // последней смерти
                        savePlayersConfig();
                        targetPlayer.sendMessage("§aВаши жизни были изменены на §e" + lives + " §aжизни!");
                        sender.sendMessage("§aВы изменили количество жизней игрока §e" + targetPlayer.getName()
                                + " §aна §e" + lives + ".");
                        return true;
                    } else {
                        sender.sendMessage("§cИгрок с таким ником не найден или он не в сети");
                        return true;
                    }
                } else {
                    sender.sendMessage("§cУ вас нет прав для изменения жизней!");
                    return true;
                }
            }

            // Если команда вызвана без аргументов
            if (args.length == 0) {
                sender.sendMessage("§aКоманды плагина LifePlugin:");
                sender.sendMessage("§e/lifeplugin life <player> <lives> §7- Установить количество жизней игроку");
                sender.sendMessage("§e/lifepluginreload §7- Перезагрузить конфигурацию плагина");
                return true;
            }

            sender.sendMessage("§cНеизвестная команда. Используйте §e/lifeplugin §cдля просмотра доступных команд");
            return true;
        }

        // Команда /checklife для игроков
        if (command.getName().equalsIgnoreCase("checklife")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;

                // Проверка на наличие разрешения
                if (player.hasPermission("lifeplugin.checklife")) {
                    UUID playerId = player.getUniqueId();
                    int maxLives = getConfig().getInt("lives", 2);
                    int currentLives = playersConfig.getInt("players." + playerId + ".lives", maxLives);
                    long lastDeathTime = playersConfig.getLong("players." + playerId + ".lastDeathTime", 0);
                    long timeSinceLastDeath = System.currentTimeMillis() - lastDeathTime;

                    // Вывод текущего количества жизней
                    if (currentLives > 1) {
                        player.sendMessage("§aУ вас осталось §e" + currentLives + " §6жизни");
                    } else if (currentLives == 1) {
                        player.sendMessage("§aУ вас осталась §e" + currentLives + " §6жизнь");
                    } else {
                        player.sendMessage("§cВнимание! §4У вас больше нет жизней");
                    }

                    // Если жизней меньше максимального, показываем время до восстановления
                    if (currentLives < maxLives) {
                        long recoveryTime = getConfig().getLong("recovery-time") * 1000L - timeSinceLastDeath; // время
                                                                                                               // восстанов
                                                                                                               // прошд
                                                                                                               // время
                        if (recoveryTime > 0) {
                            String formattedTime = formatTime(recoveryTime);
                            player.sendMessage("§aДополнительная жизнь восстановится через §e" + formattedTime + ".");
                        } else {
                            player.sendMessage(
                                    "§aДополнительная жизнь должна была восстановиться. Пожалуйста, сообщите администратору");
                        }
                    }
                    return true;
                } else {
                    sender.sendMessage("§cУ вас нет прав для использования этой команды");
                    return true;
                }
            } else {
                sender.sendMessage("§cЭта команда доступна только игрокам");
                return true;
            }
        }

        // Команда /lifepluginreload для перезагрузки конфигурации
        if (command.getName().equalsIgnoreCase("lifepluginreload")) {
            if (sender.hasPermission("lifeplugin.reload")) { // Проверка прав администратора
                try {
                    // Проверяем и загружаем players.yml
                    if (playersFile == null) {
                        playersFile = new File(getDataFolder(), "players.yml");
                    }
                    if (!playersFile.exists()) {
                        saveResource("players.yml", false); // Создаём файл, если его нет
                    }
                    playersConfig = YamlConfiguration.loadConfiguration(playersFile);

                    // Перезагружаем config.yml
                    reloadConfig();

                    // Сообщение об успехе
                    sender.sendMessage("§aКонфиги config.yml и players.yml успешно перезагружены!");
                } catch (Exception e) {
                    sender.sendMessage("§cОшибка при перезагрузке конфигов!");
                    e.printStackTrace();
                }
                return true;
            } else {
                sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
                return true;
            }
        }

        // Логика для дарения жизней
        if (command.getName().equalsIgnoreCase("giftlife")) {

            if (sender instanceof Player) {
                Player player = (Player) sender;
                UUID playerId = player.getUniqueId();

                // Проверка на разрешение игрока
                if (command.getName().equalsIgnoreCase("giftlife")) {
                    if (sender instanceof Player) {
                        Player donor = (Player) sender;

                        if (!donor.hasPermission("lifeplugin.giftlife")) {
                            donor.sendMessage("§cУ вас нет прав для использования этой команды");
                            return true;
                        }

                        if (args.length != 1) {
                            donor.sendMessage("§cИспользование: §e/giftlife <игрок>");
                            return true;
                        }

                        String targetName = args[0];
                        UUID donorId = donor.getUniqueId();
                        int donorLives = playersConfig.getInt("players." + donorId + ".lives", 2);

                        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetName);
                        if (!targetPlayer.hasPlayedBefore() && !targetPlayer.isOnline()) {
                            donor.sendMessage("§cИгрок с таким ником не найден!");
                            return true;
                        }

                        if (player.getName() == targetPlayer.getName()) {
                            donor.sendMessage("§cНельзя подарить жизнь самому себе!");
                            return true;
                        }

                        if (donorLives <= 1) {
                            donor.sendMessage("§cУ вас недостаточно жизней для подарка!");
                            return true;
                        }

                        UUID targetId = targetPlayer.getUniqueId();
                        int targetLives = playersConfig.getInt("players." + targetId + ".lives", 0);

                        if (targetLives >= 2) {
                            donor.sendMessage("§cЭтот игрок уже имеет максимальное количество жизней");
                            return true;
                        }

                        playersConfig.set("players." + donorId + ".lives", donorLives - 1);
                        playersConfig.set("players." + targetId + ".lives", targetLives + 1);
                        playersConfig.set("players." + targetId + ".lastDeathTime", currentTime); // Обновляем время
                                                                                                  // последней смерти
                                                                                                  // получателя
                        playersConfig.set("players." + playerId + ".lastDeathTime", currentTime); // Обновляем время
                                                                                                  // последней смерти
                                                                                                  // получателя

                        if (Bukkit.getBanList(BanList.Type.NAME).isBanned(targetName)) {
                            Bukkit.getBanList(BanList.Type.NAME).pardon(targetName);
                        }

                        savePlayersConfig();

                        donor.sendMessage("§aВы подарили §e1 §aжизнь игроку §e" + targetName + "§a!");

                        // Сообщение в чат
                        Bukkit.broadcastMessage(
                                "§2" + player.getName() + "§f подарил 1 жизнь игроку" + " §e" + targetPlayer.getName());

                        // Воспроизведение звука для всех
                        Bukkit.getOnlinePlayers().forEach(
                                p -> p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 1.0f, 1.0f));

                        if (targetPlayer.isOnline()) {
                            Player onlineTarget = targetPlayer.getPlayer();
                            if (onlineTarget != null) {
                                onlineTarget.sendMessage(
                                        "§aВы получили §e1 §aжизнь от игрока §e" + donor.getName() + "§a!");
                            }
                        }

                        return true;
                    } else {
                        sender.sendMessage("§cЭта команда доступна только игрокам");
                        return true;
                    }
                }

            }
        }
        return false;
    }

    private String formatTime(long millis) {
        long hours = millis / 3600000;
        long minutes = (millis % 3600000) / 60000;
        long seconds = (millis % 60000) / 1000;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
