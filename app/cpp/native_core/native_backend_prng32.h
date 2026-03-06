#ifndef NATIVE_BACKEND_PRNG32_H_INCLUDED
#define NATIVE_BACKEND_PRNG32_H_INCLUDED

#include <stdint.h>

void     prng32_seed(uint32_t const seed);
uint32_t prng32(void);

#endif