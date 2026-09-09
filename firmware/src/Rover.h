#pragma once

#include <Arduino.h>
#include <BLE2902.h>
#include <BLECharacteristic.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>

#include "config.h"
#include "proto.h"
#include "MotorDriver.h"

/*
 * Rover — главный класс логики.
 * Объединяет BLE-сервер, парсер команд, телеметрию и исполнительные узлы
 * (моторы, фонарик, мины, ноги, защита от падения).
 */

// --- Колбэки BLE (статичные, т.к. BLEServer требует free-функции) ---
class RoverServerCallbacks : public BLEServerCallbacks {
 public:
  explicit RoverServerCallbacks(class Rover* r) : r_(r) {}
  void onConnect(BLEServer*) override;
  void onDisconnect(BLEServer*) override;
 private:
  class Rover* r_;
};

class RoverCmdCallbacks : public BLECharacteristicCallbacks {
 public:
  explicit RoverCmdCallbacks(class Rover* r) : r_(r) {}
  void onWrite(BLECharacteristic* c) override;
 private:
  class Rover* r_;
};

class Rover {

 public:
  Rover() = default;

  void begin();
  void update();

  // Обработчики событий BLE
  void onConnected();
  void onDisconnected();
  void handleCmdWrite(BLECharacteristic* c);

  // Исполнительные узлы
  void drive(int8_t speed, int8_t steer);
  void stopMotors();
  void setLight(bool on);
  void triggerMine(uint8_t channel);
  void moveLeg(uint8_t channel, int8_t pos);

  bool tilted() const { return tilted_; }

 private:
  void sendTelemetry();
  void buildTelemetry(uint8_t* buf, size_t& len);

  // BLE
  BLEServer* server_ = nullptr;
  BLECharacteristic* cmdChr_ = nullptr;
  BLECharacteristic* telemChr_ = nullptr;
  bool connected_ = false;

  // Cчётчики
  uint8_t lastAckedSeq_ = 0xFF;  // 0xFF = "нет команд"
  uint8_t lastState_ = ACK_OK;

  // Моторы (2 шт, дифф. руление)
  MotorStub left_  = MotorStub("L", PIN_MOTOR_L_IN1, PIN_MOTOR_L_IN2, PIN_MOTOR_L_PWM);
  MotorStub right_ = MotorStub("R", PIN_MOTOR_R_IN1, PIN_MOTOR_R_IN2, PIN_MOTOR_R_PWM);

  // Исполнительные пины
  bool lightOn_ = false;

  // Тайминги
  unsigned long lastCmdMs_ = 0;
  unsigned long lastTelemMs_ = 0;

  // Падение
  bool tilted_ = false;
  float tiltDeg_ = 0.0f;
  unsigned long tiltSampleMs_ = 0;
  void sampleTilt();  // заглушка: обновляет tiltDeg_ (нет сенсора)

  // Дифференциальное преобразование
  void diffDrive(float speed, float steer, float& left, float& right);
};