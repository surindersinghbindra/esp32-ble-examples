#ifndef H_LED_SERVICE_
#define H_LED_SERVICE_

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

#ifdef __cplusplus
}
#endif

#endif
