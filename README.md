# LocalDrop

LocalDrop is a seamless cross-platform file transfer tool that enables instant sharing of files, photos, and videos between Android and Windows devices, serving as an open-source alternative to AirDrop.

## Features

- **Cross-Platform Transfer**: Send files, photos, and videos between Android and Windows.
- **Clipboard Sync**: Synchronize your clipboard across devices (Android feature).
- **Native Look & Feel**:
  - **Android**: Modern Material Design implementation.
  - **Windows**: Sleek GUI utilizing `customtkinter`.
- **User Friendly**: Simple discovery and transfer process.

## Android App

The Android application is built with **Kotlin** and follows modern Android development practices.

### Prerequisites
- Android Studio
- JDK 11 or higher

### Getting Started
1. Open the `android` directory in Android Studio.
2. Sync the project with Gradle files.
3. Build and Run on your emulator or physical device.

**Note**: Define your local configuration in `local.properties` if needed.

## Windows App

The Windows application is a Python-based desktop app wrapped in a standalone executable.

### Prerequisites
- Python 3.x
- `customtkinter` library

### Installation & Run

1. Navigate to the `windows` directory:
   ```bash
   cd windows
   ```
2. Install dependencies (if running from source):
   ```bash
   pip install customtkinter
   ```
3. Run the application:
   ```bash
   python main.py
   ```
   Or use the included VBS script for a silent launch:
   ```bash
   double-click LocalDrop.vbs
   ```

## License

[MIT](LICENSE)
