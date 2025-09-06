package me.IfMasterPluginsPerms.foliaPerms.Updater;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.concurrent.CompletableFuture.*;
import static me.IfMasterPluginsPerms.foliaPerms.util.Debug.*;
import static me.IfMasterPluginsPerms.foliaPerms.util.SchedulerUtil.*;
import static me.IfMasterPluginsPerms.foliaPerms.FoliaPerms.*;

public class UpdateChecker {
    private static final String GITHUB_REPO = "If-Master/foliaperms";
    private static String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static String assetUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/assets/";


    public static void checkForUpdates(CommandSender player) {
        runAsync(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "FoliaPerms-UpdateChecker");

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    String text = ("Failed to check updates. Code: " + responseCode);
                    Debugger(text, "warning");
                    runGlobalTask(getInstance(), () ->
                            player.sendMessage("§cFailed to check for updates. HTTP Code: " + responseCode));
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String responseBody = response.toString();
                int tagStart = responseBody.indexOf("\"tag_name\":\"") + 12;
                int tagEnd = responseBody.indexOf("\"", tagStart);
                String latest = responseBody.substring(tagStart, tagEnd);
                String current = getInstance().getDescription().getVersion();

                if (!current.equals(latest)) {
                    runGlobalTask(getInstance(), () -> {
                        player.sendMessage("§3Update available! Current: " + current + ", Latest: " + latest);
                        player.sendMessage("§eUse '/punish download' to download the update automatically.");
                    });
                    String text = ("Update available! Current: " + current + ", Latest: " + latest);
                    Debugger(text, "logger");

                } else {
                    runGlobalTask(getInstance(), () ->
                            player.sendMessage("§aYou have the latest version! (" + current + ")"));
                    String text = ("No updates found. Current version: " + current);
                    Debugger(text, "logger");

                }
            } catch (Exception e) {
                String text = ("Failed to check for updates: " + e.getMessage());
                Debugger(text, "warning");
                runGlobalTask(getInstance(), () ->
                        player.sendMessage("§cFailed to check for updates: " + e.getMessage()));
                e.printStackTrace();
            }
        });
    }

    public static void updatePluginFromGitHub(CommandSender sender, Plugin plugin) {
        sender.sendMessage("§6Starting plugin update download...");

        runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestProperty("User-Agent", "FoliaPerms-UpdateChecker");

                int responseCode = conn.getResponseCode();
                String text = ("DEBUG: GitHub API response code: " + responseCode);
                Debugger(text, "logger");

                if (responseCode != 200) {
                    runGlobalTask(getInstance(), () ->
                            sender.sendMessage("§cFailed to connect to GitHub API. Code: " + responseCode));
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String json = response.toString();
                String text2 = ("DEBUG: Received JSON response (first 200 chars): " +
                        json.substring(0, Math.min(200, json.length())));
                Debugger(text2, "logger");

                String pluginName = plugin.getName();
                String assetInfo = findBestAsset(json, pluginName);

                if (assetInfo == null) {
                    String descriptionName = plugin.getDescription().getName();
                    if (!descriptionName.equals(pluginName)) {
                        String text3 = ("DEBUG: Trying with description name: " + descriptionName);
                        Debugger(text3, "logger");

                        assetInfo = findBestAsset(json, descriptionName);
                    }
                }

                if (assetInfo == null) {
                    runGlobalTask(getInstance(), () -> {
                        sender.sendMessage("§cNo suitable plugin JAR found in the latest release!");
                        sender.sendMessage("§cLooked for files matching plugin name: " + pluginName);
                        sender.sendMessage("§cCheck console for detailed debug information.");
                    });
                    return;
                }

                String[] parts = assetInfo.split("\\|");
                String assetId = parts[0];
                String assetName = parts[1];

                String text4 = ("DEBUG: Proceeding with asset ID: " + assetId + ", name: " + assetName);
                Debugger(text4, "logger");

                downloadPluginUpdate(sender, assetId, assetName, plugin);

            } catch (Exception e) {
                String text = ("DEBUG: Exception in updatePluginFromGitHub: " + e.getMessage());
                Debugger(text, "warning");

                e.printStackTrace();
                runGlobalTask(getInstance(), () ->
                        sender.sendMessage("§cFailed to update plugin: " + e.getMessage()));
            }
        });
    }

    private static String findBestAsset(String json, String pluginName) {
        try {
            String text2 = ("DEBUG: Looking for assets matching plugin name: " + pluginName);
            Debugger(text2, "logger");

            int assetsStart = json.indexOf("\"assets\":[");
            if (assetsStart == -1) {
                String text = ("DEBUG: No assets array found in JSON");
                Debugger(text, "warning");

                return null;
            }

            String assetsSection = json.substring(assetsStart + 10);
            int assetsEnd = assetsSection.indexOf("]");
            if (assetsEnd == -1) {
                String text = ("DEBUG: Could not find end of assets array");
                Debugger(text, "warning");

                return null;
            }
            assetsSection = assetsSection.substring(0, assetsEnd);

            String debugSection = assetsSection.length() > 500 ? assetsSection.substring(0, 500) + "..." : assetsSection;
            String text3 = ("DEBUG: Assets section (truncated): " + debugSection);
            Debugger(text3, "logger");

            Pattern allAssetsPattern = Pattern.compile("\"name\":\"([^\"]+\\.jar)\"", Pattern.CASE_INSENSITIVE);
            Matcher allAssetsMatcher = allAssetsPattern.matcher(assetsSection);
            String text4 = ("DEBUG: Found JAR assets:");
            Debugger(text4, "logger");

            while (allAssetsMatcher.find()) {
                String text = ("DEBUG: - " + allAssetsMatcher.group(1));
                Debugger(text, "logger");

            }

            String[] patterns = {
                    pluginName.toLowerCase() + "-.*-shaded\\.jar",
                    pluginName.toLowerCase() + "-shaded\\.jar",
                    pluginName.toLowerCase() + "\\.jar",
                    ".*-shaded\\.jar",
                    ".*\\.jar"
            };

            for (String patternStr : patterns) {
                String text = ("DEBUG: Trying pattern: " + patternStr);
                Debugger(text, "logger");

                Pattern exactNamePattern = Pattern.compile("\"name\":\"([^\"]+\\.jar)\"", Pattern.CASE_INSENSITIVE);
                Matcher exactNameMatcher = exactNamePattern.matcher(assetsSection);

                while (exactNameMatcher.find()) {
                    String candidateName = exactNameMatcher.group(1);

                    if (candidateName.toLowerCase().matches(patternStr)) {
                        String text5 = ("DEBUG: Pattern matched! Asset name: " + candidateName);
                        Debugger(text5, "logger");

                        String beforeAssetName = assetsSection.substring(0, exactNameMatcher.start());

                        Pattern idPattern = Pattern.compile("\"id\":(\\d+)");
                        Matcher idMatcher = idPattern.matcher(beforeAssetName);

                        String assetId = null;
                        while (idMatcher.find()) {
                            assetId = idMatcher.group(1);
                        }

                        if (assetId != null) {
                            return assetId + "|" + candidateName;
                        } else {
                            String text6 = ("DEBUG: Could not find asset ID for: " + candidateName);
                            Debugger(text6, "warning");

                        }
                    }
                }
            }

            String text = ("DEBUG: No suitable JAR asset found in release");
            Debugger(text, "warning");

            return null;

        } catch (Exception e) {
            String text = ("DEBUG: Error parsing assets: " + e.getMessage());
            Debugger(text, "warning");

            e.printStackTrace();
            return null;
        }
    }

    public static void downloadPluginUpdate(CommandSender sender, String assetId, String assetName, Plugin plugin) {
        runAsync(() -> {
            try {
                String downloadUrl = assetUrl + assetId;
                String text = ("DEBUG: Downloading from URL: " + downloadUrl);
                Debugger(text, "logger");

                HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/octet-stream");
                conn.setRequestProperty("User-Agent", "FoliaPerms-UpdateChecker");
                conn.setInstanceFollowRedirects(true);

                int responseCode = conn.getResponseCode();
                String text2 = ("DEBUG: Download response code: " + responseCode);
                Debugger(text2, "logger");

                if (responseCode != 200) {
                    runGlobalTask(getInstance(), () -> {
                        sender.sendMessage("§cDownload failed. HTTP Code: " + responseCode);
                        String text3 = ("Asset download failed with code: " + responseCode);
                        Debugger(text3, "warning");

                    });
                    return;
                }

                File pluginsFolder = plugin.getDataFolder().getParentFile();
                String text4 = ("DEBUG: Plugins folder path: " + pluginsFolder.getAbsolutePath());
                Debugger(text4, "logger");

                if (!pluginsFolder.exists()) {
                    String text5 = ("Plugins folder does not exist: " + pluginsFolder.getAbsolutePath());
                    Debugger(text5, "warning");

                    runGlobalTask(getInstance(), () ->
                            sender.sendMessage("§cPlugins folder not found: " + pluginsFolder.getAbsolutePath()));
                    return;
                }

                if (!pluginsFolder.canWrite()) {
                    String text6 = ("Plugins folder is not writable: " + pluginsFolder.getAbsolutePath());
                    Debugger(text6, "warning");

                    runGlobalTask(getInstance(), () ->
                            sender.sendMessage("§cPlugins folder is not writable: " + pluginsFolder.getAbsolutePath()));
                    return;
                }

                String cleanAssetName = assetName.replaceAll("[^a-zA-Z0-9.-]", "_");

                String tempFileName = "temp_" + System.currentTimeMillis() + "_" + cleanAssetName;
                File tempFile = new File(pluginsFolder, tempFileName);
                String text7 = ("DEBUG: Using temporary file: " + tempFile.getAbsolutePath());
                Debugger(text7, "logger");

                try {
                    tempFile.getParentFile().mkdirs();

                    if (!tempFile.createNewFile()) {
                        String text8 = ("DEBUG: Temp file already exists, deleting: " + tempFile.getName());
                        Debugger(text8, "warning");

                        tempFile.delete();
                        tempFile.createNewFile();
                    }
                    String text9 = ("DEBUG: Successfully created temporary file: " + tempFile.getAbsolutePath());
                    Debugger(text9, "logger");

                } catch (Exception e) {
                    String text9 = ("DEBUG: Cannot create temporary file: " + e.getMessage());
                    Debugger(text9, "warning");

                    runGlobalTask(getInstance(), () ->
                            sender.sendMessage("§cCannot create temporary file: " + e.getMessage()));
                    return;
                }

                long totalBytes = 0;
                try (InputStream in = conn.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        totalBytes += bytesRead;
                    }

                    String text8 = ("DEBUG: Downloaded " + totalBytes + " bytes to temporary file");
                    Debugger(text8, "logger");

                } catch (Exception e) {
                    String text6 = ("DEBUG: Error during download: " + e.getMessage());
                    Debugger(text6, "warning");

                    e.printStackTrace();

                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                    throw e;
                }

                if (!tempFile.exists() || tempFile.length() == 0) {
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                    throw new RuntimeException("Downloaded file is empty or does not exist");
                }

                deleteOldVersions(pluginsFolder, plugin.getName());

                String finalAssetName = assetName.replaceAll("[^a-zA-Z0-9.-]", "_");
                File finalFile = new File(pluginsFolder, finalAssetName);

                String text5 = ("DEBUG: Final file will be: " + finalFile.getAbsolutePath());
                Debugger(text5, "logger");

                if (finalFile.exists()) {
                    String text3 = ("DEBUG: Deleting existing file: " + finalFile.getName());
                    Debugger(text3, "logger");

                    if (!finalFile.delete()) {
                        String text6 = ("DEBUG: Could not delete existing file: " + finalFile.getName());
                        Debugger(text6, "warning");

                    }
                }

                try {
                    Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    String text8 = ("DEBUG: Successfully moved temp file to: " + finalFile.getAbsolutePath());
                    Debugger(text8, "logger");

                } catch (Exception e) {
                    String text9 = ("DEBUG: Failed to move temp file, trying copy instead: " + e.getMessage());
                    Debugger(text9, "warning");

                    try {
                        Files.copy(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        tempFile.delete();
                        String text1 = ("DEBUG: Successfully copied temp file to: " + finalFile.getAbsolutePath());
                        Debugger(text1, "logger");

                    } catch (Exception e2) {
                        String text1 = ("DEBUG: Copy also failed: " + e2.getMessage());
                        Debugger(text1, "warning");

                        tempFile.delete();
                        throw e2;
                    }
                }

                final long fileSize = finalFile.length();

                runGlobalTask(getInstance(), () -> {
                    sender.sendMessage("§a§lPlugin updated successfully!");
                    sender.sendMessage("§eDownloaded: " + finalAssetName);
                    sender.sendMessage("§eFile size: " + fileSize + " bytes");
                    sender.sendMessage("§eSaved to: " + finalFile.getAbsolutePath());
                    sender.sendMessage("§6§lRestart the server to complete the update.");
                });

                String text3 = ("Plugin update completed successfully: " + finalAssetName + " (" + fileSize + " bytes)");
                Debugger(text3, "logger");

            } catch (Exception e) {
                runGlobalTask(getInstance(), () -> {
                    sender.sendMessage("§cFailed to download plugin: " + e.getMessage());
                    sender.sendMessage("§eCheck the console for detailed error information.");
                });
                String text = ("Download error: " + e.getMessage());
                Debugger(text, "warning");

                e.printStackTrace();
            }
        });
    }

    private static void deleteOldVersions(File pluginsFolder, String pluginName) {
        try {
            File[] files = pluginsFolder.listFiles();
            if (files == null) return;

            String pluginNameLower = pluginName.toLowerCase();

            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".jar")) {
                    String fileName = file.getName().toLowerCase();

                    if (isOldVersion(fileName, pluginNameLower)) {
                        String text = ("Deleting old plugin version: " + file.getName());
                        Debugger(text, "logger");

                        boolean deleted = false;
                        for (int i = 0; i < 3; i++) {
                            if (file.delete()) {
                                deleted = true;
                                String text2 = ("Successfully deleted: " + file.getName());
                                Debugger(text2, "logger");

                                break;
                            } else {
                                String text3 = ("Attempt " + (i + 1) + " failed to delete: " + file.getName());
                                Debugger(text3, "warning");

                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException ignored) {}
                            }
                        }

                        if (!deleted) {
                            String text4 = ("Failed to delete old version after 3 attempts: " + file.getName());
                            Debugger(text4, "warning");

                            file.deleteOnExit();
                        }
                    }
                }
            }
        } catch (Exception e) {
            String text = ("Error while deleting old versions: " + e.getMessage());
            Debugger(text, "warning");

            e.printStackTrace();
        }
    }

    private static boolean isOldVersion(String fileName, String pluginNameLower) {
        String nameWithoutExt = fileName.substring(0, fileName.length() - 4);

        String[] patterns = {
                pluginNameLower + "-\\d+\\.\\d+\\.\\d+.*",
                pluginNameLower + "-\\d+\\.\\d+.*",
                pluginNameLower + "\\.jar",
                pluginNameLower + "-.*"
        };

        for (String pattern : patterns) {
            if (nameWithoutExt.matches(pattern)) {
                return true;
            }
        }

        return false;
    }

    public static void getUpdateInfo(CommandSender sender) {
        runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setRequestProperty("User-Agent", "FoliaPerms-UpdateChecker");

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String json = response.toString();

                int tagStart = json.indexOf("\"tag_name\":\"") + 12;
                int tagEnd = json.indexOf("\"", tagStart);
                String latestVersion = json.substring(tagStart, tagEnd);

                int bodyStart = json.indexOf("\"body\":\"") + 8;
                int bodyEnd = json.indexOf("\"", bodyStart);
                String releaseNotes = json.substring(bodyStart, bodyEnd);
                if (releaseNotes.length() > 100) {
                    releaseNotes = releaseNotes.substring(0, 97) + "...";
                }

                String currentVersion = getInstance().getDescription().getVersion();
                String pluginName = getInstance().getName();
                String assetInfo = findBestAsset(json, pluginName);

                String finalReleaseNotes = releaseNotes;
                runGlobalTask(getInstance(), () -> {
                    sender.sendMessage("§6=== Plugin Update Information ===");
                    sender.sendMessage("§eCurrent Version: §f" + currentVersion);
                    sender.sendMessage("§eLatest Version: §f" + latestVersion);

                    if (!currentVersion.equals(latestVersion)) {
                        sender.sendMessage("§a✓ Update available!");
                        if (assetInfo != null) {
                            String assetName = assetInfo.split("\\|")[1];
                            sender.sendMessage("§eAvailable download: §f" + assetName);
                            sender.sendMessage("§eUse §a/punish download §eto download automatically");
                        }
                    } else {
                        sender.sendMessage("§a✓ You have the latest version!");
                    }

                    if (!finalReleaseNotes.isEmpty() && !finalReleaseNotes.equals("null")) {
                        sender.sendMessage("§eRelease Notes: §f" + finalReleaseNotes.replace("\\n", " "));
                    }

                    sender.sendMessage("§6================================");
                });

            } catch (Exception e) {
                runGlobalTask(getInstance(), () ->
                        sender.sendMessage("§cFailed to get update info: " + e.getMessage()));
                e.printStackTrace();
            }
        });
    }
}