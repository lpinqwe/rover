#pragma once

/*
 * Максимально универсальная конфигурация под ESP32-S3.
 * При необходимости поменяй пины под свою разводку.
 */

// --- Идентификация устройства ---
#define DEVICE_NAME            "ROVER-S3"
#define DEVICE_SERVICE_UUID    "12345678-1234-5678-1234-56789abcdef0"
#define DEVICE_CHAR_CMD_UUID   "12345678-1234-5678-1234-56789abcdef1"
#define DEVICE_CHAR_TELEM_UUID "12345678-1234-5678-1234-56789abcdef2"

// --- Служебные интервалы (мс) ---
#define CMD_TIMEOUT_MS       300     // стоп моторами, если нет команд дольше этого
#define TELEMETRY_PERIOD_MS  500     // период отправки телеметрии
#define ACK_PERIOD_MS        200     // период подтверждения принятых команд
#define ADVERTISE_ARGS       true    // setScanResponse(true) при рекламе

// --- Моторы: левый = пины IN1/IN2 (+ ШИМ EN) ---
// Заглушка: если не определено — используется MotorStub (лог вместо железа).
#define PIN_MOTOR_L_IN1   4
#define PIN_MOTOR_L_IN2   5
#define PIN_MOTOR_L_PWM   6
#define PIN_MOTOR_R_IN1   7
#define PIN_MOTOR_R_IN2   8
#define PIN_MOTOR_R_PWM   9

// --- Фонарик (вкл/выкл) ---
#define PIN_LIGHT         10

// --- Мины (канал 1..N) ---
#define MINE_COUNT        3
static const int MINE_PINS[MINE_COUNT] = { 11, 12, 13 };

// --- Ноги (опционально, заглушка): кол-во каналов и серво-пины ---
#define LEG_COUNT         2
static const int LEG_PINS[LEG_COUNT] = { 14, 15 };

// --- Защита от падения (гиро). Если датчик не подключён — оставь 0 ---
#define HAS_TILT_SENSOR   0     // 1 = есть MPU6050/лист; 0 = только программная заглушка
#define TILT_LIMIT_DEG    45.0f // угол, при котором режем моторы

// --- Гистерезис и защита ---
#define MOTOR_RAMP_STEP   0.05f // прирост скорости за тик (плавный разгон)
#define MIN_PWM           0.0f
#define MAX_PWM           1.0f
