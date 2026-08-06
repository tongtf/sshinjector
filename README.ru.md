# SSHInjector

<div align="center">

**🌐 Языки:** [中文](README.md) · [English](README.en.md) · [Русский](README.ru.md)

</div>

<p align="center">
  <img src="docs/images/icon.png" width="128" height="128" alt="SSHInjector">
</p>

<div align="center">
  <img src="https://img.shields.io/badge/Android-14%2B-green.svg?style=flat-square&logo=android" alt="Android 14+">
  <img src="https://img.shields.io/badge/Kotlin-1.9.24-blue.svg?style=flat-square&logo=kotlin" alt="Kotlin 1.9.24">
  <img src="https://img.shields.io/badge/Compose-Material3-orange.svg?style=flat-square&logo=jetpackcompose" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/SSH-ECDSA-red.svg?style=flat-square" alt="SSH ECDSA">
</div>

<div align="center">
  <h3>🔐 SSH SOCKS5 прокси-приложение для Android 14+</h3>
  <p>Зашифрованный туннель через динамическое перенаправление портов SSH (<code>ssh -D</code>) с использованием VpnService; через прокси идут только выбранные приложения.</p>
</div>

---

## ✨ Возможности

| Функция | Описание |
|---------|----------|
| **SSH-аутентификация** | ECDSA P-256 хранится в аппаратном Android Keystore, биометрическая разблокировка, генерация/импорт/экспорт в приложении |
| **SOCKS5-прокси** | Полная реализация RFC 1928: TCP CONNECT + UDP ASSOCIATE, IPv4/IPv6/домены |
| **Режим белого списка** | `VpnService.addAllowedApplication()` — через прокси идут только выбранные приложения |
| **Двойной стек** | IPv4 + IPv6 с адресами обоих стеков на интерфейсе TUN |
| **Удалённый DNS** | Перехватывает UDP:53 и отправляет через SSH-туннель на удалённый резолвер, предотвращая утечки |
| **Поддержка HTTP/3** | Трафик QUIC через SOCKS5 UDP ASSOCIATE |
| **Живая статистика** | Скорость загрузки/отдачи, общий трафик, время сессии, рейтинг по приложениям |
| **Автопереподключение** | Бесшовное переподключение при смене сети (WiFi↔5G) с экспоненциальной задержкой, запуск при загрузке |
| **UI Material 3** | Управление серверами, выбор белого списка, панель, настройки, управление ключами |

---

## 🏗 Архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                      Android 14+                            │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Compose)                                         │
│  ├── DashboardScreen    ← трафик/статус/действия            │
│  ├── ServerListScreen   ← серверы/тест/ключи                │
│  ├── WhitelistScreen    ← список приложений/группы/пресеты  │
│  ├── SettingsScreen     ← MTU/keepalive/DNS/IPv6/тема       │
│  └── KeyManagerScreen   ← генерация ECDSA P-256/импорт/экспорт  │
├─────────────────────────────────────────────────────────────┤
│  Domain Layer (UseCases)                                    │
│  ├── VpnController       ← жизненный цикл VPN/состояние     │
│  ├── ServerRepository    ← серверы/списки/сессии            │
│  └── KeyManager          ← генерация/импорт/подпись/экспорт │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│  ├── Room Database       ← Server/Whitelist/Session/Traffic │
│  ├── DataStore           ← настройки/карта алиасов Keystore │
│  ├── SshKeyManager       ← Android Keystore (аппаратный)    │
│  ├── JschSshClient       ← SSH-подключение/туннель/keepalive│
│  ├── Socks5ProxyServer   ← RFC 1928 (NIO Selector)          │
│  ├── PacketProcessor     ← разбор IP/TCP/UDP/пересылка      │
│  └── DnsInterceptor      ← перехват DNS/удалённое разрешение│
├─────────────────────────────────────────────────────────────┤
│  System Layer                                               │
│  ├── SshVpnService       ← VpnService (TUN/белый список)    │
│  └── BootReceiver        ← запуск при загрузке/сеть         │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 Технологический стек

