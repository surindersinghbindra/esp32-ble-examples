idf_component_register(SRCS "ota_service.c"
                       INCLUDE_DIRS "include"
                       PRIV_REQUIRES bt app_update esp_timer)
