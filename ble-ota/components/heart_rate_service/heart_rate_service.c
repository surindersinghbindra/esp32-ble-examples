/*
 * Heart Rate GATT service - standard Bluetooth SIG service (0x180D), so any
 * generic BLE heart rate viewer (nRF Connect, etc.) recognizes it correctly,
 * plus one custom characteristic that isn't part of the standard: a rate
 * control switch used to demonstrate client-side backpressure handling.
 *
 *   Heart Rate Measurement (0x2A37, notify) - flags byte (0x06: sensor
 *     contact detected + supported) + 1 byte BPM, per the Bluetooth Heart
 *     Rate Measurement characteristic format.
 *   Body Sensor Location (0x2A38, read) - fixed "Chest" (0x01), just for
 *     authenticity - real straps report this too.
 *   Rate Control (custom UUID, write) - 1 byte: 0 = normal (~1/s, realistic),
 *     1 = fast (~20/s, deliberately too fast for a naive client to keep up
 *     with, so you can see backpressure handling actually do something).
 */

#include "esp_log.h"
#include "esp_random.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "host/ble_hs.h"
#include "host/ble_uuid.h"

#include "heart_rate_service.h"

static const char *TAG = "heart_rate_service";

static const ble_uuid128_t hr_chr_rate_control_uuid =
    BLE_UUID128_INIT(0xc0, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x6a, 0x7b,
                      0x8c, 0x9d, 0xae, 0xbf, 0xc0, 0xd1, 0xe2, 0xf3);

#define BODY_SENSOR_LOCATION_CHEST 0x01

static uint16_t s_hr_measurement_val_handle;
static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static volatile bool s_hr_notify_enabled;
static volatile bool s_fast_mode;

static uint8_t random_walk_bpm(uint8_t current)
{
    int delta = (int)(esp_random() % 5) - 2; /* -2..+2 */
    int next = (int)current + delta;
    if (next < 55) {
        next = 55;
    } else if (next > 160) {
        next = 160;
    }
    return (uint8_t)next;
}

static void notify_heart_rate(uint8_t bpm)
{
    if (!s_hr_notify_enabled || s_conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return;
    }
    uint8_t payload[2] = { 0x06, bpm }; /* flags: sensor contact detected + supported */
    struct os_mbuf *om = ble_hs_mbuf_from_flat(payload, sizeof(payload));
    if (om == NULL) {
        return;
    }
    int rc = ble_gatts_notify_custom(s_conn_handle, s_hr_measurement_val_handle, om);
    if (rc != 0) {
        ESP_LOGW(TAG, "Failed to notify heart rate: rc=%d", rc);
    }
}

static int
hr_measurement_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                          struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    /* Notify-only; the stack never actually calls this for a real central
     * (there's no READ flag), but NimBLE still requires a non-null callback. */
    return BLE_ATT_ERR_UNLIKELY;
}

static int
hr_body_sensor_location_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                                   struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op != BLE_GATT_ACCESS_OP_READ_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    static const uint8_t location = BODY_SENSOR_LOCATION_CHEST;
    int rc = os_mbuf_append(ctxt->om, &location, sizeof(location));
    return rc == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static int
hr_rate_control_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                           struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (OS_MBUF_PKTLEN(ctxt->om) != 1) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    uint8_t val;
    uint16_t len;
    int rc = ble_hs_mbuf_to_flat(ctxt->om, &val, sizeof(val), &len);
    if (rc != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    s_fast_mode = (val != 0);
    ESP_LOGI(TAG, "Heart rate notify rate: %s", s_fast_mode ? "FAST (~20/s)" : "NORMAL (~1/s)");
    return 0;
}

static const struct ble_gatt_svc_def heart_rate_service_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(0x180D), /* Heart Rate Service */
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = BLE_UUID16_DECLARE(0x2A37), /* Heart Rate Measurement */
                .access_cb = hr_measurement_access_cb,
                .val_handle = &s_hr_measurement_val_handle,
                .flags = BLE_GATT_CHR_F_NOTIFY,
            }, {
                .uuid = BLE_UUID16_DECLARE(0x2A38), /* Body Sensor Location */
                .access_cb = hr_body_sensor_location_access_cb,
                .flags = BLE_GATT_CHR_F_READ,
            }, {
                .uuid = &hr_chr_rate_control_uuid.u, /* custom: notify rate control */
                .access_cb = hr_rate_control_access_cb,
                .flags = BLE_GATT_CHR_F_WRITE,
            }, {
                0, /* No more characteristics in this service. */
            },
        },
    },
    {
        0, /* No more services. */
    },
};

static void heart_rate_task(void *arg)
{
    uint8_t bpm = 75;
    while (1) {
        bpm = random_walk_bpm(bpm);
        notify_heart_rate(bpm);
        uint32_t interval_ms = s_fast_mode ? 50 : 1000;
        vTaskDelay(pdMS_TO_TICKS(interval_ms));
    }
}

void heart_rate_service_on_connect(uint16_t conn_handle)
{
    s_conn_handle = conn_handle;
}

void heart_rate_service_on_disconnect(void)
{
    s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
    s_hr_notify_enabled = false;
}

void heart_rate_service_on_subscribe(uint16_t attr_handle, bool cur_notify)
{
    if (attr_handle == s_hr_measurement_val_handle) {
        s_hr_notify_enabled = cur_notify;
    }
}

int heart_rate_service_init(void)
{
    int rc = ble_gatts_count_cfg(heart_rate_service_svcs);
    if (rc != 0) {
        return rc;
    }
    rc = ble_gatts_add_svcs(heart_rate_service_svcs);
    if (rc != 0) {
        return rc;
    }

    xTaskCreate(heart_rate_task, "heart_rate_task", 3072, NULL, 5, NULL);
    return 0;
}
