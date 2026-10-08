![InstaPack](https://raw.githubusercontent.com/4rubka/InstaPack/main/images/banner.png)

Server resource packs and resource reloads that take seconds instead of minutes.

![Results](https://raw.githubusercontent.com/4rubka/InstaPack/main/images/results.png)

[Watch the comparison video](https://files.catbox.moe/wkzkqq.mp4)

## What it does

- **Fast resource reload.** Vanilla scans every file of every zip pack each time a mod lists resources. InstaPack indexes the zip once. On a 90 mod client this took the reload from 80 s to 4 s.
- **No reload when nothing changed.** Rejoining a server, or switching servers behind a proxy with the same pack, skips the reload entirely.
- **Partial reload.** If a pack only changes languages, sounds, fonts or splash texts, only those parts are reloaded, without a loading screen.
- **Shared pack cache.** Packs are cached by hash, so the same pack from another server or with a new UUID is never downloaded twice.
- **Delta downloads** (with the server plugin). When a server updates its pack, you download only the files that changed.

## Server plugin (optional)

Paper and Velocity. Hosts the pack with a built-in web server, sends it to players on join, and pushes updates as soon as files change. Players with the mod get only changed files. Players without it get a normal zip, nothing breaks.

Put the pack, unzipped, into `plugins/InstaPack/pack/`, or point `source` at a zip made by another plugin. A zip is sent to vanilla clients byte for byte.

## Config

`config/instapack.properties` on the client. Every feature can be turned off.

## Compatibility

Client only on the mod side. Fabric and NeoForge, Minecraft 1.21 to 26.3. Works on any server, the plugin is not required.

## Links

- [Source code](https://github.com/4rubka/InstaPack)
- [Comparison video](https://files.catbox.moe/wkzkqq.mp4)
