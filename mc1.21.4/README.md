# Maparts Link — Minecraft 1.21.4 build

See the [repo root README](../README.md) for commands, upload behavior, and
the release process. This folder is the Fabric mod project targeting
Minecraft **1.21.4 only**.

It exists because 1.21.5 reworked `ClickEvent` from a flat
`ClickEvent(Action, String)` constructor into sealed per-action records
(`ClickEvent.OpenUrl`, taking a `URI`). No single source line compiles
against both, so 1.21.5 – 1.21.11 has its own build in
[`../mc1.21.5-1.21.11/`](../mc1.21.5-1.21.11). The two are otherwise the
same mod — keep the shared sources in sync when changing either.

## Requirements

- Minecraft **1.21.4**
- Fabric Loader ≥ 0.16.0 + Fabric API 0.119.4+1.21.4
- Java 21

## Building

```bash
./build.sh        # or: ./gradlew build
```

The jar lands in `build/libs/maparts-link-<version>.jar`. Gradle needs a JDK
with a compiler — on a JRE-only machine the foojay toolchain resolver
downloads one, or install it yourself (`sudo apt install openjdk-21-jdk`).

Toolchain: Yarn `1.21.4+build.8`, Loader 0.16.0, Fabric API
`0.119.4+1.21.4`, Loom 1.17.16.
