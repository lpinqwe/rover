#pragma once

#include <Arduino.h>

/*
 * Абстракция драйвера моторов.
 *
 * Сейчас — заглушка (MotorStub): только логируется, пины не дёргаются.
 * Когда решишь, какой L298N/TB6612/BTS — реализуй `MotorHardware` ниже
 * и переключи в `rover.ino`:  new MotorHardware(...)
 *
 * Интерфейс один:  setPower(-1..1), stop()
 */

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
    // TODO: подставь свой драйвер:
    //   digitalWrite(pinA_, p >= 0 ? HIGH : LOW);
    //   digitalWrite(pinB_, p >= 0 ? LOW : HIGH);
    //   analogWrite(pinPWM_, (uint8_t)(fabs(p) * 255));
    Serial.printf("[motor:%s] p=%.2f\n", name_, p);
  }

  void stop() override { setPower(0); }

  float power() const { return power_; }

 private:
  const char* name_;
  int pinA_, pinB_, pinPWM_;
  float power_;
};