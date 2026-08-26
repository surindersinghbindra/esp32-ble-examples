/*
 * BLE OTA GATT service.
 *
 * Exposes one custom service with two characteristics:
 *
 *   Control (read / write / notify) - single-byte commands in, single-byte
 *   status notifications out:
 *     write 0x01 (START)  -> begin an OTA update into the inactive OTA slot
 *     write 0x02 (END)    -> finalize + validate the image (does NOT reboot)
 *     write 0x03 (ABORT)  -> cancel an in-progress update
 *     write 0x04 (REBOOT) -> reboot now, into whichever partition is set to boot
 *     notify 0x00 IDLE, 0x01 IN_PROGRESS, 0x02 SUCCESS, 0x03 ERROR
 *
 *   Rebooting is a separate, explicit step so a client can confirm the new
 *   image validated successfully before choosing to restart into it.
 *
 *   Data (write without response) - raw firmware bytes, sent in-order in
 *   as many chunks as the negotiated ATT MTU allows. Only accepted while a
 *   START has been issued and no END/ABORT has followed it.
 *
 *   Version (read) - the running app's version string (esp_app_desc_t's
 *   `version` field, whatever `idf.py` embedded from `git describe`), as raw
 *   UTF-8 bytes with no null terminator. A client can read this before
 *   pushing an update to check whether it's about to install the same
 *   version it already has - see README's "Firmware version check" section.
 *
 * All the actual partition-safety logic (which of ota_0/ota_1 to target,
 * validating the image before switching the boot partition, aborting
 * cleanly on disconnect) lives here.
 *
 * --- Optional fast path: L2CAP CoC (educational) ---
 *
 * Every GATT write - including our Data characteristic above - travels as
 * one ATT (Attribute Protocol) packet per call, each with its own
 * request/response round trip. That's simple and always available, but it
 * caps throughput: every chunk pays full protocol overhead regardless of
 * size.
 *
 * L2CAP CoC (Connection-Oriented Channel) is a *different* layer of the
 * Bluetooth stack, one level below ATT/GATT. Instead of characteristics, a
 * CoC is a private, credit-based data pipe identified by a PSM (Protocol/
 * Service Multiplexer - think of it like a TCP port number, but for L2CAP).
 * Once open, both sides exchange raw SDUs (Service Data Units) with no ATT
 * framing at all, and flow control happens via credits at the L2CAP layer
 * itself rather than one write-then-wait-for-ack per chunk. This is the
 * same mechanism real high-throughput BLE profiles (audio, fast firmware
 * transfer) use to get meaningfully higher throughput than plain GATT.
 *
 * Here it's wired in as an alternative to the Data characteristic for the
 * bulk firmware bytes only - START/END/REBOOT and status still go over the
 * tiny Control characteristic above, since ATT overhead is irrelevant for
 * single bytes. This is explicitly a learning example, not a hardened
 * production path - see the README for what's simplified/missing (no
 * dynamic PSM negotiation, fixed buffer sizing, no reconnection handling).
 */

#include <assert.h>
#include <inttypes.h>
#include <string.h>

#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "esp_timer.h"

#include "host/ble_hs.h"
#include "host/ble_l2cap.h"
#include "host/ble_uuid.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "ota_service.h"

static const char *TAG = "ble_ota_gatt";

/* --- Custom 128-bit UUIDs for the OTA service --- */
static const ble_uuid128_t ota_svc_uuid =
    BLE_UUID128_INIT(0xa0, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x6a, 0x7b,
                      0x8c, 0x9d, 0xae, 0xbf, 0xc0, 0xd1, 0xe2, 0xf3);
static const ble_uuid128_t ota_chr_control_uuid =
    BLE_UUID128_INIT(0xa1, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x6a, 0x7b,
                      0x8c, 0x9d, 0xae, 0xbf, 0xc0, 0xd1, 0xe2, 0xf3);
