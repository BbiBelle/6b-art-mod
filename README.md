# Maparts Link

Client-side Fabric mod for the [6bMaps](https://maparts-website.vercel.app)
mapart community: sign in to the website from in-game and upload framed
maparts straight off the wall. The backend URL is compiled in — players
install the jar and go; there is nothing to configure.

## Downloads

Grab the jar for your Minecraft version from the
[Releases page](../../releases). Each release attaches one jar per
supported Minecraft version:

| Folder | Minecraft version | Fabric Loader | Java |
| --- | --- | --- | --- |
| [`mc1.21.5/`](mc1.21.5) | 1.21.5 | ≥ 0.16.0 | 21 |
| [`mc26.1.2/`](mc26.1.2) | 26.1.2 | ≥ 0.19.3 | 25 |

Drop the matching jar into your `mods/` folder alongside Fabric API.

## Commands

| Command | What it does |
| --- | --- |
| `/maplink <code>` | Confirms a sign-in code from the website's login page. First use creates your account; it also stores an upload token in `config/maparts-link.token` so you stay linked. |
| `/mapupload [title]` | Uploads the mapart wall under your crosshair as a draft (flood-fills the full rectangle). Without a title, one is guessed from the maps' custom item names ("Sunset (3/6)" → "Sunset"). |
| `/mapselect` | Two-corner selection: run it looking at one corner frame, then at the opposite corner (the same frame twice = a 1x1), then `/mapupload`. |
| `/mapuploadall` | Splits a wall holding several maparts into separate arts automatically — matching map names group first, unnamed neighbors stay together only when the image continues across the frame seam — and uploads each as its own draft (≤12 per run; irregular groups are skipped with a note). |

Trade proposals ping you in chat: a summary shortly after joining and a
notification when a new one arrives (60s poll), with clickable links to the
site.

## Uploads, exactly

The capture reads the real vanilla map data (not screenshots): per-frame
128px colors (ARGB, rotation-aware), the grid size in item frames, and each
frame's map ID + item name. The image's sha256 goes with the draft request,
so exact duplicates resolve before any bytes move: if that exact image
already exists on the site — anyone's, published or still a draft — you're
simply added as an owner of it instead of creating a new row. Nobody's data
is ever deleted or replaced by this.

A map item's held stack (and which map ID it points at) syncs the instant its
item frame is tracked, but the map's actual pixel content streams in on a
separate channel and can lag behind on a wall with many maps. Composing too
early would silently treat not-yet-loaded frames as missing, either
truncating the wall or splitting one mapart into several — so both
`/mapupload` and `/mapuploadall` wait (up to 20s) for every involved frame's
data to arrive before drawing anything, rather than sampling once.

If an upload step (draft, image PUT, or finalize) fails, it's retried once
automatically after a 5-second delay before being reported as failed — in
both single and bulk (`/mapuploadall`) uploads.

## Repository layout

This repo builds the same mod for two Minecraft versions from separate
Gradle projects — each one is a self-contained Fabric mod project:

```
mc1.21.5/   # targets Minecraft 1.21.5
mc26.1.2/   # targets Minecraft 26.1.2
```

## Building

```bash
cd mc1.21.5   # or mc26.1.2
./build.sh    # or: ./gradlew build
```

The jar lands in `<folder>/build/libs/maparts-link-<version>.jar`. Gradle
needs a JDK with a compiler — on a JRE-only machine the foojay toolchain
resolver downloads one, or install it yourself.

## Releasing

1. Bump `version` in the relevant `build.gradle`(s) and `version` in the
   matching `fabric.mod.json`(s). Keep both variants on the same mod version
   unless you're intentionally shipping a fix to only one.
2. Commit the version bump.
3. Tag it and push the tag:
   ```bash
   git tag v1.6.2
   git push origin v1.6.2
   ```
4. GitHub Actions (`.github/workflows/release.yml`) builds both variants and
   publishes a GitHub Release for the tag with a jar per Minecraft version
   attached automatically.
