# glyphew — source provenance

Generated: 2026-04-18 (v3 — seam-free + feather-blend)
Tool: AI image generator (details withheld pre-launch — business confidential). Wrap-aware output: raw wrap ΔRGB 8.70 vs interior baseline 6.73 (ratio 1.29x). Residual seam visible on-device motivated a post-process 64-col feather-blend (col 0 ≡ col W-1 enforced at seam, original detail recovered over 64 cols on each side).
Source resolution: 1440×720 equirectangular → Real-ESRGAN x4 (anime model, 5760×2880) → Lanczos 4096×2048 → np.fliplr → feather-blend seam (M=64).
Post-processing: py360convert e2c (face_w=1024, cube_format=dict) → toktx --genmipmap --bcmp (linear and --assign_oetf srgb variants).
License: AI-generated. No third-party attribution required under current platform TOS.

Prompt and generator identity to be disclosed post-launch. See private notes.
