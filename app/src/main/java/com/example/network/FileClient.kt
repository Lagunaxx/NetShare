package com.example.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FileClient(private val onLog: (String) -> Unit) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun testConnection(ip: String, port: Int, onSuccess: (JSONObject) -> Unit, onError: (String) -> Unit) {
        val url = "http://$ip:$port/status"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Unknown network error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        onError("Server returned code ${response.code}")
                        return
                    }
                    try {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        onSuccess(json)
                    } catch (e: Exception) {
                        onError("Invalid response format: ${e.message}")
                    }
                }
            }
        })
    }

    fun listFiles(ip: String, port: Int, relativePath: String, onSuccess: (List<RemoteFile>) -> Unit, onError: (String) -> Unit) {
        val encodedPath = URLEncoder.encode(relativePath, "UTF-8")
        val url = "http://$ip:$port/list?path=$encodedPath"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        val msg = response.body?.string() ?: "Unknown error"
                        onError("Server error (${response.code}): $msg")
                        return
                    }
                    try {
                        val body = response.body?.string() ?: ""
                        val arr = JSONArray(body)
                        val files = mutableListOf<RemoteFile>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            files.add(
                                RemoteFile(
                                    name = obj.getString("name"),
                                    isDirectory = obj.getBoolean("isDirectory"),
                                    size = obj.getLong("size"),
                                    lastModified = obj.getLong("lastModified"),
                                    relativePath = obj.getString("relativePath")
                                )
                            )
                        }
                        onSuccess(files)
                    } catch (e: Exception) {
                        onError("Parse error: ${e.message}")
                    }
                }
            }
        })
    }

    fun downloadFile(
        ip: String,
        port: Int,
        remoteRelPath: String,
        localDestFile: File,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val encodedPath = URLEncoder.encode(remoteRelPath, "UTF-8")
        val url = "http://$ip:$port/download?path=$encodedPath"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val msg = response.body?.string() ?: "Unknown error"
                    onError("Failed to download file (${response.code}): $msg")
                    return
                }

                try {
                    val body = response.body ?: throw IOException("Empty response body")
                    val totalBytes = body.contentLength()
                    val inputStream = body.byteStream()
                    localDestFile.parentFile?.mkdirs()
                    val outputStream = FileOutputStream(localDestFile)

                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalBytes > 0) {
                            onProgress(totalRead.toFloat() / totalBytes)
                        }
                    }

                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    onSuccess()
                } catch (e: Exception) {
                    onError("Write error: ${e.message}")
                }
            }
        })
    }

    fun uploadFile(
        ip: String,
        port: Int,
        remoteRelPath: String,
        localSourceFile: File,
        onProgress: (Float) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val encodedPath = URLEncoder.encode(remoteRelPath, "UTF-8")
        val url = "http://$ip:$port/upload?path=$encodedPath"

        val requestBody = object : RequestBody() {
            override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()

            override fun contentLength(): Long = localSourceFile.length()

            override fun writeTo(sink: okio.BufferedSink) {
                val fileSource = localSourceFile.source()
                val buffer = okio.Buffer()
                var bytesRead: Long
                var totalWritten = 0L
                val totalBytes = contentLength()

                while (fileSource.read(buffer, 8192).also { bytesRead = it } != -1L) {
                    sink.write(buffer, bytesRead)
                    totalWritten += bytesRead
                    if (totalBytes > 0) {
                        onProgress(totalWritten.toFloat() / totalBytes)
                    }
                }
                fileSource.close()
            }
        }

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        onSuccess()
                    } else {
                        val msg = response.body?.string() ?: "Unknown error"
                        onError("Upload failed (${response.code}): $msg")
                    }
                }
            }
        })
    }

    fun createFolder(ip: String, port: Int, remoteRelPath: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val encodedPath = URLEncoder.encode(remoteRelPath, "UTF-8")
        val url = "http://$ip:$port/create_folder?path=$encodedPath"
        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        onSuccess()
                    } else {
                        val msg = response.body?.string() ?: "Unknown error"
                        onError("Create folder failed (${response.code}): $msg")
                    }
                }
            }
        })
    }

    fun deleteFile(ip: String, port: Int, remoteRelPath: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val encodedPath = URLEncoder.encode(remoteRelPath, "UTF-8")
        val url = "http://$ip:$port/delete?path=$encodedPath"
        val request = Request.Builder()
            .url(url)
            .post(RequestBody.create(null, ByteArray(0)))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e.message ?: "Unknown error")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        onSuccess()
                    } else {
                        val msg = response.body?.string() ?: "Unknown error"
                        onError("Delete failed (${response.code}): $msg")
                    }
                }
            }
        })
    }
}

data class RemoteFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val relativePath: String
)
