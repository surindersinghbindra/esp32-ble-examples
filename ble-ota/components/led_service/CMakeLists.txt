idf_component_register(SRCS "led_service.c"
                       INCLUDE_DIRS "include"
                       PRIV_REQUIRES bt nvs_flash espressif__led_strip)
