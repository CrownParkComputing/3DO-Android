# 4DO Native Core Rewrite Plan

## Overview
Rewrite the libopera 3DO emulator core to modern Android-native C++ with enhancements.

## Module Structure

### Phase 1: Foundation (Current)
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

### Phase 2: Core Hardware
- [ ] ARM60 CPU emulator (opera_arm.c)
- [ ] Memory controller (opera_mem.c)
- [ ] BIOS loader (opera_bios.c)
- [ ] NVRAM manager (opera_nvram.c)

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
- [ ] Clock/Timer - opera_clock.c

### Phase 5: Advanced Features
- [ ] Save states with compression
- [ ] Cheat system
- [ ] Region detection
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
- [ ] Phase 2: Core Hardware rewrite (ARM60, Memory, BIOS, NVRAM)
- [ ] Phase 3: Custom Chips (CLIO, MADAM, VDLP, DSP, SPORT)
- [ ] Phase 4: Peripherals (XBUS, CD-ROM, PBUS, Clock)
- [ ] Phase 5: Advanced Features (save states, cheats, region, fast boot)