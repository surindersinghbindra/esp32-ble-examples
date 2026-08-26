#ifndef H_OTA_SERVICE_
#define H_OTA_SERVICE_

#include <stdbool.h>
#include <stdint.h>
#include "nimble/ble.h"
#include "modlog/modlog.h"

#ifdef __cplusplus
extern "C" {
#endif

struct ble_hs_cfg;

int ota_service_init(void);

/* Hooks called from main.c's GAP event handler, so the OTA state machine
 * always knows the current connection and never writes to a stale link. */
void ota_service_on_connect(uint16_t conn_handle);
void ota_service_on_disconnect(void);
void ota_service_on_subscribe(uint16_t attr_handle, bool cur_notify);

#ifdef __cplusplus
}
#endif

#endif
