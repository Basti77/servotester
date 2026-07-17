/*
 * ServoTester — ESP32-C3 SuperMini Firmware
 *
 * Smart servo tester with extended pulse range 700–2300 µs.
 * Counterpart of the Android app in /home/klempi/servotester-app
 * (package de.pflueger.servotester). The BLE GATT contract below MUST
 * stay in sync with the app's ServoUuids.kt.
 *
 * Features
 *  - PWM on GPIO 2 via LEDC, 50 Hz, 14-bit. Output is SILENT at boot
 *    (no pulses) until the app/MQTT enables it.
 *  - The ramp ("Smooth Transit") is authoritative HERE: every target is
 *    slew-rate limited to RAMP_RATE_US_PER_MS (full 700..2300 span in 2 s).
 *  - BLE (NimBLE) GATT server, service UUID advertised so the app's scan
 *    filter finds it. PWM + STATE report back via Notify.
 *  - BLE-OTA: the app pushes an application .bin over GATT; esp_ota via
 *    the Update library, dual app partitions (build with an OTA-capable
 *    partition scheme, e.g. min_spiffs).
 *  - WiFi 4 station (ESP32-C3 supports 802.11b/g/n only) with optional
 *    static IP, configured over BLE as JSON.
 *  - MQTT (PubSubClient), optional: {"enabled":false} => skipped entirely.
 *  - All configuration persisted in NVS (Preferences).
 *  - USB control channel: newline-delimited text over the native
 *    USB-Serial/JTAG port, so the app can drive the tester over the same
 *    cable it flashes with. Commands: "PWM <us>", "OUT <0|1>", "GET".
 *    Firmware pushes "PWM <us>" / "OUT <n>" on change and "FW <version>"
 *    on GET. Unknown lines (incl. our own log output) are ignored by both
 *    sides, so logging and control share the port safely.
 *
 * BLE GATT contract (base suffix -5c4d-4b8e-9f10-2a3b4c5d6e7f):
 *  b1a70000  Service
 *  b1a70001  PWM      R/W-NR/Notify  uint16 LE target µs
 *  b1a70002  STATE    R/W/Notify     uint8 0=off 1=on
 *  b1a70003  WIFI     W              JSON {ssid,pass,static,ip,gw,mask,dns}
 *  b1a70004  MQTT     W              JSON {enabled,host,port,user,pass}
 *  b1a70005  VERSION  R              UTF-8 firmware version string
 *  b1a70006  RATE     R/W            uint16 LE ramp full-span time in ms
 *  b1a70007  LOG      Notify         UTF-8 debug line (app console), see dbg()
 *  b1a70010  OTA_CTRL W/Notify       see OTA protocol below
 *  b1a70011  OTA_DATA W-NR           raw firmware chunk
 *
 * OTA protocol (app -> OTA_CTRL, all integers little-endian):
 *  [0x01][size:u32][md5hex:32 ascii]  begin (md5 optional, 5-byte form ok)
 *  [0x02]                             end/commit -> verify, reboot
 *  [0x03]                             abort
 * Firmware -> Notify on OTA_CTRL: [status:u8][received:u32][err:u8]
 *  status: 0x01 ready, 0x02 success (reboot follows), 0x04 aborted,
 *          0xEE error (err = Update error code)
 */

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <Update.h>
#include <esp_wifi.h>
#include <stdarg.h>

// ---- Identity ---------------------------------------------------------

static const char *FW_VERSION = "1.3.0";
static const char *BLE_NAME   = "ServoTester";

// ---- Servo domain (keep in sync with app ServoConstants.kt) ------------

static const int      SERVO_PIN         = 2;
static const uint32_t PWM_FREQ_HZ       = 50;
static const uint8_t  PWM_RES_BITS      = 14;

static const uint16_t HW_MIN_US         = 700;
static const uint16_t HW_MAX_US         = 2300;
static const uint16_t CENTER_US         = 1500;

/*
 * Ramp slew rate. The Lastenheft contains two readings (§3.2: 0.5 µs/ms
 * vs. prompts: full travel in 2 s = 0.8 µs/ms); we follow the app and use
 * ONE constant so smaller jumps scale proportionally. To switch to the
 * literal §3.2 reading set this to 0.5f.
 */
