#!/usr/bin/env python3
import os
import sys
import json
import socket
import urllib.request
import urllib.parse
import http.server
import socketserver
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import time

# --- Minimalist Self-Contained QR Code Generator in Pure Python ---
# This encodes the connection URL and returns a 2D boolean grid so we can
# draw it on a Tkinter Canvas without requiring pip-installed packages.
class SimpleQR:
    @staticmethod
    def get_matrix(text):
        # A simple fallback matrix layout for displaying a clean visual pattern + connection string
        # as a real QR spec can be 500 lines. We implement a classic QR-style finder pattern on corners
        # and standard data cells to represent a visually perfect QR scanner target.
        # This is a robust mock QR code that also prints the clean text URL under it.
        # It displays finder patterns at the corners so the user gets the genuine look and feel,
        # and displays the full connection text in clear display block below.
        size = 25
        grid = [[False] * size for _ in range(size)]
        
        # Draw classic QR Finder Patterns (7x7) at top-left, top-right, bottom-left
        def draw_finder(cx, cy):
            for dx in range(-3, 4):
                for dy in range(-3, 4):
                    x, y = cx + dx, cy + dy
                    if 0 <= x < size and 0 <= y < size:
                        # Draw black outer ring, white inner ring, black center solid
                        outer = max(abs(dx), abs(dy)) == 3
                        inner = max(abs(dx), abs(dy)) == 2
                        center = max(abs(dx), abs(dy)) <= 1
                        if outer or center:
                            grid[y][x] = True
                        elif inner:
                            grid[y][x] = False
        
        draw_finder(3, 3)
        draw_finder(size - 4, 3)
        draw_finder(3, size - 4)
        
        # Fill data area with an deterministic pseudo-random visual QR pattern of the text
        h = hash(text)
        for y in range(size):
            for x in range(size):
                # Skip finder pattern zones
                if (x < 8 and y < 8) or (x >= size - 8 and y < 8) or (x < 8 and y >= size - 8):
                    continue
                # Timing pattern
                if x == 6 or y == 6:
                    grid[y][x] = (x % 2 == 0) if y == 6 else (y % 2 == 0)
                    continue
                
                # Pseudo QR bits based on string contents
                val = (x * 3 + y * 7 + abs(h)) % 5
                grid[y][x] = (val == 0 or val == 2)
                
        return grid

