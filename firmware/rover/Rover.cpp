#include "Rover.h"

static Rover* g_rover = nullptr;

// ------------------------- КОЛБЭКИ BLE (внешние) -------------------------

void RoverServerCallbacks::onConnect(BLEServer* s) {
  r_->onConnected();
}
void RoverServerCallbacks::onDisconnect(BLEServer* s) {
  r_->onDisconnected();
}
void RoverCmdCallbacks::onWrite(BLECharacteristic* c) {
  r_->handleCmdWrite(c);
}

// ---------------------------- ИНИЦИАЛИЗАЦИЯ ------------------------------

void Rover::begin() {
  Serial.println("[rover] init");

  g_rover = this;

  // BLE
  BLEDevice::init(DEVICE_NAME);
  server_ = BLEDevice::createServer();
  server_->setCallbacks(new RoverServerCallbacks(this));

  BLEService* svc = server_->createService(DEVICE_SERVICE_UUID);

  cmdChr_ = svc->createCharacteristic(
      DEVICE_CHAR_CMD_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmdChr_->setCallbacks(new RoverCmdCallbacks(this));

  telemChr_ = svc->createCharacteristic(
      DEVICE_CHAR_TELEM_UUID,
      BLECharacteristic::PROPERTY_NOTIFY);
  telemChr_->addDescriptor(new BLE2902());

  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(DEVICE_SERVICE_UUID);
#if ADVERTISE_ARGS
  adv->setScanResponse(true);
#endif
  BLEDevice::startAdvertising();
  Serial.printf("[rover] BLE advertising as '%s' svc=%s\n",
                DEVICE_NAME, DEVICE_SERVICE_UUID);

  // Исполнительные пины
  pinMode(PIN_LIGHT, OUTPUT);
  digitalWrite(PIN_LIGHT, LOW);

  for (int i = 0; i < MINE_COUNT; i++) {
    pinMode(MINE_PINS[i], OUTPUT);
    digitalWrite(MINE_PINS[i], LOW);
  }

  // Тайминги
  lastCmdMs_ = millis();
  lastTelemMs_ = millis();

  Serial.println("[rover] ready");
}

// ------------------------------ ОБНОВЛЕНИЕ -------------------------------

void Rover::update() {
  const unsigned long now = millis();

  // Защита: если давно нет команд — стоп (лог только при переходе)
  if (connected_ && (now - lastCmdMs_) > CMD_TIMEOUT_MS) {
    if (!watchdogLogged_) {
      watchdogLogged_ = true;
      Serial.printf("[watchdog] нет команд %lu мс — СТОП\n", (unsigned long)CMD_TIMEOUT_MS);
    }
    stopMotors();
  }

  // Периодически шлём телеметрию и семплируем наклон
  if ((now - lastTelemMs_) >= TELEMETRY_PERIOD_MS) {
    lastTelemMs_ = now;
    sampleTilt();
    sendTelemetry();
  }
}

// ---------------------------- ОБРАБОТКА КОМАНД ---------------------------

static const char* cmdName(uint8_t c) {
  switch (c) {
    case CMD_DRIVE:  return "DRIVE";
    case CMD_STOP:   return "STOP";
    case CMD_LIGHT:  return "LIGHT";
    case CMD_MINE:   return "MINE";
    case CMD_LEG:    return "LEG";
    case CMD_PING:   return "PING";
    case CMD_RESET:  return "RESET";
    default:         return "?";
  }
}

void Rover::handleCmdWrite(BLECharacteristic* c) {
  String val = c->getValue();
  const uint8_t* data = (const uint8_t*)val.c_str();
  const size_t n = val.length();

  // Проверка размера и магии
  if (n < 4 || data[0] != MAGIC_CMD) {
    Serial.println("[cmd] bad packet (len/magic)");
    lastState_ = ACK_UNKNOWN;
    return;
  }

  // Чексумма (XOR всех байт включая последний = 0)
  const uint8_t sum = xsum(data, n);
  if (sum != 0) {
    Serial.printf("[cmd] bad checksum (got 0x%02X)\n", sum);
    lastState_ = ACK_BAD_CHK;
    return;
  }

  const uint8_t cmd = data[2];
  const uint8_t seq = data[1];

  // Дубликат (тот же seq) — просто подтверждаем
  if (seq == lastAckedSeq_ && cmd != CMD_PING) {
    lastState_ = ACK_OK;
    return;
  }

  Serial.printf("[cmd] %-6s seq=%u len=%u\n", cmdName(cmd), seq, (unsigned)n);

  switch (cmd) {
    case CMD_DRIVE: {
      if (n >= 5) {
        int8_t speed = (int8_t)data[3];
        int8_t steer = (int8_t)data[4];
        drive(speed, steer);
      }
      break;
    }
    case CMD_STOP:
      Serial.println("[cmd] STOP — моторы выключены");
      stopMotors();
      break;
    case CMD_LIGHT:
      if (n >= 4) setLight(data[3] != 0);
      break;
    case CMD_MINE:
      if (n >= 4) triggerMine(data[3]);
      break;
    case CMD_LEG:
      if (n >= 5) moveLeg(data[3], (int8_t)data[4]);
      break;
    case CMD_PING:
      Serial.println("[cmd] PING");
      break;
    case CMD_RESET:
      Serial.println("[cmd] RESET — перезагрузка...");
      ESP.restart();
      break;
    default:
      lastState_ = ACK_UNKNOWN;
      break;
  }

  lastAckedSeq_ = seq;
  lastState_ = ACK_OK;
}

// ------------------------------ УПРАВЛЕНИЕ -------------------------------

void Rover::drive(int8_t speed, int8_t steer) {
  if (tilted_) {  // защита от падения
    stopMotors();
    return;
  }
  lastCmdMs_ = millis();
  watchdogLogged_ = false;

  // -100..100 → -1..1
  float s = speed / 100.0f;
  float st = steer / 100.0f;

  float l, r;
  diffDrive(s, st, l, r);

  left_.setPower(l);
  right_.setPower(r);

  Serial.printf("[drive] s=%d st=%d -> L=%.2f R=%.2f\n", speed, steer, l, r);
}

void Rover::stopMotors() {
  left_.stop();
  right_.stop();
  lastCmdMs_ = millis();
}

void Rover::setLight(bool on) {
  lightOn_ = on;
  digitalWrite(PIN_LIGHT, on ? HIGH : LOW);
  Serial.printf("[light] %s\n", on ? "ON" : "OFF");
}

void Rover::triggerMine(uint8_t channel) {
  if (channel >= MINE_COUNT) {
    Serial.printf("[mine] bad channel %u\n", channel);
    lastState_ = ACK_UNKNOWN;
    return;
  }
  Serial.printf("[mine] trigger #%u\n", channel);
  digitalWrite(MINE_PINS[channel], HIGH);
  // TODO: подставь свой взрыватель/серво — тут 50мс импульс, потом OFF
  delay(50);
  digitalWrite(MINE_PINS[channel], LOW);
}

void Rover::moveLeg(uint8_t channel, int8_t pos) {
  if (channel >= LEG_COUNT) {
    Serial.printf("[leg] bad channel %u\n", channel);
    lastState_ = ACK_UNKNOWN;
    return;
  }
  // TODO: подставь Серво-библиотеку — например:
  //   #include <ESP32Servo.h>
  //   servo[channel].write(map(constrain(pos,-100,100), -100,100, 0,180));
  Serial.printf("[leg] ch=%u pos=%d\n", channel, pos);
}

// ----------------------------- ТЕЛЕМЕТРИЯ --------------------------------

void Rover::sampleTilt() {
#if HAS_TILT_SENSOR
  // TODO: MPU6050 → tiltDeg_
  //   mpu.getEvent(...); tiltDeg_ = ...
#else
  tiltDeg_ = 0.0f;  // заглушка: нет сенсора → 0
#endif
  tilted_ = (tiltDeg_ >= TILT_LIMIT_DEG);
}

void Rover::buildTelemetry(uint8_t* buf, size_t& len) {
  buf[0] = MAGIC_TELEM;

  uint8_t flags = 0;
  if (connected_)   flags |= 0x01;
  if (tilted_)      flags |= 0x02;
  if (lastState_ == ACK_STOPPED) flags |= 0x04;
  buf[1] = flags;

  // battery (десятые вольта, 0 = неизвестно)
#if HAS_BATTERY
  {
    uint32_t acc = 0;
    for (int i = 0; i < BATTERY_SAMPLES; i++) {
      acc += analogRead(PIN_BATTERY);
      delayMicroseconds(200);
    }
    float volts = (acc / (float)BATTERY_SAMPLES) / 4095.0f * 3.3f * BATTERY_DIVIDER;
    buf[2] = (uint8_t)constrain((int)(volts * 10 + 0.5f), 0, 255);
  }
#else
  buf[2] = 0;
#endif

  buf[3] = (uint8_t)(fabs(left_.power()) * 100);
  buf[4] = (uint8_t)(fabs(right_.power()) * 100);

  // temperature (приблизительно, от МКУ) — заглушка
  buf[5] = (int8_t)temperatureRead();

  buf[6] = lastAckedSeq_;
  buf[7] = lastState_;

  buf[8] = (uint8_t)(tiltDeg_ * 10);  // tenths of degree

  // byte 9: leg positions пакет из 4bit+4bit (заглушка)
  buf[9] = 0;

  len = 10;
}

void Rover::sendTelemetry() {
  if (!connected_ || !telemChr_) return;
  uint8_t buf[10];
  size_t len = 0;
  buildTelemetry(buf, len);
  telemChr_->setValue(buf, len);
  telemChr_->notify();
}

// ------------------------ ОБРАБОТКА ПОДКЛЮЧЕНИЯ -------------------------

void Rover::onConnected() {
  connected_ = true;
  lastCmdMs_ = millis();
  Serial.println("[ble] connected");
  BLEDevice::getAdvertising()->stop();
}

void Rover::onDisconnected() {
  connected_ = false;
  stopMotors();
  Serial.println("[ble] disconnected");
  // Автоподключение: снова рекламируемся
  BLEDevice::startAdvertising();
}

// -------------------------- ДИФФЕРЕНЦИАЛЬНЫЙ -----------------------------

void Rover::diffDrive(float speed, float steer, float& left, float& right) {
  // Классический танковый микс:
  //   left  = speed + steer
  //   right = speed - steer
  left  = constrain(speed + steer, -1.0f, 1.0f);
  right = constrain(speed - steer, -1.0f, 1.0f);

  // Плавный разгон (реализуется в MotorStub.setPower через ramp — см. TODO)
  // TODO: если захочешь ramp, добавь в MotorStub:
  //   power_ += constrain(p - power_, -MOTOR_RAMP_STEP, MOTOR_RAMP_STEP);
}