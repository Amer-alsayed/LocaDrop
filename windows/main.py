import sys
import subprocess

def install_dependencies():
    required = ["customtkinter", "netifaces"]
    installed_any = False

    for package in required:
        try:
            __import__(package)
        except ImportError:
            print(f"Installing {package}...")
            subprocess.check_call([sys.executable, "-m", "pip", "install", package])
            installed_any = True

    if installed_any:
        print("Dependencies installed.")

if __name__ == "__main__":
    install_dependencies()
    from gui import App  # Import after installation
    app = App()
    app.mainloop()
