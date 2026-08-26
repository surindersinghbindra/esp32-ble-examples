# ESP32-C6 Examples

A collection of ESP-IDF example projects for the ESP32-C6, each in its own self-contained
subfolder. Every subfolder is an independent ESP-IDF project — `cd` into it and run `idf.py`
commands from there.

## Examples

| Folder | Description |
|---|---|
| [`led-blink/`](led-blink/) | Blinks the onboard addressable RGB LED, cycling through a different color on each blink. |

More examples (BLE, etc.) will be added as new subfolders alongside `led-blink/`.

## Prerequisites (shared across all examples)

* [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/latest/esp32c6/get-started/index.html) v6.1 (tested against `v6.1-beta1`)
* An ESP32-C6 dev board connected via USB-C

## Working with an example

```bash
source ~/.espressif/v6.1-beta1/esp-idf/export.sh   # activate the ESP-IDF environment
cd <example-folder>                                # e.g. led-blink/
idf.py set-target esp32c6
idf.py build
idf.py -p /dev/cu.usbserial-10 flash monitor       # replace with your board's serial port
```

See each example's own `README.md` for project-specific details, structure, and configuration
options.
