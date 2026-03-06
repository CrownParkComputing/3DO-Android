#ifndef NATIVE_BACKEND_FLAGS_H_INCLUDED
#define NATIVE_BACKEND_FLAGS_H_INCLUDED

#define set_flag(V,F) ((V)|=(F))
#define clr_flag(V,F) ((V)&=~(F))
#define set_or_clr_flag(V,F,S) ((!!(S)) ? set_flag(V,F) : clr_flag(V,F))

#define flag_is_set(V,F) (!!((V)&(F)))
#define flag_is_clr(V,F) (!((V)&(F)))

#endif