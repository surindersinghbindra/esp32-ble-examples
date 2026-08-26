/*
 * LED GATT service.
 *
 * One read/write characteristic controls the onboard addressable LED live -
 * no reboot or reflash needed, unlike the old Kconfig-based approach. Every
 * write is applied to the LED immediately and saved to NVS (flash storage
 * that survives power loss and reboots), so whatever was last set is
 * restored automatically on the next boot.
 *
 * Wire format (7 bytes, written or read as one blob):
 *   byte 0   mode:  0 = off, 1 = solid, 2 = blink
 *   byte 1   red    (0-255)
 *   byte 2   green  (0-255)
 *   byte 3   blue   (0-255)
 *   byte 4   brightness (0-255) - scales red/green/blue down before display
 *   byte 5-6 blink_interval_ms, little-endian uint16 (blink mode only)
 */

#include <string.h>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "led_strip.h"
#include "nvs.h"

#include "host/ble_hs.h"
#include "host/ble_uuid.h"

#include "led_service.h"

static const char *TAG = "led_service";

#define STATUS_LED_GPIO 8

#define LED_MODE_OFF   0
#define LED_MODE_SOLID 1
#define LED_MODE_BLINK 2

#define LED_CONFIG_WIRE_LEN 7

#define NVS_NAMESPACE "storage"
#define NVS_KEY       "led_cfg"

static const ble_uuid128_t led_svc_uuid =
    BLE_UUID128_INIT(0xb0, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x6a, 0x7b,
                      0x8c, 0x9d, 0xae, 0xbf, 0xc0, 0xd1, 0xe2, 0xf3);
static const ble_uuid128_t led_chr_config_uuid =
    BLE_UUID128_INIT(0xb1, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x6a, 0x7b,
                      0x8c, 0x9d, 0xae, 0xbf, 0xc0, 0xd1, 0xe2, 0xf3);

typedef struct {
    uint8_t mode;
    uint8_t red;
    uint8_t green;
    uint8_t blue;
    uint8_t brightness;
    uint16_t blink_interval_ms;
} led_config_t;

static uint16_t s_led_config_val_handle;
static led_config_t s_config;
static SemaphoreHandle_t s_config_mutex;

static void encode_config(const led_config_t *cfg, uint8_t out[LED_CONFIG_WIRE_LEN])
{
    out[0] = cfg->mode;
    out[1] = cfg->red;
    out[2] = cfg->green;
    out[3] = cfg->blue;
    out[4] = cfg->brightness;
    out[5] = (uint8_t)(cfg->blink_interval_ms & 0xFF);
    out[6] = (uint8_t)((cfg->blink_interval_ms >> 8) & 0xFF);
}

static bool decode_config(const uint8_t *in, uint16_t len, led_config_t *cfg)
{
    if (len != LED_CONFIG_WIRE_LEN) {
        return false;
    }
    cfg->mode = in[0];
    cfg->red = in[1];
    cfg->green = in[2];
    cfg->blue = in[3];
    cfg->brightness = in[4];
    cfg->blink_interval_ms = (uint16_t)in[5] | ((uint16_t)in[6] << 8);
    return true;
}

static void save_config_to_nvs(const led_config_t *cfg)
{
    uint8_t wire[LED_CONFIG_WIRE_LEN];
    encode_config(cfg, wire);

    nvs_handle_t handle;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &handle);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "nvs_open failed: %s", esp_err_to_name(err));
        return;
    }
    err = nvs_set_blob(handle, NVS_KEY, wire, sizeof(wire));
    if (err == ESP_OK) {
        err = nvs_commit(handle);
    }
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Failed to save LED config to NVS: %s", esp_err_to_name(err));
    }
    nvs_close(handle);
}

static void load_config_from_nvs(led_config_t *cfg)
{
    /* Default, used the very first time (nothing saved yet): dim solid red -
     * matches this project's original fixed behavior before this was
     * configurable. */
    cfg->mode = LED_MODE_SOLID;
    cfg->red = 255;
    cfg->green = 0;
    cfg->blue = 0;
    cfg->brightness = 16;
    cfg->blink_interval_ms = 500;

    nvs_handle_t handle;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &handle) != ESP_OK) {
        /* First-ever boot: the "storage" namespace doesn't exist until the
         * first successful nvs_open(..., NVS_READWRITE, ...) creates it. */
        ESP_LOGI(TAG, "No saved LED config found, using default (dim solid red)");
        return;
    }
    uint8_t wire[LED_CONFIG_WIRE_LEN];
    size_t len = sizeof(wire);
    esp_err_t err = nvs_get_blob(handle, NVS_KEY, wire, &len);
    nvs_close(handle);
    if (err == ESP_OK && len == sizeof(wire)) {
        decode_config(wire, (uint16_t)len, cfg);
        ESP_LOGI(TAG, "Loaded saved LED config from NVS");
    } else {
        ESP_LOGI(TAG, "No saved LED config found, using default (dim solid red)");
    }
}

