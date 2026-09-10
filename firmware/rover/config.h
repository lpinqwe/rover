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
#define CMD_TIMEOUT_MS       1000    // стоп моторами, если нет команд дольше этого
#define TELEMETRY_PERIOD_MS  500     // период отправки телеметрии
#define ACK_PERIOD_MS        200     // период подтверждения принятых команд
#define ADVERTISE_ARGS       true    // setScanResponse(true) при рекламе

// --- Драйвер моторов ---
//   0 = заглушка (только лог, пины не трогаются)
//   1 = L298N/TB6612 (IN1/IN2 + ШИМ EN) — 3 пина на мотор
//   2 = Pololu MD12A (MC33926, ШИМ+DIR на канал) — 2 пина на мотор
#define MOTOR_DRIVER_TYPE 2
#define PWM_FREQ          5000   // Hz (для MC33926 норм, обычно 20-25кГц неслышно)
#define PWM_RES_BITS      8      // разрешение ШИМ (0..255)

#if MOTOR_DRIVER_TYPE == 2
// MD12A: ШИМ + направление на каждый мотор (EN на плате уже включён)
#  define PIN_MOTOR_L_PWM  4
#  define PIN_MOTOR_L_DIR  5
#  define PIN_MOTOR_R_PWM  6
#  define PIN_MOTOR_R_DIR  7
// 0 = "вперёд" = HIGH на DIR; 1 = инвертировать (если мотор крутит назад)
#  define MD12A_INVERT_L   0
#  define MD12A_INVERT_R   0
#else
// L298N/TB6612 (и заглушка): левый = IN1/IN2(+ШИМ), правый = IN1/IN2(+ШИМ)
#  define PIN_MOTOR_L_IN1  4
#  define PIN_MOTOR_L_IN2  5
#  define PIN_MOTOR_L_PWM  6
#  define PIN_MOTOR_R_IN1  7
#  define PIN_MOTOR_R_IN2  8
#  define PIN_MOTOR_R_PWM  9
#endif

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

// --- Батарея (измерение по АЦП через делитель на GPIO3) ---
// HAS_BATTERY=1: средняя точка делителя R1(к "+")—R2(к GND) → PIN_BATTERY.
// BATTERY_DIVIDER = (R1+R2)/R2. Пример 3S LiPo (12.6В): R1=100K, R2=22K → ≈5.545.
// Если батарея пока не подключена — поставь HAS_BATTERY 0 (телеметрия вернёт 0В).
#define HAS_BATTERY      1
#define PIN_BATTERY      3        // ADC1 GPIO3
#define BATTERY_DIVIDER  5.545f   // подгони под свой делитель
#define BATTERY_SAMPLES  8        // усреднение замеров
