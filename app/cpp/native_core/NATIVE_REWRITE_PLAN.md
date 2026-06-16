# 3DO Opera Native Core Rewrite Plan

## Overview
Rewrite the libopera 3DO emulator core to modern Android-native C++ with enhancements.

## Module Structure

### Phase 1: Foundation (Complete)
- [x] Core types and utilities (`native_types.h`)
- [x] Logging system (`native_log.h` – Android logcat integration)
- [x] Memory management with ARM NEON optimizations (`native_memory.h`)
- [x] BIOS loader (`native_bios.h`)
- [x] NVRAM manager (`native_nvram.h`)
- [x] CD-ROM interface (`native_cdrom.h`)
- [x] Audio ring-buffer & system (`native_audio.h`)
- [x] Input system with remapping (`native_input.h`)
- [x] **FourdoCore coordinator** (`native_core.h` / `native_core.cpp`)
      - Unifies all subsystems into a single native entry point
      - libopera C code is used internally as the hardware-emulation backend
      - Replaces direct opera C function calls scattered across emulator_core.cpp

### Phase 2: Core Hardware (In Progress)
- [x] BIOS loader (`opera_bios.c` removed from build – `native_bios.h` handles loading)
- [x] NVRAM format functions (`opera_nvram.c` removed from build – implemented natively in `native_core.cpp`)
- [x] libopera internal logging wired to Android logcat
- [x] Save-state support added to `FourdoCore` (`state_size`, `save_state`, `load_state`)
- [x] NativeActivity dead code removed (`android_main.cpp` and `android_native_app_glue` stripped from build)
- [x] PRNG (`prng16.c`, `prng32.c` removed – implemented natively in `native_core.cpp`)
- [x] Diagnostic port (`opera_diag_port.c` removed – implemented natively in `native_core.cpp`)
- [x] Clock/timer (`opera_clock.c` removed – implemented natively in `native_core.cpp`)
- [x] Region (`opera_region.c` removed – implemented natively in `native_core.cpp`)
- [x] Fixed-point math (`opera_fixedpoint_math.c` removed – implemented natively in `native_core.cpp`)
- [x] Region setting exposed via JNI + `SettingsActivity` UI
- [x] CPU speed control exposed via JNI (`setCpuSpeed`)
- [x] ARM60 CPU emulator (opera_arm.c)
- [x] Memory controller (opera_mem.c)

### Phase 3: Custom Chips
- [x] CLIO (I/O Controller) - opera_clio.c
- [x] MADAM (Video Processor) - opera_madam.c
- [x] VDLP (Display List Processor) - opera_vdlp.c
- [x] DSP (Audio) - opera_dsp.c
- [x] SPORT (Serial Port) - opera_sport.c

### Phase 4: Peripherals
- [x] CD-ROM interface - opera_cdrom.c (optimized native_cdrom.h with read-ahead cache)
- [x] XBUS (Expansion) - opera_xbus.c
- [x] Controller input (PBUS) - opera_pbus.c

### Phase 5: Advanced Features
- [ ] Save states with compression
- [ ] Cheat system
- [ ] Fast boot options

## Enhancements Over Original
1. **Performance**: ARM NEON SIMD, multi-threading
2. **Audio**: AAudio for low-latency, OpenSL ES fallback
3. **Video**: Hardware-scaling, shader filters
4. **Saves**: Room database for state management
5. **Cheats**: Built-in cheat database
6. **Debug**: Integrated debugger for development

## File Naming Convention
- `native_*.h` - Header files
- `native_*.cpp` - Implementation files
- Use namespaces: `fourdo::core`, `fourdo::hw`, `fourdo::util`

## Progress
- [x] OpenGL ES 3.0 Renderer
- [x] Phase 1 Foundation complete (native_core.h / native_core.cpp)
- [x] Phase 2 partial: BIOS, NVRAM, logging, save states, build cleanup
- [x] Phase 2 continued: PRNG, diag port, clock, region, fixed-point math migrated
- [ ] Phase 2 remaining: ARM60 CPU, Memory controller
- [ ] Phase 3: Custom Chips (CLIO, MADAM, VDLP, DSP, SPORT)
- [ ] Phase 4: Peripherals (XBUS, CD-ROM, PBUS)
- [ ] Phase 5: Advanced Features (save state compression, cheats, fast boot)

