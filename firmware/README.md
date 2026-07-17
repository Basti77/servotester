# ServoTester – ESP32-C3 Firmware

Firmware für den smarten Servotester (700–2300 µs) auf dem **ESP32-C3 SuperMini**.
Gegenstück zur Android-App in `~/servotester-app` – der BLE-Vertrag (UUIDs,
Datenformate) muss mit deren `ServoUuids.kt` synchron bleiben.

## Funktionsumfang

- **PWM** auf GPIO 2 (LEDC, 50 Hz, 14 Bit). Beim Einschalten ist der Ausgang
  **stillgelegt** (keine Impulse), bis App oder MQTT ihn aktivieren.
- **Rampe („Smooth Transit")** läuft autoritativ in der Firmware:
  0,8 µs/ms = voller Weg 700→2300 µs in 2 s, kleinere Sprünge proportional.
- **BLE** (NimBLE): Service `b1a70000-5c4d-4b8e-9f10-2a3b4c5d6e7f` wird
  advertised (App-Scanfilter). PWM- und Ausgangszustand kommen per Notify zurück.
- **BLE-OTA**: Die App überträgt eine neue Firmware als `.bin` über GATT.
- **WLAN** (WiFi 4, mehr kann der C3 nicht) mit optional statischer IP,
  Konfiguration per BLE als JSON.
- **MQTT** optional – `{"enabled":false}` ⇒ wird komplett übersprungen.
- **USB-Steuerkanal** (ab 1.1.0): Steuerung über das USB-C-Kabel, gleiches
  Kabel wie beim Flashen – siehe Protokoll unten.
- Alle Einstellungen liegen persistent im **NVS** (Preferences).

## Bauen

Toolchain: `arduino-cli` (in `~/bin`), ESP32-Core 3.x, Bibliotheken
NimBLE-Arduino, PubSubClient, ArduinoJson.

```bash
~/bin/arduino-cli compile \
  --fqbn "esp32:esp32:esp32c3:CDCOnBoot=cdc,PartitionScheme=min_spiffs" \
  --output-dir build \
  ServoTester
```

Wichtig: **`PartitionScheme=min_spiffs`** (oder ein anderes Schema mit zwei
App-Partitionen + otadata) – sonst funktioniert das OTA-Update nicht.
`CDCOnBoot=cdc` gibt die seriellen Logs über den USB-C-Port des SuperMini aus.

Ergebnis in `build/`:

| Datei | Zweck |
|---|---|
| `ServoTester.ino.merged.bin` | **Erstflash** per USB (Adresse 0x0) |
| `ServoTester.ino.bin` | **OTA-Update** über die App |

## Flashen – zwei Wege, beide ohne Spezialwissen

**1. Per Handy über USB-C (empfohlen, auch für fabrikneue ESP32):**
Die App (ab v1.2) spricht das esptool-ROM-Protokoll selbst. ESP32 per
USB-C/OTG-Kabel ans Handy, Einstellungen → „Firmware-Update" → „Per USB
flashen" → `merged.bin` (Komplett-Installation) oder `ServoTester.ino.bin`
wählen. Die App resettet den Chip automatisch in den Bootloader, flasht
komprimiert und prüft per MD5. Falls der automatische Reset scheitert:
BOOT-Taste gedrückt halten, einstecken, loslassen, nochmal versuchen.

**2. Klassisch per PC:**

```bash
pip install esptool   # falls noch nicht vorhanden
esptool.py --chip esp32c3 -p /dev/ttyACM0 write_flash 0x0 build/ServoTester.ino.merged.bin
```

Läuft die Firmware erst einmal, gehen Updates auch kabellos: Einstellungen →
„Firmware-Update" → `ServoTester.ino.bin` per **BLE-OTA**.

Hinweis zur App-Logik beim USB-Flashen: Dateien > 2 MB gelten als
Komplett-Image (Adresse 0x0); kleinere App-Images landen auf 0x10000, wobei
die App vorher die otadata-Partition (0xD000) leert, damit sicher der frisch
geschriebene Slot bootet.

## BLE-Vertrag

Basis-Suffix `-5c4d-4b8e-9f10-2a3b4c5d6e7f`:

| UUID | Charakteristik | Zugriff | Format |
|---|---|---|---|
| `b1a70000` | Service | – | wird advertised |
| `b1a70001` | PWM | R / W(-NR) / Notify | uint16 LE, Ziel-µs |
| `b1a70002` | Ausgang | R / W / Notify | uint8, 0 = aus, 1 = an |
| `b1a70003` | WLAN | W | JSON `{ssid,pass,static,ip,gw,mask,dns}` |
| `b1a70004` | MQTT | W | JSON `{enabled,host,port,user,pass}` |
| `b1a70005` | Version | R | UTF-8-String, z. B. `1.0.0` |
| `b1a70010` | OTA-Steuerung | W / Notify | siehe unten |
| `b1a70011` | OTA-Daten | W-NR | rohe Firmware-Blöcke |

### OTA-Protokoll

App → `b1a70010` (Integer little-endian):

| Kommando | Aufbau |
|---|---|
| Start | `0x01` + Größe (uint32) + optional MD5 (32 ASCII-Hexzeichen) |
| Abschluss | `0x02` – prüft, übernimmt, bootet neu |
| Abbruch | `0x03` |

Datenblöcke gehen als Write-without-Response an `b1a70011`
(Blockgröße = MTU − 3).

Firmware → Notify auf `b1a70010`: `[Status u8][empfangen u32][Fehlercode u8]`
mit Status `0x01` bereit, `0x02` Erfolg (Neustart folgt), `0x04` abgebrochen,
`0xEE` Fehler (Fehlercode = `Update.getError()`).

## USB-Steuerprotokoll (ab Firmware 1.1.0)

Zeilenbasierter Text über den nativen USB-Serial/JTAG-Port (LF-terminiert).
Unbekannte Zeilen werden beidseitig ignoriert – Log-Ausgaben und Steuerung
teilen sich denselben Port gefahrlos.

| Richtung | Zeile | Bedeutung |
|---|---|---|
| App → ESP | `PWM <µs>` | Zielwert setzen |
| App → ESP | `OUT 0` / `OUT 1` | Ausgang aus/ein |
| App → ESP | `GET` | Zustand + Version anfordern |
| ESP → App | `PWM <µs>` | Ist-Wert (bei Änderung, gedrosselt) |
| ESP → App | `OUT <0\|1>` | Ausgangszustand |
| ESP → App | `FW <version>` | Firmware-Version (Antwort auf `GET`) |

## MQTT-Topics

| Topic | Richtung | Inhalt |
|---|---|---|
| `servotester/cmd/pwm` | → ESP | Ziel-µs als Zahl |
| `servotester/cmd/output` | → ESP | `0`/`1`/`on`/`off` |
| `servotester/stat/pwm` | ESP → | Ist-Wert (retained) |
| `servotester/stat/output` | ESP → | `0`/`1` (retained) |
| `servotester/stat/online` | ESP → | `online`/`offline` (LWT, retained) |
