package at.agar.privatelock

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Läuft im Hintergrund (mit dauerhafter Benachrichtigung) und überwacht den Bewegungssensor.
 * Bei einem Ruck oder Sturz wird das Handy gesperrt.
 */
class LockService : Service(), SensorEventListener {

    private lateinit var sm: SensorManager
    private var accel: Sensor? = null
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var detector: Detector
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var pendingLock: Runnable? = null
    private var lastCheck = 0L

    private val stopAlarmRunnable = Runnable { stopAlarm() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPrefs() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT) stopAlarm()
            updateListening()
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = deviceOf(i) ?: return
            if (!isCar(dev)) return
            Prefs.setCarConnected(c, i.action == BluetoothDevice.ACTION_ACL_CONNECTED)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        detector = Detector { reason -> onTrigger(reason) }
        createChannels(this)
        startInForeground()
        running = true

        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefListener)
        registerSystem(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        registerSystem(btReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
        applyPrefs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setActive(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Prefs.active(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        try { unregisterReceiver(btReceiver) } catch (_: Exception) { }
        pendingLock?.let { handler.removeCallbacks(it) }
        pendingLock = null
        stopListening()
        stopAlarm()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Einstellungen

    private fun applyPrefs() {
        createChannels(this) // Kanal-Namen in der aktuellen Sprache
        detector.threshold = Prefs.threshold(this)
        detector.smart = Prefs.on(this, Flag.SMART)
        detector.fall = Prefs.on(this, Flag.FALL)
        updateNotification()
        updateListening()
    }

    // ---------------------------------------------------------------- Sensor an/aus

    /** Nur überwachen, wenn das Handy entsperrt benutzt wird – oder (Option) auch bei aus­geschaltetem Display */
    private fun shouldListen(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (pm.isInteractive && !km.isKeyguardLocked) return true
        return Prefs.on(this, Flag.SCREEN_OFF) && !km.isDeviceLocked
    }

    private fun updateListening() {
        val want = shouldListen()
        if (want && !listening) startListening()
        if (!want && listening) stopListening()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (listening && !pm.isInteractive) acquireWake() else releaseWake()
    }

    private fun startListening() {
        val s = accel ?: return
        detector.reset()
        listening = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopListening() {
        if (listening) sm.unregisterListener(this)
        listening = false
        releaseWake()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "privatelock:sensor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp / 1_000_000L
        detector.onAccel(event.values[0], event.values[1], event.values[2], t)
        // ab und zu prüfen, ob sich das Handy inzwischen selbst gesperrt hat
        if (t - lastCheck > 5000L) {
            lastCheck = t
            if (!shouldListen()) updateListening()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- Sperren

    private fun onTrigger(reason: Detector.Reason) {
        if (SystemClock.elapsedRealtime() < pausedUntil) return // Test in der App läuft
        if (pendingLock != null) return
        val r = Runnable {
            pendingLock = null
            doLock(reason)
        }
        pendingLock = r
        handler.postDelayed(r, Prefs.delayMs(this))
    }

    private fun doLock(reason: Detector.Reason) {
        val s = L.of(this)
        if (!Admin.lockNow(this)) {
            notifyEvent(s.lockFailTitle, s.lockFailText)
            return
        }
        if (Prefs.on(this, Flag.VIBRATE)) Feedback.vibrate(this, 220)
        if (Prefs.on(this, Flag.ALARM) && reason == Detector.Reason.GRAB) startAlarm()
        if (Prefs.on(this, Flag.NOTIFY)) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val text = if (reason == Detector.Reason.FALL) s.lockedFall else s.lockedGrab
            notifyEvent(s.lockedTitle, String.format(text, time))
        }
        updateListening()
    }

    private fun startAlarm() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return
            val rt = RingtoneManager.getRingtone(this, uri) ?: return
            rt.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) rt.isLooping = true
            rt.play()
            ringtone = rt
            handler.removeCallbacks(stopAlarmRunnable)
            handler.postDelayed(stopAlarmRunnable, 60_000L)
        } catch (_: Exception) {
        }
    }

    private fun stopAlarm() {
        handler.removeCallbacks(stopAlarmRunnable)
        try { ringtone?.stop() } catch (_: Exception) { }
        ringtone = null
    }

    // ---------------------------------------------------------------- Bluetooth (Auto)

    private fun deviceOf(i: Intent): BluetoothDevice? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            legacyDevice(i)
        }
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun legacyDevice(i: Intent): BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    @SuppressLint("MissingPermission")
    private fun isCar(d: BluetoothDevice): Boolean = try {
        val c = d.bluetoothClass?.deviceClass
        c == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || c == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
    } catch (_: Exception) {
        false
    }

    // ---------------------------------------------------------------- Benachrichtigungen

    private fun startInForeground() {
        val n = buildStatus()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_STATUS, n)
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildStatus(): Notification {
        val s = L.of(this)
        val mode = Prefs.effectiveMode(this)
        val modeText = if (Prefs.drivingByCar(this)) s.modeDriveCar else Prefs.modeName(this, mode)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(s.notifActiveTitle)
            .setContentText(String.format(s.notifActiveText, modeText))
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, s.turnOff, stop).build())
            .build()
    }

    private fun updateNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_STATUS, buildStatus())
        } catch (_: Exception) {
        }
    }

    private fun notifyEvent(title: String, text: String) {
        try {
            val n = Notification.Builder(this, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_stat_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID_EVENT, n)
        } catch (_: Exception) {
        }
    }

    private fun registerSystem(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
    }

    companion object {
        private const val CH_STATUS = "status"
        private const val CH_EVENTS = "events"
        private const val ID_STATUS = 1
        private const val ID_EVENT = 2
        const val ACTION_STOP = "at.agar.privatelock.STOP"

        @Volatile
        var running = false

        /** Während des Tests in der App wird nicht wirklich gesperrt */
        @Volatile
        var pausedUntil = 0L

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, LockService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, LockService::class.java))
            } catch (_: Exception) {
            }
        }

        fun createChannels(ctx: Context) {
            val s = L.of(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, s.chStatus, NotificationManager.IMPORTANCE_LOW).apply {
                    description = s.chStatusDesc
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_EVENTS, s.chEvents, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = s.chEventsDesc
                }
            )
        }
    }
}
