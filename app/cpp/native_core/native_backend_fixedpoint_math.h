#ifndef NATIVE_BACKEND_FIXEDPOINT_MATH_H_INCLUDED
#define NATIVE_BACKEND_FIXEDPOINT_MATH_H_INCLUDED

#include <stdint.h>

typedef int32_t frac16;
typedef frac16  vec3f16[3];
typedef frac16  vec4f16[4];
typedef frac16  mat33f16[3][3];
typedef frac16  mat44f16[4][4];
typedef frac16  mat34f16[4][3];

typedef struct mmv3m33d mmv3m33d;
struct mmv3m33d
{
  vec3f16  *dest;
  vec3f16  *src;
  mat33f16 *mat;
  frac16    n;
  uint32_t  count;
};

typedef struct ObjOffset1 ObjOffset1;
struct ObjOffset1
{
  int32_t oo1_DestArrayPtrOffset;
  int32_t oo1_SrcArrayPtrOffset;
  int32_t oo1_MatOffset;
  int32_t oo1_CountOffset;
};

typedef struct ObjOffset2 ObjOffset2;
struct ObjOffset2
{
  int32_t oo2_DestMatOffset;
  int32_t oo2_SrcMatOffset;
};

void MulVec3Mat33_F16(vec3f16  dest, vec3f16  vec, mat33f16 mat);
void MulMat33Mat33_F16(mat33f16 dest, mat33f16 src1, mat33f16 src2);
void MulManyVec3Mat33_F16(vec3f16 *dest, vec3f16 *src, mat33f16 mat, int32_t count);
void MulManyF16(frac16 *dest, frac16 *src1, frac16 *src2, int32_t count);
void MulScalerF16(frac16 *dest, frac16 *src, frac16 scaler, int32_t count);
void MulVec4Mat44_F16(vec4f16 dest, vec4f16 vec, mat44f16 mat);
void MulMat44Mat44_F16(mat44f16 dest, mat44f16 src1, mat44f16 src2);
void MulManyVec4Mat44_F16(vec4f16 *dest, vec4f16 *src, mat44f16 mat, int32_t count);
void MulObjectVec4Mat44_F16(void *objectlist[], ObjOffset1 *offsetstruct, int32_t count);
void MulObjectMat44_F16(void *objectlist[], ObjOffset2 *offsetstruct, mat44f16 mat, int32_t count);
frac16 Dot3_F16(vec3f16 v1, vec3f16 v2);
frac16 Dot4_F16(vec4f16 v1, vec4f16 v2);
void Cross3_F16(vec3f16 dest, vec3f16 v1, vec3f16 v2);
frac16 AbsVec3_F16(vec3f16 vec);
frac16 AbsVec4_F16(vec4f16 vec);
void MulVec3Mat33DivZ_F16(vec3f16 dest, vec3f16 vec, mat33f16 mat, frac16 n);
void MulManyVec3Mat33DivZ_F16(vec3f16 *dest, vec3f16 *src, mat33f16 *mat, frac16 n, uint32_t count_);

#endif