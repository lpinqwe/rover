/**
 * Ровер-пульт через Telegram (альтернатива ПК-консоли).
 *
 * Может работать на ЛЮБОМ токене бота, в т.ч. на том же,
 * через который получаешь APK. Чат, куда пишешь, и есть пульт.
 *
 * Мост: Telegram <-> MQTT <-> Телефон-шлюз <-> BLE <-> ESP32.
 *
 * Запуск:
 *   BOT_TOKEN=1234:... ROVER_CHAT_ID=663450648 npm start
 *   (ROVER_CHAT_ID опционален — без него бот отзывается в любом чате)
 *
 * Команды боту:
 *   /start   — меню с кнопками
 *   /status  — текущее состояние (GPS, батарея, наклон)
 */

import TelegramBot from "node-telegram-bot-api";
import mqtt from "mqtt";
import dotenv from "dotenv";

dotenv.config();

const TOKEN = process.env.BOT_TOKEN;
if (!TOKEN) {
  console.error("Задай BOT_TOKEN в env (.env) и перезапусти.");
  process.exit(1);
}

const ALLOWED_CHAT = process.env.ROVER_CHAT_ID;
const MOVES = {
  fw: { speed: 1.0, steer: 0.0 },
  bw: { speed: -1.0, steer: 0.0 },
  lt: { speed: 0.0, steer: 1.0 },
  rt: { speed: 0.0, steer: -1.0 },
};
const brokerUrl = process.env.BROKER_URL || "wss://ed44fbaa0a7a41afaf940381fb18cd2a.s1.eu.hivemq.cloud:8884/mqtt";
const brokerUser = process.env.MQTT_USER || "roverCred";
const brokerPass = process.env.MQTT_PASS || "mqttHIVE!2#";

const bot = new TelegramBot(TOKEN, { polling: true });

// --- MQTT ---
const mq = mqtt.connect(brokerUrl, brokerUser
  ? { username: brokerUser, password: brokerPass }
  : {});
const TOPIC = (s) => `rover/${process.env.ROVER_ID || "demo"}/${s}`;
const pub = (topic, obj) => mq.publish(topic, JSON.stringify(obj), { qos: 0 });

let lastTelemetry = {};
let lastSensors = {};

mq.on("connect", () => {
  console.log("mqtt ok");
  mq.subscribe([TOPIC("esptelemetry"), TOPIC("sensors"), TOPIC("status")]);
});
mq.on("message", (t, p) => {
  const j = JSON.parse(p.toString());
  if (t.endsWith("/esptelemetry")) lastTelemetry = j;
  else if (t.endsWith("/sensors")) lastSensors = j;
});

// --- Telegram ---
const keyboard = (keepBtns) => ({
  reply_markup: {
    keyboard: [
      [{ text: "fw ⬆" }, { text: "lt ⬅" }, { text: "rt ➡" }, { text: "bw ⬇" }],
      [{ text: "Стоп ⏹" }, { text: "Свет 🔦" }, { text: "Пинг" }],
      [{ text: "Мина 1" }, { text: "Мина 2" }, { text: "Мина 3" }],
      [{ text: "/status" }],
    ],
    resize_keyboard: true,
  },
});

bot.on("message", async (msg) => {
  const chatId = msg.chat.id;
  const text = (msg.text || "").trim();

  // Если задан ROVER_CHAT_ID — бот управляет ровером только из этого чата
  if (ALLOWED_CHAT && String(chatId) !== String(ALLOWED_CHAT)) {
    return;
  }

  if (!text) return;
  const cmd = text.toLowerCase();

  if (cmd.startsWith("/start")) {
    await bot.sendMessage(
      chatId,
      "Ровер-пульт. Кнопки: стрелки — движение, Стоп, Свет, Мины.\nДвижение удерживай коротко — между нажатиями робот едет, пока не отдашь /s или другой манёвр.",
      keyboard(),
    );
    return;
  }
  if (cmd.startsWith("/status") || cmd === "status") return replyStatus(chatId);

  publisher(chatId, cmd);
});

// Отправляем команду в MQTT. Короткие рывки на стрелках-кнопках.
async function publisher(chatId, cmd) {
  const move = MOVES[cmd];
  if (move) {
    pub(TOPIC("cmd"), { speed: move.speed, steer: move.steer });
    // Телефон-шлюз сам решает (BLE скорость/рамп). Бот шлёт однократно
    // — робот едет до следующей команды. Для рывка шлём стоп через 800мс.
    setTimeout(() => pub(TOPIC("cmd"), { speed: 0, steer: 0 }), 800);
    await bot.sendMessage(chatId, `Двигаюсь ${cmd}...`);
    return;
  }
  if (cmd === "стоп") { pub(TOPIC("action"), { type: "stop" }); return replyOk(chatId, "Стоп"); }
  if (cmd === "свет") {
    // toggle неизвестен — спросим состояние. Простейший: вкл/выкл.
    const key = keyboard();
    await bot.sendMessage(chatId, "Выбери:", {
      reply_markup: {
        inline_keyboard: [
          [{ text: "ВКЛ", callback_data: "light:1" }, { text: "ВЫКЛ", callback_data: "light:0" }],
        ],
      },
    });
    return;
  }
  if (cmd.startsWith("мина")) {
    const i = parseInt(cmd.split(" ")[1] || "1", 10) - 1;
    pub(TOPIC("action"), { type: "mine", channel: Math.max(0, i) });
    return replyOk(chatId, `Мина ${i + 1} активирована`);
  }
  if (cmd === "пинг") { pub(TOPIC("action"), { type: "ping" }); return replyOk(chatId, "Пинг"); }
}

bot.on("callback_query", async (q) => {
  const chatId = q.message.chat.id;
  const data = q.data;
  if (data.startsWith("light:")) {
    pub(TOPIC("action"), { type: "light", on: data === "light:1" });
    await bot.answerCallbackQuery(q.id, { text: "Свет: " + (data === "light:1" ? "ВКЛ" : "ВЫКЛ") });
  }
});

function replyStatus(chatId) {
  const t = lastTelemetry, s = lastSensors;
  const txt = [
    "Состояние ровера:",
    `🔌 BLE: ${t.ble_connected ? "подключён" : "нет"} | Тел.: ${s.battery_pct ?? "?"}%`,
    `🔋 Батарея ровера: ${t.battery ?? "?"}V | MCU: ${t.mc_temp_c ?? "?"}°C`,
    `🎚 L=${t.left_pwm ?? "?"}% R=${t.right_pwm ?? "?"}%`,
    `🧭 Наклон: ${t.esp_tilt_deg ?? "?"}° (${t.tilted ? "⚠️ защита" : "ок"})`,
    `📡 GPS: ${s.gps ? s.gps.lat.toFixed(5) + ", " + s.gps.lon.toFixed(5) : "нет"}`,
  ].join("\n");
  bot.sendMessage(chatId, txt, keyboard());
}

function replyOk(chatId, s) {
  bot.sendMessage(chatId, "✓ " + s, keyboard());
}

console.log("bot ready");