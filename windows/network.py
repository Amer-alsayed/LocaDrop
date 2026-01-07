import socket
import threading
import json
import os
import time
import struct
from dataclasses import dataclass

# Configuration
BROADCAST_PORT = 45454
TRANSFER_PORT = 45455
BUFFER_SIZE = 1024 * 1024  # 1MB Buffer for high speed

@dataclass
class Device:
    ip: str
    hostname: str
    os: str
    last_seen: float

class NetworkManager:
    def __init__(self, device_name, on_device_found=None, on_transfer_progress=None, on_confirmation=None, on_text_received=None):
        self.device_name = device_name
        self.on_device_found = on_device_found
        self.on_transfer_progress = on_transfer_progress
        self.on_confirmation = on_confirmation
        self.on_text_received = on_text_received
        self.running = True
        self.transfer_running = False
        self.cancel_requested = False # Flag for reliable cancellation
        self.on_discovery = None
        self.found_devices = {}
        
        # Batch Transfer Tracking
        self.accepted_group_id = None # Store the currently accepted Group ID
        
        # Setup UDP Socket for Discovery
        self.udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.udp_sock.bind(("", BROADCAST_PORT))
        
        # Setup TCP Socket for File Transfer
        self.tcp_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.tcp_sock.bind(("", TRANSFER_PORT))
        self.tcp_sock.listen(5)


    def start(self):
        threading.Thread(target=self._broadcast_presence, daemon=True).start()
        threading.Thread(target=self._listen_for_discovery, daemon=True).start()
        threading.Thread(target=self._prune_devices, daemon=True).start()
        threading.Thread(target=self._accept_transfers, daemon=True).start()

    def _broadcast_presence(self):
        message = json.dumps({
            "host": self.device_name,
            "os": "windows"
        }).encode('utf-8')
        
        while self.running:
            try:
                self.udp_sock.sendto(message, ('<broadcast>', BROADCAST_PORT))
                time.sleep(1)
            except Exception as e:
                # print(f"Broadcast error: {e}")
                time.sleep(2)

    def _listen_for_discovery(self):
        while self.running:
            try:
                data, addr = self.udp_sock.recvfrom(1024)
                ip = addr[0]
                # Ignore own broadcasts
                if ip == socket.gethostbyname(socket.gethostname()) or ip == '127.0.0.1':
                    continue
                    
                info = json.loads(data.decode('utf-8'))
                device = Device(ip, info['host'], info['os'], time.time())
                
                if ip not in self.found_devices:
                    self.found_devices[ip] = device
                    if self.on_device_found:
                        self.on_device_found(device)
                else:
                    self.found_devices[ip].last_seen = time.time()
            except Exception as e:
                # print(f"Discovery listener error: {e}")
                pass

    def _prune_devices(self):
        while self.running:
            time.sleep(1)
            now = time.time()
            # Create a list to avoid runtime error during iteration
            to_remove = [ip for ip, dev in self.found_devices.items() if now - dev.last_seen > 3]
            
            for ip in to_remove:
                # print(f"Device {ip} timed out")
                del self.found_devices[ip]
            
            if to_remove and self.on_device_found:
                 pass

    def _accept_transfers(self):
        while self.running:
            try:
                conn, addr = self.tcp_sock.accept()
                sender_ip = addr[0]
                threading.Thread(target=self._receive_file, args=(conn, sender_ip), daemon=True).start()
            except Exception as e:
                print(f"Accept error: {e}")


    def _receive_file(self, conn, sender_ip):
        try:
            # Read header length
            header_len_data = conn.recv(4)
            if not header_len_data: return
            header_len = struct.unpack('!I', header_len_data)[0]
            
            # Read header
            header_data = conn.recv(header_len)
            header = json.loads(header_data.decode('utf-8'))
            filename = header['filename']
            filesize = header['size']
            msg_type = header.get('type', 'file')
            group_id = header.get('group_id') # Get Group ID
            group_size = header.get('group_size')

            # --- TEXT HANDLING ---
            if msg_type == 'text':
                # Send Offset 0
                conn.send(struct.pack('!Q', 0))
                
                # Read Content
                data = b""
                received = 0
                while received < filesize:
                    chunk = conn.recv(min(BUFFER_SIZE, filesize - received))
                    if not chunk: break
                    data += chunk
                    received += len(chunk)
                
                text_content = data.decode('utf-8')
                if self.on_text_received:
                    self.on_text_received(text_content)
                return
            # --- END TEXT HANDLING ---
            
            # Check auto-accept for batch transfers based on Group ID
            auto_accepted = False
            
            if group_id is not None and group_id == self.accepted_group_id:
                auto_accepted = True
                print(f"[AutoAccept] Matched Group ID {group_id} for file {filename}")
            else:
                 # Reset accepted group ID if it's a new group or no group
                 if group_id != self.accepted_group_id:
                      self.accepted_group_id = None

            # Request Confirmation (only if not auto-accepted)
            if not auto_accepted and self.on_confirmation:
                print(f"[Confirmation] Requesting for {filename} (Group: {group_id})")
                
                # If it's a batch, maybe display batch info? 
                # For now standard prompt works, user accepts "Folder transfer" implicitly by accepting first file of group.
                display_name = f"{filename}"
                if group_id and group_size:
                     display_name += " (Part of a batch)"

                if not self.on_confirmation(display_name, filesize):
                    print("Transfer rejected by user")
                    conn.close()
                    return
                
                # User accepted
                if group_id:
                     self.accepted_group_id = group_id
                     print(f"[AutoAccept] Set Accepted Group ID to {group_id}")


            # Sanitize filename
            safe_filename = filename.replace('\\', '/')
            safe_filename = safe_filename.lstrip('/')
            if '..' in safe_filename.split('/'):
                print(f"Malicious filename detected: {filename}")
                return

            # Construct save path
            downloads_dir = os.path.expanduser("~/Downloads")
            save_path = os.path.join(downloads_dir, safe_filename)
            
            parent_dir = os.path.dirname(save_path)
            if not os.path.exists(parent_dir):
                os.makedirs(parent_dir, exist_ok=True)
            
            offset = 0
            mode = 'wb'
            
            if os.path.exists(save_path):
                current_size = os.path.getsize(save_path)
                if current_size < filesize:
                    print(f"Resuming {filename} from {current_size}")
                    offset = current_size
                    mode = 'ab'
                elif current_size == filesize:
                    print(f"File {filename} already exists. Skipping.")
                    # Rename strategy
                    counter = 1
                    while os.path.exists(save_path):
                        name, ext = os.path.splitext(save_path)
                        save_path = f"{name}_{counter}{ext}"
                        counter += 1
            
            # Send Offset to Sender
            conn.send(struct.pack('!Q', offset))

            # Batch State Management
            is_batch = False
            if group_id and group_size:
                 is_batch = True
                 # Initialize or Reset Batch State if needed
                 if not hasattr(self, 'batch_state') or self.batch_state['group_id'] != group_id:
                      self.batch_state = {
                          'group_id': group_id,
                          'total_size': group_size,
                          'received_base': 0
                      }
            
            received = offset
            start_time = time.time()
            last_update_time = start_time
            self.transfer_running = True
            
            with open(save_path, mode) as f:
                while received < filesize:
                    if not self.transfer_running:
                         print("Transfer cancelled during loop")
                         break

                    chunk = conn.recv(min(BUFFER_SIZE, filesize - received))
                    if not chunk: break
                    f.write(chunk)
                    received += len(chunk)
                    
                    current_time = time.time()
                    if self.on_transfer_progress and (current_time - last_update_time > 0.1 or received == filesize):
                        elapsed = current_time - start_time
                        
                        # Calculate Speed (Current File)
                        speed = ((received - offset) / elapsed) if elapsed > 0 else 0
                        
                        # Calculate Stats (Batch or Single)
                        if is_batch:
                             total_to_show = self.batch_state['total_size']
                             current_to_show = self.batch_state['received_base'] + received
                             # ETA based on remaining BATCH size
                             eta = (total_to_show - current_to_show) / speed if speed > 0 else 0
                             mode_str = "Receiving Batch"
                        else:
                             total_to_show = filesize
                             current_to_show = received
                             eta = (filesize - received) / speed if speed > 0 else 0
                             mode_str = "Receiving"

                        self.on_transfer_progress(filename, current_to_show, total_to_show, mode_str, speed, eta)
                        last_update_time = current_time

            if not self.transfer_running:
                print(f"Transfer of {filename} cancelled/paused.")
            else:
                print(f"Received {filename} in {time.time() - start_time:.2f}s")
                # Update Batch Base
                if is_batch:
                     self.batch_state['received_base'] += filesize
            
        except Exception as e:
            print(f"Receive error: {e}")
        finally:
            conn.close()

    def send_text(self, ip, text):
        try:
            data = text.encode('utf-8')
            size = len(data)
            filename = "Text Message"
            
            header = json.dumps({
                "filename": filename,
                "size": size,
                "type": "text"
            }).encode('utf-8')
            
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.connect((ip, TRANSFER_PORT))
            
            # Send Header
            s.send(struct.pack('!I', len(header)))
            s.send(header)
            
            # Receive Offset
            offset_data = s.recv(8)
            offset = struct.unpack('!Q', offset_data)[0]
            
            # Send Data
            s.sendall(data)
            
            s.close()
            return True
        except Exception as e:
            print(f"Send text error: {e}")
            return False

    def send_file(self, ip, file_path, remote_filename=None, group_id=None, group_size=None):
        if self.cancel_requested:
             return False

        try:
            filesize = os.path.getsize(file_path)
            # Use provided remote name (for relative paths) or basename
            filename = remote_filename if remote_filename else os.path.basename(file_path)
            
            header_dict = {
                "filename": filename,
                "size": filesize,
                "type": "file"
            }
            if group_id:
                header_dict["group_id"] = group_id
            if group_size:
                header_dict["group_size"] = group_size

            header = json.dumps(header_dict).encode('utf-8')
            
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.connect((ip, TRANSFER_PORT))
            
            # Send Header
            s.send(struct.pack('!I', len(header)))
            s.send(header)
            
            # Receive Offset
            offset_data = s.recv(8)
            offset = struct.unpack('!Q', offset_data)[0]
            
            if offset > 0:
                print(f"Resuming sending from {offset}")

            # Zero-copy send
            with open(file_path, 'rb') as f:
                f.seek(offset)
                sent = offset
                start_time = time.time()
                last_update_time = start_time
                self.transfer_running = True
                
                while sent < filesize:
                    if self.cancel_requested or not self.transfer_running:
                         break

                    # socket.sendfile is available in Python 3.5+
                    count = s.sendfile(f, offset=sent, count=min(BUFFER_SIZE, filesize-sent))
                    if count == 0: break
                    sent += count
                    
                    current_time = time.time()
                    if self.on_transfer_progress and (current_time - last_update_time > 0.1 or sent == filesize):
                        elapsed = current_time - start_time
                        if elapsed > 0:
                            speed = ((sent - offset) / elapsed)
                            eta = (filesize - sent) / speed if speed > 0 else 0
                        else:
                            speed = 0
                            eta = 0
                        self.on_transfer_progress(filename, sent, filesize, "sending", speed, eta)
                        last_update_time = current_time
                        
            s.close()
            return self.transfer_running and not self.cancel_requested
        except Exception as e:
            print(f"Send error: {e}")
            return False

    def cancel_transfer(self):
        self.transfer_running = False
        self.cancel_requested = True
        print("Cancellation requested.")

    def reset_cancel_flag(self):
        self.cancel_requested = False

    def stop(self):
        self.running = False
        self.udp_sock.close()
        self.tcp_sock.close()

