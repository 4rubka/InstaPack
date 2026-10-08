# InstaPack

![InstaPack](images/banner.png)

Fast server resource packs and resource reloads for Minecraft.

- `src/` - client mod (Fabric and NeoForge, Minecraft 1.21 to 26.3)
- `plugin/` - Paper and Velocity plugin

## Building

Mod, one jar per loader and version, written to `dist/`:

```
python3 tools/build.py                # everything
python3 tools/build.py fabric:26.3    # one target
```

Needs Java 25. Plugin:

```
cd plugin && mvn package
```

## License

MIT
