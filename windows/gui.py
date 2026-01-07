import customtkinter as ctk
import os
import threading
import time
import ctypes
import tkinter as tk
from tkinter import filedialog, messagebox, PhotoImage
import shutil
import tempfile
import uuid # For Group ID
from network import NetworkManager

# Configuration
ctk.set_appearance_mode("Dark")
ctk.set_default_color_theme("blue")

# OLED Colors
COLOR_BG = "#000000"
COLOR_CARD = "#111111" 
COLOR_TEXT = "#FFFFFF"
COLOR_ACCENT = "#1F6AA5" # Blue accent
COLOR_SUCCESS = "#2CC985"
COLOR_ERROR = "#FF4444"

class App(ctk.CTk):
    def __init__(self):
        # Single Instance Check
        self.mutex = ctypes.windll.kernel32.CreateMutexW(None, True, "LocalDrop_Instance_Mutex")
        if ctypes.windll.kernel32.GetLastError() == 183: # ERROR_ALREADY_EXISTS
            # Provide a visual cue or just exit
            # Ideally we would find the window and bring to front, but strictly requested "open only 1 time"
             ctypes.windll.user32.MessageBoxW(0, "LocalDrop is already running!", "LocalDrop", 0x40 | 0x1)
             os._exit(0)

        super().__init__()

        self.title("LocalDrop")
        self.geometry("700x500")
        self.configure(fg_color=COLOR_BG)
        
        # Set Icon
        try:
            icon_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon.png")
            if os.path.exists(icon_path):
                img = PhotoImage(file=icon_path)
                self.iconphoto(False, img)
                self.iconbitmap(default="") # clear default
                # Windows taskbar icon sometimes needs explicit appid
                myappid = 'mycompany.myproduct.subproduct.version' # arbitrary string
                ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(myappid)
                self.iconphoto(True, img)
        except Exception as e:
            print(f"Icon error: {e}")
        
        self.device_name = os.getenv('COMPUTERNAME', 'Windows PC')
        self.network = NetworkManager(
            self.device_name, 
            on_device_found=self.update_device_list,
            on_transfer_progress=self.update_progress,
            on_confirmation=self.confirm_transfer,
            on_text_received=self.show_text_received
        )
        
        self.setup_ui()
        self.network.start()
        
        self.protocol("WM_DELETE_WINDOW", self.on_closing)
        
    def setup_ui(self):
        # Header
        self.header = ctk.CTkFrame(self, fg_color=COLOR_BG, height=60, corner_radius=0)
        self.header.pack(fill="x", padx=20, pady=(20, 10))
        
        self.logo = ctk.CTkLabel(
            self.header, 
            text="LocalDrop", 
            font=ctk.CTkFont(size=24, weight="bold"),
            text_color=COLOR_TEXT
        )
        self.logo.pack(side="left")
        
        self.my_info = ctk.CTkLabel(
            self.header, 
            text=f"Visible as: {self.device_name}", 
            font=ctk.CTkFont(size=12),
            text_color="gray"
        )
        self.my_info.pack(side="right", anchor="e")

        # Main Content
        self.main_frame = ctk.CTkFrame(self, fg_color=COLOR_BG, corner_radius=0)
        self.main_frame.pack(fill="both", expand=True, padx=20)
        
        # Available Devices
        self.label_avail = ctk.CTkLabel(self.main_frame, text="Available Devices", font=ctk.CTkFont(size=14, weight="bold"), text_color="gray")
        self.label_avail.pack(anchor="w", pady=(0, 10))
        
        self.devices_frame = ctk.CTkScrollableFrame(self.main_frame, fg_color=COLOR_BG)
        self.devices_frame.pack(fill="both", expand=True)
        
        self.device_cards = {}
        
        # Status Area
        self.status_frame = ctk.CTkFrame(self, fg_color=COLOR_CARD, corner_radius=10)
        self.status_frame.pack(fill="x", padx=20, pady=20)
        
        self.status_label = ctk.CTkLabel(self.status_frame, text="Ready to share", text_color="gray")
        self.status_label.pack(pady=(10, 5))
        
        self.progress_bar = ctk.CTkProgressBar(self.status_frame, progress_color=COLOR_ACCENT)
        self.progress_bar.pack(fill="x", padx=20, pady=(0, 10))
        self.progress_bar.set(0)
        
        self.action_frame = ctk.CTkFrame(self.status_frame, fg_color="transparent")
        self.action_frame.pack(fill="x", padx=20, pady=(0, 10))
        # Buttons like Cancel/Copy will appear here

        self.after(2000, self.refresh_devices_loop)

    def refresh_devices_loop(self):
        # Sync UI with network state
        current_ips = set(self.network.found_devices.keys())
        ui_ips = set(self.device_cards.keys())
        
        # Remove offline
        for ip in ui_ips - current_ips:
            if ip in self.device_cards:
                self.device_cards[ip].destroy()
                del self.device_cards[ip]
                
        # Add new (update_device_list handles this usually, but strictly solely relying on callback might miss pruning)
        for ip in current_ips - ui_ips:
             self.update_device_list(self.network.found_devices[ip])
             
        self.after(2000, self.refresh_devices_loop)

    def update_device_list(self, device):
        if device.ip in self.device_cards:
            return

        card = ctk.CTkFrame(self.devices_frame, fg_color=COLOR_CARD, corner_radius=10)
        card.pack(fill="x", pady=5)
        
        # Icon/Name
        info_frame = ctk.CTkFrame(card, fg_color="transparent")
        info_frame.pack(side="left", padx=15, pady=15)
        
        name_lbl = ctk.CTkLabel(info_frame, text=device.hostname, font=ctk.CTkFont(size=14, weight="bold"), text_color=COLOR_TEXT)
        name_lbl.pack(anchor="w")
        
        ip_lbl = ctk.CTkLabel(info_frame, text=f"{device.os} • {device.ip}", font=ctk.CTkFont(size=11), text_color="gray")
        ip_lbl.pack(anchor="w")
        
        # Buttons
        btn_frame = ctk.CTkFrame(card, fg_color="transparent")
        btn_frame.pack(side="right", padx=15)
        
        btn_text = ctk.CTkButton(
            btn_frame, 
            text="Send Text", 
            width=80, 
            fg_color="#333333", 
            hover_color="#444444",
            command=lambda ip=device.ip: self.send_text_dialog(ip)
        )
        btn_text.pack(side="left", padx=5)
        
        btn_file = ctk.CTkButton(
            btn_frame, 
            text="Send File", 
            width=80,
            fg_color=COLOR_ACCENT,
            command=lambda ip=device.ip: self.select_file_and_send(ip)
        )
        btn_file.pack(side="left", padx=5)

        btn_folder = ctk.CTkButton(
            btn_frame,
            text="Send Folder",
            width=80,
            fg_color=COLOR_ACCENT,
            command=lambda ip=device.ip: self.select_folder_and_send(ip)
        )
        btn_folder.pack(side="left", padx=5)
        
        self.device_cards[device.ip] = card

    def send_text_dialog(self, ip):
        dialog = ctk.CTkInputDialog(text="Enter text to send:", title="Send Text")
        text = dialog.get_input()
        if text:
            self.status_label.configure(text="Sending text...")
            threading.Thread(target=self.send_wrapper, args=(ip, text, True)).start()

    def select_file_and_send(self, ip):
        # Allow multiple file selection
        filepaths = filedialog.askopenfilenames(title="Select Files to Send")
        if filepaths:
            count = len(filepaths)
            self.status_label.configure(text=f"Sending {count} file(s)...")
            threading.Thread(target=self.send_multiple_files, args=(ip, filepaths)).start()

    def send_multiple_files(self, ip, filepaths):
        """Send multiple files sequentially"""
        self.network.reset_cancel_flag()
        total_files = len(filepaths)
        
        # Calculate Group Size
        total_size = 0
        for fp in filepaths:
            try:
                total_size += os.path.getsize(fp)
            except: pass
            
        group_id = str(uuid.uuid4())
        
        self.current_batch_total_size = total_size
        self.current_batch_sent_base = 0
        self.current_batch_start_time = time.time()
        
        original_callback = self.network.on_transfer_progress
        self.network.on_transfer_progress = self.update_batch_progress

        for i, filepath in enumerate(filepaths, 1):
            if self.network.cancel_requested:
                 break
            
            self.status_label.configure(text=f"Sending {i}/{total_files}: {os.path.basename(filepath)}...")
            
            file_size = os.path.getsize(filepath)
            success = self.network.send_file(ip, filepath, group_id=group_id, group_size=total_size)
            
            if success:
                 self.current_batch_sent_base += file_size
            else:
                if self.network.cancel_requested:
                     self.status_label.configure(text="Transfer Cancelled")
                     break
                self.status_label.configure(text=f"Failed to send {os.path.basename(filepath)}")
                # Continue?? Protocol says separate files are separate unless grouped. 
                # If one fails in a group, maybe we should stop?
                # User preference usually is reliability. Let's stop on failure if it's a "package".
                break
                
        if not self.network.cancel_requested:
             self.status_label.configure(text=f"Sent {total_files} file(s)!")
             self.progress_bar.set(1)
             self.action_frame.after(1000, self.hide_controls) # Auto hide after success
        else:
             self.hide_controls()
        
        self.network.on_transfer_progress = original_callback


    def select_folder_and_send(self, ip):
        folderpath = filedialog.askdirectory()
        if folderpath:
            self.status_label.configure(text=f"Preparing to send {os.path.basename(folderpath)}...")
            threading.Thread(target=self.send_folder_recursive, args=(ip, folderpath)).start()

    def send_folder_recursive(self, ip, folderpath):
        try:
            self.network.reset_cancel_flag()
            
            # 1. Calculate Total Size and File Count
            total_size = 0
            total_files = 0
            for root, dirs, files in os.walk(folderpath):
                for file in files:
                    total_files += 1
                    total_size += os.path.getsize(os.path.join(root, file))
            
            group_id = str(uuid.uuid4())
            
            self.current_batch_total_size = total_size
            self.current_batch_sent_base = 0
            self.current_batch_start_time = time.time()
            
            # 2. Swap Progress Callback
            original_callback = self.network.on_transfer_progress
            self.network.on_transfer_progress = self.update_batch_progress
            
            files_sent = 0
            
            folder_basename = os.path.basename(folderpath)
            
            for root, dirs, files in os.walk(folderpath):
                if self.network.cancel_requested: break

                for file in files:
                    if self.network.cancel_requested: break 
                    
                    abs_path = os.path.join(root, file)
                    rel_path = os.path.relpath(abs_path, folderpath)
                    remote_filename = os.path.join(folder_basename, rel_path).replace("\\", "/")
                    
                    files_sent += 1
                    file_size = os.path.getsize(abs_path) # Get size for batch update
                    
                    # status_label updated by update_batch_progress usually, 
                    # but we can set context here too if needed. 
                    
                    success = self.network.send_file(ip, abs_path, remote_filename=remote_filename, group_id=group_id, group_size=total_size)
                    
                    if success:
                        self.current_batch_sent_base += file_size
                    else:
                        if self.network.cancel_requested:
                             self.status_label.configure(text="Transfer Cancelled")
                             self.hide_controls()
                             self.network.on_transfer_progress = original_callback
                             return
                        print(f"Failed to send {remote_filename}")
            
            if not self.network.cancel_requested:
                self.status_label.configure(text=f"Folder Sent! ({total_files} files)")
                self.progress_bar.set(1)
                self.action_frame.after(1000, self.hide_controls)
            else:
                self.hide_controls()
            
            self.network.on_transfer_progress = original_callback
            
        except Exception as e:
            print(f"Folder send error: {e}")
            self.status_label.configure(text=f"Error: {e}")
            if hasattr(self, 'network'):
                self.network.on_transfer_progress = getattr(self, 'original_callback', self.network.on_transfer_progress)
            self.hide_controls()

    def update_batch_progress(self, filename, current, total, mode, speed, eta):
        # Calculate Total Progress
        total_current = self.current_batch_sent_base + current
        total_percentage = total_current / self.current_batch_total_size if self.current_batch_total_size > 0 else 0
        
        # Calculate Total Speed / ETA
        elapsed = time.time() - self.current_batch_start_time
        if elapsed > 0:
            total_speed = total_current / elapsed
            total_eta = (self.current_batch_total_size - total_current) / total_speed if total_speed > 0 else 0
        else:
            total_speed = 0
            total_eta = 0
            
        # Update UI
        self.progress_bar.set(total_percentage)
        
        speed_mbps = (total_speed * 8) / (1024 * 1024)
        eta_str = f"{int(total_eta)}s" if total_eta < 60 else f"{int(total_eta//60)}m {int(total_eta%60)}s"
        
        status_text = f"Sending Batch: {int(total_percentage*100)}% • {speed_mbps:.1f} Mbps • {eta_str}"
        self.status_label.configure(text=status_text)
        
        # Ensure Cancel button is visible
        if not self.action_frame.winfo_children():
             self.show_pause_cancel() 

    def send_wrapper(self, ip, content, is_text_msg):
        self.network.reset_cancel_flag()
        self.last_ip = ip
        if is_text_msg:
             success = self.network.send_text(ip, content)
        else:
             self.last_filepath = content
             success = self.network.send_file(ip, content)
        
        def finish():
            if not self.network.cancel_requested:
                 self.status_label.configure(text="Sent!" if success else "Failed")
                 self.progress_bar.set(0 if not success else 1)
            self.hide_controls()

        self.after(0, finish)

    def update_progress(self, filename, current, total, mode, speed=0, eta=0):
        # Throttle UI updates handled in network layer usually, but good to check
        progress = current / total
        
        speed_mbps = (speed * 8) / (1024 * 1024)
        eta_str = f"{int(eta)}s" if eta < 60 else f"{int(eta//60)}m {int(eta%60)}s"
        status_text = f"{mode.title()}: {int(progress*100)}% • {speed_mbps:.1f} Mbps • {eta_str}"
        
        self.after(0, lambda: self.progress_bar.set(progress))
        self.after(0, lambda: self.status_label.configure(text=status_text))
        
        if current < total:
             self.after(0, self.show_pause_cancel)
        else:
             self.after(0, lambda: self.transfer_complete(filename, mode))

    def show_pause_cancel(self):
        for widget in self.action_frame.winfo_children(): widget.destroy()
        
        btn = ctk.CTkButton(self.action_frame, text="Cancel", fg_color=COLOR_ERROR, height=24, command=self.on_cancel)
        btn.pack()
        
    def on_cancel(self):
        self.network.cancel_transfer()
        self.status_label.configure(text="Cancelling...")
        # Controls hidden when loop detects cancellation

    def transfer_complete(self, filename, mode):
        self.hide_controls()
        self.status_label.configure(text=f"{mode.title()} Complete: {filename}")
        
        # Show Copy Button if it was a text file received
        if mode.lower() == "receiving" and filename.endswith(".txt"):
             self.show_copy_button(filename)

    def show_copy_button(self, filename):
        for widget in self.action_frame.winfo_children(): widget.destroy()
        
        def copy_content():
            try:
                path = os.path.join(os.path.expanduser("~/Downloads"), filename)
                if os.path.exists(path):
                    with open(path, "r", encoding="utf-8") as f:
                        content = f.read()
                    self.clipboard_clear()
                    self.clipboard_append(content)
                    self.status_label.configure(text="Copied to Clipboard!")
            except Exception as e:
                print(e)

        btn = ctk.CTkButton(self.action_frame, text="Copy Content", fg_color=COLOR_SUCCESS, height=24, command=copy_content)
        btn.pack()

    def show_text_received(self, text_content):
        # Show Dialog or Custom UI
        dialog = ctk.CTkToplevel(self)
        dialog.title("Text Received")
        dialog.geometry("400x300")
        dialog.attributes("-topmost", True)
        
        def copy_and_close():
            self.clipboard_clear()
            self.clipboard_append(text_content)
            self.status_label.configure(text="Copied received text!")
            dialog.destroy()
            
        lbl = ctk.CTkLabel(dialog, text="Incoming Text:", font=ctk.CTkFont(weight="bold"))
        lbl.pack(pady=10)
        
        textbox = ctk.CTkTextbox(dialog, height=150)
        textbox.pack(padx=20, pady=10, fill="both", expand=True)
        textbox.insert("1.0", text_content)
        textbox.configure(state="disabled") # Read-only
        
        btn_copy = ctk.CTkButton(dialog, text="Copy to Clipboard", command=copy_and_close, fg_color=COLOR_SUCCESS)
        btn_copy.pack(pady=10)

    def hide_controls(self):
        for widget in self.action_frame.winfo_children(): widget.destroy()

    def confirm_transfer(self, filename, filesize):
        import tkinter as tk
        from tkinter import messagebox
        msg = f"Incoming file: {filename}\nSize: {filesize/1024/1024:.2f} MB\n\nAccept?"
        return messagebox.askyesno("File Request", msg)

    def on_closing(self):
        self.network.stop()
        self.destroy()

if __name__ == "__main__":
    app = App()
    app.mainloop()
