#ifndef H_GATT_SVR_
#define H_GATT_SVR_

#include <stdbool.h>
#include <stdint.h>
#include "nimble/ble.h"
#include "modlog/modlog.h"

#ifdef __cplusplus
extern "C" {
#endif

struct ble_hs_cfg;
struct ble_gatt_register_ctxt;

int gatt_svr_init(void);
void gatt_svr_register_cb(struct ble_gatt_register_ctxt *ctxt, void *arg);

/* Hooks called from main.c's GAP event handler, so the OTA state machine
 * always knows the current connection and never writes to a stale link. */
void gatt_svr_ota_on_connect(uint16_t conn_handle);
void gatt_svr_ota_on_disconnect(void);
void gatt_svr_ota_on_subscribe(uint16_t attr_handle, bool cur_notify);

#ifdef __cplusplus
}
#endif

#endif
