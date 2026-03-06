#ifndef NATIVE_BACKEND_BITOP_H_INCLUDED
#define NATIVE_BACKEND_BITOP_H_INCLUDED

#include "native_backend_extern_c.h"

#include <stdint.h>

EXTERN_C_BEGIN

struct BitReaderBig
{
  uint32_t buf;
  uint32_t point;
  int32_t bitpoint;
  int32_t bitset;
};

uint32_t BitReaderBig_Read(struct BitReaderBig *bit, uint8_t bits);
void BitReaderBig_AttachBuffer(struct BitReaderBig *bit, uint32_t buff);
void BitReaderBig_SetBitRate(struct BitReaderBig *bit, uint8_t bits);
void BitReaderBig_Skip(struct BitReaderBig *bit, uint32_t bits);

EXTERN_C_END

#endif
