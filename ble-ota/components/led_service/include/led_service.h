#ifndef H_LED_SERVICE_
#define H_LED_SERVICE_

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Registers the LED GATT service, loads the last-saved config from NVS (or
 * a sensible default if none has ever been saved), and starts the task that
 * drives the onboard addressable LED accordingly.
 *
 * Must be called after nvs_flash_init(). The LED's behavior can be changed
 * at any time afterwards, live, by writing to the LED Config characteristic
 * - no reboot or reflash needed, unlike the old Kconfig-based approach this
 * replaces.
 */
int led_service_init(void);

/* Hooks called from main.c's GAP event handler. */
void led_service_on_connect(uint16_t conn_handle);
void led_service_on_disconnect(void);
void led_service_on_subscribe(uint16_t attr_handle, bool cur_indicate);

#ifdef __cplusplus
}
#endif

#endif
