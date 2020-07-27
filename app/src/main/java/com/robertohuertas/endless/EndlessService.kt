package com.robertohuertas.endless

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.github.kittinunf.fuel.Fuel
import com.github.kittinunf.fuel.core.extensions.jsonBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*


class EndlessService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceStarted = false
    private var mLocationManager: LocationManager? = null
    private val LOCATION_INTERVAL = 1000L
    private val LOCATION_DISTANCE = 1f
    override fun onBind(intent: Intent): IBinder? {
        log("Some component want to bind with the service")
        // We don't provide binding, so return null
        return null
    }

    class LocationListener(provider: String) :
        android.location.LocationListener {
        var mLastLocation: Location
        override fun onLocationChanged(location: Location) {
            log("onLocationChanged: $location")
            mLastLocation.set(location)
        }

        override fun onProviderDisabled(provider: String) {
            log("onProviderDisabled: $provider")
        }

        override fun onProviderEnabled(provider: String) {
            log("onProviderEnabled: $provider")
        }

        override fun onStatusChanged(
            provider: String,
            status: Int,
            extras: Bundle
        ) {
            log("onStatusChanged: $provider")
        }

        init {
            log("LocationListener $provider")
            mLastLocation = Location(provider)
        }
    }

    var mLocationListeners = arrayOf(
        LocationListener(LocationManager.GPS_PROVIDER),
        LocationListener(LocationManager.NETWORK_PROVIDER)
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("onStartCommand executed with startId: $startId")
        if (intent != null) {
            val action = intent.action
            log("using an intent with action $action")
            when (action) {
                Actions.START.name -> startService()
                Actions.STOP.name -> stopService()
                else -> log("This should never happen. No action in the received intent")
            }
        } else {
            log(
                "with a null intent. It has been probably restarted by the system."
            )
        }
        // by returning this we make sure the service is restarted if the system kills the service
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        log("The service has been created".toUpperCase())
        val notification = createNotification()
        startForeground(1, notification)
        initializeLocationManager()
        try {
            mLocationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, LOCATION_INTERVAL, LOCATION_DISTANCE,
                mLocationListeners[1]
            )
        } catch (ex: SecurityException) {
            log(  "fail to request location update, ignore")
        } catch (ex: IllegalArgumentException) {
            log(  "network provider does not exist, " + ex.message)
        }
        try {
            mLocationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, LOCATION_INTERVAL, LOCATION_DISTANCE,
                mLocationListeners[0]
            )
        } catch (ex: SecurityException) {
            log(  "fail to request location update, ignore")
        } catch (ex: IllegalArgumentException) {
            log(  "gps provider does not exist " + ex.message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        log("The service has been destroyed".toUpperCase())
        Toast.makeText(this, "Service destroyed", Toast.LENGTH_SHORT).show()
        if (mLocationManager != null) {
            for (element in mLocationListeners) {
                try {
                    mLocationManager?.removeUpdates(element)
                } catch (ex: java.lang.Exception) {
                    log( "fail to remove location listners, ignore")
                }
            }
        }
    }
    private fun initializeLocationManager() {
        log( "initializeLocationManager")
        if (mLocationManager == null) {
            mLocationManager =
                applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }
    }
    private fun startService() {
        if (isServiceStarted) return
        log("Starting the foreground service task")
        Toast.makeText(this, "Service starting its task", Toast.LENGTH_SHORT).show()
        isServiceStarted = true
        setServiceState(this, ServiceState.STARTED)

        // we need this lock so our service gets not affected by Doze Mode
        wakeLock =
            (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EndlessService::lock").apply {
                    acquire()
                }
            }

        // we're starting a loop in a coroutine
        GlobalScope.launch(Dispatchers.IO) {
            while (isServiceStarted) {
                launch(Dispatchers.IO) {
                    pingFakeServer()
                }
                delay(1 * 5 * 1000)
            }
            log("End of the loop for the service")
        }
    }

    private fun stopService() {
        log("Stopping the foreground service")
        Toast.makeText(this, "Service stopping", Toast.LENGTH_SHORT).show()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            stopForeground(true)
            stopSelf()
        } catch (e: Exception) {
            log("Service stopped without being started: ${e.message}")
        }
        isServiceStarted = false
        setServiceState(this, ServiceState.STOPPED)
    }

    private fun pingFakeServer() {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.mmmZ")
        val gmtTime = df.format(Date())

        val deviceId = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)

        val json =
            """
                {
                    "deviceId": "$deviceId",
                    "createdAt": "$gmtTime"
                }
            """
        try {
            Fuel.post("https://jsonplaceholder.typicode.com/posts")
                .jsonBody(json)
                .response { _, _, result ->
                    val (bytes, error) = result
                    if (bytes != null) {
                        log("[response bytes] ${String(bytes)}")
                    } else {
                        log("[response error] ${error?.message}")
                    }
                }
        } catch (e: Exception) {
            log("Error making the request: ${e.message}")
        }
    }

    private fun createNotification(): Notification {
        val notificationChannelId = "ENDLESS SERVICE CHANNEL"

        // depending on the Android API that we're dealing with we will have
        // to use a specific method to create the notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                notificationChannelId,
                "Endless Service notifications channel",
                NotificationManager.IMPORTANCE_HIGH
            ).let {
                it.description = "Endless Service channel"
                it.enableLights(true)
                it.lightColor = Color.RED
                it.enableVibration(true)
                it.vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
                it
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent, 0)
        }

        val builder: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(
            this,
            notificationChannelId
        ) else Notification.Builder(this)

        return builder
            .setContentTitle("Endless Service")
            .setContentText("This is your favorite endless service working")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setTicker("Ticker text")
            .setPriority(Notification.PRIORITY_HIGH) // for under android 26 compatibility
            .build()
    }
}
