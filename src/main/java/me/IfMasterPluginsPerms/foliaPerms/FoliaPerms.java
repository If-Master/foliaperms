package me.IfMasterPluginsPerms.foliaPerms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import me.IfMasterPluginsPerms.foliaPerms.gui.PermissionEditorGUI;
import me.IfMasterPluginsPerms.foliaPerms.util.SchedulerUtil;

import static me.IfMasterPluginsPerms.foliaPerms.Updater.UpdateChecker.*;

public class FoliaPerms extends JavaPlugin implements Listener {

    private final Map<UUID, Set<String>> playerPermissions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> groupPermissions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> playerGroups = new ConcurrentHashMap<>();
    private final Map<String, Integer> groupPriorities = new ConcurrentHashMap<>();
    private static FoliaPerms instance;

    private final Map<UUID, PermissionAttachment> playerAttachments = new ConcurrentHashMap<>();
    public static FoliaPerms getInstance() {
        return instance;
    }

    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);
    private final Object saveLock = new Object();

    private File dataFile;
    private FileConfiguration dataConfig;
    private PermissionEditorGUI permissionEditor;

    private final AtomicLong totalPermissionUpdates = new AtomicLong(0);

    @Override
    public void onEnable() {
        instance = this;

        try {
            getLogger().info("Starting FoliaPerms initialization...");

            getLogger().info("Minecraft version: " + Bukkit.getVersion());
            getLogger().info("Using scheduler: " + (SchedulerUtil.isFolia() ? "Folia" : "Bukkit"));

            setupDataFile();
            loadData();
            cleanupPermissionData();

            if (!groupPermissions.containsKey("default")) {
                groupPermissions.put("default", ConcurrentHashMap.newKeySet());
                groupPriorities.put("default", 0);
                getLogger().info("Created default permission group");
            }

            getServer().getPluginManager().registerEvents(this, this);

            for (Player player : Bukkit.getOnlinePlayers()) {
                setupPlayerPermissions(player);
            }

            permissionEditor = new PermissionEditorGUI(this);
            getLogger().info("Permission Editor GUI initialized successfully");

            startPeriodicSave();

            getLogger().info("FoliaPerms has been enabled successfully!");
            getLogger().info("Use /fp editor to open the GUI permission editor");
            getLogger().info("Use /fp help for command list");
        } catch (Exception e) {
            getLogger().severe("CRITICAL ERROR: Failed to enable FoliaPerms: " + e.getMessage());
            e.printStackTrace();
            setEnabled(false);
        }
    }

    @Override
    public void onDisable() {
        try {
            getLogger().info("Disabling FoliaPerms...");

            for (PermissionAttachment attachment : playerAttachments.values()) {
                if (attachment != null) {
                    try {
                        attachment.remove();
                    } catch (Exception e) {
                        getLogger().warning("Failed to remove attachment: " + e.getMessage());
                    }
                }
            }
            playerAttachments.clear();

            saveData();

            getLogger().info("FoliaPerms has been disabled! Total permission updates: " + totalPermissionUpdates.get());
        } catch (Exception e) {
            getLogger().severe("Error during plugin disable: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startPeriodicSave() {
        SchedulerUtil.runAtFixedRate(this, () -> {
            if (saveScheduled.get()) {
                try {
                    saveData();
                    saveScheduled.set(false);
                    getLogger().fine("Periodic save completed");
                } catch (Exception e) {
                    getLogger().severe("Error during periodic save: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, 6000L, 6000L);
    }

    public void scheduleSave() {
        saveScheduled.set(true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command == null || !Objects.equals(command.getName(), "fp")) {
            return false;
        }

        try {
            getLogger().fine("Processing FP command from: " + sender.getName() + " with args: " + Arrays.toString(args));

            if (!sender.isOp() && !sender.hasPermission("foliaperms.use") && !sender.hasPermission("foliaperms.*")) {
                sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.use");
                return true;
            }

            if (args.length == 0) {
                sendHelpMessage(sender);
                return true;
            }

            String subCommand = args[0].toLowerCase();

            switch (subCommand) {
                case "user":
                    if (!hasPermissionLevel(sender, "foliaperms.user")) {
                        sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.user");
                        return true;
                    }
                    return handleUserCommand(sender, args);
                case "group":
                    if (!hasPermissionLevel(sender, "foliaperms.group")) {
                        sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.group");
                        return true;
                    }
                    return handleGroupCommand(sender, args);
                case "info":
                    return handleInfoCommand(sender, args);
                case "reload":
                    if (!hasPermissionLevel(sender, "foliaperms.admin")) {
                        sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.admin");
                        return true;
                    }
                    return handleReloadCommand(sender);
                case "save":
                    if (!hasPermissionLevel(sender, "foliaperms.admin")) {
                        sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.admin");
                        return true;
                    }
                    return handleSaveCommand(sender);
                case "debug":
                    if (!hasPermissionLevel(sender, "foliaperms.debug")) {
                        sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.debug");
                        return true;
                    }
                    return handleDebugCommand(sender, args);
                case "help":
                    sendHelpMessage(sender);
                    return true;
                case "editor":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage("§cThis command can only be used by players.");
                        return true;
                    }
                    if (!hasPermissionLevel(sender, "foliaperms.editor")) {
                        sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.editor");
                        return true;
                    }
                    permissionEditor.openEditor((Player) sender);
                    return true;
                case "stats":
                    if (!hasPermissionLevel(sender, "foliaperms.stats")) {
                        sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.stats");
                        return true;
                    }
                    sender.sendMessage("§6=== FoliaPerms Statistics ===");
                    sender.sendMessage("§eTotal permission updates: §f" + totalPermissionUpdates.get());
                    sender.sendMessage("§eGroups: §f" + groupPermissions.size());
                    sender.sendMessage("§ePlayers with data: §f" + playerGroups.size());
                    sender.sendMessage("§eOnline players: §f" + Bukkit.getOnlinePlayers().size());
                    sender.sendMessage("§eActive attachments: §f" + playerAttachments.size());
                    sender.sendMessage("§eScheduler type: §f" + (SchedulerUtil.isFolia() ? "Folia" : "Bukkit"));
                    return true;
                case "version":
                case "ver":
                    if (!hasPermissionLevel(sender, "foliaperms.ver")) {
                        sender.sendMessage("§c§lPermission Denied! §cRequired: foliaperms.ver");
                        return true;
                    }
                    sender.sendMessage("§6=== FoliaPerms Version Info ===");
                    sender.sendMessage("§ePlugin Version: §f" + getDescription().getVersion());
                    sender.sendMessage("§eMinecraft Version: §f" + Bukkit.getVersion());
                    sender.sendMessage("§eRunning on: §f" + (SchedulerUtil.isFolia() ? "Folia" : "Paper/Spigot/Bukkit"));
                    return true;
                case "updateinfo":
                    if (!sender.hasPermission("foliaperms.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission to view update info!");
                        return true;
                    }
                    getUpdateInfo(sender);
                    return true;
                case "download":
                    if (!sender.hasPermission("foliaperms.admin")) {
                        sender.sendMessage(ChatColor.RED + "You don't have permission to download updates!");
                        return true;
                    }
                    updatePluginFromGitHub(sender, getInstance());
                    return true;

                default:
                    sender.sendMessage("§cUnknown command '§f" + subCommand + "§c'. Use §f/fp help §cfor available commands.");
                    return true;
            }

        } catch (Exception e) {
            getLogger().severe("ERROR in FP command handler: " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage("§c§lERROR: §cAn internal error occurred while processing your command.");
            sender.sendMessage("§cPlease check the console for details and report this to an superior.");
                if (!hasPermissionLevel(sender, "foliaperms.admin")) {
                    sender.sendMessage("§aFor superiors: &7Please report the issue in: https://discord.gg/VJ456PFsKa Thanks.");
                }
            return true;
        }
    }

    private boolean hasPermissionLevel(CommandSender sender, String permission) {
        return sender.isOp() || sender.hasPermission(permission) || sender.hasPermission("foliaperms.*");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command == null || !Objects.equals(command.getName(), "fp")) {
            return null;
        }

        try {
            List<String> completions = new ArrayList<>();

            if (args.length == 1) {
                List<String> commands = Arrays.asList("user", "group", "info", "reload", "save", "debug", "editor", "help", "stats", "version", "download", "updateinfo");
                completions.addAll(commands.stream()
                        .filter(cmd -> hasPermissionForCommand(sender, cmd))
                        .collect(Collectors.toList()));
            } else if (args.length == 2) {
                switch (args[0].toLowerCase()) {
                    case "user":
                        if (hasPermissionLevel(sender, "foliaperms.user")) {
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                completions.add(player.getName());
                            }
                        }
                        break;
                    case "group":
                        if (hasPermissionLevel(sender, "foliaperms.group")) {
                            completions.addAll(groupPermissions.keySet());
                        }
                        break;
                    case "debug":
                        if (hasPermissionLevel(sender, "foliaperms.debug")) {
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                completions.add(player.getName());
                            }
                        }
                        break;
                }
            } else if (args.length == 3) {
                if (args[0].equalsIgnoreCase("user") && hasPermissionLevel(sender, "foliaperms.user")) {
                    completions.addAll(Arrays.asList("addgroup", "removegroup", "addperm", "removeperm", "info", "listperms"));
                } else if (args[0].equalsIgnoreCase("group") && hasPermissionLevel(sender, "foliaperms.group")) {
                    completions.addAll(Arrays.asList("create", "delete", "addperm", "removeperm", "setpriority", "info", "listperms"));
                }
            } else if (args.length == 4) {
                if (args[0].equalsIgnoreCase("user") && hasPermissionLevel(sender, "foliaperms.user") &&
                        (args[2].equalsIgnoreCase("addgroup") || args[2].equalsIgnoreCase("removegroup"))) {
                    completions.addAll(groupPermissions.keySet());
                }
            }

            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());

        } catch (Exception e) {
            getLogger().warning("Error in tab completion: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private boolean hasPermissionForCommand(CommandSender sender, String command) {
        switch (command) {
            case "user":
                return hasPermissionLevel(sender, "foliaperms.user");
            case "group":
                return hasPermissionLevel(sender, "foliaperms.group");
            case "updateinfo":
            case "download":
            case "reload":
            case "save":
                return hasPermissionLevel(sender, "foliaperms.admin");
            case "debug":
                return hasPermissionLevel(sender, "foliaperms.debug");
            case "editor":
                return hasPermissionLevel(sender, "foliaperms.editor");
            default:
                return hasPermissionLevel(sender, "foliaperms.use");
        }
    }

    public Set<String> getAllGroups() {
        return new HashSet<>(groupPermissions.keySet());
    }

    public Set<String> getGroupPermissions(String groupName) {
        return new HashSet<>(groupPermissions.getOrDefault(groupName, ConcurrentHashMap.newKeySet()));
    }

    public int getGroupPriority(String groupName) {
        return groupPriorities.getOrDefault(groupName, 0);
    }

    public long getGroupMemberCount(String groupName) {
        return playerGroups.values().stream()
                .mapToLong(groups -> groups.contains(groupName) ? 1 : 0)
                .sum();
    }

    public Set<String> getPlayerGroups(UUID playerUUID) {
        return new HashSet<>(playerGroups.getOrDefault(playerUUID, ConcurrentHashMap.newKeySet()));
    }

    public Set<String> getPlayerPermissions(UUID playerUUID) {
        return new HashSet<>(playerPermissions.getOrDefault(playerUUID, ConcurrentHashMap.newKeySet()));
    }

    public void addPlayerPermission(UUID playerUUID, String permission) {
        if (permission == null || permission.trim().isEmpty()) {
            throw new IllegalArgumentException("Permission cannot be null or empty");
        }

        permission = permission.replaceAll("§[a-f0-9]", "").trim();
        playerPermissions.computeIfAbsent(playerUUID, k -> ConcurrentHashMap.newKeySet()).add(permission);

        Player player = Bukkit.getPlayer(playerUUID);
        if (player != null && player.isOnline()) {
            updatePlayerPermissionsAsync(player);
        }
        scheduleSave();
        getLogger().fine("Added permission '" + permission + "' to player " + playerUUID);
    }

    public void removePlayerPermission(UUID playerUUID, String permission) {
        if (permission == null || permission.trim().isEmpty()) {
            return;
        }

        permission = permission.replaceAll("§[a-f0-9]", "").trim();
        Set<String> perms = playerPermissions.get(playerUUID);
        if (perms != null && perms.remove(permission)) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                updatePlayerPermissionsAsync(player);
            }
            scheduleSave();
            getLogger().fine("Removed permission '" + permission + "' from player " + playerUUID);
        }
    }

    private void updatePlayerPermissionsAsync(Player player) {
        SchedulerUtil.run(this, player.getLocation(), () -> {
            if (player.isOnline()) {
                setupPlayerPermissions(player);
                SchedulerUtil.runDelayed(this, player.getLocation(), () -> {
                    if (player.isOnline()) {
                        setupPlayerPermissions(player);
                    }
                }, 3L);
            }
        });
    }

    public void cleanupPermissionData() {
        getLogger().info("Cleaning up permission data...");
        int cleaned = 0;

        for (Map.Entry<String, Set<String>> entry : groupPermissions.entrySet()) {
            Set<String> cleanPerms = entry.getValue().stream()
                    .map(perm -> perm.replaceAll("§[a-f0-9]", "").trim())
                    .filter(perm -> !perm.isEmpty() && perm.length() <= 100)
                    .collect(Collectors.toSet());

            if (cleanPerms.size() != entry.getValue().size()) {
                cleaned += entry.getValue().size() - cleanPerms.size();
                entry.setValue(ConcurrentHashMap.newKeySet());
                entry.getValue().addAll(cleanPerms);
            }
        }

        for (Map.Entry<UUID, Set<String>> entry : playerPermissions.entrySet()) {
            Set<String> cleanPerms = entry.getValue().stream()
                    .map(perm -> perm.replaceAll("§[a-f0-9]", "").trim())
                    .filter(perm -> !perm.isEmpty() && perm.length() <= 100)
                    .collect(Collectors.toSet());

            if (cleanPerms.size() != entry.getValue().size()) {
                cleaned += entry.getValue().size() - cleanPerms.size();
                entry.setValue(ConcurrentHashMap.newKeySet());
                entry.getValue().addAll(cleanPerms);
            }
        }

        if (cleaned > 0) {
            scheduleSave();
            getLogger().info("Permission data cleanup completed - removed " + cleaned + " invalid entries");
        } else {
            getLogger().info("Permission data cleanup completed - no issues found");
        }
    }

    public void addGroupPermission(String groupName, String permission) {
        try {
            if (groupName == null || groupName.trim().isEmpty()) {
                throw new IllegalArgumentException("Group name cannot be null or empty");
            }
            if (permission == null || permission.trim().isEmpty()) {
                throw new IllegalArgumentException("Permission cannot be null or empty");
            }

            permission = permission.replaceAll("§[a-f0-9]", "").trim();

            if (permission.length() > 100) {
                throw new IllegalArgumentException("Permission too long (max 100 characters)");
            }

            Set<String> perms = groupPermissions.get(groupName);
            if (perms == null) {
                throw new IllegalArgumentException("Group '" + groupName + "' does not exist");
            }

            boolean added = perms.add(permission);
            if (added) {
                updateAllPlayersInGroupAsync(groupName);
                scheduleSave();
                getLogger().fine("Successfully added permission '" + permission + "' to group " + groupName);
            } else {
                getLogger().fine("Permission '" + permission + "' already exists in group " + groupName);
            }
        } catch (Exception e) {
            getLogger().severe("Failed to add group permission '" + permission + "' to " + groupName + ": " + e.getMessage());
            throw e;
        }
    }

    public void removeGroupPermission(String groupName, String permission) {
        if (permission == null || permission.trim().isEmpty()) {
            return;
        }

        permission = permission.replaceAll("§[a-f0-9]", "").trim();
        Set<String> perms = groupPermissions.get(groupName);
        if (perms != null && perms.remove(permission)) {
            updateAllPlayersInGroupAsync(groupName);
            scheduleSave();
            getLogger().fine("Removed permission '" + permission + "' from group " + groupName);
        }
    }

    public void createGroup(String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            throw new IllegalArgumentException("Group name cannot be null or empty");
        }

        groupName = groupName.trim();
        if (!groupName.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Group name can only contain letters, numbers, and underscores");
        }

        if (groupName.length() > 20) {
            throw new IllegalArgumentException("Group name too long (max 20 characters)");
        }

        if (!groupPermissions.containsKey(groupName)) {
            groupPermissions.put(groupName, ConcurrentHashMap.newKeySet());
            groupPriorities.put(groupName, 0);
            scheduleSave();
            getLogger().info("Created new group: " + groupName);
        } else {
            throw new IllegalArgumentException("Group '" + groupName + "' already exists");
        }
    }

    public void deleteGroup(String groupName) {
        if (groupName == null || groupName.equals("default")) {
            throw new IllegalArgumentException("Cannot delete default group or null group name");
        }

        if (groupPermissions.remove(groupName) != null) {
            groupPriorities.remove(groupName);

            for (Set<String> playerGroupsSet : playerGroups.values()) {
                playerGroupsSet.remove(groupName);
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePlayerPermissionsAsync(player);
            }

            scheduleSave();
            getLogger().info("Deleted group: " + groupName);
        } else {
            throw new IllegalArgumentException("Group '" + groupName + "' does not exist");
        }
    }

    private void setupDataFile() {
        try {
            dataFile = new File(getDataFolder(), "data.yml");
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            if (!dataFile.exists()) {
                dataFile.createNewFile();
                getLogger().info("Created new data file");
            }
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        } catch (Exception e) {
            getLogger().severe("Failed to setup data file: " + e.getMessage());
            throw new RuntimeException("Could not initialize data file", e);
        }
    }

    @SuppressWarnings("unchecked")
    public void loadData() {
        if (dataConfig == null) {
            getLogger().severe("Data config is null, cannot load data");
            return;
        }

        try {
            groupPermissions.clear();
            groupPriorities.clear();
            playerPermissions.clear();
            playerGroups.clear();

            if (dataConfig.contains("group-permissions")) {
                var groupPermsSection = dataConfig.getConfigurationSection("group-permissions");
                if (groupPermsSection != null) {
                    Map<String, Object> groupPerms = groupPermsSection.getValues(false);
                    for (Map.Entry<String, Object> entry : groupPerms.entrySet()) {
                        try {
                            String groupName = entry.getKey();
                            List<String> perms = (List<String>) entry.getValue();
                            if (perms != null) {
                                groupPermissions.put(groupName, ConcurrentHashMap.newKeySet());
                                groupPermissions.get(groupName).addAll(perms);
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to load group permissions for " + entry.getKey() + ": " + e.getMessage());
                        }
                    }
                }
            }

            if (dataConfig.contains("group-priorities")) {
                var prioritiesSection = dataConfig.getConfigurationSection("group-priorities");
                if (prioritiesSection != null) {
                    Map<String, Object> priorities = prioritiesSection.getValues(false);
                    for (Map.Entry<String, Object> entry : priorities.entrySet()) {
                        try {
                            String groupName = entry.getKey();
                            Integer priority = (Integer) entry.getValue();
                            if (priority != null) {
                                groupPriorities.put(groupName, priority);
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to load group priority for " + entry.getKey() + ": " + e.getMessage());
                        }
                    }
                }
            }

            if (dataConfig.contains("player-permissions")) {
                var playerPermsSection = dataConfig.getConfigurationSection("player-permissions");
                if (playerPermsSection != null) {
                    Map<String, Object> playerPerms = playerPermsSection.getValues(false);
                    for (Map.Entry<String, Object> entry : playerPerms.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            List<String> perms = (List<String>) entry.getValue();
                            if (perms != null) {
                                playerPermissions.put(uuid, ConcurrentHashMap.newKeySet());
                                playerPermissions.get(uuid).addAll(perms);
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to load player permissions for " + entry.getKey() + ": " + e.getMessage());
                        }
                    }
                }
            }

            if (dataConfig.contains("player-groups")) {
                var playerGroupsSection = dataConfig.getConfigurationSection("player-groups");
                if (playerGroupsSection != null) {
                    Map<String, Object> playerGrps = playerGroupsSection.getValues(false);
                    for (Map.Entry<String, Object> entry : playerGrps.entrySet()) {
                        try {
                            UUID uuid = UUID.fromString(entry.getKey());
                            List<String> groups = (List<String>) entry.getValue();
                            if (groups != null) {
                                playerGroups.put(uuid, ConcurrentHashMap.newKeySet());
                                playerGroups.get(uuid).addAll(groups);
                            }
                        } catch (Exception e) {
                            getLogger().warning("Failed to load player groups for " + entry.getKey() + ": " + e.getMessage());
                        }
                    }
                }
            }

            getLogger().info("Data loaded successfully:");
            getLogger().info("- Groups: " + groupPermissions.size());
            getLogger().info("- Players with data: " + playerGroups.size());
            getLogger().info("- Total permissions: " + groupPermissions.values().stream().mapToInt(Set::size).sum());

        } catch (Exception e) {
            getLogger().severe("Failed to load data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void saveData() {
        if (dataConfig == null) {
            getLogger().severe("Data config is null, cannot save data");
            return;
        }

        synchronized (saveLock) {
            try {
                Map<String, List<String>> groupPermsToSave = new HashMap<>();
                for (Map.Entry<String, Set<String>> entry : groupPermissions.entrySet()) {
                    groupPermsToSave.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                dataConfig.set("group-permissions", groupPermsToSave);

                dataConfig.set("group-priorities", new HashMap<>(groupPriorities));

                Map<String, List<String>> playerPermsToSave = new HashMap<>();
                for (Map.Entry<UUID, Set<String>> entry : playerPermissions.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        playerPermsToSave.put(entry.getKey().toString(), new ArrayList<>(entry.getValue()));
                    }
                }
                dataConfig.set("player-permissions", playerPermsToSave);

                Map<String, List<String>> playerGroupsToSave = new HashMap<>();
                for (Map.Entry<UUID, Set<String>> entry : playerGroups.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        playerGroupsToSave.put(entry.getKey().toString(), new ArrayList<>(entry.getValue()));
                    }
                }
                dataConfig.set("player-groups", playerGroupsToSave);

                dataConfig.save(dataFile);
                getLogger().fine("Data saved successfully to " + dataFile.getName());

            } catch (IOException e) {
                getLogger().severe("Could not save data file: " + e.getMessage());
                e.printStackTrace();
            } catch (Exception e) {
                getLogger().severe("Unexpected error during save: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();

            Set<String> currentGroups = playerGroups.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
            if (currentGroups.isEmpty()) {
                currentGroups.add("default");
                getLogger().fine("Added " + player.getName() + " to default group");
            }

            setupPlayerPermissions(player);

            SchedulerUtil.runDelayed(this, player.getLocation(), () -> {
                if (player.isOnline()) {
                    setupPlayerPermissions(player);
                    getLogger().fine("Completed delayed permission setup for " + player.getName());
                }
            }, 20L);

        } catch (Exception e) {
            getLogger().severe("Error in PlayerJoinEvent for " + event.getPlayer().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            UUID uuid = event.getPlayer().getUniqueId();
            PermissionAttachment attachment = playerAttachments.remove(uuid);
            if (attachment != null) {
                attachment.remove();
                getLogger().fine("Removed permission attachment for " + event.getPlayer().getName());
            }
        } catch (Exception e) {
            getLogger().severe("Error in PlayerQuitEvent for " + event.getPlayer().getName() + ": " + e.getMessage());
        }
    }

    public void setupPlayerPermissions(Player player) {
        try {
            UUID uuid = player.getUniqueId();
            totalPermissionUpdates.incrementAndGet();

            PermissionAttachment oldAttachment = playerAttachments.get(uuid);
            if (oldAttachment != null) {
                try {
                    oldAttachment.remove();
                } catch (Exception e) {
                    getLogger().fine("Old attachment already removed for " + player.getName());
                }
            }

            PermissionAttachment attachment = player.addAttachment(this);
            playerAttachments.put(uuid, attachment);

            Set<String> playerGroupSet = playerGroups.getOrDefault(uuid, ConcurrentHashMap.newKeySet());
            if (playerGroupSet.isEmpty()) {
                playerGroupSet.add("default");
                playerGroups.put(uuid, ConcurrentHashMap.newKeySet());
                playerGroups.get(uuid).add("default");
            }

            List<String> sortedGroups = playerGroupSet.stream()
                    .sorted((g1, g2) -> Integer.compare(
                            groupPriorities.getOrDefault(g1, 0),
                            groupPriorities.getOrDefault(g2, 0)
                    ))
                    .collect(Collectors.toList());

            for (String group : sortedGroups) {
                Set<String> groupPerms = groupPermissions.getOrDefault(group, ConcurrentHashMap.newKeySet());
                for (String perm : groupPerms) {
                    try {
                        if (perm == null || perm.trim().isEmpty()) continue;

                        boolean value = !perm.startsWith("-");
                        String cleanPerm = perm.startsWith("-") ? perm.substring(1) : perm;

                        if (!cleanPerm.trim().isEmpty()) {
                            attachment.setPermission(cleanPerm, value);
                        }
                    } catch (Exception e) {
                        getLogger().warning("Failed to apply group permission '" + perm + "' from group '" + group + "' to " + player.getName() + ": " + e.getMessage());
                    }
                }
            }

            Set<String> individualPerms = playerPermissions.getOrDefault(uuid, ConcurrentHashMap.newKeySet());
            for (String perm : individualPerms) {
                try {
                    if (perm == null || perm.trim().isEmpty()) continue;

                    boolean value = !perm.startsWith("-");
                    String cleanPerm = perm.startsWith("-") ? perm.substring(1) : perm;

                    if (!cleanPerm.trim().isEmpty()) {
                        attachment.setPermission(cleanPerm, value);
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to apply individual permission '" + perm + "' to " + player.getName() + ": " + e.getMessage());
                }
            }

            player.recalculatePermissions();

            getLogger().fine("Updated permissions for " + player.getName() + " - " +
                    individualPerms.size() + " individual perms, " +
                    playerGroupSet.size() + " groups: " + String.join(", ", playerGroupSet));

        } catch (Exception e) {
            getLogger().severe("Error setting up permissions for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void updateAllPlayersInGroupAsync(String groupName) {
        SchedulerUtil.run(this, null, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                Set<String> playerGroupsSet = playerGroups.getOrDefault(uuid, ConcurrentHashMap.newKeySet());
                if (playerGroupsSet.contains(groupName)) {
                    updatePlayerPermissionsAsync(player);
                }
            }
            getLogger().fine("Updated permissions for all players in group: " + groupName);
        });
    }

    public void addPlayerToGroup(UUID playerUUID, String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            throw new IllegalArgumentException("Group name cannot be null or empty");
        }

        if (!groupPermissions.containsKey(groupName)) {
            throw new IllegalArgumentException("Group '" + groupName + "' does not exist");
        }

        Set<String> playerGroupsSet = playerGroups.computeIfAbsent(playerUUID, k -> ConcurrentHashMap.newKeySet());
        boolean added = playerGroupsSet.add(groupName);

        if (added) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                updatePlayerPermissionsAsync(player);
            }
            scheduleSave();
            getLogger().fine("Added player " + playerUUID + " to group " + groupName);
        }
    }

    public void removePlayerFromGroup(UUID playerUUID, String groupName) {
        if (groupName == null || groupName.equals("default")) {
            throw new IllegalArgumentException("Cannot remove player from default group or null group");
        }

        Set<String> groups = playerGroups.get(playerUUID);
        if (groups != null && groups.remove(groupName)) {
            if (groups.isEmpty()) {
                groups.add("default");
            }

            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                updatePlayerPermissionsAsync(player);
            }
            scheduleSave();
            getLogger().fine("Removed player " + playerUUID + " from group " + groupName);
        }
    }

    private boolean handleDebugCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /fp debug <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found or not online.");
            return true;
        }

        UUID uuid = target.getUniqueId();
        sender.sendMessage("§6=== Debug Info for " + target.getName() + " ===");
        sender.sendMessage("§eUUID: §f" + uuid.toString());
        sender.sendMessage("§eOnline: §f" + target.isOnline());

        sender.sendMessage("§eEffective Permissions (sample):");
        int count = 0;
        for (org.bukkit.permissions.PermissionAttachmentInfo perm : target.getEffectivePermissions()) {
            if (count >= 10) {
                sender.sendMessage("§7... and " + (target.getEffectivePermissions().size() - 10) + " more");
                break;
            }
            String permName = perm.getPermission();
            sender.sendMessage("§7- §f" + permName + " §7(§" + (perm.getValue() ? "a" : "c") + perm.getValue() + "§7)");
            count++;
        }

        Set<String> groups = playerGroups.getOrDefault(uuid, ConcurrentHashMap.newKeySet());
        sender.sendMessage("§eGroups: §f" + (groups.isEmpty() ? "None" : String.join(", ", groups)));

        Set<String> individualPerms = playerPermissions.getOrDefault(uuid, ConcurrentHashMap.newKeySet());
        sender.sendMessage("§eIndividual Permissions: §f" + individualPerms.size());

        if (!individualPerms.isEmpty()) {
            sender.sendMessage("§7Individual permissions:");
            for (String perm : individualPerms) {
                sender.sendMessage("§7  - " + perm);
            }
        }

        for (String group : groups) {
            Set<String> groupPerms = groupPermissions.getOrDefault(group, ConcurrentHashMap.newKeySet());
            int priority = groupPriorities.getOrDefault(group, 0);
            sender.sendMessage("§eGroup §f" + group + " §7(priority: " + priority + ", perms: " + groupPerms.size() + ")");
        }

        PermissionAttachment attachment = playerAttachments.get(uuid);
        sender.sendMessage("§eAttachment: §f" + (attachment != null ? "Present" : "Missing"));

        return true;
    }

    private boolean handleUserCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /fp user <player> <action> [args...]");
            sender.sendMessage("§7Actions: addgroup, removegroup, addperm, removeperm, info, listperms");
            return true;
        }

        String playerName = args[1];
        String action = args[2].toLowerCase();
        Player target = Bukkit.getPlayer(playerName);
        UUID targetUUID = null;

        if (target != null) {
            targetUUID = target.getUniqueId();
        } else {
            for (Map.Entry<UUID, Set<String>> entry : playerGroups.entrySet()) {
                targetUUID = entry.getKey();
                break;
            }

            if (targetUUID == null) {
                sender.sendMessage("§cPlayer " + playerName + " not found. Player must be online for user commands.");
                return true;
            }
        }

        try {
            switch (action) {
                case "addgroup":
                    if (args.length < 4) {
                        sender.sendMessage("§cUsage: /fp user <player> addgroup <group>");
                        return true;
                    }
                    String groupToAdd = args[3];
                    addPlayerToGroup(targetUUID, groupToAdd);
                    sender.sendMessage("§aAdded " + playerName + " to group " + groupToAdd);
                    break;

                case "removegroup":
                    if (args.length < 4) {
                        sender.sendMessage("§cUsage: /fp user <player> removegroup <group>");
                        return true;
                    }
                    String groupToRemove = args[3];
                    removePlayerFromGroup(targetUUID, groupToRemove);
                    sender.sendMessage("§aRemoved " + playerName + " from group " + groupToRemove);
                    break;

                case "addperm":
                    if (args.length < 4) {
                        sender.sendMessage("§cUsage: /fp user <player> addperm <permission>");
                        return true;
                    }
                    String permToAdd = args[3];
                    addPlayerPermission(targetUUID, permToAdd);
                    sender.sendMessage("§aAdded permission " + permToAdd + " to " + playerName);
                    break;

                case "removeperm":
                    if (args.length < 4) {
                        sender.sendMessage("§cUsage: /fp user <player> removeperm <permission>");
                        return true;
                    }
                    String permToRemove = args[3];
                    removePlayerPermission(targetUUID, permToRemove);
                    sender.sendMessage("§aRemoved permission " + permToRemove + " from " + playerName);
                    break;

                case "info":
                    Set<String> groups = playerGroups.getOrDefault(targetUUID, ConcurrentHashMap.newKeySet());
                    Set<String> perms = playerPermissions.getOrDefault(targetUUID, ConcurrentHashMap.newKeySet());

                    sender.sendMessage("§6=== User Info for " + playerName + " ===");
                    sender.sendMessage("§eUUID: §f" + targetUUID.toString());
                    sender.sendMessage("§eOnline: §f" + (target != null));
                    sender.sendMessage("§eGroups: §f" + (groups.isEmpty() ? "None" : String.join(", ", groups)));
                    sender.sendMessage("§eIndividual Permissions: §f" + perms.size());
                    break;

                case "listperms":
                    sender.sendMessage("§6=== Permissions for " + playerName + " ===");

                    Set<String> individualPerms = playerPermissions.getOrDefault(targetUUID, ConcurrentHashMap.newKeySet());
                    if (!individualPerms.isEmpty()) {
                        sender.sendMessage("§eIndividual Permissions:");
                        for (String perm : individualPerms) {
                            sender.sendMessage("§7- §f" + perm);
                        }
                    }

                    Set<String> playerGroupsSet = playerGroups.getOrDefault(targetUUID, ConcurrentHashMap.newKeySet());
                    for (String group : playerGroupsSet) {
                        Set<String> groupPerms = groupPermissions.getOrDefault(group, ConcurrentHashMap.newKeySet());
                        if (!groupPerms.isEmpty()) {
                            int priority = groupPriorities.getOrDefault(group, 0);
                            sender.sendMessage("§eFrom group " + group + " §7(priority: " + priority + "):");
                            for (String perm : groupPerms) {
                                sender.sendMessage("§7- §f" + perm);
                            }
                        }
                    }

                    if (individualPerms.isEmpty() && playerGroupsSet.stream().allMatch(g -> groupPermissions.getOrDefault(g, ConcurrentHashMap.newKeySet()).isEmpty())) {
                        sender.sendMessage("§7No permissions found for this player.");
                    }
                    break;

                default:
                    sender.sendMessage("§cAvailable actions: addgroup, removegroup, addperm, removeperm, info, listperms");
                    break;
            }
        } catch (Exception e) {
            sender.sendMessage("§cError: " + e.getMessage());
            getLogger().warning("Error in user command: " + e.getMessage());
        }

        return true;
    }

    private boolean handleGroupCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /fp group <group> <action> [args...]");
            sender.sendMessage("§7Actions: create, delete, addperm, removeperm, setpriority, info, listperms");
            return true;
        }

        String groupName = args[1];
        String action = args[2].toLowerCase();

        try {
            switch (action) {
                case "create":
                    createGroup(groupName);
                    sender.sendMessage("§aCreated group §f" + groupName);
                    break;

                case "delete":
                    deleteGroup(groupName);
                    sender.sendMessage("§aDeleted group " + groupName);
                    break;

                case "addperm":
                    if (args.length < 4) {
                        sender.sendMessage("§cUsage: /fp group <group> addperm <permission>");
                        return true;
                    }
                    String permToAdd = args[3];
                    addGroupPermission(groupName, permToAdd);
                    sender.sendMessage("§aAdded permission " + permToAdd + " to group " + groupName);
                    break;

                case "removeperm":
                    if (args.length < 4) {
                        sender.sendMessage("§cUsage: /fp group <group> removeperm <permission>");
                        return true;
                    }
                    String permToRemove = args[3];
                    removeGroupPermission(groupName, permToRemove);
                    sender.sendMessage("§aRemoved permission " + permToRemove + " from group " + groupName);
                    break;

                case "setpriority":
                    if (args.length < 4) {
                        sender.sendMessage("§cUsage: /fp group <group> setpriority <number>");
                        return true;
                    }
                    try {
                        int priority = Integer.parseInt(args[3]);
                        groupPriorities.put(groupName, priority);
                        sender.sendMessage("§aSet priority of group " + groupName + " to " + priority);
                        updateAllPlayersInGroupAsync(groupName);
                        scheduleSave();
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cInvalid number: " + args[3]);
                    }
                    break;

                case "info":
                    if (!groupPermissions.containsKey(groupName)) {
                        sender.sendMessage("§cGroup " + groupName + " does not exist.");
                        return true;
                    }

                    int priority = groupPriorities.getOrDefault(groupName, 0);
                    Set<String> permissions = groupPermissions.get(groupName);
                    long memberCount = getGroupMemberCount(groupName);

                    sender.sendMessage("§6=== Group Info for " + groupName + " ===");
                    sender.sendMessage("§ePriority: §f" + priority);
                    sender.sendMessage("§ePermissions: §f" + permissions.size());
                    sender.sendMessage("§eMembers: §f" + memberCount);
                    break;

                case "listperms":
                    if (!groupPermissions.containsKey(groupName)) {
                        sender.sendMessage("§cGroup " + groupName + " does not exist.");
                        return true;
                    }

                    Set<String> groupPerms = groupPermissions.get(groupName);
                    int groupPriority = groupPriorities.getOrDefault(groupName, 0);

                    sender.sendMessage("§6=== Permissions for group " + groupName + " ===");
                    sender.sendMessage("§ePriority: §f" + groupPriority);

                    if (groupPerms.isEmpty()) {
                        sender.sendMessage("§7No permissions set for this group.");
                    } else {
                        for (String perm : groupPerms) {
                            sender.sendMessage("§7- §f" + perm);
                        }
                    }
                    break;

                default:
                    sender.sendMessage("§cAvailable actions: create, delete, addperm, removeperm, setpriority, info, listperms");
                    break;
            }
        } catch (Exception e) {
            sender.sendMessage("§cError: " + e.getMessage());
            getLogger().warning("Error in group command: " + e.getMessage());
        }

        return true;
    }

    private boolean handleInfoCommand(CommandSender sender, String[] args) {
        sender.sendMessage("§6=== FoliaPerms Information ===");
        sender.sendMessage("§ePlugin Version: §f" + getDescription().getVersion());
        sender.sendMessage("§eScheduler: §f" + (SchedulerUtil.isFolia() ? "Folia" : "Bukkit"));
        sender.sendMessage("§eTotal Groups: §f" + groupPermissions.size());
        sender.sendMessage("§eOnline Players: §f" + Bukkit.getOnlinePlayers().size());
        sender.sendMessage("§ePlayers with Data: §f" + playerGroups.size());
        sender.sendMessage("§eActive Attachments: §f" + playerAttachments.size());
        sender.sendMessage("§ePermission Updates: §f" + totalPermissionUpdates.get());

        if (args.length > 1 && args[1].equalsIgnoreCase("detailed")) {
            sender.sendMessage("§6=== Detailed Information ===");
            for (Map.Entry<String, Set<String>> entry : groupPermissions.entrySet()) {
                String group = entry.getKey();
                int priority = groupPriorities.getOrDefault(group, 0);
                long members = getGroupMemberCount(group);
                sender.sendMessage("§eGroup §f" + group + " §7(priority: " + priority +
                        ", perms: " + entry.getValue().size() + ", members: " + members + ")");
            }
        }

        return true;
    }

    private boolean handleReloadCommand(CommandSender sender) {
        try {
            sender.sendMessage("§eReloading FoliaPerms...");

            loadData();

            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePlayerPermissionsAsync(player);
            }

            sender.sendMessage("§aFoliaPerms reloaded successfully!");
            getLogger().info("FoliaPerms reloaded by " + sender.getName());
        } catch (Exception e) {
            sender.sendMessage("§cError during reload: " + e.getMessage());
            getLogger().severe("Error during reload: " + e.getMessage());
        }
        return true;
    }

    private boolean handleSaveCommand(CommandSender sender) {
        try {
            saveData();
            sender.sendMessage("§aFoliaPerms data saved successfully!");
            getLogger().info("Data manually saved by " + sender.getName());
        } catch (Exception e) {
            sender.sendMessage("§cError during save: " + e.getMessage());
            getLogger().severe("Error during manual save: " + e.getMessage());
        }
        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage("§6=== FoliaPerms Help ===");
        sender.sendMessage("§e/fp editor §7- Open the GUI permission editor");
        sender.sendMessage("§e/fp group <group> create §7- Create a new group");
        sender.sendMessage("§e/fp group <group> delete §7- Delete a group");
        sender.sendMessage("§e/fp group <group> addperm <perm> §7- Add permission to group");
        sender.sendMessage("§e/fp group <group> removeperm <perm> §7- Remove permission from group");
        sender.sendMessage("§e/fp group <group> setpriority <num> §7- Set group priority");
        sender.sendMessage("§e/fp group <group> info §7- Show group information");
        sender.sendMessage("§e/fp group <group> listperms §7- List group permissions");
        sender.sendMessage("§e/fp user <player> addgroup <group> §7- Add player to group");
        sender.sendMessage("§e/fp user <player> removegroup <group> §7- Remove player from group");
        sender.sendMessage("§e/fp user <player> addperm <perm> §7- Add permission to player");
        sender.sendMessage("§e/fp user <player> removeperm <perm> §7- Remove permission from player");
        sender.sendMessage("§e/fp user <player> info §7- Show player information");
        sender.sendMessage("§e/fp user <player> listperms §7- List player permissions");
        sender.sendMessage("§e/fp info [detailed] §7- Show general information");
        sender.sendMessage("§e/fp debug <player> §7- Debug player permissions");
        sender.sendMessage("§e/fp stats §7- Show performance statistics");
        sender.sendMessage("§e/fp version §7- Show version information");
        sender.sendMessage("§e/fp updateinfo §7- get update info (admin)");
        sender.sendMessage("§e/fp download §7- download the latest update (admin)");
        sender.sendMessage("§e/fp reload §7- Reload configuration (admin)");
        sender.sendMessage("§e/fp save §7- Save data to file (admin)");
    }
}