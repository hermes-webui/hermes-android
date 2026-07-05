# Play Store Assets

- `icon-512.png`: Google Play listing icon upload (512x512 PNG, opaque background)
- `icon-512.svg`: Editable vector source for the listing icon

Regenerate both files from repo root:

```powershell
py -3 -m pip install --user -r .\tools\requirements-play-icon.txt
py -3 .\tools\generate_play_store_icon.py
```

The generator writes the current Hermes WebUI brand mark into both the editable
SVG source and the exported Play Console PNG.