// Ramp speed is now user-adjustable (app slider): stored as the time the FULL
// 700..2300 span should take, in ms. The slew rate follows from it. Bounds keep
// it sane — 500 ms = fastest, 5 s = very gentle. Default 2000 ms (0.8 µs/ms).
static const uint16_t RAMP_SPAN_MIN_MS = 500;
static const uint16_t RAMP_SPAN_MAX_MS = 5000;
static const uint16_t RAMP_SPAN_DEF_MS = 2000;
static uint16_t       rampSpanMs       = RAMP_SPAN_DEF_MS;

/** Current slew rate in µs/ms, derived from the configured full-span time. */
static inline float rampRatePerMs() {
  return (float)(HW_MAX_US - HW_MIN_US) / (float)rampSpanMs;
}

static const uint32_t RAMP_STEP_MS        = 20;   // one 50 Hz PWM frame
static const uint32_t NOTIFY_THROTTLE_MS  = 100;  // max PWM notify rate while moving

// ---- BLE UUIDs (keep in sync with app ServoUuids.kt) --------------------

#define UUID_SUFFIX "-5c4d-4b8e-9f10-2a3b4c5d6e7f"
static const char *UUID_SERVICE  = "b1a70000" UUID_SUFFIX;
static const char *UUID_PWM      = "b1a70001" UUID_SUFFIX;
static const char *UUID_STATE    = "b1a70002" UUID_SUFFIX;
static const char *UUID_WIFI     = "b1a70003" UUID_SUFFIX;
static const char *UUID_MQTT     = "b1a70004" UUID_SUFFIX;
static const char *UUID_VERSION  = "b1a70005" UUID_SUFFIX;
static const char *UUID_RATE     = "b1a70006" UUID_SUFFIX;
static const char *UUID_LOG      = "b1a70007" UUID_SUFFIX;
static const char *UUID_OTA_CTRL = "b1a70010" UUID_SUFFIX;
static const char *UUID_OTA_DATA = "b1a70011" UUID_SUFFIX;

// OTA opcodes / status codes
enum : uint8_t {
  OTA_OP_BEGIN = 0x01, OTA_OP_END = 0x02, OTA_OP_ABORT = 0x03,
  OTA_ST_READY = 0x01, OTA_ST_SUCCESS = 0x02, OTA_ST_ABORTED = 0x04, OTA_ST_ERROR = 0xEE,
};

// ---- State --------------------------------------------------------------

static Preferences prefs;

static volatile uint16_t targetUs  = CENTER_US;  // where we ramp to
static float             currentUs = CENTER_US;  // ramped "Ist" value
static volatile bool     outputOn  = false;      // silent until enabled

static NimBLECharacteristic *chPwm     = nullptr;
static NimBLECharacteristic *chState   = nullptr;
static NimBLECharacteristic *chRate    = nullptr;
static NimBLECharacteristic *chLog     = nullptr;
static NimBLECharacteristic *chOtaCtrl = nullptr;

// True while a BLE central is connected — gates debug notifies so we never push
// into the void (and never fight the OTA transfer).
static volatile bool bleClientConnected = false;

// Flags set from BLE callbacks, handled in loop() (keep callbacks short).
static volatile bool wifiConfigDirty = false;
static volatile bool mqttConfigDirty = false;
static volatile bool rebootPending   = false;
static uint32_t      rebootAtMs      = 0;

// Servo-state persistence: remember the last position + output across power
// cycles, so after unplug/replug the servo re-engages where it was left
// instead of snapping to a hardcoded center. Written back debounced (only once
// motion has settled) to spare the flash from the per-frame ramp stream.
static volatile bool  servoStateDirty     = false;
static uint32_t       lastServoChangeMs   = 0;
static const uint32_t SERVO_SAVE_DELAY_MS = 1500;
static uint16_t       savedUs             = CENTER_US;  // last value in NVS
static bool           savedOut            = false;
static uint16_t       savedSpan           = RAMP_SPAN_DEF_MS;

// OTA session
static volatile bool otaActive   = false;
static uint32_t      otaSize     = 0;
static uint32_t      otaReceived = 0;