static const ble_uuid128_t ota_chr_data_uuid =
    BLE_UUID128_INIT(0xa2, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x6a, 0x7b,
                      0x8c, 0x9d, 0xae, 0xbf, 0xc0, 0xd1, 0xe2, 0xf3);
static const ble_uuid128_t ota_chr_version_uuid =
    BLE_UUID128_INIT(0xa3, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x6a, 0x7b,
                      0x8c, 0x9d, 0xae, 0xbf, 0xc0, 0xd1, 0xe2, 0xf3);

#define OTA_CMD_START  0x01
#define OTA_CMD_END    0x02
#define OTA_CMD_ABORT  0x03
#define OTA_CMD_REBOOT 0x04

#define OTA_STATUS_IDLE        0x00
#define OTA_STATUS_IN_PROGRESS 0x01
#define OTA_STATUS_SUCCESS     0x02
#define OTA_STATUS_ERROR       0x03

#define OTA_DATA_MAX_CHUNK 512

/* --- L2CAP CoC fast path (educational; see the big comment above) ---
 * PSM chosen from the LE dynamic range (0x0080-0x00FF per the Bluetooth Core
 * spec) - arbitrary, just needs to match the client. MTU matches
 * OTA_DATA_MAX_CHUNK so both data paths can share one receive buffer. */
#define OTA_L2CAP_PSM        0x00F0
#define OTA_L2CAP_MTU        OTA_DATA_MAX_CHUNK
#define OTA_L2CAP_BUF_COUNT  6

typedef enum {
    OTA_STATE_IDLE = 0,
    OTA_STATE_IN_PROGRESS,
} ota_state_t;

static ota_state_t s_ota_state = OTA_STATE_IDLE;
static esp_ota_handle_t s_ota_handle;
static const esp_partition_t *s_ota_partition;
static size_t s_ota_bytes_written;
static uint8_t s_data_buf[OTA_DATA_MAX_CHUNK];

static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_ota_control_val_handle;
static uint16_t s_ota_data_val_handle;
static bool s_control_notify_enabled;

static esp_timer_handle_t s_reboot_timer;

static void reboot_timer_cb(void *arg)
{
    ESP_LOGI(TAG, "Rebooting into new firmware now");
    esp_restart();
}

static void send_status(uint8_t status)
{
    if (!s_control_notify_enabled || s_conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return;
    }
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&status, sizeof(status));
    if (om == NULL) {
        ESP_LOGW(TAG, "Out of mbufs, dropped a status notification");
        return;
    }
    int rc = ble_gatts_notify_custom(s_conn_handle, s_ota_control_val_handle, om);
    if (rc != 0) {
        ESP_LOGW(TAG, "Failed to notify OTA status: rc=%d", rc);
    }
}

static void ota_abort_if_in_progress(const char *reason)
{
    if (s_ota_state != OTA_STATE_IN_PROGRESS) {
        return;
    }
    ESP_LOGW(TAG, "Aborting in-progress OTA: %s", reason);
    esp_ota_abort(s_ota_handle);
    s_ota_state = OTA_STATE_IDLE;
    s_ota_bytes_written = 0;
}

static int
read_flat(struct os_mbuf *om, void *dst, uint16_t max_len, uint16_t *out_len)
{
    uint16_t om_len = OS_MBUF_PKTLEN(om);
    if (om_len > max_len) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    int rc = ble_hs_mbuf_to_flat(om, dst, max_len, out_len);
    return rc == 0 ? 0 : BLE_ATT_ERR_UNLIKELY;
}

