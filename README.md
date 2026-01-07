# LocalDrop

**Seamless Cross-Platform File Transfer for the Modern Workflow.**

LocalDrop is an open-source, high-speed file transfer solution designed to bridge the gap between Android and Windows ecosystems. Inspired by the simplicity of AirDrop, it provides a secure, private, and instantaneous way to share files, photos, videos, and clipboard content over your local network without the need for internet access or third-party servers.

## 🚀 Key Features

*   **⚡ Blazing Fast Speeds**: Transfer huge files in seconds using your local Wi-Fi network. No bandwidth caps, no compression.
*   **🔒 Secure & Private**: All transfers happen directly between devices. Your data never leaves your local network, ensuring maximum privacy.
*   **📱 Cross-Platform Harmony**:
    *   **Android**: A native, Material Design application tailored for modern Android devices.
    *   **Windows**: A sleek, modern desktop counterpart built with a focus on usability and aesthetics.
*   **📋 Clipboard Sync**: Instantly copy text on your phone and paste it on your PC (and vice versa).
*   **🛠️ Zero Configuration**: Auto-discovery protocols mean no IP addresses to type, no pairing codes to remember. Just open and share.

## 📥 Downloads

Get the latest release for your platform:

[**Download Latest Version**](https://github.com/0wver/LocaDrop/releases/latest)

*Includes `Windows Executable` and `Android APK`.*

## 🛠️ Installation & Setup

### Windows
1.  Download the `LocalDrop` folder from the releases page.
2.  Run `LocalDrop.exe`.
3.  *(Optional)* Use `LocalDrop.vbs` for a silent background start.

### Android
1.  Download and install the `AirDrop_Android.apk` on your device.
2.  Grant the necessary permissions (Storage, Location/Network for discovery) to ensure seamless operation.

## 💻 Development

LocalDrop is proudly open-source. We welcome contributions!

### Tech Stack
*   **Android**: Native Kotlin, Jetpack Compose / XML suitable for modern Android development.
*   **Windows**: Python 3, `customtkinter` for a modern UI, `socket` programming for networking.

### Building from Source

**Windows:**
```bash
git clone https://github.com/0wver/LocaDrop.git
cd LocaDrop/windows
pip install customtkinter
python main.py
```

**Android:**
Open the `android` project directory in Android Studio and let Gradle sync.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