// WiFi / MQTT
static WiFiClient   netClient;
static PubSubClient mqtt(netClient);
static uint32_t     lastWifiAttemptMs = 0;
static uint32_t     lastMqttAttemptMs = 0;

// MQTT topics
static const char *T_CMD_PWM     = "servotester/cmd/pwm";
static const char *T_CMD_OUTPUT  = "servotester/cmd/output";
static const char *T_STAT_PWM    = "servotester/stat/pwm";
static const char *T_STAT_OUTPUT = "servotester/stat/output";
static const char *T_STAT_ONLINE = "servotester/stat/online";

// ---- PWM ----------------------------------------------------------------

static void applyPwm() {
  if (!outputOn) {
    ledcWrite(SERVO_PIN, 0);                       // line low, no pulses
    return;
  }
  uint32_t maxDuty = (1UL << PWM_RES_BITS);
  uint32_t duty = (uint32_t)(currentUs * PWM_FREQ_HZ * maxDuty / 1000000.0f + 0.5f);
  ledcWrite(SERVO_PIN, duty);
}

static uint16_t clampUs(int v) {
  if (v < HW_MIN_US) return HW_MIN_US;
  if (v > HW_MAX_US) return HW_MAX_US;
  return (uint16_t)v;
}

// ---- Notifications back to the app ---------------------------------------

static void notifyPwm(uint16_t us) {
  if (chPwm) {
    uint8_t buf[2] = { (uint8_t)(us & 0xFF), (uint8_t)(us >> 8) };
    chPwm->setValue(buf, 2);
    chPwm->notify();
  }
  if (Serial) Serial.printf("PWM %u\n", (unsigned)us);
}

static void notifyState() {
  if (chState) {
    uint8_t v = outputOn ? 1 : 0;
    chState->setValue(&v, 1);
    chState->notify();
  }
  if (Serial) Serial.printf("OUT %d\n", outputOn ? 1 : 0);
}

static void notifyOta(uint8_t status, uint32_t received, uint8_t err = 0) {
  if (!chOtaCtrl) return;
  uint8_t buf[6] = {
    status,
    (uint8_t)(received), (uint8_t)(received >> 8),
    (uint8_t)(received >> 16), (uint8_t)(received >> 24),
    err,
  };
  chOtaCtrl->setValue(buf, 6);
  chOtaCtrl->notify();
}

static void publishMqttState(bool pwmToo, bool outputToo) {
  if (!mqtt.connected()) return;
  char buf[8];
  if (pwmToo) {
    snprintf(buf, sizeof(buf), "%u", (unsigned)lroundf(currentUs));
    mqtt.publish(T_STAT_PWM, buf, true);
  }
  if (outputToo) mqtt.publish(T_STAT_OUTPUT, outputOn ? "1" : "0", true);
}

// ---- Debug / phase log ----------------------------------------------------

/*
 * One printf-style log sink feeding BOTH channels the app's console reads:
 *  - USB: printed as a plain line (the app treats unknown serial lines as log),
 *  - BLE: pushed over the LOG characteristic as a Notify so the console works
 *    wirelessly too. Gated on a live central and suppressed during OTA so the
 *    firmware upload never has to share the air with chatter.
 * Keep messages short — one BLE notify, no fragmentation.
 */
static void dbg(const char *fmt, ...) {
  char buf[96];
  va_list ap;
  va_start(ap, fmt);
  int n = vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  if (n < 0) return;
  size_t len = (n < (int)sizeof(buf) - 1) ? (size_t)n : sizeof(buf) - 1;
  if (Serial) Serial.printf("[dbg] %s\n", buf);
  if (chLog && bleClientConnected && !otaActive) {
    chLog->setValue((uint8_t *)buf, len);
    chLog->notify();
  }
}

// ---- Ramp ("Smooth Transit", authoritative here) --------------------------