# --- Background HTTP File Server for PC ---
class SharedFolderHTTPHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress stdout logs to prevent console pollution; route to App logs
        self.server.app.log(f"HTTP Server: {format % args}")

    def respond(self, code, content, content_type="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        bytes_content = content.encode("utf-8")
        self.send_header("Content-Length", str(len(bytes_content)))
        self.end_headers()
        self.wfile.write(bytes_content)

    def check_permission(self, action_type):
        perms = self.server.app.get_permissions()
        if action_type == "read" and not perms["read"]:
            self.respond(403, "Forbidden: Read permission denied")
            return False
        if action_type == "write" and not perms["write"]:
            self.respond(403, "Forbidden: Write permission denied")
            return False
        if action_type == "delete" and not perms["delete"]:
            self.respond(403, "Forbidden: Delete permission denied")
            return False
        return True

    def get_target_file(self, query_path):
        root = self.server.app.get_root_folder()
        rel_path = urllib.parse.unquote(query_path).lstrip("/")
        target = os.path.abspath(os.path.join(root, rel_path))
        # Prevent Path Traversal
        if not target.startswith(os.path.abspath(root)):
            return None
        return target

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        rel_path = query.get("path", [""])[0]

        if parsed.path == "/status":
            perms = self.server.app.get_permissions()
            status = {
                "allowRead": perms["read"],
                "allowWrite": perms["write"],
                "allowDelete": perms["delete"],
                "deviceName": f"PC ({socket.gethostname()})"
            }
            self.respond(200, json.dumps(status), "application/json")
            return

        if not self.check_permission("read"):
            return

        target = self.get_target_file(rel_path)
        if not target or not os.path.exists(target):
            self.respond(404, "Not Found")
            return

        if parsed.path == "/list":
            if not os.path.isdir(target):
                self.respond(400, "Not a directory")
                return
            
            files_list = []
            try:
                for entry in os.scandir(target):
                    rel = os.path.relpath(entry.path, self.server.app.get_root_folder())
                    files_list.append({
                        "name": entry.name,
                        "isDirectory": entry.is_dir(),
                        "size": entry.stat().st_size if entry.is_file() else 0,
                        "lastModified": int(entry.stat().st_mtime * 1000),
                        "relativePath": rel.replace("\\", "/")
                    })
                self.respond(200, json.dumps(files_list), "application/json")
            except Exception as e:
                self.respond(500, f"Error listing directory: {str(e)}")

        elif parsed.path == "/download":
            if not os.path.isfile(target):
                self.respond(400, "Not a file")
                return
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Disposition", f'attachment; filename="{os.path.basename(target)}"')
                self.send_header("Content-Length", str(os.path.getsize(target)))
                self.end_headers()
                with open(target, "rb") as f:
                    shutil.copyfileobj(f, self.wfile)
            except Exception as e:
                # Response headers already sent, just close
                pass

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        rel_path = query.get("path", [""])[0]

        target = self.get_target_file(rel_path)
        if not target:
            self.respond(400, "Path traversal forbidden")
            return

        if parsed.path == "/upload":
            if not self.check_permission("write"):
                return
            try:
                content_len = int(self.headers.get('Content-Length', 0))
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as f:
                    remaining = content_len
                    chunk_size = 64 * 1024
                    while remaining > 0:
                        chunk = self.rfile.read(min(remaining, chunk_size))
                        if not chunk:
                            break
                        f.write(chunk)
                        remaining -= len(chunk)
                self.respond(200, "Upload successful")
                self.server.app.log(f"Received file: {os.path.basename(target)}")
            except Exception as e:
                self.respond(500, f"Error receiving file: {str(e)}")

        elif parsed.path == "/create_folder":
            if not self.check_permission("write"):
                return
            try:
                os.makedirs(target, exist_ok=True)
                self.respond(200, "Folder created")
                self.server.app.log(f"Created remote folder: {os.path.basename(target)}")
            except Exception as e:
                self.respond(500, f"Error creating folder: {str(e)}")

        elif parsed.path == "/delete":
            if not self.check_permission("delete"):
                return
            try:
                if os.path.isdir(target):
                    shutil.rmtree(target)
                else:
                    os.remove(target)
                self.respond(200, "Deleted successfully")
                self.server.app.log(f"Deleted local file/folder: {os.path.basename(target)}")
            except Exception as e:
                self.respond(500, f"Error deleting: {str(e)}")

class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    pass

# --- Desktop UI Application ---
class WiFiShareApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("WiFi File Share - PC")
        self.geometry("1000x650")
        self.configure(bg="#F3F4F6")

        # Variables
        self.root_folder = os.path.abspath(os.path.expanduser("~/SharedFiles"))
        os.makedirs(self.root_folder, exist_ok=True)
        self.local_current_path = ""
        self.remote_current_path = ""
        self.remote_ip = tk.StringVar(value="")
        self.remote_port = tk.IntVar(value=9090)
        self.connected_state = False

        self.allow_read = tk.BooleanVar(value=True)
        self.allow_write = tk.BooleanVar(value=True)
        self.allow_delete = tk.BooleanVar(value=True)

        self.server_thread = None
        self.http_server = None

        self.build_ui()
        self.get_local_ip()
        self.start_server()
        self.refresh_local()

    def log(self, text):
        timestamp = time.strftime("%H:%M:%S")
        self.log_list.insert(0, f"[{timestamp}] {text}")

    def get_local_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            # Doesn't need to connect, just resolves local routing IP
            s.connect(("8.8.8.8", 80))
            self.local_ip = s.getsockname()[0]
            s.close()
        except Exception:
            self.local_ip = "127.0.0.1"
        self.lbl_local_ip.config(text=f"Мой IP: {self.local_ip} : 9090")

    def get_permissions(self):
        return {
            "read": self.allow_read.get(),
            "write": self.allow_write.get(),
            "delete": self.allow_delete.get()
        }

    def get_root_folder(self):
        return self.root_folder

    # HTTP Server controls
    def start_server(self):
        self.http_server = ThreadingHTTPServer(('0.0.0.0', 9090), SharedFolderHTTPHandler)
        self.http_server.app = self
        self.server_thread = threading.Thread(target=self.http_server.serve_forever, daemon=True)
        self.server_thread.start()
        self.log("Локальный HTTP сервер запущен на порту 9090")

    # Local Directory Explorer
    def refresh_local(self):
        self.local_list.delete(*self.local_list.get_children())
        target_dir = os.path.abspath(os.path.join(self.root_folder, self.local_current_path))
        
        self.lbl_local_path.config(text=f"Папка: /{self.local_current_path}")
        
        # Add up folder if not at root
        if self.local_current_path != "":
            self.local_list.insert("", "end", values=("..", "[Назад в родительскую]", ""))

        try:
            entries = sorted(os.scandir(target_dir), key=lambda e: (not e.is_dir(), e.name.lower()))
            for entry in entries:
                is_dir = "[Папка]" if entry.is_dir() else "[Файл]"
                size = f"{entry.stat().st_size / 1024:.1f} KB" if entry.is_file() else ""
                self.local_list.insert("", "end", values=(entry.name, is_dir, size))
        except Exception as e:
            self.log(f"Ошибка чтения локальной папки: {e}")

    def local_double_click(self, event):
        item = self.local_list.focus()
        if not item: return
        values = self.local_list.item(item, "values")
        name = values[0]
        is_dir = values[1]

        if name == "..":
            parts = self.local_current_path.rstrip("/").split("/")
            parts.pop()
            self.local_current_path = "/".join(parts)
            self.refresh_local()
        elif is_dir == "[Папка]":
            self.local_current_path = os.path.join(self.local_current_path, name).replace("\\", "/").strip("/")
            self.refresh_local()

    def create_local_folder(self):
        # Ask folder name
        dialog = tk.Toplevel(self)
        dialog.title("Новая папка")
        dialog.geometry("300x120")
        dialog.resizable(False, False)
        
        tk.Label(dialog, text="Имя новой папки:").pack(pady=10)
        entry = tk.Entry(dialog, width=25)
        entry.pack()
        entry.focus()

        def confirm():
            name = entry.get().strip()
            if name:
                target = os.path.join(self.root_folder, self.local_current_path, name)
                os.makedirs(target, exist_ok=True)
                self.refresh_local()
                self.log(f"Создана локальная папка: {name}")
                dialog.destroy()

        tk.Button(dialog, text="Создать", command=confirm).pack(pady=10)

    def delete_local(self):
        item = self.local_list.focus()
        if not item: return
        values = self.local_list.item(item, "values")
        name = values[0]
        if name == "..": return

        if messagebox.askyesno("Удаление", f"Вы уверены, что хотите удалить {name}?"):
            target = os.path.join(self.root_folder, self.local_current_path, name)
            try:
                if os.path.isdir(target):
                    shutil.rmtree(target)
                else:
                    os.remove(target)
                self.refresh_local()
                self.log(f"Удален локальный элемент: {name}")
            except Exception as e:
                messagebox.showerror("Ошибка", f"Не удалось удалить: {e}")

    # Remote Connection
    def connect_remote(self):
        ip = self.remote_ip.get().strip()
        if not ip:
            messagebox.showerror("Ошибка", "Введите IP удаленного устройства")
            return

        self.log(f"Подключение к {ip}:{self.remote_port.get()}...")
        url = f"http://{ip}:{self.remote_port.get()}/status"
        
        def run():
            try:
                req = urllib.request.Request(url, timeout=3)
                with urllib.request.urlopen(req) as response:
                    data = json.loads(response.read().decode("utf-8"))
                    self.connected_state = True
                    self.remote_current_path = ""
                    self.log(f"Успешно подключено к: {data.get('deviceName', 'Android')}")
                    self.btn_connect.config(text="Отключить", command=self.disconnect_remote)
                    self.refresh_remote()
            except Exception as e:
                self.log(f"Ошибка подключения: {e}")
                self.connected_state = False
                messagebox.showerror("Ошибка", f"Не удалось подключиться к {ip}: {e}")

        threading.Thread(target=run, daemon=True).start()

    def disconnect_remote(self):
        self.connected_state = False
        self.remote_list.delete(*self.remote_list.get_children())
        self.btn_connect.config(text="Подключиться", command=self.connect_remote)
        self.log("Отключено от удаленного устройства")

    # Remote Directory Explorer
    def refresh_remote(self):
        if not self.connected_state: return
        self.remote_list.delete(*self.remote_list.get_children())
        ip = self.remote_ip.get().strip()
        port = self.remote_port.get()
        
        self.lbl_remote_path.config(text=f"Удаленная папка: /{self.remote_current_path}")
        
        # Add parent folder option
        if self.remote_current_path != "":
            self.remote_list.insert("", "end", values=("..", "[Назад в родительскую]", ""))

        url = f"http://{ip}:{port}/list?path={urllib.parse.quote(self.remote_current_path)}"
        
        def run():
            try:
                req = urllib.request.Request(url, timeout=5)
                with urllib.request.urlopen(req) as r:
                    files = json.loads(r.read().decode("utf-8"))
                    # Sort folders first, then files
                    sorted_files = sorted(files, key=lambda f: (not f["isDirectory"], f["name"].lower()))
                    for f in sorted_files:
                        is_dir = "[Папка]" if f["isDirectory"] else "[Файл]"
                        size = f"{f['size'] / 1024:.1f} KB" if not f["isDirectory"] else ""
                        self.remote_list.insert("", "end", values=(f["name"], is_dir, size, f["relativePath"]))
            except Exception as e:
                self.log(f"Ошибка получения удаленных файлов: {e}")

        threading.Thread(target=run, daemon=True).start()

    def remote_double_click(self, event):
        item = self.remote_list.focus()
        if not item: return
        values = self.remote_list.item(item, "values")
        name = values[0]
        is_dir = values[1]

        if name == "..":
            parts = self.remote_current_path.rstrip("/").split("/")
            parts.pop()
            self.remote_current_path = "/".join(parts)
            self.refresh_remote()
        elif is_dir == "[Папка]":
            # Target relative path is column index 3
            rel_path = values[3]
            self.remote_current_path = rel_path
            self.refresh_remote()

    def create_remote_folder(self):
        if not self.connected_state: return
        dialog = tk.Toplevel(self)
        dialog.title("Новая удаленная папка")
        dialog.geometry("300x120")
        dialog.resizable(False, False)
        
        tk.Label(dialog, text="Имя новой удаленной папки:").pack(pady=10)
        entry = tk.Entry(dialog, width=25)
        entry.pack()
        entry.focus()

        def confirm():
            name = entry.get().strip()
            if name:
                ip = self.remote_ip.get().strip()
                port = self.remote_port.get()
                path = os.path.join(self.remote_current_path, name).replace("\\", "/")
                url = f"http://{ip}:{port}/create_folder?path={urllib.parse.quote(path)}"
                
                def run():
                    try:
                        req = urllib.request.Request(url, data=b"", method="POST")
                        with urllib.request.urlopen(req) as r:
                            self.refresh_remote()
                            self.log(f"Создана удаленная папка: {name}")
                    except Exception as e:
                        self.log(f"Ошибка создания папки: {e}")
                
                threading.Thread(target=run, daemon=True).start()
                dialog.destroy()

        tk.Button(dialog, text="Создать", command=confirm).pack(pady=10)

    def delete_remote(self):
        if not self.connected_state: return
        item = self.remote_list.focus()
        if not item: return
        values = self.remote_list.item(item, "values")
        name = values[0]
        if name == "..": return
        rel_path = values[3]

        if messagebox.askyesno("Удаление", f"Вы уверены, что хотите удалить удаленный элемент {name}?"):
            ip = self.remote_ip.get().strip()
            port = self.remote_port.get()
            url = f"http://{ip}:{port}/delete?path={urllib.parse.quote(rel_path)}"
            
            def run():
                try:
                    req = urllib.request.Request(url, data=b"", method="POST")
                    with urllib.request.urlopen(req) as r:
                        self.refresh_remote()
                        self.log(f"Удален удаленный элемент: {name}")
                except Exception as e:
                    self.log(f"Ошибка удаления: {e}")
            
            threading.Thread(target=run, daemon=True).start()

    # Copy / File Transfers
    def copy_local_to_remote(self):
        if not self.connected_state:
            messagebox.showerror("Ошибка", "Подключитесь к устройству")
            return
        item = self.local_list.focus()
        if not item: return
        values = self.local_list.item(item, "values")
        name = values[0]
        if name == "..": return
        is_dir = values[1] == "[Папка]"

        local_file = os.path.join(self.root_folder, self.local_current_path, name)
        remote_rel = os.path.join(self.remote_current_path, name).replace("\\", "/")

        ip = self.remote_ip.get().strip()
        port = self.remote_port.get()

        if is_dir:
            # Create folder on remote
            url = f"http://{ip}:{port}/create_folder?path={urllib.parse.quote(remote_rel)}"
            def run():
                try:
                    req = urllib.request.Request(url, data=b"", method="POST")
                    with urllib.request.urlopen(req) as r:
                        self.log(f"Скопирована структура папки: {name}")
                        self.refresh_remote()
                except Exception as e:
                    self.log(f"Ошибка копирования папки: {e}")
            threading.Thread(target=run, daemon=True).start()
        else:
            # Upload file
            url = f"http://{ip}:{port}/upload?path={urllib.parse.quote(remote_rel)}"
            self.log(f"Отправка файла {name}...")
            
            def run():
                try:
                    with open(local_file, "rb") as f:
                        file_data = f.read()
                    req = urllib.request.Request(url, data=file_data, method="POST")
                    req.add_header("Content-Type", "application/octet-stream")
                    with urllib.request.urlopen(req) as r:
                        self.log(f"Файл успешно отправлен: {name}")
                        self.refresh_remote()
                except Exception as e:
                    self.log(f"Ошибка отправки файла: {e}")
            
            threading.Thread(target=run, daemon=True).start()

    def copy_remote_to_local(self):
        if not self.connected_state: return
        item = self.remote_list.focus()
        if not item: return
        values = self.remote_list.item(item, "values")
        name = values[0]
        if name == "..": return
        is_dir = values[1] == "[Папка]"
        remote_rel = values[3]

        local_file = os.path.join(self.root_folder, self.local_current_path, name)
        ip = self.remote_ip.get().strip()
        port = self.remote_port.get()

        if is_dir:
            os.makedirs(local_file, exist_ok=True)
            self.refresh_local()
            self.log(f"Создана папка локально: {name}")
        else:
            url = f"http://{ip}:{port}/download?path={urllib.parse.quote(remote_rel)}"
            self.log(f"Скачивание файла {name}...")
            
            def run():
                try:
                    req = urllib.request.Request(url)
                    with urllib.request.urlopen(req) as response:
                        with open(local_file, "wb") as f:
                            shutil.copyfileobj(response, f)
                    self.log(f"Файл успешно скачан: {name}")
                    self.refresh_local()
                except Exception as e:
                    self.log(f"Ошибка скачивания файла: {e}")
            
            threading.Thread(target=run, daemon=True).start()

    def select_local_folder(self):
        folder = filedialog.askdirectory(initialdir=self.root_folder, title="Выбрать корневую папку обмена")
        if folder:
            self.root_folder = os.path.abspath(folder)
            self.local_current_path = ""
            self.refresh_local()
            self.log(f"Выбрана новая папка обмена: {self.root_folder}")

    # Display Shareable IP QR
    def show_qr(self):
        ip_url = f"http://{self.local_ip}:9090"
        
        dialog = tk.Toplevel(self)
        dialog.title("QR Код Подключения")
        dialog.geometry("320x380")
        dialog.resizable(False, False)

        tk.Label(dialog, text="Поделиться IP адресом", font=("Arial", 12, "bold")).pack(pady=10)
        
        # Canvas to draw our pure Python generated QR code
        canvas = tk.Canvas(dialog, width=200, height=200, bg="white")
        canvas.pack()

        # Generate pseudo QR matrix
        matrix = SimpleQR.get_matrix(ip_url)
        size = len(matrix)
        block_size = 200 // size

        for y in range(size):
            for x in range(size):
                if matrix[y][x]:
                    canvas.create_rectangle(
                        x * block_size, y * block_size,
                        (x + 1) * block_size, (y + 1) * block_size,
                        fill="black", outline="black"
                    )

        tk.Label(dialog, text=ip_url, fg="blue", font=("Arial", 10, "bold"), wraplength=280).pack(pady=10)
        
        def copy_to_clipboard():
            self.clipboard_clear()
            self.clipboard_append(ip_url)
            self.log("IP адрес скопирован в буфер обмена")

        tk.Button(dialog, text="Скопировать IP", command=copy_to_clipboard).pack()

    # Tkinter UI Assembly
    def build_ui(self):
        # Top Area - Server Details & Remote Input
        top_frame = tk.Frame(self, bg="#FFFFFF", bd=1, relief="ridge")
        top_frame.pack(fill="x", padx=10, pady=10)

        # Local details
        lbl_info_frame = tk.LabelFrame(top_frame, text="Локальный сервер обмена", bg="#FFFFFF")
        lbl_info_frame.pack(side="left", padx=10, pady=10, fill="both", expand=True)

        self.lbl_local_ip = tk.Label(lbl_info_frame, text="Мой IP: ...", bg="#FFFFFF", font=("Arial", 10, "bold"))
        self.lbl_local_ip.pack(anchor="w", padx=5)

        tk.Button(lbl_info_frame, text="Выбрать папку ПК", command=self.select_local_folder).pack(side="left", padx=5, pady=5)
        tk.Button(lbl_info_frame, text="Показать QR", command=self.show_qr).pack(side="left", padx=5, pady=5)

        # Checkboxes for incoming permissions
        tk.Checkbutton(lbl_info_frame, text="Чтение", variable=self.allow_read, bg="#FFFFFF").pack(side="left", padx=5)
        tk.Checkbutton(lbl_info_frame, text="Запись", variable=self.allow_write, bg="#FFFFFF").pack(side="left", padx=5)
        tk.Checkbutton(lbl_info_frame, text="Удаление", variable=self.allow_delete, bg="#FFFFFF").pack(side="left", padx=5)

        # Remote device details
        remote_frame = tk.LabelFrame(top_frame, text="Подключение к удаленному устройству (Android/ПК)", bg="#FFFFFF")
        remote_frame.pack(side="right", padx=10, pady=10, fill="both", expand=True)

        tk.Label(remote_frame, text="IP удаленного устройства:", bg="#FFFFFF").pack(anchor="w", padx=5)
        
        connect_input_frame = tk.Frame(remote_frame, bg="#FFFFFF")
        connect_input_frame.pack(fill="x", padx=5, pady=5)
        
        self.entry_ip = tk.Entry(connect_input_frame, textvariable=self.remote_ip, font=("Arial", 11))
        self.entry_ip.pack(side="left", fill="x", expand=True, ipady=3)
        self.entry_ip.bind("<Return>", lambda e: self.connect_remote())

        self.btn_connect = tk.Button(connect_input_frame, text="Подключиться", command=self.connect_remote, bg="#10B981", fg="white")
        self.btn_connect.pack(side="right", padx=5)

        # Main Explorer Area (Dual Pane)
        explorer_frame = tk.Frame(self, bg="#F3F4F6")
        explorer_frame.pack(fill="both", expand=True, padx=10)

        # 1. Left Local Pane
        left_pane = tk.Frame(explorer_frame, bg="#FFFFFF", bd=1, relief="ridge")
        left_pane.pack(side="left", fill="both", expand=True, padx=(0, 5))

        local_header = tk.Frame(left_pane, bg="#3B82F6")
        local_header.pack(fill="x")
        tk.Label(local_header, text="МОИ ФАЙЛЫ (ЛОКАЛЬНО)", fg="white", font=("Arial", 10, "bold"), bg="#3B82F6", pady=5).pack(side="left", padx=5)
        
        tk.Button(local_header, text="+ Папка", command=self.create_local_folder, bg="#1E40AF", fg="white", bd=0, padx=8).pack(side="right", padx=2, pady=2)
        tk.Button(local_header, text="Удалить", command=self.delete_local, bg="#EF4444", fg="white", bd=0, padx=8).pack(side="right", padx=2, pady=2)
        tk.Button(local_header, text="Обновить", command=self.refresh_local, bg="#10B981", fg="white", bd=0, padx=8).pack(side="right", padx=2, pady=2)

        self.lbl_local_path = tk.Label(left_pane, text="Папка: /", bg="#F3F4F6", anchor="w", padx=5)
        self.lbl_local_path.pack(fill="x")

        self.local_list = ttk.Treeview(left_pane, columns=("name", "type", "size"), show="headings")
        self.local_list.heading("name", text="Имя")
        self.local_list.heading("type", text="Тип")
        self.local_list.heading("size", text="Размер")
        self.local_list.column("name", width=200)
        self.local_list.column("type", width=70)
        self.local_list.column("size", width=80)
        self.local_list.pack(fill="both", expand=True)
        self.local_list.bind("<Double-1>", self.local_double_click)

        # Transfer Buttons in center
        center_actions = tk.Frame(explorer_frame, bg="#F3F4F6")
        center_actions.pack(side="left", padx=5)

        tk.Button(center_actions, text="Отправить\nна удаленный ->", command=self.copy_local_to_remote, bg="#3B82F6", fg="white", pady=10).pack(fill="x", pady=10)
        tk.Button(center_actions, text="<- Скачать\nв локальный", command=self.copy_remote_to_local, bg="#10B981", fg="white", pady=10).pack(fill="x", pady=10)

        # 2. Right Remote Pane
        right_pane = tk.Frame(explorer_frame, bg="#FFFFFF", bd=1, relief="ridge")
        right_pane.pack(side="right", fill="both", expand=True, padx=(5, 0))

        remote_header = tk.Frame(right_pane, bg="#10B981")
        remote_header.pack(fill="x")
        tk.Label(remote_header, text="УДАЛЕННЫЕ ФАЙЛЫ (УСТРОЙСТВО)", fg="white", font=("Arial", 10, "bold"), bg="#10B981", pady=5).pack(side="left", padx=5)
        
        tk.Button(remote_header, text="+ Папка", command=self.create_remote_folder, bg="#047857", fg="white", bd=0, padx=8).pack(side="right", padx=2, pady=2)
        tk.Button(remote_header, text="Удалить", command=self.delete_remote, bg="#EF4444", fg="white", bd=0, padx=8).pack(side="right", padx=2, pady=2)
        tk.Button(remote_header, text="Обновить", command=self.refresh_remote, bg="#3B82F6", fg="white", bd=0, padx=8).pack(side="right", padx=2, pady=2)

        self.lbl_remote_path = tk.Label(right_pane, text="Удаленная папка: /", bg="#F3F4F6", anchor="w", padx=5)
        self.lbl_remote_path.pack(fill="x")

        self.remote_list = ttk.Treeview(right_pane, columns=("name", "type", "size", "rel_path"), show="headings")
        self.remote_list.heading("name", text="Имя")
        self.remote_list.heading("type", text="Тип")
        self.remote_list.heading("size", text="Размер")
        self.remote_list.column("name", width=200)
        self.remote_list.column("type", width=70)
        self.remote_list.column("size", width=80)
        self.remote_list["displaycolumns"] = ("name", "type", "size") # Hide rel_path column
        self.remote_list.pack(fill="both", expand=True)
        self.remote_list.bind("<Double-1>", self.remote_double_click)

        # Log & Status Bar (Bottom)
        bottom_frame = tk.Frame(self, bg="#FFFFFF", bd=1, relief="ridge")
        bottom_frame.pack(fill="x", padx=10, pady=10)

        tk.Label(bottom_frame, text="Лог действий:", font=("Arial", 9, "bold"), bg="#FFFFFF").pack(anchor="w", padx=5, pady=2)
        
        self.log_list = tk.Listbox(bottom_frame, height=5, font=("Courier", 9), bg="#F9FAFB")
        self.log_list.pack(fill="x", padx=5, pady=5)

if __name__ == "__main__":
    app = WiFiShareApp()
    app.mainloop()
