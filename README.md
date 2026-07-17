# ServoTester

Smarter Servotester mit erweitertem Impulsbereich **700–2300 µs** — ESP32-C3-Firmware
plus Android-App (BLE + USB-C).

## 📥 App herunterladen

**[➡️ ServoTester-App (APK) — neueste Version herunterladen](https://github.com/Basti77/servotester/releases/latest/download/app-debug.apk)**

Direkter Download der aktuellen Android-App. Danach im Handy „Unbekannte Apps
installieren" für den Browser/die Dateien-App erlauben, falls Android nachfragt.

> Der Link zeigt immer auf das **neueste Release**. Er funktioniert, sobald ein
> Release mit dem Asset-Namen `app-debug.apk` veröffentlicht ist.

## Aufbau

Monorepo:

```
servotester/
├── app/        # Android-App (Kotlin / Jetpack Compose)
└── firmware/   # ESP32-C3-Firmware (Arduino / NimBLE)
```

## Firmware (`firmware/`)

ESP32-C3 SuperMini. PWM auf **GPIO 2** (LEDC, 50 Hz, 14 Bit). BLE-GATT-Steuerung,
optional WLAN + MQTT, BLE-OTA und USB-Steuerkanal. Rampe ("Smooth Transit") mit
einstellbarer Geschwindigkeit; letzte Position/Output/Tempo werden in NVS gemerkt
und beim Booten wiederhergestellt.

```bash
cd firmware
arduino-cli compile \
  --fqbn "esp32:esp32:esp32c3:CDCOnBoot=cdc,PartitionScheme=min_spiffs" \
  --output-dir build ServoTester
```

`PartitionScheme=min_spiffs` ist Pflicht (OTA). Build-Artefakte:
`build/ServoTester.ino.bin` (BLE-OTA / App-USB-Flash),
`build/ServoTester.ino.merged.bin` (Erstflash `@0x0`).

## App (`app/`)

Kotlin/Compose. Verbindung per BLE oder USB-C, Jogdial + Presets + Tempo-Slider,
Firmware-Flashen direkt aus der App (USB-C-Erstflash und BLE-OTA).

```bash
cd app
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```

## BLE-Vertrag

Service `b1a70000-5c4d-4b8e-9f10-2a3b4c5d6e7f`. Characteristics:

| UUID (Suffix `-5c4d-…`) | Bedeutung | Zugriff |
|---|---|---|
| `b1a70001` | PWM-Ziel (uint16 LE, µs) | R/W-NR/Notify |
| `b1a70002` | Output an/aus (uint8) | R/W/Notify |
| `b1a70003` | WLAN-Config (JSON) | W |
| `b1a70004` | MQTT-Config (JSON) | W |
| `b1a70005` | Firmware-Version | R |
| `b1a70006` | Rampen-Tempo (uint16 LE, ms für Vollausschlag) | R/W |
| `b1a70010` | OTA-Control | W/Notify |
| `b1a70011` | OTA-Data | W-NR |

USB-Steuerkanal (Zeilenprotokoll über USB-Serial/JTAG): `PWM <µs>`, `OUT <0|1>`,
`RATE <ms>`, `GET` rein — `PWM`/`OUT`/`RATE`/`FW` raus.

## Roadmap

- Online-Update für App + Firmware über GitHub-Releases dieses Repos.
