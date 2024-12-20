package com.wish.fishingskill;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.io.File;
import java.util.*;

public class FishingSkill extends JavaPlugin implements Listener {
    private Random random;
    private File configFile;
    private FileConfiguration config;
    private final String GUI_TITLE = ChatColor.GOLD + "Recompensas de Pesca";
    private static final long FISHING_COOLDOWN_MS = 1500; // 1.5 segundos de cooldown
    private Map<UUID, Long> fishingCooldown = new HashMap<>();
    private long fishingCooldownMs;
    private boolean cooldownEnabled;
    private String cooldownMessage;

    @Override
    public void onEnable() {
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        }
        reloadCustomConfig();
        random = new Random();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::cleanupCooldowns, 6000L, 6000L);
        getLogger().info("FishingSkill ha sido activado!");
    }

    private void reloadCustomConfig() {
        config = YamlConfiguration.loadConfiguration(configFile);
        if (config.getConfigurationSection("rewards") == null) {
            config.createSection("rewards");
        }
        if (config.getConfigurationSection("cooldown") == null) {
            config.createSection("cooldown");
            config.set("cooldown.enabled", true);
            config.set("cooldown.time", 1500);
            config.set("cooldown.message", "&c¡Debes esperar antes de volver a pescar!");
        }
        if (config.getConfigurationSection("anti-cheat") == null) {
            config.createSection("anti-cheat");
            config.set("anti-cheat.enabled", true);
            config.set("anti-cheat.check-radius", 0.5);
            config.set("anti-cheat.water-check-points", 4);
        }
        saveCustomConfig();

        // Cargar configuraciones de cooldown
        cooldownEnabled = config.getBoolean("cooldown.enabled", true);
        fishingCooldownMs = config.getLong("cooldown.time", 1500);
        cooldownMessage = ChatColor.translateAlternateColorCodes('&',
                config.getString("cooldown.message", "&c¡Debes esperar antes de volver a pescar!"));
    }

    private void saveCustomConfig() {
        try {
            config.save(configFile);
        } catch (Exception e) {
            getLogger().severe("Error al guardar la configuración: " + e.getMessage());
        }
    }

    @Override
    public FileConfiguration getConfig() {
        return config;
    }

    @Override
    public void saveConfig() {
        saveCustomConfig();
    }

    @Override
    public void reloadConfig() {
        reloadCustomConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("fishing")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Este comando solo puede ser ejecutado por jugadores.");
                return true;
            }

            Player player = (Player) sender;

            if (args.length == 0 || !args[0].equalsIgnoreCase("rewards")) {
                player.sendMessage(ChatColor.RED + "Uso correcto: /fishing rewards");
                return true;
            }

            if (!player.hasPermission("fishingskill.rewards")) {
                player.sendMessage(ChatColor.RED + "No tienes permiso para usar este comando.");
                return true;
            }

            openRewardsGUI(player);
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Este comando solo puede ser ejecutado por jugadores.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("fishingskill.admin")) {
            player.sendMessage(ChatColor.RED + "No tienes permiso para usar este comando.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(ChatColor.GOLD + "Comandos disponibles:");
            player.sendMessage(ChatColor.YELLOW + "/fskill add - Agrega el item en tu mano como recompensa");
            player.sendMessage(ChatColor.YELLOW + "/fskill remove <id> - Elimina una recompensa");
            player.sendMessage(ChatColor.YELLOW + "/fskill list - Muestra todas las recompensas");
            player.sendMessage(ChatColor.YELLOW + "/fskill chance <id> <probabilidad> - Establece la probabilidad");
            player.sendMessage(ChatColor.YELLOW + "/fskill reload - Recarga la configuración");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "add":
                ItemStack item = player.getItemInHand();
                if (item == null || item.getType() == Material.AIR) {
                    player.sendMessage(ChatColor.RED + "Debes tener un item en tu mano.");
                    return true;
                }

                ConfigurationSection rewardsSection = getConfig().getConfigurationSection("rewards");
                if (rewardsSection == null) {
                    rewardsSection = getConfig().createSection("rewards");
                }

                int id = rewardsSection.getKeys(false).isEmpty() ? 1 :
                        rewardsSection.getKeys(false).size() + 1;

                getConfig().set("rewards." + id + ".chance", 50.0);
                getConfig().set("rewards." + id + ".amount", item.getAmount());
                
                getConfig().set("rewards." + id + ".item.type", item.getType().name());

                if (item.hasItemMeta()) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta.hasDisplayName()) {
                        getConfig().set("rewards." + id + ".item.display-name", meta.getDisplayName());
                    }
                    if (meta.hasLore()) {
                        getConfig().set("rewards." + id + ".item.lore", meta.getLore());
                    }
                    if (meta.hasEnchants()) {
                        Map<String, Integer> enchants = new HashMap<>();
                        for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                            enchants.put(entry.getKey().getName(), entry.getValue());
                        }
                        getConfig().set("rewards." + id + ".item.enchantments", enchants);
                    }
                    
                    Set<ItemFlag> flags = meta.getItemFlags();
                    if (!flags.isEmpty()) {
                        List<String> itemFlags = new ArrayList<>();
                        for (ItemFlag flag : flags) {
                            itemFlags.add(flag.name());
                        }
                        getConfig().set("rewards." + id + ".item.item-flags", itemFlags);
                    }
                }

                saveConfig();
                reloadConfig();

                player.sendMessage(ChatColor.GREEN + "¡Item agregado como recompensa con ID " + id + "!");
                player.sendMessage(ChatColor.GRAY + "Probabilidad por defecto: 50%");
                break;

            case "list":
                ConfigurationSection rewards = getConfig().getConfigurationSection("rewards");
                if (rewards == null || rewards.getKeys(false).isEmpty()) {
                    player.sendMessage(ChatColor.RED + "No hay recompensas configuradas.");
                    return true;
                }

                player.sendMessage(ChatColor.GOLD + "=== Recompensas Configuradas ===");
                for (String key : rewards.getKeys(false)) {
                    try {
                        ConfigurationSection itemSection = rewards.getConfigurationSection(key + ".item");
                        if (itemSection == null) continue;

                        double chance = rewards.getDouble(key + ".chance", 0.0);
                        ItemStack rewardItem = ItemStack.deserialize(itemSection.getValues(true));
                        String itemName = rewardItem.hasItemMeta() && rewardItem.getItemMeta().hasDisplayName() ?
                                rewardItem.getItemMeta().getDisplayName() : rewardItem.getType().toString();
                        player.sendMessage(ChatColor.YELLOW + "ID: " + key + " - " + itemName +
                                ChatColor.GRAY + " (Probabilidad: " + chance + "%)");
                    } catch (Exception e) {
                        getLogger().warning("Error al cargar la recompensa " + key + ": " + e.getMessage());
                    }
                }
                break;

            case "remove":
                if (args.length != 2) {
                    player.sendMessage(ChatColor.RED + "Uso correcto: /fskill remove <id>");
                    return true;
                }

                try {
                    int removeId = Integer.parseInt(args[1]);
                    if (!getConfig().contains("rewards." + removeId)) {
                        player.sendMessage(ChatColor.RED + "No existe una recompensa con ese ID.");
                        return true;
                    }

                    getConfig().set("rewards." + removeId, null);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "¡Recompensa eliminada correctamente!");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Por favor, ingresa un ID válido.");
                }
                break;

            case "chance":
                if (args.length != 3) {
                    player.sendMessage(ChatColor.RED + "Uso correcto: /fskill chance <id> <probabilidad>");
                    return true;
                }

                try {
                    int rewardId = Integer.parseInt(args[1]);
                    double chance = Double.parseDouble(args[2]);

                    if (chance <= 0 || chance > 100) {
                        player.sendMessage(ChatColor.RED + "La probabilidad debe estar entre 0 y 100");
                        return true;
                    }

                    if (!getConfig().contains("rewards." + rewardId)) {
                        player.sendMessage(ChatColor.RED + "No existe una recompensa con ese ID.");
                        return true;
                    }

                    getConfig().set("rewards." + rewardId + ".chance", chance);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "¡Probabilidad actualizada!");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Por favor, ingresa números válidos.");
                }
                break;

            case "reload":
                reloadConfig();
                if (getConfig().getConfigurationSection("rewards") == null) {
                    getConfig().createSection("rewards");
                    saveConfig();
                }
                player.sendMessage(ChatColor.GREEN + "¡Configuración recargada!");
                break;

            default:
                player.sendMessage(ChatColor.RED + "Comando desconocido. Usa /fskill para ver los comandos disponibles.");
                break;
        }

        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Verificar si el cooldown está habilitado
        if (cooldownEnabled) {
            // Verificar cooldown
            if (fishingCooldown.containsKey(playerUUID)) {
                long timeLeft = fishingCooldown.get(playerUUID) - System.currentTimeMillis();
                if (timeLeft > 0) {
                    event.setCancelled(true);
                    player.sendMessage(cooldownMessage);
                    return;
                }
            }
        }

        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            event.setCancelled(true);

            if (isHookInWater(event)) {
                // Establecer cooldown solo si está habilitado
                if (cooldownEnabled) {
                    fishingCooldown.put(playerUUID, System.currentTimeMillis() + fishingCooldownMs);
                }

                // Procesar recompensa
                processReward(player);
            }
        }
    }

    // Añade este método para limpiar el mapa de cooldown periódicamente
    private void cleanupCooldowns() {
        long currentTime = System.currentTimeMillis();
        fishingCooldown.entrySet().removeIf(entry -> entry.getValue() < currentTime);
    }

    private boolean isHookInWater(PlayerFishEvent event) {
        if (event.getHook() == null) return false;

        // Si el anti-cheat está deshabilitado, usar verificación simple
        if (!config.getBoolean("anti-cheat.enabled", true)) {
            Block block = event.getHook().getLocation().getBlock();
            return block.getType() == Material.WATER || block.getType() == Material.STATIONARY_WATER;
        }

        Location hookLocation = event.getHook().getLocation();
        double radius = config.getDouble("anti-cheat.check-radius", 0.5);
        int points = config.getInt("anti-cheat.water-check-points", 4);
        double step = (radius * 2) / (points - 1);

        for (double x = -radius; x <= radius; x += step) {
            for (double z = -radius; z <= radius; z += step) {
                Block block = hookLocation.clone().add(x, 0, z).getBlock();
                if (block.getType() == Material.WATER || block.getType() == Material.STATIONARY_WATER) {
                    return true;
                }
            }
        }

        return false;
    }

    private void processReward(Player player) {
        ConfigurationSection rewards = getConfig().getConfigurationSection("rewards");
        if (rewards == null || rewards.getKeys(false).isEmpty()) {
            player.sendMessage(ChatColor.RED + "No hay recompensas configuradas.");
            return;
        }

        double totalChance = 0;
        for (String key : rewards.getKeys(false)) {
            totalChance += rewards.getDouble(key + ".chance", 0.0);
        }

        if (totalChance <= 0) {
            player.sendMessage(ChatColor.RED + "Error en la configuración de probabilidades.");
            return;
        }

        double randomValue = random.nextDouble() * totalChance;
        double currentSum = 0;

        for (String key : rewards.getKeys(false)) {
            currentSum += rewards.getDouble(key + ".chance", 0.0);
            if (randomValue <= currentSum) {
                giveReward(player, key);
                break;
            }
        }
    }

    private void giveReward(Player player, String rewardId) {
        try {
            ConfigurationSection itemSection = getConfig().getConfigurationSection("rewards." + rewardId + ".item");
            if (itemSection == null) {
                getLogger().warning("Error: Item section is null for reward " + rewardId);
                return;
            }

            Material material = Material.valueOf(itemSection.getString("type"));
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            if (itemSection.contains("display-name")) {
                meta.setDisplayName(itemSection.getString("display-name"));
            }

            if (itemSection.contains("lore")) {
                meta.setLore(itemSection.getStringList("lore"));
            }

            if (itemSection.contains("enchantments")) {
                ConfigurationSection enchants = itemSection.getConfigurationSection("enchantments");
                for (String enchName : enchants.getKeys(false)) {
                    Enchantment ench = Enchantment.getByName(enchName);
                    if (ench != null) {
                        meta.addEnchant(ench, enchants.getInt(enchName), true);
                    }
                }
            }

            if (itemSection.contains("item-flags")) {
                for (String flag : itemSection.getStringList("item-flags")) {
                    try {
                        meta.addItemFlags(ItemFlag.valueOf(flag));
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            item.setItemMeta(meta);
            int amount = getConfig().getInt("rewards." + rewardId + ".amount", 1);
            item.setAmount(amount);

            if (player.getInventory().firstEmpty() != -1) {
                player.getInventory().addItem(item);
                player.sendMessage(ChatColor.GREEN + "¡Has pescado un premio!");
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
                player.sendMessage(ChatColor.YELLOW + "¡Tu inventario está lleno! El premio ha caído al suelo.");
            }
        } catch (Exception e) {
            getLogger().warning("Error al dar la recompensa " + rewardId + ": " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Ha ocurrido un error al darte la recompensa.");
        }
    }

    private void openRewardsGUI(Player player) {
        ConfigurationSection rewards = getConfig().getConfigurationSection("rewards");
        if (rewards == null || rewards.getKeys(false).isEmpty()) {
            player.sendMessage(ChatColor.RED + "No hay recompensas configuradas.");
            return;
        }

        // Calcular el tamaño del inventario (múltiplo de 9)
        int size = (((rewards.getKeys(false).size() - 1) / 9) + 1) * 9;
        size = Math.min(54, Math.max(9, size)); // Mínimo 9, máximo 54

        Inventory gui = Bukkit.createInventory(null, size, GUI_TITLE);

        for (String key : rewards.getKeys(false)) {
            try {
                ConfigurationSection itemSection = rewards.getConfigurationSection(key + ".item");
                if (itemSection == null) continue;

                Material material = Material.valueOf(itemSection.getString("type"));
                ItemStack displayItem = new ItemStack(material);
                ItemMeta meta = displayItem.getItemMeta();
                double chance = rewards.getDouble(key + ".chance", 0.0);
                int amount = rewards.getInt(key + ".amount", 1);

                // Aplicar nombre personalizado
                if (itemSection.contains("display-name")) {
                    meta.setDisplayName(itemSection.getString("display-name"));
                }

                // Crear lore con información
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Probabilidad: " + ChatColor.YELLOW + chance + "%");
                lore.add(ChatColor.GRAY + "Cantidad: " + ChatColor.YELLOW + amount);

                // Agregar lore original si existe
                if (itemSection.contains("lore")) {
                    lore.add(ChatColor.GRAY + "---------------");
                    lore.addAll(itemSection.getStringList("lore"));
                }

                meta.setLore(lore);

                // Aplicar encantamientos si existen
                if (itemSection.contains("enchantments")) {
                    ConfigurationSection enchants = itemSection.getConfigurationSection("enchantments");
                    for (String enchName : enchants.getKeys(false)) {
                        Enchantment ench = Enchantment.getByName(enchName);
                        if (ench != null) {
                            meta.addEnchant(ench, enchants.getInt(enchName), true);
                        }
                    }
                }

                // Aplicar ItemFlags si existen
                if (itemSection.contains("item-flags")) {
                    for (String flag : itemSection.getStringList("item-flags")) {
                        try {
                            meta.addItemFlags(ItemFlag.valueOf(flag));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }

                displayItem.setItemMeta(meta);
                gui.addItem(displayItem);
            } catch (Exception e) {
                getLogger().warning("Error al cargar el item " + key + " para la GUI: " + e.getMessage());
            }
        }

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(GUI_TITLE)) {
            event.setCancelled(true); // Prevenir que se tomen los items
        }
    }
}