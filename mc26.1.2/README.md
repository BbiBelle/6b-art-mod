# Maparts Link — Minecraft 26.1.2 build

See the [repo root README](../README.md) for commands, upload behavior, and
the release process. This folder is the Fabric mod project targeting
Minecraft **26.1.2** specifically.

## Requirements

- Minecraft **26.1.2**
- Fabric Loader ≥ 0.19.3 + Fabric API 0.154.2+26.1.2
- Java 25

## Building

```bash
./build.sh        # or: ./gradlew build
```

The jar lands in `build/libs/maparts-link-<version>.jar`. Gradle needs a JDK
with a compiler — on a JRE-only machine the foojay toolchain resolver
downloads one, or install it yourself.

Toolchain: Loader 0.19.3, Fabric API `0.154.2+26.1.2`, Loom 1.17.16.