static int
ota_control_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t status = (s_ota_state == OTA_STATE_IN_PROGRESS) ?
                          OTA_STATUS_IN_PROGRESS : OTA_STATUS_IDLE;
        int rc = os_mbuf_append(ctxt->om, &status, sizeof(status));
        return rc == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    uint8_t cmd;
    uint16_t len;
    int rc = read_flat(ctxt->om, &cmd, sizeof(cmd), &len);
    if (rc != 0 || len != sizeof(cmd)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    switch (cmd) {
    case OTA_CMD_START:
        if (s_ota_state == OTA_STATE_IN_PROGRESS) {
            ESP_LOGW(TAG, "START ignored: OTA already in progress");
            break;
        }

        s_ota_partition = esp_ota_get_next_update_partition(NULL);
        if (s_ota_partition == NULL) {
            ESP_LOGE(TAG, "No free OTA partition found");
            send_status(OTA_STATUS_ERROR);
            break;
        }

        esp_err_t err = esp_ota_begin(s_ota_partition, OTA_WITH_SEQUENTIAL_WRITES,
                                       &s_ota_handle);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "esp_ota_begin failed: %s", esp_err_to_name(err));
            send_status(OTA_STATUS_ERROR);
            break;
        }

        s_ota_bytes_written = 0;
        s_ota_state = OTA_STATE_IN_PROGRESS;
        ESP_LOGI(TAG, "OTA started -> writing to '%s' (subtype %d, offset 0x%" PRIx32 ")",
                 s_ota_partition->label, s_ota_partition->subtype, s_ota_partition->address);
        send_status(OTA_STATUS_IN_PROGRESS);
        break;

    case OTA_CMD_END: {
        if (s_ota_state != OTA_STATE_IN_PROGRESS) {
            ESP_LOGW(TAG, "END ignored: no OTA in progress");
            break;
        }

        esp_err_t err = esp_ota_end(s_ota_handle);
        s_ota_state = OTA_STATE_IDLE;
        if (err != ESP_OK) {
            /* esp_ota_end() validates the image (magic byte + SHA-256) before
             * accepting it. A corrupt or truncated transfer is rejected here
             * and the boot partition is never changed, so the board just
             * keeps running the firmware it already had. */
            if (err == ESP_ERR_OTA_VALIDATE_FAILED) {
                ESP_LOGE(TAG, "New image failed validation (corrupt/incomplete transfer) - "
                         "keeping current firmware");
            } else {
                ESP_LOGE(TAG, "esp_ota_end failed: %s", esp_err_to_name(err));
            }
            send_status(OTA_STATUS_ERROR);
            break;
        }

        err = esp_ota_set_boot_partition(s_ota_partition);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "esp_ota_set_boot_partition failed: %s", esp_err_to_name(err));
            send_status(OTA_STATUS_ERROR);
            break;
        }

        ESP_LOGI(TAG, "New image valid (%u bytes) - boot partition switched, "
                 "waiting for an explicit REBOOT command",
                 (unsigned) s_ota_bytes_written);
        send_status(OTA_STATUS_SUCCESS);
        break;
    }

    case OTA_CMD_ABORT:
        ota_abort_if_in_progress("client requested abort");
        send_status(OTA_STATUS_IDLE);
        break;

    case OTA_CMD_REBOOT:
        if (s_ota_state == OTA_STATE_IN_PROGRESS) {
            ESP_LOGW(TAG, "REBOOT ignored: OTA still in progress (send END or ABORT first)");
            break;
        }
        ESP_LOGI(TAG, "Reboot requested - restarting in 500ms");
        /* Give the BLE stack a moment to actually get the write response /
         * any pending notification out over the air before we reset. */
        esp_timer_start_once(s_reboot_timer, 500 * 1000);
        break;

    default:
        ESP_LOGW(TAG, "Unknown OTA control command: 0x%02x", cmd);
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    return 0;
}

/*
 * Shared by both data paths (GATT Data characteristic and the L2CAP CoC
 * channel below): writes one chunk into the in-progress OTA handle. Returns
 * false (and has already aborted + notified ERROR) on failure, so each
 * caller only needs to decide how to surface that in its own transport's
 * terms (an ATT error code for GATT; just a log line for L2CAP).
 */
