import sys
import subprocess

def install_dependencies():
    try:
        import customtkinter
    except ImportError:
        print("Installing dependencies...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "customtkinter"])

if __name__ == "__main__":
    install_dependencies()
    from gui import App  # Import after installation
    app = App()
    app.mainloop()
