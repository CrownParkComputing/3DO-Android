# 4DO Native Core Rewrite Plan

## Overview
Rewrite the libopera 3DO emulator core to modern Android-native C++ with enhancements.

## Module Structure

### Phase 1: Foundation (Current)
- [ ] Core types and utilities
- [ ] Logging system (Android logcat integration)
- [ ] Memory management with ARM NEON optimizations
- [ ] Configuration system

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
- [ ] Native Core Rewrite (Starting...)