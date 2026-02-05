# 🔔 Sound Alarm App

Aplikacja Android do zdalnego odtwarzania alarmu przez HTTP.

## 📱 Funkcje

- **Serwer HTTP** działający w tle na porcie 8080
- **Dźwięk w pętli** - gra dopóki nie wyłączysz
- **Zdalne sterowanie** - włącz/wyłącz alarm przez HTTP
- **Foreground Service** - nie zostanie ubity przez system
- **Auto-start** po restarcie telefonu
- **Web UI** - wbudowana strona do sterowania

## 🚀 Instalacja

1. Otwórz projekt w **Android Studio** (Arctic Fox lub nowszy)
2. Poczekaj na sync Gradle
3. Podłącz telefon lub uruchom emulator
4. Kliknij **Run** (Shift+F10)

## 📡 Endpointy API

Po uruchomieniu serwera, dostępne są następujące endpointy:

| Endpoint | Opis |
|----------|------|
| `GET /` | Strona web z przyciskami |
| `GET /play` | Włącz alarm |
| `GET /stop` | Wyłącz alarm |
| `GET /status` | Sprawdź status |

## 💻 Przykłady użycia

### Z przeglądarki
```
http://192.168.1.100:8080/play
```

### Z curl
```bash
# Włącz alarm
curl http://192.168.1.100:8080/play

# Wyłącz alarm
curl http://192.168.1.100:8080/stop

# Sprawdź status
curl http://192.168.1.100:8080/status
```

### Z JavaScript
```javascript
fetch('http://192.168.1.100:8080/play')
  .then(res => res.json())
  .then(data => console.log(data));
```

### Z Node.js
```javascript
const http = require('http');
http.get('http://192.168.1.100:8080/play');
```

## 🔧 Uprawnienia

Aplikacja wymaga następujących uprawnień:

- `INTERNET` - serwer HTTP
- `FOREGROUND_SERVICE` - działanie w tle
- `MODIFY_AUDIO_SETTINGS` - ustawienie głośności
- `WAKE_LOCK` - zapobieganie usypianiu
- `RECEIVE_BOOT_COMPLETED` - auto-start

## 📁 Struktura projektu

```
app/src/main/
├── java/com/soundalarm/
│   ├── MainActivity.kt        # Główny UI
│   ├── AlarmServerService.kt  # Serwis z serwerem HTTP
│   └── BootReceiver.kt        # Auto-start po restarcie
├── res/
│   ├── layout/
│   │   └── activity_main.xml  # Layout UI
│   ├── raw/
│   │   └── alarm_sound.mp3    # Twój dźwięk alarmu
│   └── values/
│       ├── strings.xml
│       └── themes.xml
└── AndroidManifest.xml
```

## ⚠️ Uwagi

1. **Telefon i komputer muszą być w tej samej sieci WiFi**
2. **Firewall** - upewnij się że port 8080 nie jest blokowany
3. **Battery optimization** - wyłącz optymalizację baterii dla tej aplikacji w ustawieniach
4. **Głośność** - aplikacja automatycznie ustawia max głośność przy odtwarzaniu

## 🔊 Zmiana dźwięku

Podmień plik `app/src/main/res/raw/alarm_sound.mp3` na swój własny.

## 📜 Licencja

MIT - rób co chcesz!
