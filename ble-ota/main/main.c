/*
 * BLE OTA example - now with a modular GATT profile.
 *
 * This file only brings up the NimBLE stack and wires together three
 * independent services, each a self-contained component:
 *
 *   components/ota_service/        push a new firmware image over BLE and
 *                                   install it into whichever OTA partition
 *                                   isn't currently running (see that
 *                                   component and the project README for the
 *                                   wire protocol and the safety precautions)
 *   components/led_service/         live, persisted control of the onboard
 *                                   addressable LED (color/brightness/blink)
 *   components/heart_rate_service/  standard Bluetooth Heart Rate service
 *                                   with a simulated BPM value
 *
 * Also handles the boot-time OTA rollback confirmation - see self_check_ok()
 * below - which is about *this running app* proving itself healthy, not
 * about any particular GATT service, so it stays here.
 */

#include <assert.h>
#include <inttypes.h>
#include <string.h>

#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_system.h"
#include "nvs_flash.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_att.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "heart_rate_service.h"
#include "led_service.h"
#include "ota_service.h"

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
            ota_service_on_connect(event->connect.conn_handle);
            heart_rate_service_on_connect(event->connect.conn_handle);
        } else {
            ble_ota_advertise();
        }
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnect; reason=%d", event->disconnect.reason);
        ota_service_on_disconnect();
        heart_rate_service_on_disconnect();
        ble_ota_advertise();
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        ble_ota_advertise();
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE:
        ESP_LOGI(TAG, "subscribe event; attr_handle=%d cur_notify=%d",
                 event->subscribe.attr_handle, event->subscribe.cur_notify);
        ota_service_on_subscribe(event->subscribe.attr_handle, event->subscribe.cur_notify);
        heart_rate_service_on_subscribe(event->subscribe.attr_handle, event->subscribe.cur_notify);
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

/* Shared by every service below - just logs what got registered where. */
static void
gatt_register_cb(struct ble_gatt_register_ctxt *ctxt, void *arg)
{
    char buf[BLE_UUID_STR_LEN];

    switch (ctxt->op) {
    case BLE_GATT_REGISTER_OP_SVC:
        ESP_LOGD(TAG, "registered service %s with handle=%d",
                 ble_uuid_to_str(ctxt->svc.svc_def->uuid, buf), ctxt->svc.handle);
        break;

    case BLE_GATT_REGISTER_OP_CHR:
        ESP_LOGD(TAG, "registering characteristic %s with def_handle=%d val_handle=%d",
                 ble_uuid_to_str(ctxt->chr.chr_def->uuid, buf),
                 ctxt->chr.def_handle, ctxt->chr.val_handle);
        break;

    default:
        break;
    }
}

void app_main(void)
{
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
    ble_hs_cfg.gatts_register_cb = gatt_register_cb;

#if CONFIG_BT_NIMBLE_GAP_SERVICE
    ble_svc_gap_init();
#endif
    ble_svc_gatt_init();

    int rc = ota_service_init();
    assert(rc == 0);
    rc = led_service_init();
    assert(rc == 0);
    rc = heart_rate_service_init();
    assert(rc == 0);

    rc = ble_svc_gap_device_name_set(device_name);
    assert(rc == 0);

    nimble_port_freertos_init(ble_ota_host_task);
}