static int
led_config_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                      struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        led_config_t cfg;
        xSemaphoreTake(s_config_mutex, portMAX_DELAY);
        cfg = s_config;
        xSemaphoreGive(s_config_mutex);

        uint8_t wire[LED_CONFIG_WIRE_LEN];
        encode_config(&cfg, wire);
        int rc = os_mbuf_append(ctxt->om, wire, sizeof(wire));
        return rc == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    uint16_t om_len = OS_MBUF_PKTLEN(ctxt->om);
    if (om_len != LED_CONFIG_WIRE_LEN) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    uint8_t wire[LED_CONFIG_WIRE_LEN];
    uint16_t out_len;
    int rc = ble_hs_mbuf_to_flat(ctxt->om, wire, sizeof(wire), &out_len);
    if (rc != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    led_config_t cfg;
    if (!decode_config(wire, out_len, &cfg) || cfg.mode > LED_MODE_BLINK) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    xSemaphoreTake(s_config_mutex, portMAX_DELAY);
    s_config = cfg;
    xSemaphoreGive(s_config_mutex);

    save_config_to_nvs(&cfg);
    ESP_LOGI(TAG, "LED config updated: mode=%d rgb=(%d,%d,%d) brightness=%d blink_ms=%d",
             cfg.mode, cfg.red, cfg.green, cfg.blue, cfg.brightness, cfg.blink_interval_ms);

    return 0;
}

static const struct ble_gatt_svc_def led_service_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &led_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &led_chr_config_uuid.u,
                .access_cb = led_config_access_cb,
                .val_handle = &s_led_config_val_handle,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
            }, {
                0, /* No more characteristics in this service. */
            },
        },
    },
    {
        0, /* No more services. */
    },
};

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

    bool blink_on = false;
    while (1) {
        led_config_t cfg;
        xSemaphoreTake(s_config_mutex, portMAX_DELAY);
        cfg = s_config;
        xSemaphoreGive(s_config_mutex);

        uint32_t r = (uint32_t)cfg.red * cfg.brightness / 255;
        uint32_t g = (uint32_t)cfg.green * cfg.brightness / 255;
        uint32_t b = (uint32_t)cfg.blue * cfg.brightness / 255;

        if (cfg.mode == LED_MODE_BLINK) {
            if (blink_on) {
                led_strip_set_pixel(strip, 0, r, g, b);
            } else {
                led_strip_set_pixel(strip, 0, 0, 0, 0);
            }
            led_strip_refresh(strip);
            blink_on = !blink_on;
            uint32_t half_interval = cfg.blink_interval_ms / 2;
            vTaskDelay(pdMS_TO_TICKS(half_interval > 0 ? half_interval : 1));
        } else {
            if (cfg.mode == LED_MODE_SOLID) {
                led_strip_set_pixel(strip, 0, r, g, b);
            } else {
                led_strip_set_pixel(strip, 0, 0, 0, 0);
            }
            led_strip_refresh(strip);
            /* Re-check the config periodically even when not blinking, so a
             * new write (mode/color/brightness change) takes effect quickly
             * without needing its own task-notification plumbing. */
            vTaskDelay(pdMS_TO_TICKS(300));
        }
    }
}

int led_service_init(void)
{
    s_config_mutex = xSemaphoreCreateMutex();
    if (s_config_mutex == NULL) {
        return -1;
    }

    load_config_from_nvs(&s_config);

    int rc = ble_gatts_count_cfg(led_service_svcs);
    if (rc != 0) {
        return rc;
    }
    rc = ble_gatts_add_svcs(led_service_svcs);
    if (rc != 0) {
        return rc;
    }

    xTaskCreate(led_task, "led_task", 3072, NULL, 5, NULL);
    return 0;
}