| Категория | Технология |
|-----------|------------|
| Язык/UI | Kotlin 1.9.24 + Jetpack Compose (Material 3) |
| Архитектура | MVVM + Clean Architecture + Repository |
| DI | Hilt (KSP) |
| База данных | Room 2.6.1 (KSP) |
| Настройки | DataStore Preferences (RxJava3 Flow) |
| SSH-клиент | mwiede/jsch:0.2.14 (активный форк, поддержка ECDSA P-256) |
| DNS | dnsjava 3.5.7 |
| Сеть I/O | Java NIO Selector + Kotlinx Coroutines |
| Keystore | Android Keystore (аппаратный) + BiometricPrompt |
| Coroutines | Kotlinx Coroutines 1.8.1 + Flow |
| Навигация | Navigation Compose 2.7.7 |
| Изображения | Coil 2.6.0 |

---

## 🚀 Быстрый старт

### Требования

- Android 14 (API 34) или новее
- Сервер с **OpenSSH** (рекомендуется; для автоматической настройки нужны root или sudo)
- sshd с `AllowTcpForwarding yes` (по умолчанию)

### Настройка сервера

**Вариант A: автоматическая настройка в приложении (рекомендуется)**

В мастере «Добавить сервер» укажите учётную запись с правами **root или sudo**, и приложение сделает всё остальное:

- Создаёт выделенный туннельный аккаунт `sshproxy`: `nologin` + заблокированный пароль, вход в shell невозможен
- Изоляция chroot — доступна только минимальная файловая система (`ChrootDirectory`)
- Только аутентификация по ключам (`PasswordAuthentication no`), `authorized_keys` заблокирован через `chattr +i`
- Добавляет блок `Match User sshproxy` в sshd; делает бэкап и перезагружает конфиг только после успешного `sshd -t`

**Вариант B: ручная настройка**

Следуйте **[docs/server-setup.md](docs/server-setup.md)** для ручного создания аккаунта `sshproxy`, каталога chroot и усиления sshd.

### Сборка

```bash
# Требуется JDK 17 (JDK 25 по умолчанию не собирает проект)
export JAVA_HOME=/path/to/jdk-17

# Клонирование
git clone https://github.com/tongtf/sshinjector.git
cd sshinjector

# Debug APK
./gradlew assembleDebug

# Release AAB
./gradlew bundleRelease
```

---

## 📖 Использование

> Полное руководство: **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)** (установка, настройка, устранение неполадок, FAQ)

### 1. Добавление сервера

1. Нажмите **+**, чтобы добавить сервер
2. Заполните: имя, хост, порт (по умолчанию 22), пользователя
3. Нажмите **Создать ключ** для генерации пары ECDSA P-256 (требуется биометрия)
4. Скопируйте открытый ключ → вставьте в `~/.ssh/authorized_keys` на VPS
5. Нажмите **Проверить соединение**

### 2. Настройка белого списка

1. Откройте страницу **Белый список**
2. Найдите/просмотрите установленные приложения
3. Отметьте приложения для проксирования (только отмеченные перехватываются)
4. Пресеты: только браузер / соцсети / свой

### 3. Подключение

1. Нажмите большую кнопку **Подключить**
2. Разрешите доступ к VPN при запросе
3. После успеха: локальный IP / удалённый IP / график трафика
4. Постоянный статус в уведомлении; поддержка отключения/паузы

### 4. Расширенные настройки

| Настройка | Рекомендация | Описание |
|-----------|--------------|----------|
| MTU | 1500 | Настройте под свою сеть; меньшее MTU может устранить задержки |
| Keepalive | 30s | SSH-сердцебиение, предотвращает разрывы на сервере |
| Режим DNS | Удалённый | Предотвращает утечки DNS; сервер должен достигать 8.8.8.8 |
| IPv6 | Вкл | Двойной стек, доступ к IPv6-сайтам |
| Биометрия | Вкл | Защищает закрытый ключ |

---

## 🔒 Модель безопасности

