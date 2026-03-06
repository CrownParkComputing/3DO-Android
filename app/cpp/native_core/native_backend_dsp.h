#ifndef NATIVE_BACKEND_DSP_H_INCLUDED
#define NATIVE_BACKEND_DSP_H_INCLUDED

#include "native_backend_extern_c.h"

#include <stdint.h>

EXTERN_C_BEGIN

uint32_t opera_dsp_loop(void);

uint16_t opera_dsp_imem_read(uint16_t addr_);
void     opera_dsp_imem_write(uint16_t addr_, uint16_t val_);

void     opera_dsp_mem_write(uint16_t addr_, uint16_t val_);

void     opera_dsp_set_running(int val_);

void     opera_dsp_arm_semaphore_write(uint32_t val_);
uint32_t opera_dsp_arm_semaphore_read(void);

void     opera_dsp_init(void);
void     opera_dsp_reset(void);

uint32_t opera_dsp_state_size(void);
uint32_t opera_dsp_state_save(void *buf_);
uint32_t opera_dsp_state_load(void const *buf_);

EXTERN_C_END

#endif
