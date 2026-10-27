# Folkways × Superb Warfare

Optional NeoForge 1.21.1 extension, mod ID `folkways_warfare`. Install with
Core, Vanilla, Superb Warfare 0.8.9.2 and Kotlin for Forge 5.12.0.

Six handheld guns use finite profile ammunition and SBW's firing API. Posted
sentries lease supported fixed weapons inside their sentry zone. Mortars and
howitzers use shared spotting; trajectory and impact safety remain mandatory.
AP, TOW, vehicles and unpredictable effects are excluded. RTS is optional.

See [setup and contracts](../../docs/en/warfare.md) and
[简体中文](../../docs/zh-CN/warfare.md).

```sh
npm run setup
./gradlew build
./gradlew -PwarfareTests=true -PgameTestMod=warfare runGameTestServer
```

`blockwright.toml#warfare` installs the pinned native dependencies. The default
GameTest server omits this integration; `-PwarfareTests=true` enables it.
