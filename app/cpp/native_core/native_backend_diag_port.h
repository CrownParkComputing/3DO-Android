#ifndef NATIVE_BACKEND_DIAG_PORT_H_INCLUDED
#define NATIVE_BACKEND_DIAG_PORT_H_INCLUDED

#include "native_backend_extern_c.h"

#include <stdint.h>

EXTERN_C_BEGIN

void     opera_diag_port_init(const int32_t test_code_);
uint32_t opera_diag_port_get(void);
void     opera_diag_port_send(const uint32_t val_);

EXTERN_C_END

#endif