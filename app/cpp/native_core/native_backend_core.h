#ifndef NATIVE_BACKEND_CORE_H_INCLUDED
#define NATIVE_BACKEND_CORE_H_INCLUDED

#include "native_backend_extern_c.h"

#include <stdint.h>

#define EXT_DSP_TRIGGER   2

typedef void* (*opera_ext_interface_t)(int, void*);

EXTERN_C_BEGIN

extern uint32_t FIXMODE;
extern int      CNBFIX;

EXTERN_C_END

#endif
