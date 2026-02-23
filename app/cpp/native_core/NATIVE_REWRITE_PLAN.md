# 4DO Native Core Rewrite Plan

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
- [ ] ARM60 CPU emulator (opera_arm.c)
- [ ] Memory controller (opera_mem.c)

### Phase 3: Custom Chips
- [ ] CLIO (I/O Controller) - opera_clio.c
- [ ] MADAM (Video Processor) - opera_madam.c
- [ ] VDLP (Display List Processor) - opera_vdlp.c
- [ ] DSP (Audio) - opera_dsp.c
- [ ] SPORT (Serial Port) - opera_sport.c

### Phase 4: Peripherals
- [ ] XBUS (Expansion) - opera_xbus.c
- [ ] CD-ROM interface - opera_cdrom.c
- [ ] Controller input (PBUS) - opera_pbus.c

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

## libopera Files Remaining in Build
The following libopera C files are still compiled as the hardware-emulation
backend. Each will be removed once a native C++ replacement is complete:

| File                      | Purpose                 | Replacement status       |
|---------------------------|-------------------------|--------------------------|
| opera_3do.c               | Core init / state       | Pending (Phase 2/3)      |
| opera_arm.c               | ARM60 CPU               | Pending (Phase 2)        |
| opera_bitop.c             | Bit operations          | Used by opera_madam.c    |
| opera_cdrom.c             | CD-ROM callbacks        | Pending (Phase 4)        |
| opera_clio.c              | CLIO I/O chip           | Pending (Phase 3)        |
| opera_dsp.c               | Audio DSP               | Pending (Phase 3)        |
| opera_log.c               | Internal logging        | Wired to logcat ✓        |
| opera_madam.c             | MADAM video processor   | Pending (Phase 3)        |
| opera_mem.c               | Memory controller       | Pending (Phase 2)        |
| opera_pbus.c              | Controller input bus    | Pending (Phase 4)        |
| opera_sport.c             | SPORT serial port       | Pending (Phase 3)        |
| opera_state.c             | Save-state chunks       | Wrapped by FourdoCore ✓  |
| opera_vdlp.c              | Display list processor  | Pending (Phase 3)        |
| opera_xbus.c              | XBUS expansion          | Pending (Phase 4)        |
| opera_xbus_cdrom_plugin.c | CD-ROM XBUS plugin      | Pending (Phase 4)        |

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
