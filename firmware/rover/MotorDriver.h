#pragma once

#include <Arduino.h>

/*
 * Абстракция драйвера моторов.
 *
 * Доступны три реализации (выбор в config.h: MOTOR_DRIVER_TYPE):
 *   0 - MotorStub   — заглушка: только лог, пины не дёргаются
 *   1 - MotorL298N  — L298N/TB6612: IN1/IN2 + ШИМ на EN
 *   2 - MotorPwmDir — Pololu MD12A (MC33926): ШИМ + DIR на каждый мотор
 *
 * Интерфейс один:  setPower(-1..1), stop()
 * Работает и на ESP32 core 2.x, и на 3.x — LEDC API выбирается сам.
 */

// LEDC: в core 2.x это ledcSetup/ledcAttachPin/ledcWrite(chan),
//       в core 3.x — ledcAttach/ledcWrite(pin).
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  #define LEDC_BEGIN(pin, ch)        ledcAttach(pin, PWM_FREQ, PWM_RES_BITS)
  #define LEDC_SET_POWER(pin, ch, d) ledcWrite(pin, (d))
#else
  #define LEDC_BEGIN(pin, ch)        ledcSetup(ch, PWM_FREQ, PWM_RES_BITS); ledcAttachPin(pin, ch)
  #define LEDC_SET_POWER(pin, ch, d) ledcWrite(ch, (d))
#endif

class MotorChannel {
 public:
  virtual ~MotorChannel() {}
  virtual void setPower(float p) = 0;  // -1..1
  virtual void stop() = 0;
};

// --- Заглушка: лог в Serial, ничего не трогает ---
class MotorStub : public MotorChannel {
 public:
  MotorStub(const char* name, int pinA, int pinB, int pinPWM)
      : name_(name), pinA_(pinA), pinB_(pinB), pinPWM_(pinPWM), power_(0) {
    pinMode(pinA_, OUTPUT);
    pinMode(pinB_, OUTPUT);
    pinMode(pinPWM_, OUTPUT);
  }

  void setPower(float p) override {
    p = constrain(p, -1.0f, 1.0f);
    power_ = p;
    Serial.printf("[motor:%s] p=%.2f\n", name_, p);
  }

  void stop() override { setPower(0); }

  float power() const { return power_; }

 private:
  const char* name_;
  int pinA_, pinB_, pinPWM_;
  float power_;
};

// --- L298N / TB6612 (IN1/IN2 + ШИМ на EN) ---
// Включается в config.h:  #define MOTOR_DRIVER_TYPE 1
class MotorL298N : public MotorChannel {
 public:
  MotorL298N(const char* name, int pinA, int pinB, int pinPWM, int ledcChannel = 0)
      : name_(name), pinA_(pinA), pinB_(pinB), pinPWM_(pinPWM), channel_(ledcChannel), power_(0) {
    pinMode(pinA_, OUTPUT);
    pinMode(pinB_, OUTPUT);
    pinMode(pinPWM_, OUTPUT);
    LEDC_BEGIN(pinPWM_, channel_);
  }

  void setPower(float p) override {
    p = constrain(p, -1.0f, 1.0f);
    power_ = p;
    digitalWrite(pinA_, p > 0.01f ? HIGH : LOW);
    digitalWrite(pinB_, p < -0.01f ? HIGH : LOW);
    LEDC_SET_POWER(pinPWM_, channel_, (uint32_t)(fabs(p) * ((1 << PWM_RES_BITS) - 1)));
  }

  void stop() override { setPower(0); }

  float power() const { return power_; }

 private:
  const char* name_;
  int pinA_, pinB_, pinPWM_;
  int channel_;
  float power_;
};

// --- Pololu MD12A (двойной H-мост MC33926): ШИМ + DIR на канал ---
// Включается в config.h:  #define MOTOR_DRIVER_TYPE 2
class MotorPwmDir : public MotorChannel {
 public:
  MotorPwmDir(const char* name, int pinPWM, int pinDIR, bool invert, int ledcChannel = 0)
      : name_(name), pinPWM_(pinPWM), pinDIR_(pinDIR), invert_(invert), channel_(ledcChannel), power_(0) {
    pinMode(pinPWM_, OUTPUT);
    pinMode(pinDIR_, OUTPUT);
    LEDC_BEGIN(pinPWM_, channel_);
  }

