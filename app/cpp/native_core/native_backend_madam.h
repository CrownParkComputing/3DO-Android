#ifndef NATIVE_BACKEND_MADAM_H_INCLUDED
#define NATIVE_BACKEND_MADAM_H_INCLUDED

#include "native_backend_extern_c.h"

#include <stdint.h>

#define FSM_IDLE 1
#define FSM_INPROCESS 2
#define FSM_SUSPENDED 3

EXTERN_C_BEGIN

void      opera_madam_init(void);
void      opera_madam_reset(void);

uint32_t  opera_madam_fsm_get(void);
void      opera_madam_fsm_set(uint32_t val_);

void      opera_madam_cel_handle(void);

uint32_t *opera_madam_registers(void);

void      opera_madam_poke(uint32_t addr_, uint32_t val_);
uint32_t  opera_madam_peek(uint32_t addr_);

void      opera_madam_kprint_enable(void);
void      opera_madam_kprint_disable(void);
void      opera_madam_me_mode_software(void);
void      opera_madam_me_mode_hardware(void);

uint32_t  opera_madam_state_size(void);
uint32_t  opera_madam_state_save(void *buf_);
uint32_t  opera_madam_state_load(void const *buf_);

EXTERN_C_END

#endif
