# ServoTester — Android-App (Teilprojekt B)

Steuerzentrale für den ESP32-C3-Servotester. Kotlin + Jetpack Compose,
Material-3 mit dynamischem Light/Dark-Theme. Kommuniziert primär über
**BLE** mit dem ESP32-C3; MQTT wird nur am ESP32 konfiguriert (über BLE
übertragen), nicht von der App selbst gesprochen.

## Bauen

Voraussetzung: Android Studio (Ladybug/2024.2+) oder JDK 17 + Android SDK 34.

```
# In Android Studio:  File → Open → dieses Verzeichnis → Sync
# oder per CLI (nachdem der Wrapper existiert):
./gradlew assembleDebug
```

> **Hinweis zum Gradle-Wrapper:** Aus Lizenzgründen liegt die Binärdatei
> `gradle/wrapper/gradle-wrapper.jar` nicht bei. Android Studio erzeugt sie
> beim ersten Sync automatisch. Ohne Studio einmalig
> `gradle wrapper --gradle-version 8.9` ausführen (systemweites Gradle nötig),
> danach funktioniert `./gradlew`.

`local.properties` (SDK-Pfad) wird von Android Studio automatisch angelegt.

## Funktionen (laut Lastenheft §3)

- **Jogdial** (`ui/Jogdial.kt`) — großes rundes Drehrad, stufenlose µs-Einstellung
  per Drag/Tap, mit mittigem **AN/AUS-Toggle**.
- **Zentrieren** — Schnellbutton auf 1500 µs.
- **4 Presets** — 2 links / 2 rechts unten, im Einstellungsmenü frei belegbar,
  persistent gespeichert.
- **Software-Limits** — Min/Max im Drawer; Jogdial und Presets werden hart
  darauf geclamped.
- **Smooth Transit** (`control/RampController.kt`) — jeder Wertwechsel wird als
  lineare Rampe an den ESP gestreamt (nicht-blockierend, coroutine-basiert).
- **Verbindung** — BLE-Scan (Service-UUID-gefiltert), Laufzeit-Permissions für
  Android 12+ (`BLUETOOTH_SCAN/CONNECT`) und Legacy (`ACCESS_FINE_LOCATION`).
- **WLAN-Config** an den ESP über BLE (SSID/Passwort, optional statische
  IP/Gateway/Maske/DNS) als JSON.
- **MQTT-Config** an den ESP (aktivierbar; deaktiviert ⇒ Firmware überspringt
  MQTT komplett), persistent gespeichert.

### Rampen-Geschwindigkeit (Lastenheft-Widerspruch aufgelöst)

Das Lastenheft nennt an einer Stelle „1000→2000 µs in 2 s" (= 0,5 µs/ms), die
Prompts „voller Weg 700→2300 µs in 2 s" (= 0,8 µs/ms). Aufgelöst über **eine**
zentrale Konstante `ServoConstants.RAMP_RATE_US_PER_MS`. Default = voller
700–2300-Weg in 2 s. Kleinere Sprünge sind dadurch automatisch proportional.
Zum Umstellen auf die 0,5-µs/ms-Lesart siehe Kommentar in
`control/ServoConstants.kt`.

## BLE-Protokoll-Vertrag (mit der Firmware synchron halten)

Custom-Service, alle Characteristics unter derselben 128-bit-Basis.

| Characteristic | UUID (Suffix `-5c4d-4b8e-9f10-2a3b4c5d6e7f`) | Props | Payload |
|---|---|---|---|
| Service   | `b1a70000-…` | —              | — |
| PWM-Wert  | `b1a70001-…` | Read/Write/Notify | uint16 **little-endian**, Ziel-µs (700–2300) |
| Status    | `b1a70002-…` | Read/Write/Notify | uint8: 0 = OFF, 1 = ON |
| WLAN-Cfg  | `b1a70003-…` | Write          | UTF-8-JSON `{ssid,pass,static,ip,gw,mask,dns}` |
| MQTT-Cfg  | `b1a70004-…` | Write          | UTF-8-JSON `{enabled,host,port,user,pass}` |

- PWM-Writes der App nutzen **Write-Without-Response** (schneller Rampen-Stream).
- Der ESP soll denselben Rampen-Filter beim Empfang anwenden (autoritativer
  Fallback bei Verbindungsabriss) und PWM/Status per **Notify** zurückmelden —
  die App spiegelt diese Ist-Werte (auch MQTT-getriebene Änderungen).

## Projektstruktur

```
app/src/main/java/de/pflueger/servotester/
├── MainActivity.kt              Einstieg, Theme + Screen
├── ble/
│   ├── BleManager.kt            GATT-Client (Scan/Connect/Notify/Write)
│   └── ServoUuids.kt            UUIDs + WifiConfig/MqttConfig-Payloads
├── control/
│   ├── ServoConstants.kt        Grenzwerte + Rampenrate (Single Source)
│   ├── RampController.kt        Smooth-Transit-Slew-Limiter
│   └── ServoViewModel.kt        Zustands-Orchestrierung
├── data/SettingsStore.kt        DataStore: Limits, Presets, MQTT, Gerät
└── ui/
    ├── ServoScreen.kt           Hauptbildschirm + Drawer-Gerüst
    ├── Jogdial.kt               Drehrad-Composable
    ├── SettingsScreen.kt        Drawer: Verbindung/Limits/WLAN/MQTT
    └── theme/                   Farben, Typo, dynamisches Theme
```