static bool ota_write_chunk(const uint8_t *data, uint16_t len)
{
    if (s_ota_state != OTA_STATE_IN_PROGRESS) {
        ESP_LOGW(TAG, "Dropping OTA data: no OTA in progress (send START first)");
        return false;
    }

    esp_err_t err = esp_ota_write(s_ota_handle, data, len);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "esp_ota_write failed: %s", esp_err_to_name(err));
        ota_abort_if_in_progress("esp_ota_write failure");
        send_status(OTA_STATUS_ERROR);
        return false;
    }

    s_ota_bytes_written += len;
    return true;
}

static int
ota_data_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                    struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    uint16_t len;
    int rc = read_flat(ctxt->om, s_data_buf, sizeof(s_data_buf), &len);
    if (rc != 0) {
        ota_abort_if_in_progress("oversized data chunk");
        send_status(OTA_STATUS_ERROR);
        return rc;
    }

    return ota_write_chunk(s_data_buf, len) ? 0 : BLE_ATT_ERR_UNLIKELY;
}

#if MYNEWT_VAL(BLE_L2CAP_COC_MAX_NUM) >= 1

static os_membuf_t s_l2cap_mem[OS_MEMPOOL_SIZE(OTA_L2CAP_BUF_COUNT, OTA_L2CAP_MTU)];
static struct os_mempool s_l2cap_mempool;
static struct os_mbuf_pool s_l2cap_mbuf_pool;

/* Allocates a fresh receive buffer and tells the L2CAP stack it's ready for
 * the next SDU. This doubling as our flow-control point is deliberate: we
 * only re-arm once we've actually drained the previous buffer into
 * esp_ota_write(), so a slow flash write naturally throttles how fast the
 * peer's credits refill - no separate backpressure mechanism needed here,
 * unlike the heart-rate-over-GATT-notify case in the other services. */
static int ota_l2cap_rearm(struct ble_l2cap_chan *chan)
{
    struct os_mbuf *sdu_rx = os_mbuf_get_pkthdr(&s_l2cap_mbuf_pool, 0);
    if (sdu_rx == NULL) {
        ESP_LOGE(TAG, "L2CAP: out of receive buffers");
        return BLE_HS_ENOMEM;
    }
    return ble_l2cap_recv_ready(chan, sdu_rx);
}

static int ota_l2cap_event_cb(struct ble_l2cap_event *event, void *arg)
{
    switch (event->type) {
    case BLE_L2CAP_EVENT_COC_CONNECTED:
        if (event->connect.status != 0) {
            ESP_LOGW(TAG, "L2CAP CoC connect failed: %d", event->connect.status);
            return 0;
        }
        ESP_LOGI(TAG, "L2CAP CoC channel open - fast OTA data path ready");
        return 0;

    case BLE_L2CAP_EVENT_COC_DISCONNECTED:
        ESP_LOGI(TAG, "L2CAP CoC channel closed");
        return 0;

    case BLE_L2CAP_EVENT_COC_ACCEPT:
        /* A peer is opening a channel to our PSM; hand back a buffer so the
         * stack can accept the connection. */
        return ota_l2cap_rearm(event->accept.chan);

    case BLE_L2CAP_EVENT_COC_DATA_RECEIVED: {
        struct os_mbuf *sdu = event->receive.sdu_rx;
        if (sdu != NULL) {
            uint16_t len;
            int rc = ble_hs_mbuf_to_flat(sdu, s_data_buf, sizeof(s_data_buf), &len);
            os_mbuf_free_chain(sdu);
            if (rc == 0) {
                ota_write_chunk(s_data_buf, len);
            } else {
                ESP_LOGW(TAG, "L2CAP: received SDU too large for buffer, dropped");
            }
        }
        /* Re-arm for the next SDU regardless of outcome above - a single bad
         * chunk shouldn't wedge the channel; esp_ota_end() is what actually
         * catches a corrupted overall transfer. */
        ota_l2cap_rearm(event->receive.chan);
        return 0;
    }

    default:
        return 0;
    }
}

