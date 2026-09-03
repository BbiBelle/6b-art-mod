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
| [`mc1.21.4/`](mc1.21.4) | 1.21.4 only | ≥ 0.16.0 | 21 |
| [`mc1.21.5-1.21.11/`](mc1.21.5-1.21.11) | 1.21.5 – 1.21.11 | ≥ 0.16.0 | 21 |
| [`mc26.1+/`](mc26.1+) | 26.1.2 and up | ≥ 0.16.0 | 25 |

1.21.4 gets its own jar because 1.21.5 reworked `ClickEvent` into sealed
per-action records — the chat-link code can't span both, so it's two builds.

Drop the matching jar into your `mods/` folder alongside Fabric API.

The ranges are the declared Fabric dependency constraint, not a
per-version test matrix — each jar is built once against its folder's own
mappings, so versions past the pin aren't individually verified.

## Commands

| Command | What it does |
| --- | --- |
| `/maplink <code>` | Confirms a sign-in code from the website's login page. First use creates your account; it also stores an upload token in `config/maparts-link.token` so you stay linked. |
| `/mapupload [title]` | Uploads the mapart wall or floor under your crosshair as a draft (flood-fills the full rectangle). Without a title, one is guessed from the maps' custom item names ("Sunset (3/6)" → "Sunset"). |
| `/mapselect` | Two-corner selection: run it looking at one corner frame, then at the opposite corner (the same frame twice = a 1x1), then `/mapupload`. |
| `/mapuploadall` | Splits a wall holding several maparts into separate arts automatically — matching map names group first, unnamed neighbors stay together only when the image continues across the frame seam — and uploads each as its own draft (≤12 per run; irregular groups are skipped with a note). |

Wall- and floor-mounted item frames are both supported (ceiling frames
aren't — an "up" grid has no clear top edge to read the image from). For a
floor grid, column runs east and row runs south, as if viewed from directly
above with north at the top.

The mod checks for a newer release on the title screen. If one's available,
it asks before installing anything — accepting downloads and swaps in the
new jar (effective after restarting Minecraft); declining just dismisses the
prompt for that launch.

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

This repo builds the same mod for three Minecraft version ranges from separate
Gradle projects — each one is a self-contained Fabric mod project:

```
mc1.21.4/           # targets Minecraft 1.21.4 only
mc1.21.5-1.21.11/   # targets Minecraft 1.21.5 - 1.21.11
mc26.1+/            # targets Minecraft 26.1.2 and up
```

`mc1.21.4/` and `mc1.21.5-1.21.11/` are the same mod against different
`ClickEvent` APIs (1.21.5 made it sealed per-action records); keep their
shared sources in sync.

## Building

```bash
cd mc1.21.5-1.21.11   # or mc1.21.4, or mc26.1+
./build.sh            # or: ./gradlew build
```

The jar lands in `<folder>/build/libs/maparts-link-<version>.jar`. Gradle
needs a JDK with a compiler — on a JRE-only machine the foojay toolchain
resolver downloads one, or install it yourself.

## Releasing

Each Minecraft version range is released **independently** — its own tag,
its own GitHub Release, its own single jar. Tags are named
`<folder>-v<version>`:

- `mc1.21.4-v1.6.1`, `mc1.21.4-v1.6.2`, … for the `mc1.21.4/` build
- `mc1.21.5-1.21.11-v1.6.1`, `mc1.21.5-1.21.11-v1.6.2`, … for the
  `mc1.21.5-1.21.11/` build
- `mc26.1+-v1.6.1`, `mc26.1+-v1.7.0`, … for the `mc26.1+/` build

The variants don't need matching version numbers — bump only the one
you're actually shipping.

1. Bump `version` in that folder's `build.gradle` and its
   `src/**/resources/fabric.mod.json`.
2. Commit the version bump.
3. Tag it (prefixed with the folder name) and push the tag:
   ```bash
   git tag mc1.21.5-1.21.11-v1.6.2
   git push origin mc1.21.5-1.21.11-v1.6.2
   ```
4. GitHub Actions (`.github/workflows/release.yml`) reads the folder name off
   the tag prefix, builds only that variant, and publishes a GitHub Release
   for that tag with just its one jar attached — release notes are generated
   from the commits touching that folder since its own last release, so
   changes to the other variant never show up in the wrong changelog.
