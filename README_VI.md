# NG Sound Editor Android — prototype v3.8

Android Studio/Kotlin source project.

## New in v3.8
- Adds an entry filter field that searches by global index, source voice name, codec and record/sub-index.
- Entry rows carry search metadata; filtering only hides rows and does not alter bank data.

## Current capabilities
- SND/XWSFILE/KWB2 parsing and embedded-bank scanning/cycling.
- Export standalone bank, individual WAVs, batch WAV ZIP and sound map CSV.
- Preview supported mono MS-ADPCM and mono/stereo PCM16.
- Same-size replacement for supported codecs when target audio ranges are exclusive and metadata/size match.
- Variable-length per-entry and batch ZIP repack for mono MS-ADPCM in SND/XWS when structural checks and capacity permit.
- Parser/repack unit tests and GitHub Actions debug APK workflow.

## Limitations
- No APK built or device-tested in this environment.
- Embedded DAT reconstruction, DSP/ATRAC9 and automatic resampling are not implemented.
- Structural safety checks do not guarantee in-game compatibility.