static esp_err_t ota_l2cap_init(void)
{
    int rc = os_mempool_init(&s_l2cap_mempool, OTA_L2CAP_BUF_COUNT, OTA_L2CAP_MTU,
                              s_l2cap_mem, "ota_l2cap_pool");
    if (rc != 0) {
        return ESP_FAIL;
    }
    rc = os_mbuf_pool_init(&s_l2cap_mbuf_pool, &s_l2cap_mempool, OTA_L2CAP_MTU,
                            OTA_L2CAP_BUF_COUNT);
    if (rc != 0) {
        return ESP_FAIL;
    }

    /* Registered once, globally, at boot - not per-connection. This is a
     * server listening on a PSM (like a TCP server socket on a port); it
     * accepts a channel from whichever central connects and opens one. */
    rc = ble_l2cap_create_server(OTA_L2CAP_PSM, OTA_L2CAP_MTU, ota_l2cap_event_cb, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "Failed to create OTA L2CAP CoC server: rc=%d", rc);
        return ESP_FAIL;
    }

    ESP_LOGI(TAG, "OTA fast-update L2CAP CoC server listening on PSM 0x%04x (educational)",
             OTA_L2CAP_PSM);
    return ESP_OK;
}

#endif /* MYNEWT_VAL(BLE_L2CAP_COC_MAX_NUM) >= 1 */

static int
ota_version_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op != BLE_GATT_ACCESS_OP_READ_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    const esp_app_desc_t *desc = esp_app_get_description();
    size_t len = strnlen(desc->version, sizeof(desc->version));
    int rc = os_mbuf_append(ctxt->om, desc->version, len);
    return rc == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static const struct ble_gatt_svc_def ota_service_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &ota_svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &ota_chr_control_uuid.u,
                .access_cb = ota_control_access_cb,
                .val_handle = &s_ota_control_val_handle,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_NOTIFY,
            }, {
                .uuid = &ota_chr_data_uuid.u,
                .access_cb = ota_data_access_cb,
                .val_handle = &s_ota_data_val_handle,
                /* Both are accepted; a client should prefer plain WRITE
                 * (with response) unless it implements its own flow control,
                 * since write-without-response has no built-in backpressure
                 * and can silently outrun the link, corrupting the transfer. */
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            }, {
                .uuid = &ota_chr_version_uuid.u,
                .access_cb = ota_version_access_cb,
                .flags = BLE_GATT_CHR_F_READ,
            }, {
                0, /* No more characteristics in this service. */
            },
        },
    },
    {
        0, /* No more services. */
    },
};

void ota_service_on_connect(uint16_t conn_handle)
{
    s_conn_handle = conn_handle;
}

void ota_service_on_disconnect(void)
{
    ota_abort_if_in_progress("BLE link dropped");
    s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
    s_control_notify_enabled = false;
}

void ota_service_on_subscribe(uint16_t attr_handle, bool cur_notify)
{
    if (attr_handle == s_ota_control_val_handle) {
        s_control_notify_enabled = cur_notify;
    }
}

int
ota_service_init(void)
{
    int rc;

    const esp_timer_create_args_t timer_args = {
        .callback = reboot_timer_cb,
        .name = "ota_reboot",
    };
    esp_err_t err = esp_timer_create(&timer_args, &s_reboot_timer);
    if (err != ESP_OK) {
        return -1;
    }

    rc = ble_gatts_count_cfg(ota_service_svcs);
    if (rc != 0) {
        return rc;
    }

    rc = ble_gatts_add_svcs(ota_service_svcs);
    if (rc != 0) {
        return rc;
    }

#if MYNEWT_VAL(BLE_L2CAP_COC_MAX_NUM) >= 1
    /* Non-fatal if this fails - the GATT Data characteristic still works
     * fine on its own; L2CAP CoC is strictly an optional fast path. */
    ota_l2cap_init();
#else
    ESP_LOGW(TAG, "L2CAP CoC fast-update path disabled (CONFIG_BT_NIMBLE_L2CAP_COC_MAX_NUM=0)");
#endif

    return 0;
}