```
Закрытый ключ → Android Keystore (TEE/StrongBox) → аппаратная изоляция
     ↓
Подпись ключа → BiometricPrompt (отпечаток/лицо/пароль) → согласие
     ↓
SSH-авторизация → JSch Identity → подпись Keystore
     ↓
Открытый ключ → формат OpenSSH → ручное развёртывание на сервере
```

**Ключевые моменты:**
- Закрытый ключ **никогда не покидает** Keystore и не может быть экспортирован
- TOFU при первом подключении (Trust On First Use) + кэш отпечатков ключей
- Проверка ключа хоста через `StrictHostKeyChecking=ask` и known_hosts
- Тип фонового сервиса `FOREGROUND_SERVICE_SPECIAL_USE`

---

## 📂 Структура проекта

```
SSHInjector/
├── app/
│   ├── src/main/
│   │   ├── java/cn/srv0/sshinjector/
│   │   │   ├── data/
│   │   │   │   ├── local/          # Room + DataStore
│   │   │   │   ├── remote/ssh/     # Обёртка JSch + Keystore
│   │   │   │   └── repository/     # Реализации репозиториев
│   │   │   ├── domain/
│   │   │   │   ├── model/          # Доменные модели
│   │   │   │   ├── usecase/        # VpnController/Repository
│   │   │   │   └── vpn/            # Основные VPN-компоненты
│   │   │   ├── di/                 # Hilt Modules/EntryPoints
│   │   │   ├── ui/                 # Экраны Compose
│   │   │   └── vpn/                # SshVpnService + BootReceiver
│   │   ├── res/                    # Ресурсы
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── docs/
│   └── server-setup.md             # Руководство по настройке сервера
├── .github/workflows/ci.yml        # CI/CD
├── detekt.yml                      # Правила стиля кода
├── build.gradle.kts / settings.gradle.kts
└── README.md
```

---

## 🧪 Тестирование

```bash
# Модульные тесты
./gradlew testDebugUnitTest

# Проверки качества кода
./gradlew ktlintCheck detekt lint
```

---

## 📦 Чек-лист релиза

- [ ] Обновить версию (`versionCode` / `versionName`)
- [ ] Обновить `CHANGELOG.md`
- [ ] Запустить полный конвейер CI
- [ ] Опубликовать APK/AAB в GitHub Releases / Play Console
- [ ] Ссылка на политику конфиденциальности (требуется Play Console)

---

## 🤝 Вклад

1. Сделайте форк репозитория
2. Создайте ветку: `git checkout -b feature/amazing-feature`
3. Внесите изменения: `git commit -m 'Add amazing feature'`
4. Отправьте: `git push origin feature/amazing-feature`
5. Откройте Pull Request

### Стиль кода

- Следуйте [соглашениям по коду Kotlin](https://kotlinlang.org/docs/coding-conventions.html)
- `ktlint` + `detekt` + `Android Lint` должны проходить
- Conventional Commits

---

## 📄 Лицензия

```
MIT License

Copyright (c) 2024 SSHInjector Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 🙏 Благодарности

- [mwiede/jsch](https://github.com/mwiede/jsch) — активно поддерживаемый форк JSch с поддержкой ECDSA P-256
- [dnsjava](https://github.com/dnsjava/dnsjava) — библиотека DNS-протокола для Java
- [Android Networking Samples](https://github.com/android/networking-samples) — пример VpnService
- [SocksDroid](https://github.com/6b6b6b/socksdroid) — пример SOCKS5-прокси

---

## ⚠️ Отказ от ответственности

Этот проект предназначен только для обучения, исследований и защиты личной конфиденциальности. Пользователи несут ответственность за соблюдение местных законов и нормативных актов. Авторы не несут ответственности за любые последствия использования данного программного обеспечения.

---

<div align="center">
  <sub>Сделано с ❤️ для Android 14+ | <a href="https://github.com/tongtf/sshinjector">GitHub</a> | <a href="https://github.com/tongtf/sshinjector/issues">Issues</a></sub>
</div>
