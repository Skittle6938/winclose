# WinClose - Automatic Window Closing for MG4

Automatic window closing application for MG4 electric vehicles running Android Automotive OS (AAOS). Seamlessly closes all windows when you exit the vehicle—perfect for parking in unpredictable weather.

## Features

- 🪟 **Automatic Window Closing** – All 4 windows close automatically when you park and exit the vehicle
- ⚙️ **Per-Window Configuration** – Choose closing mode for each window independently:
  - **AUTO**: Automatic close command (if supported by window hardware)
  - **PULSE**: Holds the command for a configurable duration (closes all windows)
  - **OFF**: Skip this window
- 🎯 **Configurable Triggers**:
  - **Speed Threshold** (5–40 km/h): Minimum speed to "arm" the system
  - **Arming Duration** (1–15 min): Minimum driving time to arm without reaching speed threshold
  - **Closing Delay** (0–30 sec): Delay between door opening and window closing
- 🌐 **Bilingual UI** – Supports French and English with one-tap language switching
- 🎮 **Manual Close Button** – Manually close windows with a 5-second pulse
- 📊 **Real-Time Activity Log** – Monitor system events in real-time
- 🔋 **Foreground Service** – Persistent background operation with wake lock
- 🔐 **System Integration** – Deep integration with SAIC vehicle services via Binder/IPC

## How It Works

### Trigger Logic
1. **Monitoring**: App monitors vehicle speed via SAIC API continuously
2. **Arming**: System arms when:
   - You exceed the speed threshold **OR**
   - You drive longer than the configured arming duration
3. **Trigger**: Upon arrival, when you:
   - Shift to **Park (P)**
   - Open the **driver door**
   - After the configured **closing delay** → all 4 windows close automatically

### Architecture
- **WindowHardware**: Core hardware interface using Katman4/Katman5 Binder services and CarStateClient for real-time state
- **WindowService**: Background foreground service monitoring ignition state and triggering window closing
- **Settings**: Persistent configuration storage with SharedPreferences
- **MainActivity**: Bilingual UI with settings dialog and activity log

## Installation

### Prerequisites
- MG4 with Android Automotive OS (tested on SWI69, others untested)
- ADB (Android Debug Bridge) installed
- USB debugging enabled on the head unit (via service menu)
- AOSP test platform signature trust (standard on MG4 fleet)

### Steps

1. **Download the APK**
   - Get `winclose-signed.apk` from the [Releases](../../releases) page

2. **Connect via ADB**
   ```bash
   adb connect <MG4_HEAD_UNIT_IP>:5555
   adb devices  # Verify connection
   ```

3. **Install**
   ```bash
   adb install -f winclose-signed.apk
   ```
   The `-f` flag is required to allow downgrade/replacement of system apps with test signatures.

4. **Launch**
   - Open the WinClose app from the launcher
   - Enable "Auto-close" toggle to activate

## Usage

### Main Screen
- **Auto-close Toggle**: Enable/disable automatic window closing
- **Close (PULSE 5s) Button**: Manually trigger a 5-second pulse to all windows
- **Clear Log Button**: Clear the activity log
- **⚙ Settings Button**: Configure thresholds and per-window modes
- **ℹ Info Button**: View feature documentation
- **Activity Log**: Real-time events and system status

### Settings Dialog
- **Speed Threshold**: Minimum speed to arm the system (default: 20 km/h)
- **Arming Duration**: Minimum RUN time for auto-arm (default: 5 minutes)
- **Closing Delay**: Delay before closing after door opens (default: 5 seconds)
- **Per-Window Mode**: Choose AUTO, PULSE, or OFF for each of the 4 windows
- **Language**: Toggle between French and English
- **Reset Button**: Restore all settings to defaults

## Configuration Reference

| Setting | Min | Default | Max | Unit |
|---------|-----|---------|-----|------|
| Speed Threshold | 5 | 20 | 40 | km/h |
| Arming Duration | 1 | 5 | 15 | minutes |
| Closing Delay | 0 | 5 | 30 | seconds |

## System Behavior

- **Cooldown**: 60-second cooldown between consecutive close operations to prevent repeated triggering
- **Auto-Rearm**: System automatically re-arms after each close cycle in the same session
- **Foreground Service**: Runs continuously in background with persistent notification
- **Wake Lock**: Maintains partial wake lock to ensure timely trigger detection
- **Service Restart**: Automatically restarts at device boot

## Technical Details

### Binder/IPC Services Used
- **Katman4/Katman5**: Low-level window control via SAIC firmware
- **CarStateClient**: Real-time vehicle state (gear, parking brake, door sensors)
- **CarGeneralClient**: Current vehicle speed
- **CarAdapterClient**: Service discovery and initialization

### Window Control Modes
- **AUTO (Code 3)**: Sends native automatic close command
- **PULSED (Code 1)**: Holds close command for configured duration (5-30 seconds)
- **OFF**: No action on this window

### Area Mapping
- **FL (0)**: Front Left (driver side)
- **FR (1)**: Front Right (passenger side)
- **RL (2)**: Rear Left
- **RR (3)**: Rear Right

## Compatibility

- **Tested**: MG4 AAOS SWI69
- **Expected Compatible**: MG4 units with standard AOSP test keys
- **Not Compatible**: Older MG4 models with different vehicle service architecture

## Troubleshooting

### App not closing windows
1. **Check system activation**:
   - Enable "Auto-close" toggle in main screen
   - Watch the activity log for trigger events
   - Verify speed threshold is reached or arming duration elapsed

2. **Check per-window configuration**:
   - Go to ⚙ Settings → Per-window closing mode
   - Ensure at least one window is set to AUTO or PULSE
   - Verify window mode color indicates selection (green=AUTO, red=PULSE, gray=OFF)

3. **Test manual close**:
   - Click "⬆ Close windows" button
   - Watch activity log for pulse events
   - If manual close works, auto-trigger may need configuration adjustment

4. **Check permissions**:
   - Ensure app has required permissions: WAKE_LOCK, FOREGROUND_SERVICE
   - Monitor Android system logs for permission errors

### High battery drain
- Adjust arming duration to longer times (system monitors less frequently)
- Disable auto-close when not needed via main toggle
- Use manual close button instead of relying on auto-trigger

### Windows not responding
- Try different per-window modes (AUTO vs PULSE)
- Increase closing delay to allow windows more time to respond
- Some MG4 units may have hardware quirks; PULSE mode is most reliable

## Development

### Build Requirements
- Android Studio with Android Automotive SDK
- Kotlin 1.8+
- Min SDK: 30 (Android Automotive 11)
- Target SDK: 34

### Build Instructions
```bash
./gradlew assembleRelease
```

### Signing
The APK is pre-signed with AOSP test platform keys (CN=Android, EMAILADDRESS=android@android.com), which are trusted by default on MG4 fleet units.

## License

[Add appropriate license - GPL/MIT/Apache/etc.]

## Contributing

Pull requests and issue reports welcome! Please include:
- MG4 firmware version (check vehicle settings)
- Detailed description of unexpected behavior
- Steps to reproduce issues
- Activity log output (if applicable)

## Support & Community

For issues, questions, or compatibility reports:
- Open an [Issue](../../issues) on GitHub
- Check existing issues for common problems
- Share your MG4 firmware version for compatibility tracking

## Disclaimer

This application interacts with vehicle hardware and services. Use at your own risk. The developer assumes no liability for damage to vehicle electronics, unexpected vehicle behavior, or safety issues. Always verify window operation in normal mode before relying on automatic closing.

---

**WinClose** — Keeping your MG4 dry, one automatic window at a time. 🚗💨
