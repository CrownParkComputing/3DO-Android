#ifndef NATIVE_BACKEND_XBUS_H_INCLUDED
#define NATIVE_BACKEND_XBUS_H_INCLUDED

#include "native_backend_extern_c.h"

#define XBP_INIT        0
#define XBP_RESET       1
#define XBP_SET_COMMAND 2
#define XBP_FIQ         3
#define XBP_SET_DATA    4
#define XBP_GET_DATA    5
#define XBP_GET_STATUS  6
#define XBP_SET_POLL    7
#define XBP_GET_POLL    8
#define XBP_SELECT      9
#define XBP_RESERV      10
#define XBP_DESTROY     11
#define XBP_GET_SAVESIZE 19
#define XBP_GET_SAVEDATA 20
#define XBP_SET_SAVEDATA 21

EXTERN_C_BEGIN

typedef void* (*opera_xbus_device)(int, void*);

void     opera_xbus_init(opera_xbus_device zero_dev_);
void     opera_xbus_destroy(void);

int      opera_xbus_attach(opera_xbus_device dev);

void     opera_xbus_device_load(int dev, const char *name);
void     opera_xbus_device_eject(int dev);

void     opera_xbus_set_sel(const uint32_t val_);
uint32_t opera_xbus_get_res(void);

void     opera_xbus_set_poll(const uint32_t val_);
uint32_t opera_xbus_get_poll(void);

void     opera_xbus_fifo_set_cmd(const uint32_t val_);
uint32_t opera_xbus_fifo_get_status(void);

void     opera_xbus_fifo_set_data(const uint32_t val_);
uint32_t opera_xbus_fifo_get_data(void);

uint32_t opera_xbus_state_size(void);
uint32_t opera_xbus_state_save(void *data);
uint32_t opera_xbus_state_load(void const *data);

EXTERN_C_END

#endif
