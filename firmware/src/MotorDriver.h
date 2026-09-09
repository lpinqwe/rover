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
  MotorL298N(const char* name, int pinA, int pinB, int pinPWM)
      : name_(name), pinA_(pinA), pinB_(pinB), pinPWM_(pinPWM), power_(0) {
    pinMode(pinA_, OUTPUT);
    pinMode(pinB_, OUTPUT);
    ledcAttach(pinPWM_, PWM_FREQ, PWM_RES_BITS);
  }

  void setPower(float p) override {
    p = constrain(p, -1.0f, 1.0f);
    power_ = p;
    digitalWrite(pinA_, p > 0.01f ? HIGH : LOW);
    digitalWrite(pinB_, p < -0.01f ? HIGH : LOW);
    ledcWrite(pinPWM_, (uint32_t)(fabs(p) * ((1 << PWM_RES_BITS) - 1)));
  }

  void stop() override { setPower(0); }

  float power() const { return power_; }

 private:
  const char* name_;
  int pinA_, pinB_, pinPWM_;
  float power_;
};

// --- Pololu MD12A (двойной H-мост MC33926): ШИМ + DIR на канал ---
// Включается в config.h:  #define MOTOR_DRIVER_TYPE 2
class MotorPwmDir : public MotorChannel {
 public:
  MotorPwmDir(const char* name, int pinPWM, int pinDIR, bool invert)
      : name_(name), pinPWM_(pinPWM), pinDIR_(pinDIR), invert_(invert), power_(0) {
    pinMode(pinPWM_, OUTPUT);
    pinMode(pinDIR_, OUTPUT);
    ledcAttach(pinPWM_, PWM_FREQ, PWM_RES_BITS);
  }

  void setPower(float p) override {
    p = constrain(p, -1.0f, 1.0f);
    power_ = p;
    // p>=0 → "вперёд"; invert_=1 переворачивает полярность DIR
    digitalWrite(pinDIR_, (p >= 0) != invert_ ? HIGH : LOW);
    ledcWrite(pinPWM_, (uint32_t)(fabs(p) * ((1 << PWM_RES_BITS) - 1)));
  }

  void stop() override { setPower(0); }

  float power() const { return power_; }

 private:
  const char* name_;
  int pinPWM_, pinDIR_;
  bool invert_;
  float power_;
};