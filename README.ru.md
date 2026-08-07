# SSHInjector

<div align="center">

**🌐 Языки:** [中文](README.md) · [English](README.en.md) · [Русский](README.ru.md)

</div>

<p align="center">
  <img src="docs/images/icon.png" width="128" height="128" alt="SSHInjector">
</p>

<div align="center">
  <img src="https://img.shields.io/badge/Android-14%2B-green.svg?style=flat-square&logo=android" alt="Android 14+">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-blue.svg?style=flat-square&logo=kotlin" alt="Kotlin 2.0.21">
  <img src="https://img.shields.io/badge/Compose-Material3-orange.svg?style=flat-square&logo=jetpackcompose" alt="Jetpack Compose">
  <img src="https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square" alt="MIT License">
  <img src="https://img.shields.io/badge/SSH-ECDSA-red.svg?style=flat-square" alt="SSH ECDSA">
</div>

<div align="center">
  <h3>🔐 SSH SOCKS5 прокси-приложение для Android 14+</h3>
  <p>VpnService захватывает трафик; встроенный SOCKS5-сервер пересылает его через SSH-туннели <code>direct-tcpip</code>; через прокси идут только выбранные приложения.</p>
</div>

---

## ✨ Возможности

| Функция | Описание |
|---------|----------|
| **SSH-аутентификация** | ECDSA P-256/P-384 хранится в Android Keystore, биометрическая разблокировка, генерация/импорт/копирование открытого ключа |
| **SOCKS5-прокси** | Прокси TCP CONNECT с поддержкой IPv4/IPv6/доменов (UDP ASSOCIATE пока не поддерживается) |
| **Режим белого списка** | `VpnService.addAllowedApplication()` — через прокси идут только выбранные приложения |
| **Двойной стек** | IPv4 + IPv6 с адресами обоих стеков на интерфейсе TUN |
| **Удалённый DNS** | Перехватывает UDP:53 и отправляет через SSH-туннель на удалённый резолвер, предотвращая утечки |
| **Статистика соединения** | Статус соединения, трафик, время сессии (реальное время в процессе) |
| **Автопереподключение** | Автопереподключение при смене сети (WiFi↔5G, задержка 2с); линейная задержка на уровне SSH-сессии |
| **UI Material 3** | Панель, управление серверами, белый список, настройки, управление ключами |

---

## 🏗 Архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                      Android 14+                            │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Compose)                                         │
│  ├── DashboardScreen    ← статус/подключение/серверы         │
│  ├── ServerListScreen   ← серверы/мастер настройки           │
│  ├── WhitelistScreen    ← список приложений/выбор            │
│  ├── SettingsScreen     ← режим DNS/язык/биометрия           │
│  └── KeyManagerScreen   ← генерация ECDSA P-256/импорт/копия │
├─────────────────────────────────────────────────────────────┤
│  Domain Layer (UseCases)                                    │
│  ├── VpnController       ← жизненный цикл VPN/состояние      │
│  ├── ServerRepository    ← серверы/белые списки              │
│  └── KeyManager          ← генерация/импорт/подпись/экспорт  │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│  ├── Room Database       ← Server/WhitelistApp               │
│  ├── DataStore           ← настройки/карта алиасов Keystore  │
│  ├── SshKeyManager       ← Android Keystore (не экспорт.)    │
│  ├── JschSshClient       ← SSH-подключение/туннель/keepalive │
│  ├── TunnelManager       ← мост локальный SOCKS5 ↔ SSH       │
│  ├── Socks5ProxyServer   ← SOCKS5 TCP (NIO Selector)         │
│  ├── PacketProcessor     ← разбор IP/TCP/UDP/пересылка       │
│  └── DnsInterceptor      ← перехват DNS/удалённое разрешение │
├─────────────────────────────────────────────────────────────┤
│  System Layer                                               │
│  ├── SshVpnService       ← VpnService (TUN/белый список)     │
│  └── BootReceiver        ← запуск при загрузке               │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 Технологический стек

| Категория | Технология |
|-----------|------------|
| Язык/UI | Kotlin 2.0.21 + Jetpack Compose (Material 3) |
| Архитектура | MVVM + Clean Architecture + Repository |
| DI | Hilt 2.53.1 (KSP) |
| База данных | Room 2.6.1 (KSP) |
| Настройки | DataStore Preferences (Flow) |
| SSH-клиент | mwiede/jsch:0.2.14 (активный форк, поддержка ECDSA P-256) |
| DNS | dnsjava 3.6.5 |
| Сеть I/O | Java NIO Selector + Kotlinx Coroutines |
| Keystore | Android Keystore + BiometricPrompt (ключи не экспортируются) |
| Coroutines | Kotlinx Coroutines 1.9.0 + Flow |
| Навигация | Navigation Compose 2.8.5 |

---

## 🚀 Быстрый старт

### Требования