static void rampTick() {
  static uint32_t lastTickMs   = 0;
  static uint32_t lastNotifyMs = 0;
  static uint16_t lastNotified = 0xFFFF;

  uint32_t now = millis();
  if (now - lastTickMs < RAMP_STEP_MS) return;
  uint32_t elapsed = now - lastTickMs;
  lastTickMs = now;

  float tgt = (float)targetUs;
  if (currentUs != tgt) {
    float step = rampRatePerMs() * (float)elapsed;
    if (fabsf(tgt - currentUs) <= step) currentUs = tgt;
    else currentUs += (tgt > currentUs) ? step : -step;
    applyPwm();
  }

  // Report the "Ist" value: throttled while moving, always on arrival.
  uint16_t cur = (uint16_t)lroundf(currentUs);
  bool arrived = (cur == targetUs);
  if (cur != lastNotified && (arrived || now - lastNotifyMs >= NOTIFY_THROTTLE_MS)) {
    lastNotified = cur;
    lastNotifyMs = now;
    notifyPwm(cur);
    if (arrived) { publishMqttState(true, false); dbg("ramp arrived %u", (unsigned)cur); }
  }
}

static void setTarget(int us) {
  uint16_t v = clampUs(us);
  if (v != us) dbg("rx PWM %d -> clamp %u", us, (unsigned)v);
  if (v == targetUs) { dbg("rx PWM %u (schon Ziel)", (unsigned)v); return; }
  dbg("rx PWM %u (Ziel war %u)%s", (unsigned)v, (unsigned)targetUs,
      outputOn ? "" : " [Ausgang AUS]");
  targetUs = v;
  markServoDirty();
}

static void setOutput(bool on) {
  if (outputOn == on) { dbg("rx OUT %d (unverändert)", on ? 1 : 0); return; }
  outputOn = on;
  applyPwm();
  notifyState();
  publishMqttState(false, true);
  dbg("Ausgang %s @ %u µs", on ? "AN" : "AUS", (unsigned)lroundf(currentUs));
  markServoDirty();
}

static void setRampSpan(int ms) {
  uint16_t v = (uint16_t)constrain(ms, RAMP_SPAN_MIN_MS, RAMP_SPAN_MAX_MS);
  if (v == rampSpanMs) return;
  rampSpanMs = v;
  if (chRate) { uint8_t b[2] = { (uint8_t)(v & 0xFF), (uint8_t)(v >> 8) }; chRate->setValue(b, 2); }
  if (Serial) Serial.printf("RATE %u\n", (unsigned)v);
  dbg("Tempo %u ms Vollausschlag", (unsigned)v);
  markServoDirty();
}

// ---- Config persistence (NVS) ---------------------------------------------

struct WifiCfg { String ssid, pass, ip, gw, mask, dns; bool useStatic = false; };
struct MqttCfg { bool enabled = false; String host, user, pass; uint16_t port = 1883; };

static WifiCfg wifiCfg;
static MqttCfg mqttCfg;

static void loadConfig() {
  prefs.begin("servocfg", true);
  wifiCfg.ssid      = prefs.getString("w_ssid", "");
  wifiCfg.pass      = prefs.getString("w_pass", "");
  wifiCfg.useStatic = prefs.getBool  ("w_static", false);
  wifiCfg.ip        = prefs.getString("w_ip", "");
  wifiCfg.gw        = prefs.getString("w_gw", "");
  wifiCfg.mask      = prefs.getString("w_mask", "");
  wifiCfg.dns       = prefs.getString("w_dns", "");
  mqttCfg.enabled   = prefs.getBool  ("m_en", false);
  mqttCfg.host      = prefs.getString("m_host", "");
  mqttCfg.port      = (uint16_t)prefs.getUShort("m_port", 1883);
  mqttCfg.user      = prefs.getString("m_user", "");
  mqttCfg.pass      = prefs.getString("m_pass", "");
  prefs.end();
}

static void saveWifiConfig() {
  prefs.begin("servocfg", false);
  prefs.putString("w_ssid", wifiCfg.ssid);
  prefs.putString("w_pass", wifiCfg.pass);
  prefs.putBool  ("w_static", wifiCfg.useStatic);
  prefs.putString("w_ip", wifiCfg.ip);
  prefs.putString("w_gw", wifiCfg.gw);
  prefs.putString("w_mask", wifiCfg.mask);
  prefs.putString("w_dns", wifiCfg.dns);
  prefs.end();
}

