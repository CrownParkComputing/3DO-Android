#ifndef NATIVE_BACKEND_SPORT_H_INCLUDED
#define NATIVE_BACKEND_SPORT_H_INCLUDED

#include "native_backend_extern_c.h"

#include <stdint.h>

EXTERN_C_BEGIN

void     opera_sport_init(void);

void     opera_sport_set_source(const uint32_t idx_);
void     opera_sport_write_access(const uint32_t idx_, const uint32_t mask_);

uint32_t opera_sport_state_size(void);
uint32_t opera_sport_state_save(void *buf_);
uint32_t opera_sport_state_load(void const *buf_);

EXTERN_C_END

#endif
