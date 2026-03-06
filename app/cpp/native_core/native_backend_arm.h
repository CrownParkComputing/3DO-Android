#ifndef NATIVE_BACKEND_ARM_H_INCLUDED
#define NATIVE_BACKEND_ARM_H_INCLUDED

#include "native_backend_extern_c.h"

#include <stdint.h>

EXTERN_C_BEGIN

int32_t  opera_arm_execute(void);
void     opera_arm_init(void);
void     opera_arm_reset(void);
void     opera_arm_destroy(void);

void     opera_io_write(const uint32_t addr_, const uint32_t val_);

uint32_t opera_arm_state_size(void);
uint32_t opera_arm_state_save(void *buf_);
uint32_t opera_arm_state_load(const void *buf_);

void     opera_arm_swi_hle_set(const int hle);
int      opera_arm_swi_hle_get(void);

EXTERN_C_END

#endif