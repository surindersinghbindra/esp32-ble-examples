#ifndef H_HEART_RATE_SERVICE_
#define H_HEART_RATE_SERVICE_

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Standard Bluetooth SIG Heart Rate Service (0x180D) with a simulated BPM
 * value, plus one custom characteristic to switch the notify rate between a
 * realistic ~1/s and a deliberately fast ~20/s (real heart rate hardware
 * never needs backpressure at 1/s - the fast mode exists purely so a client
 * app has something to actually exercise its backpressure handling against).
 */
int heart_rate_service_init(void);

/* Hooks called from main.c's GAP event handler. */
void heart_rate_service_on_connect(uint16_t conn_handle);
void heart_rate_service_on_disconnect(void);
void heart_rate_service_on_subscribe(uint16_t attr_handle, bool cur_notify);

#ifdef __cplusplus
}
#endif

#endif
