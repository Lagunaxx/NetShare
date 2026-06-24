package com.example.network

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FileServer(
    private val getSharedRoot: () -> File,
    private val getPermissions: () -> ConnectionPermissions,
    private val onLog: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    val port = 9090

    data class ConnectionPermissions(
        var allowRead: Boolean = true,
        var allowWrite: Boolean = true,
        var allowDelete: Boolean = true
    )

    fun start(): String? {
        try {
            serverSocket = ServerSocket(port)
            executor = Executors.newCachedThreadPool()
            onLog("Server started on port $port")
            
            Thread {
                while (serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val socket = serverSocket!!.accept()
                        executor?.submit {
                            handleClient(socket)
                        }
                    } catch (e: Exception) {
                        // socket closed or error
                    }
                }
            }.start()
            
            return "http://localhost:$port"
        } catch (e: Exception) {
            onLog("Server failed to start: ${e.message}")
            Log.e("FileServer", "Start error", e)
        }
        return null
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        onLog("Server stopped")
    }

    private fun readHeaderLine(input: InputStream): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        var c: Int
        while (input.read().also { c = it } != -1) {
            if (c == '\n'.toInt()) {
                break
            }
            if (c != '\r'.toInt()) {
                byteArrayOutputStream.write(c)
            }
        }
        return byteArrayOutputStream.toString("UTF-8")
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. Read request line
            val requestLine = readHeaderLine(input)
            if (requestLine.isEmpty()) return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendErrorResponse(output, 400, "Bad Request")
                return
            }
            val method = parts[0]
            val fullUrl = parts[1]

            // 2. Read headers
            var contentLength = 0L
            while (true) {
                val line = readHeaderLine(input)
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substring(15).trim().toLongOrNull() ?: 0L
                }
            }

            val urlParts = fullUrl.split("?", limit = 2)
            val path = urlParts[0]
            val query = if (urlParts.size > 1) urlParts[1] else ""
            val params = parseQuery(query)

            when (path) {
                "/status" -> handleStatus(output)
                "/list" -> handleList(output, params)
                "/download" -> handleDownload(output, params)
                "/upload" -> handleUpload(input, output, params, contentLength)
                "/create_folder" -> handleCreateFolder(output, params, method)
                "/delete" -> handleDelete(output, params, method)
                else -> sendErrorResponse(output, 404, "Not Found")
            }
        } catch (e: Exception) {
            Log.e("FileServer", "Client error", e)
        } finally {
            try {
                socket.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun handleStatus(output: OutputStream) {
        try {
            val obj = JSONObject()
            obj.put("allowRead", getPermissions().allowRead)
            obj.put("allowWrite", getPermissions().allowWrite)
            obj.put("allowDelete", getPermissions().allowDelete)
            obj.put("deviceName", android.os.Build.MODEL)
            sendResponse(output, 200, obj.toString(), "application/json")
        } catch (e: Exception) {
            sendErrorResponse(output, 500, "Error: ${e.message}")
        }
    }

    private fun handleList(output: OutputStream, params: Map<String, String>) {
        try {
            if (!getPermissions().allowRead) {
                sendErrorResponse(output, 403, "Read permission denied")
                return
            }

            val relPath = params["path"] ?: ""
            val decodedRelPath = URLDecoder.decode(relPath, "UTF-8")

            val root = getSharedRoot()
            val targetDir = File(root, decodedRelPath).canonicalFile

            if (!targetDir.absolutePath.startsWith(root.absolutePath)) {
                sendErrorResponse(output, 400, "Path traversal forbidden")
                return
            }

            if (!targetDir.exists() || !targetDir.isDirectory) {
                sendErrorResponse(output, 404, "Directory not found")
                return
            }

            val filesArray = JSONArray()
            val listFiles = targetDir.listFiles() ?: emptyArray()
            for (f in listFiles) {
                val obj = JSONObject()
                obj.put("name", f.name)
                obj.put("isDirectory", f.isDirectory)
                obj.put("size", if (f.isDirectory) 0 else f.length())
                obj.put("lastModified", f.lastModified())
                val relative = f.absolutePath.removePrefix(root.absolutePath).removePrefix("/")
                obj.put("relativePath", relative)
                filesArray.put(obj)
            }

            sendResponse(output, 200, filesArray.toString(), "application/json")
        } catch (e: Exception) {
            sendErrorResponse(output, 500, "Error: ${e.message}")
        }
    }

    private fun handleDownload(output: OutputStream, params: Map<String, String>) {
        try {
            if (!getPermissions().allowRead) {
                sendErrorResponse(output, 403, "Read permission denied")
                return
            }

            val relPath = params["path"] ?: ""
            val decodedRelPath = URLDecoder.decode(relPath, "UTF-8")

            val root = getSharedRoot()
            val file = File(root, decodedRelPath).canonicalFile

            if (!file.absolutePath.startsWith(root.absolutePath)) {
                sendErrorResponse(output, 400, "Path traversal forbidden")
                return
            }

            if (!file.exists() || file.isDirectory) {
                sendErrorResponse(output, 404, "File not found")
                return
            }

            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Disposition: attachment; filename=\"${file.name}\"\r\n" +
                    "Content-Length: ${file.length()}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))

            val fileInputStream = FileInputStream(file)
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
            fileInputStream.close()
            output.flush()
        } catch (e: Exception) {
            try {
                sendErrorResponse(output, 500, "Error: ${e.message}")
            } catch (ignored: Exception) {}
        }
    }

    private fun handleUpload(input: InputStream, output: OutputStream, params: Map<String, String>, contentLength: Long) {
        try {
            if (!getPermissions().allowWrite) {
                sendErrorResponse(output, 403, "Write permission denied")
                return
            }

            val relPath = params["path"] ?: ""
            val decodedRelPath = URLDecoder.decode(relPath, "UTF-8")

            val root = getSharedRoot()
            val destFile = File(root, decodedRelPath).canonicalFile

            if (!destFile.absolutePath.startsWith(root.absolutePath)) {
                sendErrorResponse(output, 400, "Path traversal forbidden")
                return
            }

            destFile.parentFile?.mkdirs()

            val fileOutputStream = FileOutputStream(destFile)
            val buffer = ByteArray(64 * 1024)
            var totalRead = 0L
            while (totalRead < contentLength) {
                val remaining = contentLength - totalRead
                val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                val bytesRead = input.read(buffer, 0, toRead)
                if (bytesRead == -1) break
                fileOutputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
            }
            fileOutputStream.close()

            sendResponse(output, 200, "Upload successful")
            onLog("Uploaded file: ${destFile.name}")
        } catch (e: Exception) {
            sendErrorResponse(output, 500, "Error: ${e.message}")
        }
    }

    private fun handleCreateFolder(output: OutputStream, params: Map<String, String>, method: String) {
        try {
            if (!getPermissions().allowWrite) {
                sendErrorResponse(output, 403, "Write permission denied")
                return
            }

            if (!method.equals("POST", ignoreCase = true)) {
                sendErrorResponse(output, 405, "Method not allowed")
                return
            }

            val relPath = params["path"] ?: ""
            val decodedRelPath = URLDecoder.decode(relPath, "UTF-8")

            val root = getSharedRoot()
            val newFolder = File(root, decodedRelPath).canonicalFile

            if (!newFolder.absolutePath.startsWith(root.absolutePath)) {
                sendErrorResponse(output, 400, "Path traversal forbidden")
                return
            }

            if (newFolder.exists()) {
                sendErrorResponse(output, 400, "Folder or file already exists")
                return
            }

            val created = newFolder.mkdirs()
            if (created) {
                sendResponse(output, 200, "Folder created")
                onLog("Created folder: ${newFolder.name}")
            } else {
                sendErrorResponse(output, 500, "Failed to create folder")
            }
        } catch (e: Exception) {
            sendErrorResponse(output, 500, "Error: ${e.message}")
        }
    }

    private fun handleDelete(output: OutputStream, params: Map<String, String>, method: String) {
        try {
            if (!getPermissions().allowDelete) {
                sendErrorResponse(output, 403, "Delete permission denied")
                return
            }

            if (!method.equals("POST", ignoreCase = true)) {
                sendErrorResponse(output, 405, "Method not allowed")
                return
            }

            val relPath = params["path"] ?: ""
            val decodedRelPath = URLDecoder.decode(relPath, "UTF-8")

            val root = getSharedRoot()
            val fileToDelete = File(root, decodedRelPath).canonicalFile

            if (!fileToDelete.absolutePath.startsWith(root.absolutePath)) {
                sendErrorResponse(output, 400, "Path traversal forbidden")
                return
            }

            if (!fileToDelete.exists()) {
                sendErrorResponse(output, 404, "File not found")
                return
            }

            val deleted = deleteRecursively(fileToDelete)
            if (deleted) {
                sendResponse(output, 200, "Deleted successfully")
                onLog("Deleted: ${fileToDelete.name}")
            } else {
                sendErrorResponse(output, 500, "Failed to delete")
            }
        } catch (e: Exception) {
            sendErrorResponse(output, 500, "Error: ${e.message}")
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursively(child)
                }
            }
        }
        return file.delete()
    }

    private fun sendResponse(output: OutputStream, code: Int, content: String, contentType: String = "text/plain") {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun sendErrorResponse(output: OutputStream, code: Int, message: String) {
        sendResponse(output, code, message, "text/plain")
    }

    private fun parseQuery(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        if (query.isEmpty()) return params
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                params[key] = value
            }
        }
        return params
    }
}