## Legacy Backend Modules Still Used
The following legacy backend modules are still used for emulation behavior.
They are no longer compiled directly from `cpp/libopera/*.c`; instead they are
built through `cpp/native_core/native_*.c` wrapper translation units.
Each module will still need a true native rewrite before `libopera` can be
fully deleted:

| File                      | Purpose                 | Replacement status       |
|---------------------------|-------------------------|--------------------------|
| opera_3do.c               | Core init / state       | Converted ✓              |
| opera_arm.c               | ARM60 CPU               | Converted ✓              |
| opera_bitop.c             | Bit operations          | Used by opera_madam.c    |
| opera_clio.c              | CLIO I/O chip           | Converted ✓              |
| opera_dsp.c               | Audio DSP               | Converted ✓              |
| opera_log.c               | Internal logging        | Wired to logcat ✓        |
| opera_madam.c             | MADAM video processor   | Converted ✓              |
| opera_mem.c               | Memory controller       | Converted ✓              |
| opera_pbus.c              | Controller input bus    | Converted ✓              |
| opera_sport.c             | SPORT serial port       | Converted ✓              |
| opera_state.c             | Save-state chunks       | Wrapped by FourdoCore ✓  |
| opera_vdlp.c              | Display list processor  | Converted ✓              |
| opera_xbus.c              | XBUS expansion          | Converted ✓              |
| opera_xbus_cdrom_plugin.c | CD-ROM XBUS plugin      | Converted ✓              |

Current status:
- Direct `cpp/libopera/*.c` compile entries: **0** (all migrated to native_core wrappers)
- `libopera` header dependency status: **completed** (`native_backend_*` replacements are active)
- `libopera` folder cleanup status: **ready/removed** (no source include dependencies remain)

Latest native conversions (implemented in `cpp/native_core` without including legacy `.c`):
- `native_log.c`
- `native_state.c`
- `native_bitop.c`
- `native_xbus_cdrom_plugin.c`
- `native_pbus.c`
- `native_xbus.c`
- `native_cdrom.c`
- `native_sport.c`
- `native_mem.c`
- `native_3do.c`
- `native_arm.c`
- `native_vdlp.c`
- `native_clio.c`
- `native_dsp.c`
- `native_madam.c`

Legacy `libopera` source cleanup completed for converted modules:
- deleted `opera_log.c`, `opera_state.c`, `opera_bitop.c`
- deleted `opera_xbus_cdrom_plugin.c`, `opera_pbus.c`, `opera_xbus.c`
- deleted `opera_cdrom.c`
- deleted `opera_sport.c`
- deleted `opera_mem.c`
- deleted `opera_3do.c`
- deleted `opera_arm.c`
- deleted `opera_vdlp.c`
- deleted `opera_clio.c`
- deleted `opera_dsp.c`
- deleted `opera_madam.c`

Additional unreferenced legacy files removed:
- `opera_3do.h`, `opera_bios.h`, `opera_cdrom.h`, `opera_dsp2_i.h`, `opera_nvram.h`, `opera_xbus.h`, `pbus.txt`
- `opera_log.h`, `opera_state.h`, `opera_pbus.h`, `flags.h`, `hack_flags.h`, `opera_mem.h`, `opera_region.h`, `opera_region_i.h`
- `opera_fixedpoint_math.h`, `opera_swi_hle_0x5XXXX.h`
- `prng16.h`, `prng32.h`
- `discdata.h`, `linkedmemblock.h`
- `opera_clock.h`, `opera_diag_port.h`

Next batch in progress:
- symbol/interface naming cleanup (`opera_*` API naming normalization to native naming)

Migration progress note:
- direct `#include "libopera/..."` usage in C/C++ source (`app/cpp/**/*.{c,cpp,h,hpp}`): **0**.
- all native backend modules now include `native_backend_*` headers instead of `libopera` headers.

## libopera Files Removed from Build (native replacements in native_core.cpp)
| File                      | Replacement              |
|---------------------------|--------------------------|
| opera_bios.c              | native_bios.h            |
| opera_nvram.c             | native_core.cpp NVRAM    |
| opera_clock.c             | native_core.cpp clock    |
| opera_region.c            | native_core.cpp region   |
| opera_diag_port.c         | native_core.cpp diag     |
| opera_fixedpoint_math.c   | native_core.cpp fp math  |
| prng16.c                  | native_core.cpp PRNG     |
| prng32.c                  | native_core.cpp PRNG     |