- Android 14 (API 34) или новее
- Сервер: популярный Linux или **OpenWrt**, с OpenSSH или dropbear (для автоматической настройки нужны root или sudo)
- OpenSSH требует `AllowTcpForwarding yes` (по умолчанию; dropbear разрешает TCP-переадресацию по умолчанию)

### Настройка сервера

**Вариант A: автоматическая настройка в приложении (рекомендуется)**

В мастере «Добавить сервер» укажите учётную запись с правами **root или sudo**, и приложение сделает всё остальное:

- Создаёт выделенный туннельный аккаунт `sshproxy`: `nologin` + заблокированный пароль, вход в shell невозможен
- Изоляция chroot — доступна только минимальная файловая система (`ChrootDirectory`)
- Только аутентификация по ключам (`PasswordAuthentication no`), `authorized_keys` заблокирован через `chattr +i`
- Добавляет блок `Match User sshproxy` в sshd; делает бэкап и перезагружает конфиг только после успешного `sshd -t`

**Вариант B: ручная настройка**

Следуйте **[docs/server-setup.md](docs/server-setup.md)** для ручного создания аккаунта `sshproxy`, каталога chroot и усиления sshd.

> **OpenWrt**: установите `openssh-server` для полного усиления (chroot + Match); по умолчанию dropbear работает в базовом режиме совместимости (аккаунт `sshproxy` + только ключи, без изоляции chroot), глобальная конфигурация dropbear не изменяется. См. [docs/server-setup.md](docs/server-setup.md).

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

1. Нажмите **+**, чтобы открыть мастер добавления сервера
2. Заполните: имя, хост, порт (по умолчанию 22), пользователя/пароль (права root или sudo)
3. Мастер автоматически генерирует пару ECDSA и настраивает сервер (выделенный аккаунт `sshproxy` + усиление sshd)
4. Или выберите «только ключ»: скопируйте открытый ключ → вставьте в `~/.ssh/authorized_keys` на VPS

### 2. Настройка белого списка

1. Откройте страницу **Белый список**
2. Найдите/просмотрите установленные приложения
3. Отметьте приложения для проксирования (только отмеченные перехватываются)

### 3. Подключение

1. Нажмите большую кнопку **Подключить**
2. Разрешите доступ к VPN при запросе
3. После успеха: локальный IP / удалённый IP / статус соединения
4. Постоянный статус в уведомлении; нажмите для отключения

### 4. Настройки

| Настройка | Описание |
|-----------|----------|
| Режим DNS | Удалённый (по умолчанию, защита от утечек) / прямой / белый список / разделение доменов |
| Список доменов | Домены для правил разделения |
| Язык | 中文 / English / Русский / системный |
| Биометрия | При включении разблокировка ключа требует отпечаток/лицо |

---

## 🔒 Модель безопасности

```
Закрытый ключ → Android Keystore → изоляция на уровне системы
     ↓
Подпись ключа → BiometricPrompt (отпечаток/лицо) → согласие
     ↓
SSH-авторизация → JSch Identity → подпись Keystore
     ↓
Открытый ключ → формат OpenSSH → развёртывание на сервере
```

**Ключевые моменты:**
- Закрытый ключ **никогда не покидает** Keystore и не может быть экспортирован
- TOFU при первом подключении (Trust On First Use) + кэш отпечатков ключей; при изменении отпечатка соединение отклоняется (защита от MITM)
- Только безопасные алгоритмы: обмен ключей curve25519 / diffie-hellman-group14+, ключи хоста ed25519 / ECDSA P-256+
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
│   │   │   │   └── remote/         # ssh (JSch+Keystore) / tunnel / config (ServerProvisioner)
│   │   │   ├── domain/
│   │   │   │   ├── model/          # Доменные модели
│   │   │   │   ├── usecase/        # VpnController/Repository
│   │   │   │   └── vpn/            # VPN-компоненты (Socks5/TCP/UDP/DNS/туннель)
│   │   │   ├── di/                 # Hilt Modules
│   │   │   ├── ui/
│   │   │   │   ├── screen/         # Экраны (dashboard/server/whitelist/settings/keymanager)
│   │   │   │   ├── component/      # Общие компоненты
│   │   │   │   └── theme/          # Тема Material3
│   │   │   └── vpn/                # SshVpnService + BootReceiver
│   │   ├── res/                    # Ресурсы
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── docs/
│   ├── server-setup.md             # Руководство по настройке сервера
│   ├── USER_GUIDE.md               # Руководство пользователя
│   └── diagrams/                   # Диаграммы архитектуры/потоков/состояний
├── .github/workflows/ci.yml        # CI/CD
├── detekt.yml                      # Правила стиля кода
├── cliff.toml                      # Генерация журнала изменений релиза
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
- [ ] Запустить полный конвейер CI (lint / detekt / ktlint / test)
- [ ] Отправить тег `v*` — CI собирает релиз и генерирует журнал изменений (git-cliff)
- [ ] Опубликовать APK/AAB в GitHub Releases / Play Console

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