  void setPower(float p) override {
    p = constrain(p, -1.0f, 1.0f);
    power_ = p;
    // p>=0 → "вперёд"; invert_=1 переворачивает полярность DIR
    digitalWrite(pinDIR_, (p >= 0) != invert_ ? HIGH : LOW);
    LEDC_SET_POWER(pinPWM_, channel_, (uint32_t)(fabs(p) * ((1 << PWM_RES_BITS) - 1)));
  }

  void stop() override { setPower(0); }

  float power() const { return power_; }

 private:
  const char* name_;
  int pinPWM_, pinDIR_;
  int channel_;
  bool invert_;
  float power_;
};

// --- Arduino + L298N через UART (MOTOR_DRIVER_TYPE = 3) ---
// ESP32 не может дать нужный вольтаж на L298N напрямую, поэтому моторами
// рулит Arduino (см. firmware/arduino_l298n/motor_controller.ino), а ESP32
// шлёт ему по UART текстовые команды:
//   F/B/L/R/S  — вперёд/назад/поворот/стоп (оба мотора)
//   1..4       — одиночный мотор вперёд/назад
//   V<0..255>  — скорость
//
// Ровер не знает про UART: он зовёт setPower(-1..1) на каждый мотор, а общий
// мост переводит пару (левая, правая) в одну текстовую команду. Логика
// остальных узлов и телеметрия не меняются.
class ArduinoBridge {
 public:
  ArduinoBridge() = default;

  // Идемпотентная инициализация: вызывается из обоих моторов при старте.
  void begin() {
    if (txInit_) return;
    txInit_ = true;
    Serial1.begin(ESP_UART_BAUD, SERIAL_8N1, PIN_ESP_UART_RX, PIN_ESP_UART_TX);
  }

  // Обновить мощность одной стороны (-1..1) и согласовать Arduino.
  void setSide(bool isLeft, float p) {
    begin();
    if (isLeft) left_ = p; else right_ = p;
    sync();
  }

  float power(bool isLeft) const { return isLeft ? left_ : right_; }

 private:
  void send(const char* s) {
    if (strcmp(lastCmd_, s) == 0) return;  // дедуп одинаковых команд
    strncpy(lastCmd_, s, sizeof(lastCmd_) - 1);
    lastCmd_[sizeof(lastCmd_) - 1] = '\0';
    Serial.printf("[uart>] %s\n", s);
    Serial1.print(s);
    Serial1.print('\n');
    Serial1.flush();
  }

  void sync() {
    const float eps = 0.02f;
    const float lm = fabs(left_);
    const float rm = fabs(right_);

    // Оба стоят -> одна S (и сбрасываем скорость, чтобы потом всё прислать заново)
    if (lm < eps && rm < eps) {
      sentSpeed_ = -1;
      send("S");
      return;
    }

    // Общая скорость = максимум из двух (у Arduino глобальный speedPWM)
    const int sp = constrain((int)(max(lm, rm) * 255.0f), 0, 255);
    if (sp != sentSpeed_) {
      char buf[12];
      snprintf(buf, sizeof(buf), "V%d", sp);
      send(buf);
      sentSpeed_ = sp;
    }

    const bool lF = left_ >= 0;    // левый вперёд
    const bool rF = right_ >= 0;   // правый вперёд

    if (lm > eps && rm > eps) {
      if (lF && rF)                    send("F");
      else if (!lF && !rF)             send("B");
      else if (!lF)                    send("L");  // левый назад, правый вперёд
      else                             send("R");  // левый вперёд, правый назад
    } else if (lm > eps) {
      send(lF ? "1" : "2");            // только левый
    } else {
      send(rF ? "3" : "4");            // только правый
    }
  }

  float left_ = 0.0f, right_ = 0.0f;
  char lastCmd_[8] = { 0 };
  int sentSpeed_ = -1;
  bool txInit_ = false;
};

// Драйвер-обёртка над ArduinoBridge, реализует общий интерфейс MotorChannel.
class MotorArduinoUart : public MotorChannel {
 public:
  MotorArduinoUart(ArduinoBridge& bridge, bool isLeft)
      : bridge_(bridge), isLeft_(isLeft), power_(0) {
    bridge_.begin();
  }

  void setPower(float p) override {
    p = constrain(p, -1.0f, 1.0f);
    power_ = p;
    bridge_.setSide(isLeft_, p);
  }

  void stop() override { setPower(0); }

  float power() const { return power_; }

 private:
  ArduinoBridge& bridge_;
  bool isLeft_;
  float power_;
};