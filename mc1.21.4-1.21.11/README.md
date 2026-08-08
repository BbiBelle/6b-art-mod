# Maparts Link — Minecraft 1.21.4 – 1.21.11 build

See the [repo root README](../README.md) for commands, upload behavior, and
the release process. This folder is the Fabric mod project targeting
Minecraft **1.21.4 through 1.21.11**, built and verified against **1.21.5**.

## Requirements

- Minecraft **1.21.5**
- Fabric Loader ≥ 0.16.0 + Fabric API 0.119.2+1.21.5
- Java 21

## Building

```bash
./build.sh        # or: ./gradlew build
```

The jar lands in `build/libs/maparts-link-<version>.jar`. Gradle needs a JDK
with a compiler — on a JRE-only machine the foojay toolchain resolver
downloads one, or install it yourself (`sudo apt install openjdk-21-jdk`).

Toolchain: Yarn `1.21.5+build.1`, Loader 0.16.0, Fabric API
`0.119.2+1.21.5`, Loom 1.17.16.
