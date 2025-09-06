package me.IfMasterPluginsPerms.foliaPerms.util;


import static org.bukkit.Bukkit.getLogger;

public class Debug {
    public static void Debugger(String text, String version) {
        if (version.equals("warning")) {
            getLogger().warning(text);
        } else if (version.equals("logger")) {
            getLogger().info(text);
        }
    }

}
