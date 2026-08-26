idf_component_register(SRCS "main.c"
                       PRIV_REQUIRES bt nvs_flash app_update
                                     ota_service led_service heart_rate_service
                       INCLUDE_DIRS ".")
