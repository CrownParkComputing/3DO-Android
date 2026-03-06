#ifndef NATIVE_BACKEND_LINKEDMEMBLOCK_H_INCLUDED
#define NATIVE_BACKEND_LINKEDMEMBLOCK_H_INCLUDED

#include <stdint.h>

#define FINGERPRINT_FILEBLOCK   0xBE4F32A6
#define FINGERPRINT_FREEBLOCK   0x7AA565BD
#define FINGERPRINT_ANCHORBLOCK 0x855A02B6

typedef struct LinkedMemBlock LinkedMemBlock;
struct LinkedMemBlock
{
  uint32_t fingerprint;
  uint32_t flinkoffset;
  uint32_t blinkoffset;
  uint32_t blockcount;
  uint32_t headerblockcount;
};

#endif