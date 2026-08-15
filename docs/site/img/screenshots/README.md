# Screenshot slot convention for `index.html`

The page references these images by name. If a file is missing, the page
gracefully shows a small placeholder tag instead of a broken-image icon —
you can ship the page at any time and fill screenshots in later.

## Drop your screenshots here with these exact filenames

| Slot filename              | Where it shows up          | Suggested content                                                 |
|----------------------------|----------------------------|-------------------------------------------------------------------|
| `01-hero.png`              | Hero panel (right side)    | Your most cinematic shot — phone-on-monitor with remote desktop |
| `02-pc-mode.png`           | Gallery, position 1        | Phone in PC mode / external display setup                         |
| `03-connection-list.png`   | Gallery, position 2        | Connection list / main screen                                     |
| `04-rdp-session.png`       | Gallery, position 3        | Active RDP session (any remote desktop view)                      |
| `05-moonlight.png`         | Gallery, position 4        | Moonlight / NVStream game streaming                              |
| `06-ssh.png`               | Gallery, position 5        | SSH terminal session                                              |
| `07-gamepad.png`           | Gallery, position 6        | On-screen gamepad mode in action                                 |
| `../app-icon.png`          | Nav logo + og:image        | Already filled — `bVNC/src/main/res/drawable/app_icon.png`        |

## Format tips

- **Aspect ratio** — Gallery is `9 / 19.5` (phone portrait). Shots that don't match
  will be center-cropped. If you have landscape shots, name them
  `08-landscape-<anything>.png` and I can wire them into a separate landscape row.
- **Size** — anything ≥ 1080px on the long edge is fine; the page serves them at ~400 CSS px.
- **No naming collisions** — these are the only filenames `index.html` references.

## How to copy from the SMB share

```bash
# from your workstation, once the share is mounted
cp /mnt/onedrive/.../googleplay/01-hero.png    /path/to/repo/img/screenshots/01-hero.png
cp /mnt/onedrive/.../googleplay/02-pc-mode.png  /path/to/repo/img/screenshots/02-pc-mode.png
# ... etc
```

After dropping the files in, `git add img/screenshots/ && git commit` and the page
goes live.
