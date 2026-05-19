# Listing images (F-Droid)

F-Droid pulls listing imagery from this directory.

Expected files (drop in here when you have them, then commit):

- `icon.png` — 512×512 (already mirrored as `app/src/main/res/drawable-nodpi/sequred_icon.png`)
- `featureGraphic.png` — 1024×500 banner shown atop the F-Droid listing
- `phoneScreenshots/1.png` … `7.png` — up to 8 phone-sized screenshots
- `sevenInchScreenshots/`, `tenInchScreenshots/` — optional tablet screenshots

Screenshot naming: F-Droid sorts them lexicographically, so `01-vault.png`
beats `1-vault.png` if you add more than 9.

You don't need all of these to submit — the listing renders with just the
icon and a couple of screenshots. The rest can be added in subsequent
fastlane updates without re-submitting the metadata YAML.
