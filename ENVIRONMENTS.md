# Environments — Authoring Cookbook

How to add, replace, or audit a bundled VR environment (skybox) in FingerDance.

---

## 1. What an environment is

A 6-face cubemap rendered around the user at world origin. The user sees it as the background behind web content, floor grid, and HUD.

Runtime entry points:

- `app/src/main/cpp/Skybox.cpp:45` — `LoadTextureCube()` reads the 6 KTX face files.
- `app/src/main/cpp/Skybox.cpp:144, 173` — called from `Load()` (initial) and `SetVisible()` (switch).
- `app/src/common/shared/com/igalia/wolvic/utils/EnvironmentUtils.java` — builtin vs. external resolution; the `isBuiltinEnvironment()` check gates whether assets are read from APK or downloaded to disk.
- `app/src/main/cpp/BrowserWorld.cpp:1357` + JNI at `2255` — environment switching glue.

Two kinds:

- **Builtin** — shipped in the APK under `app/src/main/assets/cubemap/{envId}/`. Zero network. Instant load.
- **External** — downloaded on first use from the `payload` URL in the remote `props.json` feed. Cached on device.

---

## 2. Asset layout

Every environment `{envId}` needs:

```
app/src/main/assets/cubemap/{envId}/
├── posx.ktx        # +X (East)  — linear, used by Chromium backend
├── negx.ktx        # -X (West)
├── posy.ktx        # +Y (up)
├── negy.ktx        # -Y (down)
├── posz.ktx        # +Z (North)
├── negz.ktx        # -Z (South)
├── posx_srgb.ktx   # sRGB variants, used by Gecko / OCULUSVR / PICOXR / PFDMXR
├── negx_srgb.ktx
├── posy_srgb.ktx
├── negy_srgb.ktx
├── posz_srgb.ktx
├── negz_srgb.ktx
└── SOURCE.md       # optional — provenance (AI prompt, HDRI URL, license)

app/src/main/res/drawable/environment_{envId}.jpg  # 512×288 picker thumbnail
```

12 KTX files per environment. Filenames are fixed — `Skybox.cpp` looks for exactly these names.

Compass convention (OpenGL-default, right-handed, Y-up):

| Face      | World direction |
|-----------|-----------------|
| `posx`    | +X / East       |
| `negx`    | −X / West       |
| `posy`    | +Y / up         |
| `negy`    | −Y / down       |
| `posz`    | +Z / North      |
| `negz`    | −Z / South      |

If on-device orientation looks wrong after dropping faces in, swap `posx`/`negx` or `posz`/`negz` — some authoring tools use a left-handed or Vulkan convention. Verify with a test pattern before committing final pixels.

---

## 3. Authoring pipeline

Three steps: source panorama → slice to 6 faces → compress to KTX.

### 3.1 Source panorama

One equirectangular image at 4096×2048 px (2:1 aspect). Acceptable sources:

- **AI-generated** (Midjourney, DALL·E, Stable Diffusion). Save the prompt into `SOURCE.md` for audit.
- **HDRI** from polyhaven.com (CC0) or similar. CC0 only for bundled builtins — attribution-required licenses introduce compliance overhead.
- **Blender render** — any scene baked to equirectangular.

Paid-app constraints:

- No CC-BY-NC (NonCommercial forbidden).
- No CC-BY-ND (NoDerivatives forbidden — cube slicing is a derivative).
- CC-BY is legal if attribution is wired into the Third-Party Credits screen (see §8).

### 3.2 Slice equirectangular → 6 cube faces

Option A — **py360convert** (Python CLI):

```bash
pip install py360convert Pillow numpy
python -c "
import py360convert, numpy as np
from PIL import Image
pano = np.array(Image.open('panorama.png'))
for face, name in zip(['F','R','B','L','U','D'], ['posz','posx','negz','negx','posy','negy']):
    out = py360convert.e2c(pano, face_w=1024, mode='bilinear', cube_format='dict')[face]
    Image.fromarray(out).save(f'{name}.png')
"
```