static void saveMqttConfig() {
  prefs.begin("servocfg", false);
  prefs.putBool  ("m_en", mqttCfg.enabled);
  prefs.putString("m_host", mqttCfg.host);
  prefs.putUShort("m_port", mqttCfg.port);
  prefs.putString("m_user", mqttCfg.user);
  prefs.putString("m_pass", mqttCfg.pass);
  prefs.end();
}

// ---- Servo-state persistence ----------------------------------------------

/** Restore the last position + output state saved before the last power-down. */
static void loadServoState() {
  prefs.begin("servocfg", true);
  targetUs   = clampUs(prefs.getUShort("s_us", CENTER_US));
  outputOn   = prefs.getBool("s_out", false);
  rampSpanMs = (uint16_t)constrain(prefs.getUShort("s_span", RAMP_SPAN_DEF_MS),
                                   RAMP_SPAN_MIN_MS, RAMP_SPAN_MAX_MS);
  prefs.end();
  currentUs  = (float)targetUs;  // no ramp on boot: we ARE at the restored value
  savedUs    = targetUs;
  savedOut   = outputOn;
  savedSpan  = rampSpanMs;
}

/** Persist the current position + output + ramp speed. Called debounced from loop(). */
static void saveServoState() {
  prefs.begin("servocfg", false);
  prefs.putUShort("s_us", targetUs);
  prefs.putBool  ("s_out", outputOn);
  prefs.putUShort("s_span", rampSpanMs);
  prefs.end();
  savedUs   = targetUs;
  savedOut  = outputOn;
  savedSpan = rampSpanMs;
}

/** Mark state changed; the debounce timer in loop() writes it once it settles. */
static void markServoDirty() {
  servoStateDirty   = true;
  lastServoChangeMs = millis();
}

// ---- WiFi -----------------------------------------------------------------

static void wifiConnect() {
  if (wifiCfg.ssid.isEmpty()) return;

  WiFi.disconnect(true);
  WiFi.mode(WIFI_STA);
  // ESP32-C3 tops out at WiFi 4; pin the protocol set explicitly (b/g/n).
  esp_wifi_set_protocol(WIFI_IF_STA,
      WIFI_PROTOCOL_11B | WIFI_PROTOCOL_11G | WIFI_PROTOCOL_11N);

  if (wifiCfg.useStatic) {
    IPAddress ip, gw, mask, dns;
    if (ip.fromString(wifiCfg.ip) && gw.fromString(wifiCfg.gw) &&
        mask.fromString(wifiCfg.mask)) {
      if (!dns.fromString(wifiCfg.dns)) dns = gw;
      WiFi.config(ip, gw, mask, dns);
    }
  }
  WiFi.begin(wifiCfg.ssid.c_str(), wifiCfg.pass.c_str());
  Serial.printf("[wifi] connecting to \"%s\"...\n", wifiCfg.ssid.c_str());
}

// ---- MQTT -------------------------------------------------------------------

static void mqttCallback(char *topic, byte *payload, unsigned int len) {
  char buf[16];
  if (len >= sizeof(buf)) return;
  memcpy(buf, payload, len);
  buf[len] = '\0';

  if (strcmp(topic, T_CMD_PWM) == 0) {
    int v = atoi(buf);
    if (v > 0) setTarget(v);
  } else if (strcmp(topic, T_CMD_OUTPUT) == 0) {
    setOutput(buf[0] == '1' || strcasecmp(buf, "on") == 0 || strcasecmp(buf, "true") == 0);
  }
}

static void mqttMaintain() {
  if (!mqttCfg.enabled || mqttCfg.host.isEmpty() || WiFi.status() != WL_CONNECTED) return;
  if (mqtt.connected()) { mqtt.loop(); return; }

  uint32_t now = millis();
  if (now - lastMqttAttemptMs < 5000) return;
  lastMqttAttemptMs = now;

  mqtt.setServer(mqttCfg.host.c_str(), mqttCfg.port);
  mqtt.setCallback(mqttCallback);
  String clientId = String("ServoTester-") + String((uint32_t)ESP.getEfuseMac(), HEX);
  bool ok = mqttCfg.user.isEmpty()
      ? mqtt.connect(clientId.c_str(), T_STAT_ONLINE, 0, true, "offline")
      : mqtt.connect(clientId.c_str(), mqttCfg.user.c_str(), mqttCfg.pass.c_str(),
                     T_STAT_ONLINE, 0, true, "offline");
  if (ok) {
    Serial.println("[mqtt] connected");
    mqtt.publish(T_STAT_ONLINE, "online", true);
    mqtt.subscribe(T_CMD_PWM);
    mqtt.subscribe(T_CMD_OUTPUT);
    publishMqttState(true, true);
  }
}

