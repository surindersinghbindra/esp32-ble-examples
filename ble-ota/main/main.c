/*
 * BLE OTA example.
 *
 * Advertises a custom GATT service (see gatt_svr.c) that a BLE central can
 * use to push a new firmware image into whichever OTA partition (ota_0 or
 * ota_1) isn't currently running, then reboot into it. See this project's
 * README for the wire protocol and the precautions this example takes to
 * make sure a bad update can't leave the board unbootable.
 */

#include <assert.h>
#include <inttypes.h>
#include <string.h>

#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "led_strip.h"
#include "nvs_flash.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_att.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"

#include "gatt_svr.h"

static const char *TAG = "ble_ota_main";
static const char *device_name = "esp32-ble-ota";

static uint8_t own_addr_type;

static int ble_ota_gap_event(struct ble_gap_event *event, void *arg);

/*
 * A minimal health check run once, right after booting a freshly-flashed
 * OTA image, before we tell the bootloader to trust it permanently.
 *
 * This only proves the new binary isn't outright corrupt/misbuilt (its app
 * descriptor is readable, and it isn't immediately starving the heap). Treat
 * it as a placeholder: a real project should replace or extend this with
 * whatever actually proves *its* firmware is healthy - e.g. a successful
 * sensor read, a successful network connection, a specific self-test.
 */
static bool self_check_ok(void)
{
    esp_app_desc_t desc;
    const esp_partition_t *running = esp_ota_get_running_partition();

    if (esp_ota_get_partition_description(running, &desc) != ESP_OK) {
        ESP_LOGE(TAG, "Self-check failed: could not read app descriptor");
        return false;
    }

    uint32_t free_heap = esp_get_free_heap_size();
    if (free_heap < 20000) {
        ESP_LOGE(TAG, "Self-check failed: free heap too low (%" PRIu32 " bytes)", free_heap);
        return false;
    }

    ESP_LOGI(TAG, "Self-check OK: running '%s', free heap %" PRIu32 " bytes",
             desc.version, free_heap);
    return true;
}

static void
ble_ota_advertise(void)
{
    struct ble_gap_adv_params adv_params;
    struct ble_hs_adv_fields fields;
    int rc;

    memset(&fields, 0, sizeof(fields));
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.tx_pwr_lvl_is_present = 1;
    fields.tx_pwr_lvl = BLE_HS_ADV_TX_PWR_LVL_AUTO;
    fields.name = (uint8_t *)device_name;
    fields.name_len = strlen(device_name);
    fields.name_is_complete = 1;

    rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "error setting advertisement data; rc=%d", rc);
        return;
    }

    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &adv_params,
                           ble_ota_gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "error enabling advertisement; rc=%d", rc);
        return;
    }
}

static int
ble_ota_gap_event(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        ESP_LOGI(TAG, "connection %s; status=%d",
                 event->connect.status == 0 ? "established" : "failed",
                 event->connect.status);
        if (event->connect.status == 0) {
            gatt_svr_ota_on_connect(event->connect.conn_handle);
        } else {
            ble_ota_advertise();
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnect; reason=%d", event->disconnect.reason);
        gatt_svr_ota_on_disconnect();
        ble_ota_advertise();
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        ble_ota_advertise();
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI(TAG, "subscribe event; attr_handle=%d cur_notify=%d",
                 event->subscribe.attr_handle, event->subscribe.cur_notify);
        gatt_svr_ota_on_subscribe(event->subscribe.attr_handle,
                                  event->subscribe.cur_notify);
        return 0;

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "mtu update event; conn_handle=%d mtu=%d",
                 event->mtu.conn_handle, event->mtu.value);
        return 0;

    default:
        return 0;
    }
}

static void
ble_ota_on_sync(void)
{
    int rc = ble_hs_id_infer_auto(0, &own_addr_type);
    assert(rc == 0);

    uint8_t addr_val[6] = {0};
    ble_hs_id_copy_addr(own_addr_type, addr_val, NULL);
    ESP_LOGI(TAG, "Device address: %02x:%02x:%02x:%02x:%02x:%02x",
             addr_val[5], addr_val[4], addr_val[3],
             addr_val[2], addr_val[1], addr_val[0]);

    ble_ota_advertise();
}

static void
ble_ota_on_reset(int reason)
{
    ESP_LOGE(TAG, "Resetting BLE state; reason=%d", reason);
}

static void ble_ota_host_task(void *param)
{
    ESP_LOGI(TAG, "BLE host task started");
    nimble_port_run();
    nimble_port_freertos_deinit();
}