Option B — [panorama-to-cubemap web tool](https://jaxry.github.io/panorama-to-cubemap/) — upload, download 6 PNGs. Rename per §2.

Output: 6 × 1024×1024 PNGs.

### 3.3 Compress PNG → KTX

Uses the bundled `toktx` from `app/src/main/cpp/KTX-Software/tools/toktx/`. Build it once:

```bash
cd app/src/main/cpp/KTX-Software
cmake -B build -DKTX_FEATURE_TOOLS=ON
cmake --build build --target toktx -j
```

Then for each of the 6 faces, produce **two** outputs (linear + sRGB):

```bash
# Linear (Chromium backend)
toktx --genmipmap --bcmp posx.ktx posx.png

# sRGB (Gecko / OCULUSVR / PICOXR / PFDMXR)
toktx --genmipmap --bcmp --srgb posx_srgb.ktx posx.png
```

`--bcmp` = BasisLZ/ETC1S compression (small on-disk, GPU-decoded). `--genmipmap` = full mipmap chain (required — skyboxes sample at many distances). Skip `--t2` — `Skybox::LoadTextureCube` expects KTX1, not KTX2.

Drop all 12 outputs into `app/src/main/assets/cubemap/{envId}/`.

---

## 4. Wiring a new builtin into the picker

`app/src/main/res/values/options_values.xml` — add to **all three** arrays in matching order:

```xml
<string-array name="developer_options_environments" translatable="false">
    <item>@string/developer_options_env_void</item>
    <item>FingerDance</item>
    <item>MyNewEnv</item>                 <!-- label -->
</string-array>

<string-array name="developer_options_environments_values" translatable="false">
    <item>void</item>
    <item>fingerdance</item>
    <item>mynewenv</item>                 <!-- envId, must match cubemap dir -->
</string-array>

<array name="developer_options_environments_images" translatable="false">
    <item>@color/black</item>
    <item>@drawable/environment_fingerdance</item>
    <item>@drawable/environment_mynewenv</item>   <!-- picker thumbnail -->
</array>
```

Add the picker thumbnail: `app/src/main/res/drawable/environment_mynewenv.jpg` — 512×288 px, derived from the panorama (wrap 360° so all of the scene reads in preview).

Update test: `app/src/test/java/com/igalia/wolvic/EnvironmentsTest.kt`:

```kotlin
@Test fun `Environment is builtin`() {
    assertTrue(EnvironmentUtils.isBuiltinEnvironment(context, "void"))
    assertTrue(EnvironmentUtils.isBuiltinEnvironment(context, "fingerdance"))
    assertTrue(EnvironmentUtils.isBuiltinEnvironment(context, "mynewenv"))
}
```

Optional — set as default for new installs: `app/src/common/shared/com/igalia/wolvic/browser/SettingsStore.java:161`:

```java
public final static String ENV_DEFAULT = "mynewenv";
```

---

## 5. Combo-aware environments (FingerDance-specific)

4-dir combo mode uses nodes `2 / 4 / 6 / 8` — N / W / E / S. If an environment's scenic composition has a directional reading (e.g. four seasons, four biomes, four moods), aligning each quadrant to a combo direction reinforces the input language.

Mapping: cube face ↔ world direction ↔ combo node:

| Cube face | World direction | 4-dir node |
|-----------|-----------------|------------|
| `posz`    | +Z / North      | `2` (↑)    |
| `posx`    | +X / East       | `6` (→)    |
| `negz`    | −Z / South      | `8` (↓)    |
| `negx`    | −X / West       | `4` (←)    |

The FingerDance builtin maps Spring→N, Summer→E, Autumn→S, Winter→W. Generate as **one** equirectangular panorama with smoothly-blended transitions at the 45°/135°/225°/315° intercardinals — never four separate images stitched, or the seams show.

---

## 6. FingerDance house style

BRAND.md palette: 60% `--fd-blue` (#111259), 30% neutral, 10% `--fd-yellow` (#FDDE0A). Environments should whisper these in shadow/highlight tones rather than dominate.

- **Minimal / painterly** beats photoreal. HUD is flat-geometric; photoreal skyboxes clash.
- **One accent hue per environment.** Don't color-code directions — use composition and light.
- **Eye-level horizon.** The user is standing; put the scenic band at the middle of the panorama, not tilted.
- **No text, no people, no animals.** Visual-only, culturally neutral.

---

## 7. Testing checklist

Before committing a new environment:

1. `./gradlew assembleOculusvrArm64ChromiumGenericDebug` — no duplicate-resource or missing-drawable errors.
2. APK install hygiene: `adb shell am force-stop com.thanford.fingerdance && adb shell pm clear com.thanford.fingerdance && adb uninstall com.thanford.fingerdance && adb install <apk>`.
3. Quest WiFi: `adb connect 192.168.4.55:5555`.
4. Settings → Environment → tap the new entry. Confirm:
   - Loads instantly (builtin) — `adb logcat | rg -i 'downloadEnvironment|HttpURLConnection'` should show **no** network traffic.
   - Physically turn the headset: scene reads correctly at N/E/S/W.
   - Floor (look down) and sky (look up) read as coherent, not stretched or seamed.
5. 72–90 Hz sustained (CLAUDE.md §5.2) — confirm via Quest Developer Hub overlay or `adb shell dumpsys SurfaceFlinger --latency`.
6. Thumbnail in the picker matches the loaded scene.

---

## 8. Legal

| Source license | Attribution required? | Where it goes |
|----------------|----------------------|---------------|
| CC0 / Public Domain | No | Optional — `SOURCE.md` for provenance |
| CC-BY 4.0 | **Yes** — §3.a.1 | Third-Party Credits screen: creator, license notice with link to `creativecommons.org/licenses/by/4.0/`, source URI hyperlink, modification indicator, disclaimer |
| CC-BY-NC / CC-BY-ND | N/A — **do not use** | Paid-app incompatible (NC) or derivative-forbidden (ND) |
| AI-generated | No statutory obligation | `SOURCE.md` with full prompt + tool + date — audit trail |

The Third-Party Credits screen is built at:

- `app/src/common/shared/com/igalia/wolvic/ui/widgets/settings/ThirdPartyCreditsView.java`
- `app/src/main/res/layout/options_third_party_credits.xml`

Reached from Settings → Environment → "Third-Party Credits" link (visible only when the remote env list is non-empty).

Remote env feed is filtered to CC0 + CC-BY only at parse time — see `RemoteProperties.kt:isPermittedForCommercialUse`. NC/ND entries from the upstream `igalia.github.io/wolvic/props.json` are silently dropped.

---

## 9. `SOURCE.md` template

Drop this alongside the KTX files for any AI-generated or notable environment:

```markdown
# {envId} — source provenance

Generated: {YYYY-MM-DD}
Tool: {Midjourney v7 / DALL·E 3 / Stable Diffusion XL / …}
Prompt:
> {full verbatim prompt}

Post-processing: {upscale tool, slicer, toktx flags}
License: {CC0 / CC-BY / AI-generated, no attribution required}
Source URL: {if applicable}
```

Audit trail requirement — AI outputs can face retroactive IP challenges; the prompt is the defensive record.