// ---- USB control channel (newline-delimited text over USB-Serial/JTAG) ------

static void serialSendState() {
  if (!Serial) return;
  Serial.printf("PWM %u\n", (unsigned)lroundf(currentUs));
  Serial.printf("OUT %d\n", outputOn ? 1 : 0);
  Serial.printf("RATE %u\n", (unsigned)rampSpanMs);
  Serial.printf("FW %s\n", FW_VERSION);
}

static void serialHandleLine(const char *line) {
  if (strncmp(line, "PWM ", 4) == 0) {
    int v = atoi(line + 4);
    if (v > 0) setTarget(v);
  } else if (strncmp(line, "OUT ", 4) == 0) {
    setOutput(line[4] == '1');
  } else if (strncmp(line, "RATE ", 5) == 0) {
    int v = atoi(line + 5);
    if (v > 0) setRampSpan(v);
  } else if (strcmp(line, "GET") == 0) {
    serialSendState();
  }
  // anything else: not for us (e.g. echoed log lines) — ignore
}

static void serialTick() {
  static char buf[32];
  static size_t len = 0;
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      buf[len] = '\0';
      if (len) serialHandleLine(buf);
      len = 0;
    } else if (len < sizeof(buf) - 1) {
      buf[len++] = c;
    } else {
      len = 0;   // oversized garbage — drop the line
    }
  }
}

// ---- BLE callbacks -----------------------------------------------------------

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *, NimBLEConnInfo &info) override {
    Serial.printf("[ble] connected: %s\n", info.getAddress().toString().c_str());
    bleClientConnected = true;
    // Push the current state so the app syncs immediately.
    notifyState();
    notifyPwm((uint16_t)lroundf(currentUs));
    dbg("BLE verbunden — Ziel %u, Ist %u, Ausgang %s", (unsigned)targetUs,
        (unsigned)lroundf(currentUs), outputOn ? "AN" : "AUS");
  }
  void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int reason) override {
    Serial.printf("[ble] disconnected (reason %d)\n", reason);
    bleClientConnected = false;
    if (otaActive) {              // half-finished upload is worthless
      Update.abort();
      otaActive = false;
    }
    NimBLEDevice::startAdvertising();
  }
};

class PwmCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *ch, NimBLEConnInfo &) override {
    NimBLEAttValue v = ch->getValue();
    if (v.size() >= 2) setTarget((uint16_t)v[0] | ((uint16_t)v[1] << 8));
  }
};

class RateCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *ch, NimBLEConnInfo &) override {
    NimBLEAttValue v = ch->getValue();
    if (v.size() >= 2) setRampSpan((uint16_t)v[0] | ((uint16_t)v[1] << 8));
  }
};

class StateCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *ch, NimBLEConnInfo &) override {
    NimBLEAttValue v = ch->getValue();
    if (v.size() >= 1) setOutput(v[0] != 0);
  }
};

class WifiCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *ch, NimBLEConnInfo &) override {
    JsonDocument doc;
    if (deserializeJson(doc, ch->getValue().c_str())) return;
    wifiCfg.ssid      = doc["ssid"]   | "";
    wifiCfg.pass      = doc["pass"]   | "";
    wifiCfg.useStatic = doc["static"] | false;
    wifiCfg.ip        = doc["ip"]     | "";
    wifiCfg.gw        = doc["gw"]     | "";
    wifiCfg.mask      = doc["mask"]   | "";
    wifiCfg.dns       = doc["dns"]    | "";
    saveWifiConfig();
    wifiConfigDirty = true;           // (re)connect from loop(), not from here
  }
};

class MqttCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *ch, NimBLEConnInfo &) override {
    JsonDocument doc;
    if (deserializeJson(doc, ch->getValue().c_str())) return;
    mqttCfg.enabled = doc["enabled"] | false;
    mqttCfg.host    = doc["host"]    | "";
    mqttCfg.port    = doc["port"]    | 1883;
    mqttCfg.user    = doc["user"]    | "";
    mqttCfg.pass    = doc["pass"]    | "";
    saveMqttConfig();
    mqttConfigDirty = true;
  }
};

class OtaCtrlCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *ch, NimBLEConnInfo &) override {
    NimBLEAttValue v = ch->getValue();
    if (v.size() < 1) return;

    switch (v[0]) {
      case OTA_OP_BEGIN: {
        if (v.size() < 5) { notifyOta(OTA_ST_ERROR, 0, 0); return; }
        if (otaActive) Update.abort();
        otaSize = (uint32_t)v[1] | ((uint32_t)v[2] << 8) |
                  ((uint32_t)v[3] << 16) | ((uint32_t)v[4] << 24);
        otaReceived = 0;
        if (!Update.begin(otaSize, U_FLASH)) {
          notifyOta(OTA_ST_ERROR, 0, Update.getError());
          return;
        }
        if (v.size() >= 5 + 32) {      // optional MD5 (32 hex chars)
          char md5[33];
          memcpy(md5, v.data() + 5, 32);
          md5[32] = '\0';
          Update.setMD5(md5);
        }
        // Safety: stop driving the servo while we burn flash.
        setOutput(false);
        otaActive = true;
        Serial.printf("[ota] begin, %u bytes\n", (unsigned)otaSize);
        notifyOta(OTA_ST_READY, 0);
        break;
      }
      case OTA_OP_END: {
        if (!otaActive) { notifyOta(OTA_ST_ERROR, otaReceived, 0); return; }
        otaActive = false;
        if (Update.end(true)) {
          Serial.println("[ota] success, rebooting");
          notifyOta(OTA_ST_SUCCESS, otaReceived);
          rebootPending = true;
          rebootAtMs = millis() + 700;   // let the notify get out first
        } else {
          Serial.printf("[ota] end failed: %u\n", Update.getError());
          notifyOta(OTA_ST_ERROR, otaReceived, Update.getError());
        }
        break;
      }
      case OTA_OP_ABORT: {
        if (otaActive) Update.abort();
        otaActive = false;
        Serial.println("[ota] aborted by app");
        notifyOta(OTA_ST_ABORTED, otaReceived);
        break;
      }
    }
  }
};

class OtaDataCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *ch, NimBLEConnInfo &) override {
    if (!otaActive) return;
    NimBLEAttValue v = ch->getValue();
    if (Update.write((uint8_t *)v.data(), v.size()) != v.size()) {
      Update.abort();
      otaActive = false;
      notifyOta(OTA_ST_ERROR, otaReceived, Update.getError());
      return;
    }
    otaReceived += v.size();
  }
};

// ---- BLE setup -----------------------------------------------------------