/* Onboard addressable LED (GPIO8 on the C6-DevKitC).
 * Purely a visual marker so it's obvious which build/config is running -
 * not part of the OTA logic itself. Mode is chosen via `idf.py menuconfig`
 * (Example Configuration -> Status LED mode); see main/Kconfig.projbuild.
 *
 * This runs from its own task rather than inline as the first thing in
 * app_main(): calling the RMT-based LED driver that early was unreliable in
 * testing (produced a stuck/incorrect color on the strip); deferring it to a
 * task that runs after the rest of system init gets going fixed it. */
#define STATUS_LED_GPIO 8

static void led_task(void *arg)
{
    led_strip_handle_t strip;
    led_strip_config_t strip_config = {
        .strip_gpio_num = STATUS_LED_GPIO,
        .max_leds = 1,
    };
    led_strip_rmt_config_t rmt_config = {
        .resolution_hz = 10 * 1000 * 1000,
        .flags.with_dma = false,
    };
    esp_err_t err = led_strip_new_rmt_device(&strip_config, &rmt_config, &strip);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "led_strip_new_rmt_device failed: %s", esp_err_to_name(err));
        vTaskDelete(NULL);
        return;
    }

#if CONFIG_LED_MODE_ROTATE
    struct { const char *label; uint32_t r, g, b; } phases[] = {
        { "red",   16, 0,  0  },
        { "green", 0,  16, 0  },
        { "blue",  0,  0,  16 },
        { "off",   0,  0,  0  },
    };
    ESP_LOGI(TAG, "Status LED: rotating R/G/B/off every %d s", CONFIG_LED_ROTATE_DELAY_SECONDS);
    while (1) {
        for (int i = 0; i < 4; i++) {
            led_strip_set_pixel(strip, 0, phases[i].r, phases[i].g, phases[i].b);
            led_strip_refresh(strip);
            ESP_LOGI(TAG, "Status LED: %s", phases[i].label);
            vTaskDelay(pdMS_TO_TICKS(CONFIG_LED_ROTATE_DELAY_SECONDS * 1000));
        }
    }
#else
#if CONFIG_LED_MODE_ONLY_GREEN
    err = led_strip_set_pixel(strip, 0, 0, 16, 0);
    const char *color_name = "green";
#else
    err = led_strip_set_pixel(strip, 0, 16, 0, 0);
    const char *color_name = "red";
#endif
    if (err == ESP_OK) {
        err = led_strip_refresh(strip);
    }
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "Status LED set to %s on GPIO%d", color_name, STATUS_LED_GPIO);
    } else {
        ESP_LOGE(TAG, "Failed to set status LED: %s", esp_err_to_name(err));
    }
    vTaskDelete(NULL);
#endif
}

static void start_status_led(void)
{
    xTaskCreate(led_task, "led_task", 3072, NULL, 5, NULL);
}

void app_main(void)
{
    start_status_led();

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    /*
     * --- OTA rollback safety net (precaution #1) ---
     * After an OTA update, the bootloader boots the new image with its
     * state set to ESP_OTA_IMG_PENDING_VERIFY. If nothing here confirms it
     * by calling esp_ota_mark_app_valid_cancel_rollback(), the *next* reset
     * of this partition - including one from a crash or watchdog timeout -
     * makes the bootloader automatically fall back to the previous OTA
     * slot instead of retrying the broken image. This is the mechanism
     * that actually prevents a bad update from bricking the board, and it
     * only works because CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y is set in
     * sdkconfig.defaults.
     */
    const esp_partition_t *running = esp_ota_get_running_partition();
    esp_ota_img_states_t ota_state;
    if (esp_ota_get_state_partition(running, &ota_state) == ESP_OK &&
        ota_state == ESP_OTA_IMG_PENDING_VERIFY) {
        ESP_LOGW(TAG, "First boot of a new OTA image on '%s' - self-checking before confirming it",
                 running->label);
        if (self_check_ok()) {
            ESP_LOGI(TAG, "Confirming this image as valid (rollback cancelled)");
            esp_ota_mark_app_valid_cancel_rollback();
        } else {
            ESP_LOGE(TAG, "Rolling back to the previous image now");
            esp_ota_mark_app_invalid_rollback_and_reboot();
            /* Only returns if there was no valid previous image to roll back to. */
        }
    }

    ret = nimble_port_init();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to init nimble: %d", ret);
        return;
    }

    /* Negotiate a large ATT MTU so OTA data chunks aren't limited to ~20 bytes. */
    ble_att_set_preferred_mtu(517);

    ble_hs_cfg.sync_cb = ble_ota_on_sync;
    ble_hs_cfg.reset_cb = ble_ota_on_reset;
    ble_hs_cfg.gatts_register_cb = gatt_svr_register_cb;

    int rc = gatt_svr_init();
    assert(rc == 0);

    rc = ble_svc_gap_device_name_set(device_name);
    assert(rc == 0);

    nimble_port_freertos_init(ble_ota_host_task);
}
