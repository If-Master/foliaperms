package me.IfMasterPluginsPerms.foliaPerms.gui;

import me.IfMasterPluginsPerms.foliaPerms.FoliaPerms;
import me.IfMasterPluginsPerms.foliaPerms.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import net.kyori.adventure.text.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class PermissionEditorGUI implements Listener {

    private final FoliaPerms plugin;
    private final Map<UUID, GUISession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pluginPermissions = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingChatInput = new ConcurrentHashMap<>();

    private final Set<String> secureGUITitles = new HashSet<>();
    private final Map<UUID, Long> lastGUIAccess = new ConcurrentHashMap<>();
    private static final long GUI_ACCESS_COOLDOWN = 1000;
    private static final String GUI_SIGNATURE = "§k§l§r§k§l";

    private volatile long lastPluginScan = 0;
    private static final long SCAN_CACHE_DURATION = 300000;
    private final AtomicLong guiUpdates = new AtomicLong(0);

    public enum GUIState {
        MAIN_MENU,
        PLAYER_SELECTION,
        GROUP_SELECTION,
        PLUGIN_LIST,
        PERMISSION_LIST,
        CONFIRMATION,
        PLAYER_GROUP_MANAGEMENT
    }

    public static class GUISession {
        public GUIState currentState = GUIState.MAIN_MENU;
        public boolean editingPlayer = false;
        public String selectedTarget = null;
        public UUID selectedPlayerUUID = null;
        public String selectedPlugin = null;
        public Set<String> pendingChanges = Collections.newSetFromMap(new ConcurrentHashMap<>());
        public Set<String> pendingRemovals = Collections.newSetFromMap(new ConcurrentHashMap<>());

        public int currentPage = 0;
        public int maxPage = 0;

        public long sessionStartTime = System.currentTimeMillis();
        public int securityViolations = 0;

        public void reset() {
            currentState = GUIState.MAIN_MENU;
            editingPlayer = false;
            selectedTarget = null;
            selectedPlayerUUID = null;
            selectedPlugin = null;
            pendingChanges.clear();
            pendingRemovals.clear();
            currentPage = 0;
            maxPage = 0;
            securityViolations = 0;
        }
    }

    public PermissionEditorGUI(FoliaPerms plugin) {
        this.plugin = plugin;
        initializeSecurityFeatures();
        scanAllPluginPermissionsAsync();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void initializeSecurityFeatures() {
        secureGUITitles.addAll(Arrays.asList(
                "Permission Editor - Main Menu",
                "Select Player to Edit",
                "Select Group to Edit",
                "Select Plugin",
                "Permissions",
                "Manage Group:"
        ));

        plugin.getLogger().info("Security features initialized for Permission Editor GUI");
    }

    private String createSecureTitle(String baseTitle) {
        return GUI_SIGNATURE + "§6" + baseTitle;
    }

    private boolean isSecureGUI(String title) {
        if (title == null) return false;

        if (!title.startsWith(GUI_SIGNATURE)) return false;

        String cleanTitle = title.substring(GUI_SIGNATURE.length());
        return secureGUITitles.stream().anyMatch(secureTitle ->
                cleanTitle.contains(secureTitle) || secureTitle.contains("Permission Editor") ||
                        secureTitle.contains("Select") || secureTitle.contains("Manage Group"));
    }

    private boolean performSecurityChecks(Player player, String action) {
        UUID playerUUID = player.getUniqueId();

        Long lastAccess = lastGUIAccess.get(playerUUID);

        if (!player.isOp() && !player.hasPermission("foliaperms.editor")) {
            logSecurityViolation(player, "Attempted GUI access without permission: " + action);
            return false;
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && !player.hasPermission("foliaperms.admin")) {
            logSecurityViolation(player, "Attempted GUI access in creative mode without admin permission");
            return false;
        }

        return true;
    }

    private void logSecurityViolation(Player player, String violation) {
        plugin.getLogger().warning("SECURITY VIOLATION - Player: " + player.getName() +
                " (" + player.getUniqueId() + ") - " + violation);

        GUISession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            session.securityViolations++;
            if (session.securityViolations >= 3) {
                kickUnauthorizedPlayer(player);
                return;
            }
        }

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("foliaperm.warn")) {
                staff.sendMessage("§c§lSECURITY WARNING: §cUnauthorized access attempt detected! The User: " + player.getName().toString()
                        + " at the following location flagged the system: World; "
                        + player.getWorld().getName().toString()
                        + " X: " + player.getLocation().getX()
                        + " Y: " + player.getLocation().getY()
                        + " Z: " + player.getLocation().getZ()
                )
                ;
            }
        }
        player.sendMessage("§c§lSECURITY WARNING: §cUnauthorized access attempt detected! Alerted online staff members");

    }

    private CompletableFuture<Void> scanAllPluginPermissionsAsync() {
        return CompletableFuture.runAsync(() -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPluginScan < SCAN_CACHE_DURATION) {
                return;
            }

            scanAllPluginPermissions();
            lastPluginScan = currentTime;
        });
    }

    private void scanAllPluginPermissions() {
        plugin.getLogger().info("Scanning plugins for permissions...");
        pluginPermissions.clear();

        Set<String> minecraftPerms = Collections.newSetFromMap(new ConcurrentHashMap<>());

        minecraftPerms.addAll(Arrays.asList(
                "minecraft.command.help", "minecraft.command.me", "minecraft.command.tell",
                "minecraft.command.msg", "minecraft.command.w", "minecraft.command.say",
                "minecraft.command.list", "minecraft.command.seed", "minecraft.command.time",
                "minecraft.command.gamemode", "minecraft.command.difficulty", "minecraft.command.weather",
                "minecraft.command.tp", "minecraft.command.teleport", "minecraft.command.summon",
                "minecraft.command.kill", "minecraft.command.give", "minecraft.command.clear",
                "minecraft.command.effect", "minecraft.command.enchant", "minecraft.command.experience",
                "minecraft.command.xp", "minecraft.command.fill", "minecraft.command.setblock",
                "minecraft.command.clone", "minecraft.command.testfor", "minecraft.command.op",
                "minecraft.command.deop", "minecraft.command.kick", "minecraft.command.ban",
                "minecraft.command.pardon", "minecraft.command.ban-ip", "minecraft.command.pardon-ip",
                "minecraft.command.whitelist", "minecraft.command.stop", "minecraft.command.save-all",
                "minecraft.command.save-on", "minecraft.command.save-off", "minecraft.command.reload",
                "minecraft.command.scoreboard", "minecraft.command.team", "minecraft.command.execute",
                "minecraft.command.testforblock", "minecraft.command.testforblocks", "minecraft.command.stats",
                "minecraft.command.replaceitem", "minecraft.command.blockdata", "minecraft.command.entitydata",
                "minecraft.command.playsound", "minecraft.command.stopsound", "minecraft.command.title",
                "minecraft.command.tellraw", "minecraft.command.worldborder", "minecraft.command.gamerule",
                "minecraft.command.defaultgamemode", "minecraft.command.setworldspawn", "minecraft.command.spawnpoint",
                "minecraft.command.function", "minecraft.command.datapack", "minecraft.command.data"
        ));

        pluginPermissions.put("Minecraft", minecraftPerms);

        for (Plugin loadedPlugin : Bukkit.getPluginManager().getPlugins()) {
            String pluginName = loadedPlugin.getName();
            Set<String> permissions = Collections.newSetFromMap(new ConcurrentHashMap<>());

            try {
                PluginDescriptionFile description = loadedPlugin.getDescription();
                if (description.getPermissions() != null) {
                    for (Permission permission : description.getPermissions()) {
                        permissions.add(permission.getName());

                        if (permission.getChildren() != null) {
                            permissions.addAll(permission.getChildren().keySet());
                        }
                    }
                }

                for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
                    String permName = permission.getName().toLowerCase();
                    String pluginNameLower = pluginName.toLowerCase();

                    if (permName.startsWith(pluginNameLower + ".") ||
                            permName.contains(pluginNameLower) ||
                            pluginNameLower.contains(permName.split("\\.")[0])) {
                        permissions.add(permission.getName());
                    }
                }

                String[] commonPrefixes = {pluginName.toLowerCase(), pluginName.toLowerCase().replace(" ", "")};
                for (Permission permission : Bukkit.getPluginManager().getPermissions()) {
                    for (String prefix : commonPrefixes) {
                        if (permission.getName().toLowerCase().startsWith(prefix + ".")) {
                            permissions.add(permission.getName());
                        }
                    }
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to scan permissions for plugin " + pluginName + ": " + e.getMessage());
            }

            if (!permissions.isEmpty()) {
                pluginPermissions.put(pluginName, permissions);
            }
        }

        plugin.getLogger().info("Scanned " + pluginPermissions.size() + " plugins with " +
                pluginPermissions.values().stream().mapToInt(Set::size).sum() + " total permissions");
    }

    public void openEditor(Player player) {
        if (!player.isOp() && !player.hasPermission("foliaperms.editor")) {
            player.sendMessage("§cYou don't have permission to use the permission editor.");
            return;
        }

        GUISession session = activeSessions.computeIfAbsent(player.getUniqueId(), k -> new GUISession());
        session.reset();

        try {
            openMainMenu(player, session);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to open main menu for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cFailed to open permission editor. Check console for errors.");
        }
    }

    private void openMainMenu(Player player, GUISession session) {
        session.currentState = GUIState.MAIN_MENU;
        guiUpdates.incrementAndGet();

        Inventory inv = Bukkit.createInventory(null, 27, createSecureTitle("Permission Editor - Main Menu"));

        ItemStack playerItem = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta playerMeta = playerItem.getItemMeta();
        playerMeta.setDisplayName("§a§lEdit Player Permissions");
        playerMeta.setLore(Arrays.asList(
                "§7Click to edit permissions for a specific player",
                "§7You can modify individual player permissions",
                "§7and manage their group memberships"
        ));
        playerItem.setItemMeta(playerMeta);
        inv.setItem(11, playerItem);

        ItemStack groupItem = new ItemStack(Material.BOOKSHELF);
        ItemMeta groupMeta = groupItem.getItemMeta();
        groupMeta.setDisplayName("§b§lEdit Group Permissions");
        groupMeta.setLore(Arrays.asList(
                "§7Click to edit permissions for a group",
                "§7Changes will affect all players in the group",
                "§7You can also create and delete groups"
        ));
        groupItem.setItemMeta(groupMeta);
        inv.setItem(15, groupItem);

        ItemStack infoItem = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = infoItem.getItemMeta();
        infoMeta.setDisplayName("§e§lInformation");
        infoMeta.setLore(Arrays.asList(
                "§7This editor allows you to manage permissions",
                "§7for players and groups through a GUI interface",
                "§7",
                "§7Plugins scanned: §f" + pluginPermissions.size(),
                "§7Total permissions found: §f" + pluginPermissions.values().stream().mapToInt(Set::size).sum(),
                "§7GUI Updates: §f" + guiUpdates.get(),
                "§7Session Security: §a" + (session.securityViolations == 0 ? "Clean" : "§c" + session.securityViolations + " violations"),
                "§7",
                "§6§lTip: Press Q on permission to add custom permission!"
        ));
        infoItem.setItemMeta(infoMeta);
        inv.setItem(22, infoItem);

        player.openInventory(inv);
    }

    private void openPlayerSelection(Player player, GUISession session) {
        if (!performSecurityChecks(player, "openPlayerSelection")) {
            return;
        }

        session.currentState = GUIState.PLAYER_SELECTION;
        session.editingPlayer = true;

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        Inventory inv = Bukkit.createInventory(null, 54, createSecureTitle("Select Player to Edit"));

        addBackButton(inv, 45);

        ItemStack groupManageItem = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta groupManageMeta = groupManageItem.getItemMeta();
        groupManageMeta.setDisplayName("§b§lManage Player Groups");
        groupManageMeta.setLore(Arrays.asList(
                "§7Click to add/remove players from groups",
                "§7Select which group to manage, then add players to it"
        ));
        groupManageItem.setItemMeta(groupManageMeta);
        inv.setItem(53, groupManageItem);

        for (int i = 0; i < Math.min(45, onlinePlayers.size()); i++) {
            Player target = onlinePlayers.get(i);
            ItemStack playerItem = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = playerItem.getItemMeta();

            meta.setDisplayName("§a" + target.getName());

            Set<String> playerGroups = plugin.getPlayerGroups(target.getUniqueId());
            List<String> lore = new ArrayList<>();
            lore.add("§7Click to edit this player's permissions");
            lore.add("§7UUID: " + target.getUniqueId().toString());
            lore.add("§7");
            lore.add("§7Current Groups:");
            if (playerGroups.isEmpty()) {
                lore.add("§8  None");
            } else {
                for (String group : playerGroups) {
                    lore.add("§f  - " + group);
                }
            }

            meta.setLore(lore);
            playerItem.setItemMeta(meta);
            inv.setItem(i, playerItem);
        }

        player.openInventory(inv);
    }

    private void openGroupSelection(Player player, GUISession session) {
        if (!performSecurityChecks(player, "openGroupSelection")) {
            return;
        }

        session.currentState = GUIState.GROUP_SELECTION;
        session.editingPlayer = false;

        Set<String> groups = plugin.getAllGroups();
        Inventory inv = Bukkit.createInventory(null, 54, createSecureTitle("Select Group to Edit"));

        addBackButton(inv, 45);

        ItemStack createItem = new ItemStack(Material.EMERALD);
        ItemMeta createMeta = createItem.getItemMeta();
        createMeta.setDisplayName("§a§l+ Create New Group");
        createMeta.setLore(Arrays.asList(
                "§7Click to create a new permission group",
                "§7You'll be prompted to enter the group name in chat",
                "§7",
                "§eRequirements:",
                "§7- No spaces allowed",
                "§7- Only letters, numbers, and underscores",
                "§7- Cannot be 'default'",
                "§7- Maximum 20 characters"
        ));
        createItem.setItemMeta(createMeta);
        inv.setItem(53, createItem);

        int slot = 0;
        for (String group : groups) {
            if (slot >= 45) break;

            ItemStack groupItem = new ItemStack(Material.BOOKSHELF);
            ItemMeta meta = groupItem.getItemMeta();
            meta.setDisplayName("§b" + group);

            List<String> lore = new ArrayList<>();
            lore.add("§7Click to edit this group's permissions");
            lore.add("§7");

            int priority = plugin.getGroupPriority(group);
            Set<String> permissions = plugin.getGroupPermissions(group);
            long memberCount = plugin.getGroupMemberCount(group);

            lore.add("§7Priority: §f" + priority);
            lore.add("§7Permissions: §f" + permissions.size());
            lore.add("§7Members: §f" + memberCount);

            if (!group.equals("default")) {
                lore.add("§7");
                lore.add("§c§lShift-Click to delete group");
            }

            meta.setLore(lore);
            groupItem.setItemMeta(meta);
            inv.setItem(slot++, groupItem);
        }

        player.openInventory(inv);
    }

    private void openPluginList(Player player, GUISession session) {
        if (!performSecurityChecks(player, "openPluginList")) {
            return;
        }

        session.currentState = GUIState.PLUGIN_LIST;
        session.currentPage = 0;

        Inventory inv = Bukkit.createInventory(null, 54, createSecureTitle("Select Plugin - " +
                (session.editingPlayer ? session.selectedTarget : "Group: " + session.selectedTarget)));

        addBackButton(inv, 45);

        ItemStack refreshItem = new ItemStack(Material.COMPASS);
        ItemMeta refreshMeta = refreshItem.getItemMeta();
        refreshMeta.setDisplayName("§e§lRefresh Plugin List");
        refreshMeta.setLore(Arrays.asList(
                "§7Click to re-scan all plugins for permissions",
                "§7Use this if you've installed new plugins"
        ));
        refreshItem.setItemMeta(refreshMeta);
        inv.setItem(53, refreshItem);

        int slot = 0;
        for (Map.Entry<String, Set<String>> entry : pluginPermissions.entrySet()) {
            if (slot >= 45) break;

            String pluginName = entry.getKey();
            Set<String> permissions = entry.getValue();

            ItemStack pluginItem = new ItemStack(pluginName.equals("Minecraft") ? Material.GRASS_BLOCK : Material.PAPER);
            ItemMeta meta = pluginItem.getItemMeta();
            meta.setDisplayName("§a" + pluginName);

            List<String> lore = new ArrayList<>();
            lore.add("§7Click to view permissions for this plugin");
            lore.add("§7");
            lore.add("§7Permissions available: §f" + permissions.size());

            if (!permissions.isEmpty()) {
                lore.add("§7Examples:");
                int count = 0;
                for (String perm : permissions) {
                    if (count >= 3) break;
                    lore.add("§8  - " + perm);
                    count++;
                }
                if (permissions.size() > 3) {
                    lore.add("§8  ... and " + (permissions.size() - 3) + " more");
                }
            }

            meta.setLore(lore);
            pluginItem.setItemMeta(meta);
            inv.setItem(slot++, pluginItem);
        }

        if (pluginPermissions.isEmpty()) {
            ItemStack infoItem = new ItemStack(Material.BARRIER);
            ItemMeta infoMeta = infoItem.getItemMeta();
            infoMeta.setDisplayName("§cNo Plugins with Permissions Found");
            infoMeta.setLore(Arrays.asList(
                    "§7No plugins with discoverable permissions were found.",
                    "§7Try clicking the refresh button or check if plugins",
                    "§7are properly declaring their permissions."
            ));
            infoItem.setItemMeta(infoMeta);
            inv.setItem(22, infoItem);
        }

        player.openInventory(inv);
    }

    private void openPermissionList(Player player, GUISession session) {
        if (!performSecurityChecks(player, "openPermissionList")) {
            return;
        }

        session.currentState = GUIState.PERMISSION_LIST;

        Set<String> permissions = pluginPermissions.getOrDefault(session.selectedPlugin, Collections.newSetFromMap(new ConcurrentHashMap<>()));

        if (permissions.isEmpty()) {
            player.sendMessage("§cNo permissions found for plugin " + session.selectedPlugin);
            session.currentState = GUIState.PLUGIN_LIST;
            openPluginList(player, session);
            return;
        }

        List<String> sortedPerms = new ArrayList<>(permissions);
        Collections.sort(sortedPerms);

        int itemsPerPage = 36;
        session.maxPage = (sortedPerms.size() - 1) / itemsPerPage;

        if (session.currentPage > session.maxPage) {
            session.currentPage = session.maxPage;
        }
        if (session.currentPage < 0) {
            session.currentPage = 0;
        }

        Inventory inv = Bukkit.createInventory(null, 54, createSecureTitle(session.selectedPlugin + " Permissions §8(" + (session.currentPage + 1) + "/" + (session.maxPage + 1) + ")"));

        addBackButton(inv, 45);

        if (session.currentPage > 0) {
            ItemStack prevItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
            ItemMeta prevMeta = prevItem.getItemMeta();
            prevMeta.setDisplayName("§c§l← Previous Page");
            prevMeta.setLore(Arrays.asList("§7Click to go to page " + session.currentPage));
            prevItem.setItemMeta(prevMeta);
            inv.setItem(48, prevItem);
        }

        ItemStack pageItem = new ItemStack(Material.BOOK);
        ItemMeta pageMeta = pageItem.getItemMeta();
        pageMeta.setDisplayName("§e§lPage " + (session.currentPage + 1) + "/" + (session.maxPage + 1));
        pageMeta.setLore(Arrays.asList(
                "§7Showing permissions " + (session.currentPage * itemsPerPage + 1) +
                        " to " + Math.min((session.currentPage + 1) * itemsPerPage, sortedPerms.size()) +
                        " of " + sortedPerms.size(),
                "§7Plugin: §f" + session.selectedPlugin,
                "§7",
                "§6§lTip: Press Q on any permission to add custom!"
        ));
        pageItem.setItemMeta(pageMeta);
        inv.setItem(49, pageItem);

        if (session.currentPage < session.maxPage) {
            ItemStack nextItem = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
            ItemMeta nextMeta = nextItem.getItemMeta();
            nextMeta.setDisplayName("§a§lNext Page →");
            nextMeta.setLore(Arrays.asList("§7Click to go to page " + (session.currentPage + 2)));
            nextItem.setItemMeta(nextMeta);
            inv.setItem(50, nextItem);
        }

        ItemStack saveItem = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta saveMeta = saveItem.getItemMeta();
        saveMeta.setDisplayName("§a§lSave Changes");
        saveMeta.setLore(Arrays.asList(
                "§7Click to apply all pending changes",
                "§7",
                "§7Pending additions: §a" + session.pendingChanges.size(),
                "§7Pending removals: §c" + session.pendingRemovals.size()
        ));
        saveItem.setItemMeta(saveMeta);
        inv.setItem(53, saveItem);

        Set<String> currentPermissions;
        if (session.editingPlayer) {
            currentPermissions = plugin.getPlayerPermissions(session.selectedPlayerUUID);
        } else {
            currentPermissions = plugin.getGroupPermissions(session.selectedTarget);
        }

        int startIndex = session.currentPage * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, sortedPerms.size());

        for (int i = startIndex; i < endIndex; i++) {
            String permission = sortedPerms.get(i);
            permission = permission.replaceAll("§[a-f0-9]", "").trim();
            if (permission.isEmpty()) continue;

            int slot = i - startIndex;
            if (slot >= 36) break;

            boolean hasPositive = currentPermissions.contains(permission);
            boolean hasNegative = currentPermissions.contains("-" + permission);
            boolean hasPendingGrant = session.pendingChanges.contains(permission);
            boolean hasPendingDeny = session.pendingChanges.contains("-" + permission);
            boolean hasPendingRemovalPos = session.pendingRemovals.contains(permission);
            boolean hasPendingRemovalNeg = session.pendingRemovals.contains("-" + permission);

            Material material;
            String statusText;

            if (hasPendingRemovalPos || hasPendingRemovalNeg) {
                material = Material.BARRIER;
                statusText = "§8§lPENDING REMOVAL";
            } else if (hasPendingGrant) {
                material = Material.YELLOW_CONCRETE;
                statusText = "§e§lPENDING GRANT";
            } else if (hasPendingDeny) {
                material = Material.ORANGE_CONCRETE;
                statusText = "§6§lPENDING DENY";
            } else if (hasPositive && !hasNegative) {
                material = Material.LIME_CONCRETE;
                statusText = "§a§lGRANTED";
            } else if (hasNegative && !hasPositive) {
                material = Material.RED_CONCRETE;
                statusText = "§c§lDENIED";
            } else if (hasPositive && hasNegative) {
                material = Material.PURPLE_CONCRETE;
                statusText = "§5§lCONFLICT";
            } else {
                material = Material.GRAY_CONCRETE;
                statusText = "§7NOT SET";
            }

            ItemStack permItem = new ItemStack(material);
            ItemMeta meta = permItem.getItemMeta();
            meta.setDisplayName("§f" + permission);

            List<String> lore = new ArrayList<>();
            lore.add("§7Permission: §f" + permission);
            lore.add("§7Status: " + statusText);
            lore.add("§8Debug: pos=" + hasPositive + " neg=" + hasNegative +
                    " pg=" + hasPendingGrant + " pd=" + hasPendingDeny + " pr=" + (hasPendingRemovalPos || hasPendingRemovalNeg));
            lore.add("§7");
            lore.add("§7§lClick Actions:");
            lore.add("§a  Left Click: §7Grant permission");
            lore.add("§c  Right Click: §7Deny permission");
            lore.add("§e  Middle Click: §7Remove permission");
            lore.add("§b  Shift+Click: §7Queue for batch save");
            lore.add("§6  Q Key + Click: §7Add custom permission");

            meta.setLore(lore);
            permItem.setItemMeta(meta);
            inv.setItem(slot, permItem);
        }

        player.openInventory(inv);
    }

    private void openPlayerGroupManagement(Player player, GUISession session) {
        if (!performSecurityChecks(player, "openPlayerGroupManagement")) {
            return;
        }

        session.currentState = GUIState.PLAYER_GROUP_MANAGEMENT;
        session.editingPlayer = false;

        Set<String> groups = plugin.getAllGroups();
        Inventory inv = Bukkit.createInventory(null, 54, createSecureTitle("Select Group to Manage Players"));

        addBackButton(inv, 45);

        int slot = 0;
        for (String group : groups) {
            if (slot >= 45) break;

            ItemStack groupItem = new ItemStack(Material.BOOKSHELF);
            ItemMeta meta = groupItem.getItemMeta();
            meta.setDisplayName("§b" + group);

            List<String> lore = new ArrayList<>();
            lore.add("§7Click to add/remove players from this group");
            lore.add("§7");

            int priority = plugin.getGroupPriority(group);
            Set<String> permissions = plugin.getGroupPermissions(group);
            long memberCount = plugin.getGroupMemberCount(group);

            lore.add("§7Priority: §f" + priority);
            lore.add("§7Permissions: §f" + permissions.size());
            lore.add("§7Current Members: §f" + memberCount);

            meta.setLore(lore);
            groupItem.setItemMeta(meta);
            inv.setItem(slot++, groupItem);
        }

        player.openInventory(inv);
    }

    private void openGroupMemberManagement(Player player, GUISession session) {
        if (!performSecurityChecks(player, "openGroupMemberManagement")) {
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 54, createSecureTitle("Manage Group: " + session.selectedTarget));

        addBackButton(inv, 45);

        ItemStack infoItem = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = infoItem.getItemMeta();
        infoMeta.setDisplayName("§e§lGroup: " + session.selectedTarget);
        infoMeta.setLore(Arrays.asList(
                "§7Click players to add/remove them from this group",
                "§a§lGreen name §7= Player is IN the group",
                "§7§lGray name §7= Player is NOT in the group",
                "§7",
                "§7Current members: §f" + plugin.getGroupMemberCount(session.selectedTarget)
        ));
        infoItem.setItemMeta(infoMeta);
        inv.setItem(53, infoItem);

        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

        for (int i = 0; i < Math.min(45, onlinePlayers.size()); i++) {
            Player target = onlinePlayers.get(i);
            ItemStack playerItem = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = playerItem.getItemMeta();

            Set<String> playerGroups = plugin.getPlayerGroups(target.getUniqueId());
            boolean isInGroup = playerGroups.contains(session.selectedTarget);

            meta.setDisplayName((isInGroup ? "§a" : "§7") + target.getName());

            List<String> lore = new ArrayList<>();
            if (isInGroup) {
                lore.add("§a✓ Player is in this group");
                lore.add("§7Click to REMOVE from group");
            } else {
                lore.add("§c✗ Player is NOT in this group");
                lore.add("§7Click to ADD to group");
            }
            lore.add("§7");
            lore.add("§7Current Groups:");
            if (playerGroups.isEmpty()) {
                lore.add("§8  None");
            } else {
                for (String group : playerGroups) {
                    if (group.equals(session.selectedTarget)) {
                        lore.add("§a  ✓ " + group + " §7(current)");
                    } else {
                        lore.add("§f  - " + group);
                    }
                }
            }

            meta.setLore(lore);
            playerItem.setItemMeta(meta);
            inv.setItem(i, playerItem);
        }

        player.openInventory(inv);
    }

    private void addBackButton(Inventory inv, int slot) {
        ItemStack backItem = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName("§c§l← Back");
        backMeta.setLore(Arrays.asList("§7Click to go back to the previous menu"));
        backItem.setItemMeta(backMeta);
        inv.setItem(slot, backItem);
    }

    private void kickUnauthorizedPlayer(Player player) {
        Component kickMessage = Component.text("§c§lSECURITY VIOLATION: §cYou have been kicked for unauthorized access attempts to the permission editor.");
        player.kick(kickMessage);
        plugin.getLogger().warning("SECURITY KICK - User " + player.getName() + " (" + player.getUniqueId() + ") was kicked for multiple unauthorized access attempts.");

        activeSessions.remove(player.getUniqueId());
        awaitingChatInput.remove(player.getUniqueId());
        lastGUIAccess.remove(player.getUniqueId());
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String title = event.getView().getTitle();
        if (!isSecureGUI(title)) {
            return;
        }

        if (!performSecurityChecks(player, "inventoryClick")) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        event.setCancelled(true);

        UUID playerUUID = player.getUniqueId();
        GUISession session = activeSessions.get(playerUUID);
        if (session == null) {
            logSecurityViolation(player, "Attempted to use GUI without valid session");
            player.closeInventory();
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }

        if (event.getClick() == org.bukkit.event.inventory.ClickType.DROP) {
            if (session.currentState == GUIState.PERMISSION_LIST && event.getSlot() < 36) {
                handleCustomPermissionInput(player, session, clicked);
                return;
            }
        }

        if (isBackButton(clicked)) {
            handleBackButton(player, session);
            return;
        }

        switch (session.currentState) {
            case MAIN_MENU:
                handleMainMenuClick(player, session, clicked);
                break;
            case PLAYER_SELECTION:
                handlePlayerSelectionClick(player, session, clicked, event.getSlot());
                break;
            case GROUP_SELECTION:
                handleGroupSelectionClick(player, session, clicked, event);
                break;
            case PLUGIN_LIST:
                handlePluginListClick(player, session, clicked);
                break;
            case PERMISSION_LIST:
                handlePermissionListClick(player, session, clicked, event);
                break;
            case PLAYER_GROUP_MANAGEMENT:
                if (session.selectedTarget != null && event.getView().getTitle().contains("Manage Group:")) {
                    handleGroupMemberManagementClick(player, session, clicked, event.getSlot());
                } else {
                    handlePlayerGroupManagementClick(player, session, clicked, event.getSlot());
                }
                break;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();

        GUISession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            String title = event.getView().getTitle();
            if (!isSecureGUI(title)) {
                if (event.getInventory().getType() == InventoryType.CHEST ||
                        event.getInventory().getType() == InventoryType.ENDER_CHEST ||
                        event.getInventory().getType() == InventoryType.SHULKER_BOX) {

                    logSecurityViolation(player, "Attempted to access chest/container while GUI session active");
                    activeSessions.remove(player.getUniqueId());
                    awaitingChatInput.remove(player.getUniqueId());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        GUISession session = activeSessions.get(player.getUniqueId());

        if (session != null) {
            if (session.currentState == GUIState.PERMISSION_LIST) {
                return;
            }

            event.setCancelled(true);
            logSecurityViolation(player, "Attempted to drop items while GUI active");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        String title = event.getView().getTitle();
        if (isSecureGUI(title)) {
            event.setCancelled(true);
            logSecurityViolation(player, "Attempted inventory drag in secure GUI");
        }
    }

    private void handleCustomPermissionInput(Player player, GUISession session, ItemStack clicked) {
        if (!performSecurityChecks(player, "customPermissionInput")) {
            return;
        }

        String basePermission = "";
        if (clicked.hasItemMeta() && clicked.getItemMeta().hasDisplayName()) {
            String displayName = clicked.getItemMeta().getDisplayName();
            if (displayName.startsWith("§f")) {
                basePermission = displayName.substring(2);
                if (basePermission.contains(".")) {
                    basePermission = basePermission.substring(0, basePermission.indexOf(".") + 1);
                }
            }
        }

        player.closeInventory();
        player.sendMessage("§eEnter the custom permission in chat:");
        player.sendMessage("§7Suggested prefix: §f" + basePermission);
        player.sendMessage("§7Type 'cancel' to cancel");

        awaitingChatInput.put(player.getUniqueId(), session.selectedPlugin + "|" +
                (session.editingPlayer ? "player:" + session.selectedTarget : "group:" + session.selectedTarget));
    }

    private boolean isBackButton(ItemStack item) {
        if (item.getType() != Material.ARROW) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getDisplayName().equals("§c§l← Back");
    }

    private void handleMainMenuClick(Player player, GUISession session, ItemStack clicked) {
        if (!performSecurityChecks(player, "mainMenuClick")) {
            return;
        }

        if (clicked.getType() == Material.PLAYER_HEAD) {
            openPlayerSelection(player, session);
        } else if (clicked.getType() == Material.BOOKSHELF) {
            openGroupSelection(player, session);
        }
    }

    private void handlePlayerSelectionClick(Player player, GUISession session, ItemStack clicked, int slot) {
        if (!performSecurityChecks(player, "playerSelectionClick")) {
            return;
        }

        if (slot == 53 && clicked.getType() == Material.WRITABLE_BOOK) {
            openPlayerGroupManagement(player, session);
            return;
        }

        if (clicked.getType() == Material.PLAYER_HEAD && slot < 45 && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta.hasDisplayName()) {
                String displayName = meta.getDisplayName();
                String playerName = displayName.replaceAll("§[a-f0-9]", "").trim();

                Player target = Bukkit.getPlayer(playerName);
                if (target != null) {
                    session.selectedTarget = playerName;
                    session.selectedPlayerUUID = target.getUniqueId();
                    openPluginList(player, session);
                } else {
                    player.sendMessage("§cPlayer '" + playerName + "' is no longer online.");
                }
            }
        }
    }

    private void handleGroupSelectionClick(Player player, GUISession session, ItemStack clicked, InventoryClickEvent event) {
        if (!performSecurityChecks(player, "groupSelectionClick")) {
            return;
        }

        if (clicked.getType() == Material.EMERALD && event.getSlot() == 53) {
            player.closeInventory();
            player.sendMessage("§eEnter the name for the new group in chat:");
            player.sendMessage("§7Requirements: No spaces, letters/numbers/underscores only, max 20 chars");
            player.sendMessage("§7Type 'cancel' to cancel");

            awaitingChatInput.put(player.getUniqueId(), "create_group");
            return;
        }

        if (clicked.getType() == Material.BOOKSHELF) {
            String groupName = clicked.getItemMeta().getDisplayName().substring(2);

            if (event.isShiftClick() && !groupName.equals("default")) {
                plugin.deleteGroup(groupName);
                player.sendMessage("§cDeleted group: " + groupName);
                openGroupSelection(player, session);
            } else {
                session.selectedTarget = groupName;
                openPluginList(player, session);
            }
        }
    }

    private void handlePluginListClick(Player player, GUISession session, ItemStack clicked) {
        if (!performSecurityChecks(player, "pluginListClick")) {
            return;
        }

        if (clicked.getType() == Material.COMPASS) {
            scanAllPluginPermissionsAsync().thenRun(() -> {
                SchedulerUtil.run(plugin, player.getLocation(), () -> {
                    if (player.isOnline()) {
                        player.sendMessage("§aPlugin permissions refreshed!");
                        openPluginList(player, session);
                    }
                });
            });
        } else if (clicked.getType() == Material.PAPER || clicked.getType() == Material.GRASS_BLOCK) {
            String pluginName = clicked.getItemMeta().getDisplayName().substring(2);
            session.selectedPlugin = pluginName;
            openPermissionList(player, session);
        }
    }

    private void handlePermissionListClick(Player player, GUISession session, ItemStack clicked, InventoryClickEvent event) {
        if (!performSecurityChecks(player, "permissionListClick")) {
            return;
        }

        int slot = event.getSlot();

        if (slot == 48 && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
            if (session.currentPage > 0) {
                session.currentPage--;
                openPermissionList(player, session);
            }
            return;
        } else if (slot == 50 && clicked.getType() == Material.GREEN_STAINED_GLASS_PANE) {
            if (session.currentPage < session.maxPage) {
                session.currentPage++;
                openPermissionList(player, session);
            }
            return;
        } else if (slot == 53 && clicked.getType() == Material.EMERALD_BLOCK) {
            applyPendingChanges(player, session);
            return;
        }

        if (slot < 36 && clicked.hasItemMeta() && clicked.getItemMeta().hasDisplayName()) {
            String displayName = clicked.getItemMeta().getDisplayName();
            if (!displayName.startsWith("§f")) return;

            String permissionName = displayName.substring(2).replaceAll("§[a-f0-9]", "").trim();

            session.pendingChanges.remove(permissionName);
            session.pendingChanges.remove("-" + permissionName);
            session.pendingRemovals.remove(permissionName);
            session.pendingRemovals.remove("-" + permissionName);

            boolean shouldRefresh = false;

            if (event.getClick().isLeftClick()) {
                if (event.isShiftClick()) {
                    session.pendingChanges.add(permissionName);
                    player.sendMessage("§eQueued permission grant: " + permissionName);
                    shouldRefresh = true;
                } else {
                    grantPermissionWithScheduling(player, session, permissionName, false);
                    shouldRefresh = true;
                }
            } else if (event.getClick().isRightClick()) {
                if (event.isShiftClick()) {
                    session.pendingChanges.add("-" + permissionName);
                    player.sendMessage("§eQueued permission deny: " + permissionName);
                    shouldRefresh = true;
                } else {
                    grantPermissionWithScheduling(player, session, permissionName, true);
                    shouldRefresh = true;
                }
            } else if (event.getClick() == org.bukkit.event.inventory.ClickType.MIDDLE) {
                if (event.isShiftClick()) {
                    session.pendingRemovals.add(permissionName);
                    session.pendingRemovals.add("-" + permissionName);
                    player.sendMessage("§eQueued permission removal: " + permissionName);
                    shouldRefresh = true;
                } else {
                    removePermissionWithScheduling(player, session, permissionName);
                    shouldRefresh = true;
                }
            }

            if (shouldRefresh) {
                plugin.saveData();

                SchedulerUtil.runDelayed(plugin, player.getLocation(), () -> {
                    if (player.isOnline() && activeSessions.containsKey(player.getUniqueId())) {
                        GUISession currentSession = activeSessions.get(player.getUniqueId());
                        if (currentSession != null && currentSession.currentState == GUIState.PERMISSION_LIST) {
                            openPermissionList(player, currentSession);
                        }
                    }
                }, 3L);
            }
        }
    }

    private void handlePlayerGroupManagementClick(Player player, GUISession session, ItemStack clicked, int slot) {
        if (!performSecurityChecks(player, "playerGroupManagementClick")) {
            return;
        }

        if (clicked.getType() == Material.BOOKSHELF && slot < 45) {
            String groupName = clicked.getItemMeta().getDisplayName().substring(2);
            session.selectedTarget = groupName;
            openGroupMemberManagement(player, session);
        }
    }

    private void handleGroupMemberManagementClick(Player player, GUISession session, ItemStack clicked, int slot) {
        if (!performSecurityChecks(player, "groupMemberManagementClick")) {
            return;
        }

        if (clicked.getType() == Material.PLAYER_HEAD && slot < 45 && clicked.hasItemMeta()) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta.hasDisplayName()) {
                String displayName = meta.getDisplayName();
                String playerName = displayName.replaceAll("§[a-f0-9]", "").trim();

                plugin.getLogger().info("Group member click: " + playerName + " for group " + session.selectedTarget);

                Player target = Bukkit.getPlayer(playerName);
                if (target != null) {
                    UUID targetUUID = target.getUniqueId();
                    Set<String> playerGroups = plugin.getPlayerGroups(targetUUID);
                    String groupName = session.selectedTarget;

                    try {
                        if (playerGroups.contains(groupName)) {
                            plugin.removePlayerFromGroup(targetUUID, groupName);
                            player.sendMessage("§cRemoved " + playerName + " from group " + groupName);
                            plugin.getLogger().info("Successfully removed " + playerName + " from group " + groupName);
                        } else {
                            plugin.addPlayerToGroup(targetUUID, groupName);
                            player.sendMessage("§aAdded " + playerName + " to group " + groupName);
                            plugin.getLogger().info("Successfully added " + playerName + " to group " + groupName);
                        }

                        plugin.saveData();

                        SchedulerUtil.runDelayed(plugin, player.getLocation(), () -> {
                            if (player.isOnline() && activeSessions.containsKey(player.getUniqueId())) {
                                GUISession currentSession = activeSessions.get(player.getUniqueId());
                                if (currentSession != null && currentSession.selectedTarget != null) {
                                    openGroupMemberManagement(player, currentSession);
                                }
                            }
                        }, 5L);

                    } catch (Exception e) {
                        player.sendMessage("§cFailed to update group membership: " + e.getMessage());
                        plugin.getLogger().severe("Failed to update group membership: " + e.getMessage());
                        e.printStackTrace();
                    }
                } else {
                    player.sendMessage("§cPlayer " + playerName + " is no longer online.");
                }
            }
        }
    }

    private void handleBackButton(Player player, GUISession session) {
        if (!performSecurityChecks(player, "backButton")) {
            return;
        }

        switch (session.currentState) {
            case PERMISSION_LIST:
                openPluginList(player, session);
                break;
            case PLUGIN_LIST:
                if (session.editingPlayer) {
                    openPlayerSelection(player, session);
                } else {
                    openGroupSelection(player, session);
                }
                break;
            case PLAYER_GROUP_MANAGEMENT:
                openPlayerSelection(player, session);
                break;
            case PLAYER_SELECTION:
            case GROUP_SELECTION:
                openMainMenu(player, session);
                break;
            default:
                player.closeInventory();
                activeSessions.remove(player.getUniqueId());
                break;
        }
    }

    private void grantPermissionWithScheduling(Player player, GUISession session, String permission, boolean deny) {
        if (!performSecurityChecks(player, "grantPermission")) {
            return;
        }

        String permToAdd = deny ? "-" + permission : permission;
        plugin.getLogger().info("Granting permission: " + permToAdd + " to " +
                (session.editingPlayer ? "player " + session.selectedTarget : "group " + session.selectedTarget));

        try {
            if (session.editingPlayer) {
                plugin.removePlayerPermission(session.selectedPlayerUUID, deny ? permission : "-" + permission);
                plugin.addPlayerPermission(session.selectedPlayerUUID, permToAdd);
                player.sendMessage("§a" + (deny ? "Denied" : "Granted") + " permission " + permission + " to " + session.selectedTarget);
            } else {
                plugin.removeGroupPermission(session.selectedTarget, deny ? permission : "-" + permission);
                plugin.addGroupPermission(session.selectedTarget, permToAdd);
                player.sendMessage("§a" + (deny ? "Denied" : "Granted") + " permission " + permission + " to group " + session.selectedTarget);
            }
        } catch (Exception e) {
            player.sendMessage("§cFailed to " + (deny ? "deny" : "grant") + " permission: " + e.getMessage());
            plugin.getLogger().severe("Failed to grant permission: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void removePermissionWithScheduling(Player player, GUISession session, String permission) {
        if (!performSecurityChecks(player, "removePermission")) {
            return;
        }

        plugin.getLogger().info("Removing permission: " + permission + " from " +
                (session.editingPlayer ? "player " + session.selectedTarget : "group " + session.selectedTarget));

        try {
            if (session.editingPlayer) {
                plugin.removePlayerPermission(session.selectedPlayerUUID, permission);
                plugin.removePlayerPermission(session.selectedPlayerUUID, "-" + permission);
                player.sendMessage("§cRemoved permission " + permission + " from " + session.selectedTarget);
            } else {
                plugin.removeGroupPermission(session.selectedTarget, permission);
                plugin.removeGroupPermission(session.selectedTarget, "-" + permission);
                player.sendMessage("§cRemoved permission " + permission + " from group " + session.selectedTarget);
            }
        } catch (Exception e) {
            player.sendMessage("§cFailed to remove permission: " + e.getMessage());
            plugin.getLogger().severe("Failed to remove permission: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void applyPendingChanges(Player player, GUISession session) {
        if (!performSecurityChecks(player, "applyPendingChanges")) {
            return;
        }

        int applied = 0;

        for (String perm : new HashSet<>(session.pendingChanges)) {
            try {
                if (session.editingPlayer) {
                    plugin.addPlayerPermission(session.selectedPlayerUUID, perm);
                } else {
                    plugin.addGroupPermission(session.selectedTarget, perm);
                }
                applied++;
            } catch (Exception e) {
                player.sendMessage("§cFailed to add " + perm + ": " + e.getMessage());
                plugin.getLogger().severe("Failed to apply pending change: " + e.getMessage());
            }
        }

        for (String perm : new HashSet<>(session.pendingRemovals)) {
            try {
                if (session.editingPlayer) {
                    plugin.removePlayerPermission(session.selectedPlayerUUID, perm);
                    plugin.removePlayerPermission(session.selectedPlayerUUID, "-" + perm);
                } else {
                    plugin.removeGroupPermission(session.selectedTarget, perm);
                    plugin.removeGroupPermission(session.selectedTarget, "-" + perm);
                }
                applied++;
            } catch (Exception e) {
                player.sendMessage("§cFailed to remove " + perm + ": " + e.getMessage());
                plugin.getLogger().severe("Failed to apply pending removal: " + e.getMessage());
            }
        }

        session.pendingChanges.clear();
        session.pendingRemovals.clear();

        player.sendMessage("§aApplied " + applied + " permission changes!");

        plugin.saveData();
        SchedulerUtil.runDelayed(plugin, player.getLocation(), () -> {
            if (player.isOnline() && activeSessions.containsKey(player.getUniqueId())) {
                openPermissionList(player, session);
            }
        }, 3L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (!awaitingChatInput.containsKey(playerUUID)) {
            return;
        }

        event.setCancelled(true);
        String input = event.getMessage().trim();
        String context = awaitingChatInput.remove(playerUUID);

        if (!performSecurityChecks(player, "chatInput")) {
            player.sendMessage("§cChat input cancelled due to security restrictions.");
            return;
        }

        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage("§7Operation cancelled.");
            GUISession session = activeSessions.get(playerUUID);
            if (session != null) {
                SchedulerUtil.runDelayed(plugin, player.getLocation(), () -> {
                    if (player.isOnline() && activeSessions.containsKey(playerUUID)) {
                        reopenPreviousGUI(player, session);
                    }
                }, 5L);
            }
            return;
        }

        if (context.equals("create_group")) {
            handleGroupCreation(player, input);
        } else if (context.contains("|")) {
            handleCustomPermissionCreation(player, context, input);
        }
    }

    private void handleGroupCreation(Player player, String groupName) {
        if (groupName.isEmpty() || groupName.length() > 20) {
            player.sendMessage("§cGroup name must be between 1 and 20 characters.");
            return;
        }

        if (!groupName.matches("[a-zA-Z0-9_]+")) {
            player.sendMessage("§cGroup name can only contain letters, numbers, and underscores.");
            return;
        }

        if (groupName.equalsIgnoreCase("default")) {
            player.sendMessage("§cCannot create a group named 'default'.");
            return;
        }

        if (plugin.getAllGroups().contains(groupName)) {
            player.sendMessage("§cGroup '" + groupName + "' already exists.");
            return;
        }

        try {
            plugin.createGroup(groupName);
            player.sendMessage("§aSuccessfully created group: " + groupName);

            SchedulerUtil.runDelayed(plugin, player.getLocation(), () -> {
                if (player.isOnline() && activeSessions.containsKey(player.getUniqueId())) {
                    GUISession session = activeSessions.get(player.getUniqueId());
                    if (session != null) {
                        openGroupSelection(player, session);
                    }
                }
            }, 10L);

        } catch (Exception e) {
            player.sendMessage("§cFailed to create group: " + e.getMessage());
            plugin.getLogger().severe("Failed to create group " + groupName + ": " + e.getMessage());
        }
    }

    private void handleCustomPermissionCreation(Player player, String context, String permission) {
        String[] parts = context.split("\\|", 2);
        if (parts.length != 2) {
            player.sendMessage("§cInvalid context format.");
            return;
        }

        String pluginName = parts[0];
        String targetInfo = parts[1];

        if (permission.isEmpty() || permission.length() > 100) {
            player.sendMessage("§cPermission must be between 1 and 100 characters.");
            return;
        }

        if (!permission.matches("[a-zA-Z0-9._\\-*]+")) {
            player.sendMessage("§cPermission can only contain letters, numbers, dots, underscores, hyphens, and asterisks.");
            return;
        }

        GUISession session = activeSessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("§cSession expired. Please try again.");
            return;
        }

        try {
            if (targetInfo.startsWith("player:")) {
                String playerName = targetInfo.substring(7);
                Player target = Bukkit.getPlayer(playerName);
                if (target != null && session.selectedPlayerUUID != null) {
                    plugin.addPlayerPermission(session.selectedPlayerUUID, permission);
                    player.sendMessage("§aAdded custom permission '" + permission + "' to player " + playerName);
                } else {
                    player.sendMessage("§cPlayer no longer online or session invalid.");
                    return;
                }
            } else if (targetInfo.startsWith("group:")) {
                String groupName = targetInfo.substring(6);
                plugin.addGroupPermission(groupName, permission);
                player.sendMessage("§aAdded custom permission '" + permission + "' to group " + groupName);
            } else {
                player.sendMessage("§cInvalid target type.");
                return;
            }

            plugin.saveData();

            SchedulerUtil.runDelayed(plugin, player.getLocation(), () -> {
                if (player.isOnline() && activeSessions.containsKey(player.getUniqueId())) {
                    GUISession currentSession = activeSessions.get(player.getUniqueId());
                    if (currentSession != null) {
                        Set<String> pluginPerms = pluginPermissions.get(pluginName);
                        if (pluginPerms != null) {
                            pluginPerms.add(permission);
                        }
                        openPermissionList(player, currentSession);
                    }
                }
            }, 10L);

        } catch (Exception e) {
            player.sendMessage("§cFailed to add custom permission: " + e.getMessage());
            plugin.getLogger().severe("Failed to add custom permission: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void reopenPreviousGUI(Player player, GUISession session) {
        switch (session.currentState) {
            case GROUP_SELECTION:
                openGroupSelection(player, session);
                break;
            case PERMISSION_LIST:
                openPermissionList(player, session);
                break;
            case PLUGIN_LIST:
                openPluginList(player, session);
                break;
            case PLAYER_SELECTION:
                openPlayerSelection(player, session);
                break;
            default:
                openMainMenu(player, session);
                break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();

        activeSessions.remove(playerUUID);
        awaitingChatInput.remove(playerUUID);
        lastGUIAccess.remove(playerUUID);

        plugin.getLogger().fine("Cleaned up GUI session data for " + event.getPlayer().getName());
    }

    public void cleanupOldSessions() {
        long currentTime = System.currentTimeMillis();
        long maxSessionAge = 30 * 60 * 1000;

        activeSessions.entrySet().removeIf(entry -> {
            GUISession session = entry.getValue();
            if (currentTime - session.sessionStartTime > maxSessionAge) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player == null || !player.isOnline()) {
                    plugin.getLogger().fine("Cleaned up expired session for UUID: " + entry.getKey());
                    return true;
                }
            }
            return false;
        });

        awaitingChatInput.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            return player == null || !player.isOnline();
        });

        lastGUIAccess.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            return player == null || !player.isOnline();
        });
    }

    private void startPeriodicCleanup() {
        SchedulerUtil.runAtFixedRate(plugin, this::cleanupOldSessions, 12000L, 12000L);
    }
}

