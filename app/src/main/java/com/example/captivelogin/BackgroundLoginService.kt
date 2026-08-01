package com.example.captivelogin

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

class BackgroundLoginService : Service() {

    private var webView: WebView? = null
    private val channelId = "background_login_channel"
    private val notificationId = 101
    private var hasInjected = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = createNotification("Preparing background login...")
        startForeground(notificationId, notification)

        val prefs = getSharedPreferences("creds", Context.MODE_PRIVATE)
        val portalUrl = prefs.getString("portal_url", "https://10.0.112.2:8090/httpclient.html") ?: ""
        
        // Load profiles to get current credentials
        val profilesJson = prefs.getString("profiles_json", null)
        val selectedIndex = prefs.getInt("selected_profile_index", 0)
        
        var username = ""
        var password = ""
        
        try {
            if (!profilesJson.isNullOrEmpty()) {
                val arr = JSONArray(profilesJson)
                if (selectedIndex in 0 until arr.length()) {
                    val obj = arr.getJSONObject(selectedIndex)
                    username = obj.optString("username", "")
                    password = obj.optString("password", "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (username.isEmpty() || password.isEmpty()) {
            showToast("No credentials found for background login")
            stopSelf()
            return START_NOT_STICKY
        }

        Handler(Looper.getMainLooper()).post {
            webView = WebView(applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                
                webViewClient = object : WebViewClient() {
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.proceed()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!hasInjected) {
                            hasInjected = true
                            updateNotification("Filling credentials...")
                            injectLoginScript(username, password)
                        }
                    }
                }
                loadUrl(portalUrl)
            }
        }

        return START_NOT_STICKY
    }

    private fun injectLoginScript(u: String, p: String) {
        val js = """
            (function() {
                var uEl = document.getElementById('username');
                var pEl = document.getElementById('password');
                if(uEl) uEl.value = ${jsString(u)};
                if(pEl) pEl.value = ${jsString(p)};
                if(typeof submitRequest === 'function') {
                    submitRequest();
                    return 'submitted';
                }
                return 'submitRequest not found';
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js) { result ->
            if (result.contains("submitted")) {
                updateNotification("Login request submitted")
            } else {
                updateNotification("Login failed: $result")
            }
            // Give it a moment to actually send the request before killing service
            Handler(Looper.getMainLooper()).postDelayed({
                stopSelf()
            }, 5000)
        }
    }

    private fun jsString(s: String): String {
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId, "Background Login Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Quick Login")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }
}