static void bleSetup() {
  NimBLEDevice::init(BLE_NAME);
  NimBLEDevice::setMTU(517);          // big MTU => fast OTA chunks
  NimBLEDevice::setPower(3);          // dBm; SuperMini antenna is mediocre

  NimBLEServer *server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService *svc = server->createService(UUID_SERVICE);

  chPwm = svc->createCharacteristic(
      UUID_PWM,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE |
      NIMBLE_PROPERTY::WRITE_NR | NIMBLE_PROPERTY::NOTIFY);
  chPwm->setCallbacks(new PwmCallbacks());
  { uint8_t init[2] = { (uint8_t)(targetUs & 0xFF), (uint8_t)(targetUs >> 8) };
    chPwm->setValue(init, 2); }

  chState = svc->createCharacteristic(
      UUID_STATE,
      NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
  chState->setCallbacks(new StateCallbacks());
  { uint8_t st = outputOn ? 1 : 0; chState->setValue(&st, 1); }

  svc->createCharacteristic(UUID_WIFI, NIMBLE_PROPERTY::WRITE)
      ->setCallbacks(new WifiCallbacks());
  svc->createCharacteristic(UUID_MQTT, NIMBLE_PROPERTY::WRITE)
      ->setCallbacks(new MqttCallbacks());

  NimBLECharacteristic *chVersion =
      svc->createCharacteristic(UUID_VERSION, NIMBLE_PROPERTY::READ);
  chVersion->setValue(FW_VERSION);

  chRate = svc->createCharacteristic(
      UUID_RATE, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);
  chRate->setCallbacks(new RateCallbacks());
  { uint8_t b[2] = { (uint8_t)(rampSpanMs & 0xFF), (uint8_t)(rampSpanMs >> 8) };
    chRate->setValue(b, 2); }

  // Debug/phase log stream for the app console (notify-only, no write back).
  chLog = svc->createCharacteristic(UUID_LOG, NIMBLE_PROPERTY::NOTIFY);

  chOtaCtrl = svc->createCharacteristic(
      UUID_OTA_CTRL, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::NOTIFY);
  chOtaCtrl->setCallbacks(new OtaCtrlCallbacks());

  svc->createCharacteristic(UUID_OTA_DATA, NIMBLE_PROPERTY::WRITE_NR)
      ->setCallbacks(new OtaDataCallbacks());

  svc->start();

  // The app's scan filter matches on the service UUID -> it MUST be
  // advertised. Name goes into the scan response (31-byte adv limit).
  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(UUID_SERVICE);
  adv->setName(BLE_NAME);
  adv->enableScanResponse(true);
  adv->start();
  Serial.println("[ble] advertising");
}

// ---- Arduino entry points ---------------------------------------------------

void setup() {
  Serial.begin(115200);
  Serial.setTxTimeoutMs(0);   // never stall the ramp when no host is reading
  Serial.printf("\nServoTester FW %s\n", FW_VERSION);

  // PWM output configured; starts LOW. The last position + output state are
  // restored from NVS below, so after a power cycle the servo re-engages where
  // it was left (v1.2.0) rather than snapping to a hardcoded center.
  ledcAttach(SERVO_PIN, PWM_FREQ_HZ, PWM_RES_BITS);
  ledcWrite(SERVO_PIN, 0);

  loadConfig();
  loadServoState();
  applyPwm();        // drive the restored position immediately if output was on
  bleSetup();        // advertises the restored values via chPwm/chState
  dbg("Boot FW %s — Ist %u µs, Ausgang %s, Tempo %u ms", FW_VERSION,
      (unsigned)targetUs, outputOn ? "AN" : "AUS", (unsigned)rampSpanMs);

  if (!wifiCfg.ssid.isEmpty()) wifiConnect();
  lastWifiAttemptMs = millis();
}

void loop() {
  rampTick();
  serialTick();

  // Persist servo position/output once motion has settled (debounced so the
  // per-frame ramp stream doesn't hammer the flash). Skipped during OTA so we
  // never store the deliberately-forced-off state from the update path.
  if (servoStateDirty && !otaActive &&
      millis() - lastServoChangeMs >= SERVO_SAVE_DELAY_MS) {
    servoStateDirty = false;
    if (targetUs != savedUs || outputOn != savedOut || rampSpanMs != savedSpan) saveServoState();
  }

  // Deferred actions from BLE callbacks.
  if (rebootPending && (int32_t)(millis() - rebootAtMs) >= 0) ESP.restart();
  if (wifiConfigDirty) { wifiConfigDirty = false; wifiConnect(); lastWifiAttemptMs = millis(); }
  if (mqttConfigDirty) {
    mqttConfigDirty = false;
    if (mqtt.connected()) mqtt.disconnect();
    lastMqttAttemptMs = 0;            // retry immediately with new config
  }

  // WiFi watchdog: retry every 30 s if configured but not connected.
  if (!wifiCfg.ssid.isEmpty() && WiFi.status() != WL_CONNECTED &&
      millis() - lastWifiAttemptMs > 30000) {
    wifiConnect();
    lastWifiAttemptMs = millis();
  }
  static bool wasConnected = false;
  bool isConnected = (WiFi.status() == WL_CONNECTED);
  if (isConnected && !wasConnected) {
    Serial.printf("[wifi] connected, IP %s\n", WiFi.localIP().toString().c_str());
  }
  wasConnected = isConnected;

  mqttMaintain();
  delay(2);   // yield; ramp granularity is 20 ms anyway
}
