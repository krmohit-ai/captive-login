package com.example.captivelogin

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.captivelogin.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { updateConnectivityUI() }
        }

        override fun onLost(network: Network) {
            runOnUiThread { updateConnectivityUI() }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            runOnUiThread { updateConnectivityUI() }
        }
    }

    private val loginReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LoginTileService.ACTION_QUICK_LOGIN) {
                binding.btnFillLogin.performClick()
            }
        }
    }

    private val prefsName = "creds"
    private val keyProfiles = "profiles_json"
    private val keySelectedProfileIndex = "selected_profile_index"
    private val keyPortalUrl = "portal_url"
    private val defaultPortalUrl = "https://10.0.112.2:8090/httpclient.html"

    private val profiles = mutableListOf<Profile>()
    private var selectedIndex = 0

    data class Profile(var name: String, var username: String, var password: String)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // ---- Portal URL ----
        val savedUrl = prefs.getString(keyPortalUrl, defaultPortalUrl) ?: defaultPortalUrl
        binding.etPortalUrl.setText(savedUrl)

        // ---- WebView ----
        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.loadsImagesAutomatically = true
        binding.webView.settings.useWideViewPort = true
        binding.webView.settings.loadWithOverviewMode = true

        binding.webView.webChromeClient = WebChromeClient()

        // Ignore SSL warnings (as you requested)
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.proceed()
            }
        }

        binding.webView.loadUrl(savedUrl)

        // Initial UI update
        updateConnectivityUI()

        // Register network callback
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Register Quick Login receiver
        val loginFilter = IntentFilter(LoginTileService.ACTION_QUICK_LOGIN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(loginReceiver, loginFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(loginReceiver, loginFilter)
        }

        // Check if started from Quick Settings Tile
        if (intent?.getBooleanExtra("trigger_login", false) == true) {
            binding.root.postDelayed({
                binding.btnFillLogin.performClick()
            }, 1000) // Small delay to ensure WebView is ready
        }

        // Save Portal URL button
        binding.btnSaveUrl.setOnClickListener {
            var newUrl = binding.etPortalUrl.text.toString().trim()
            if (newUrl.isEmpty()) {
                toast("URL cannot be empty")
                return@setOnClickListener
            }

            // Auto add https:// if missing
            if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                newUrl = "https://$newUrl"
            }

            prefs.edit().putString(keyPortalUrl, newUrl).apply()
            binding.webView.loadUrl(newUrl)
            toast("Portal URL saved")
        }

        // Reload Portal button (always reload saved URL)
        binding.btnReload.setOnClickListener {
            val reloadUrl = prefs.getString(keyPortalUrl, defaultPortalUrl) ?: defaultPortalUrl
            binding.webView.loadUrl(reloadUrl)
        }

        // ---- Profiles ----
        loadProfiles(prefs)

        selectedIndex = prefs.getInt(keySelectedProfileIndex, 0)
        if (selectedIndex < 0) selectedIndex = 0
        if (selectedIndex >= profiles.size) selectedIndex = profiles.size - 1

        setupProfileSpinner()
        applyProfileToFields(selectedIndex)

        // When user selects a profile from dropdown
        binding.spProfile.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                selectedIndex = position
                prefs.edit().putInt(keySelectedProfileIndex, selectedIndex).apply()
                applyProfileToFields(selectedIndex)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ADD profile button
        binding.btnAddProfile.setOnClickListener {
            val newProfile = Profile(
                name = "Profile ${profiles.size + 1}",
                username = "",
                password = ""
            )
            profiles.add(newProfile)
            saveProfiles(prefs)
            setupProfileSpinner()
            selectedIndex = profiles.size - 1
            binding.spProfile.setSelection(selectedIndex)
            applyProfileToFields(selectedIndex)
            toast("Profile added")
        }

        // DELETE profile button
        binding.btnDeleteProfile.setOnClickListener {
            if (profiles.size <= 1) {
                toast("Cannot delete last profile")
                return@setOnClickListener
            }
            profiles.removeAt(selectedIndex)
            if (selectedIndex >= profiles.size) selectedIndex = profiles.size - 1
            saveProfiles(prefs)
            setupProfileSpinner()
            binding.spProfile.setSelection(selectedIndex)
            applyProfileToFields(selectedIndex)
            toast("Profile deleted")
        }

        // SAVE credentials to current profile
        binding.btnSave.setOnClickListener {
            val u = binding.etUsername.text.toString()
            val p = binding.etPassword.text.toString()

            val prof = profiles[selectedIndex]
            prof.username = u
            prof.password = p
            saveProfiles(prefs)

            toast("Saved to ${prof.name}")
        }

        // Fill + Login using current profile values
        binding.btnFillLogin.setOnClickListener {
            val u = binding.etUsername.text.toString()
            val p = binding.etPassword.text.toString()

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

            binding.webView.evaluateJavascript(js, null)
        }

        // Wi-Fi Toggle
        binding.btnWifi.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires using Settings Panel for toggling Wi-Fi
                val intent = Intent(Settings.Panel.ACTION_WIFI)
                startActivity(intent)
            } else {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = !wifiManager.isWifiEnabled
                toast("Toggling Wi-Fi...")
            }
        }

        // Mobile Data Settings
        binding.btnData.setOnClickListener {
            val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Callback might not be registered
        }
        try {
            unregisterReceiver(loginReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("trigger_login", false)) {
            binding.btnFillLogin.performClick()
        }
    }

    private fun updateConnectivityUI() {
        // Wi-Fi Status
        val isWifiEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android 10+, wifiManager.isWifiEnabled is still reliable for status check
            wifiManager.isWifiEnabled
        } else {
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled
        }

        val wifiColor = if (isWifiEnabled) "#16A34A" else "#9CA3AF"
        binding.btnWifi.backgroundTintList = ColorStateList.valueOf(Color.parseColor(wifiColor))

        // Mobile Data Status
        val isDataEnabled = isMobileDataEnabled()
        val dataColor = if (isDataEnabled) "#16A34A" else "#9CA3AF"
        binding.btnData.backgroundTintList = ColorStateList.valueOf(Color.parseColor(dataColor))
    }

    private fun isMobileDataEnabled(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, "mobile_data", 0) == 1
        } catch (e: Exception) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
    }

    private fun setupProfileSpinner() {
        val names = profiles.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spProfile.adapter = adapter
    }

    private fun applyProfileToFields(index: Int) {
        if (index < 0 || index >= profiles.size) return
        val prof = profiles[index]
        binding.etUsername.setText(prof.username)
        binding.etPassword.setText(prof.password)
    }

    private fun loadProfiles(prefs: android.content.SharedPreferences) {
        profiles.clear()

        val json = prefs.getString(keyProfiles, null)
        if (json.isNullOrEmpty()) {
            // First run: create default profile
            profiles.add(Profile("Profile 1", "", ""))
            saveProfiles(prefs)
            return
        }

        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                profiles.add(
                    Profile(
                        name = obj.optString("name", "Profile ${i + 1}"),
                        username = obj.optString("username", ""),
                        password = obj.optString("password", "")
                    )
                )
            }

            if (profiles.isEmpty()) {
                profiles.add(Profile("Profile 1", "", ""))
            }
        } catch (e: Exception) {
            // If corrupted JSON, reset safely
            profiles.add(Profile("Profile 1", "", ""))
            saveProfiles(prefs)
        }
    }

    private fun saveProfiles(prefs: android.content.SharedPreferences) {
        val arr = JSONArray()
        for (p in profiles) {
            val obj = JSONObject()
            obj.put("name", p.name)
            obj.put("username", p.username)
            obj.put("password", p.password)
            arr.put(obj)
        }
        prefs.edit().putString(keyProfiles, arr.toString()).apply()
    }

    private fun jsString(s: String): String {
        return "\"" + s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
