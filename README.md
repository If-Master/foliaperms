# FoliaPerms

**FoliaPerms** is a lightweight and flexible permissions plugin built for servers running [Folia](https://github.com/PaperMC/Folia) and Paper. It provides both GUI and command-line tools for managing permissions for users and groups, designed with performance and usability in mind.

---

## Features

- GUI-based permission editor (`/fp editor`)
- Group creation, deletion, permission editing
- User-specific permission and group assignment
- Group priorities and detailed info commands
- Built-in debugging and performance tools
- Auto-update support
- Admin-only save and reload commands

---

## 🧾 Commands Overview

Below is the full list of in-game commands available:

```text
/fp editor                              - Open the GUI permission editor
/fp group <group> create               - Create a new group
/fp group <group> delete               - Delete a group
/fp group <group> addperm <perm>       - Add permission to group
/fp group <group> removeperm <perm>    - Remove permission from group
/fp group <group> setpriority <num>    - Set group priority
/fp group <group> info                 - Show group information
/fp group <group> listperms            - List group permissions

/fp user <player> addgroup <group>     - Add player to group
/fp user <player> removegroup <group>  - Remove player from group
/fp user <player> addperm <perm>       - Add permission to player
/fp user <player> removeperm <perm>    - Remove permission from player
/fp user <player> info                 - Show player information
/fp user <player> listperms            - List player permissions

/fp info [detailed]                    - Show general plugin information
/fp debug <player>                     - Debug player permissions
/fp stats                              - Show performance statistics
/fp version                            - Show version information
/fp updateinfo                         - Get update info (admin only)
/fp download                           - Download the latest update (admin only)
/fp reload                             - Reload configuration (admin only)
/fp save                               - Save data to file (admin only)
````

---

## Requirements

* Minecraft server (Folia build or Paper build)
* Java 17 or higher

---

## Installation

1. Download the latest release from the [Releases](../../releases) tab.
2. Place the `.jar` file into your server's `plugins/` directory.
3. Start or reload your server.
4. Use `/fp editor` or the command-line tools to configure permissions.

---

## License

```
Custom Plugin License

Copyright (c) 2025 If_Master / Furt_22 / Kanuunankuula

This plugin and its source code are the intellectual property of If_Master / Furt_22 / Kanuunankuula.

You are permitted to:
- Use and modify this plugin for personal or public use.

You are not permitted to:
- Claim ownership or credit for the original or modified code.
- Sell or monetize this plugin or any modified versions of it.

By using this plugin, you agree to the above terms.
```

---

## Credits

Created by If_Master / Furt_22 / Kanuunankuula

---

## Support / Contributing

Have a suggestion or found a bug? Feel free to open an [Issue](../../issues) or submit a [Pull Request](../../pulls).
