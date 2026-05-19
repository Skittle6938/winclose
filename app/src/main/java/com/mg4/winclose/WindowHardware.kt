package com.mg4.winclose

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

object WindowHardware {

    private const val TAG = "WH"

    private const val LAUNCHER_PKG       = "com.saicmotor.launcher"
    private const val CAR_ADAPTER_CLASS  = "com.saicmotor.carapi.CarAdapterClient"
    private const val VSM_CLIENT_CLASS   = "com.saicmotor.carapi.client.CarVehicleSettingClient"
    private const val VSM_SERVICE_CODE   = 0x8
    private const val CAR_GENERAL_CLASS  = "com.saicmotor.carapi.client.CarGeneralClient"
    private const val BIND_CODE_GENERAL  = 0x1
    private const val IGNITION_CALLBACK_DESCRIPTOR = "com.saicmotor.carapi.general.ICarGeneralCallback"
    private const val TX_IGNITION_CHANGE = 0x7
    private const val CAR_STATE_CLASS    = "com.saicmotor.carapi.client.CarStateClient"
    private const val BIND_CODE_CAR_STATE = 11
    private const val CAR_STATE_CALLBACK_DESCRIPTOR = "com.saicmotor.carapi.carstate.ICarStateCallback"
    private const val TX_GEAR_CHANGE     = 3
    private const val TX_PARKING_BRAKE   = 7
    private const val TX_SERVICE_READY   = 1
    private const val GEAR_PARK          = 1
    private const val TX_DOOR_SENSOR     = 6
    private const val DOOR_OPEN          = 0   // v=0 = porte ouverte (observé sur MG4)

    private const val TRIGGER_COOLDOWN_MS = 60_000L

    @Volatile private var sAppContext: Context? = null
    private fun speedThresholdKmh(): Float =
        (sAppContext?.let { Settings.getSpeedKmh(it) } ?: Settings.DEFAULT_SPEED_KMH).toFloat()
    private fun timeThresholdMs(): Long =
        (sAppContext?.let { Settings.getTimeMin(it) } ?: Settings.DEFAULT_TIME_MIN) * 60_000L
    private fun triggerDelayMs(): Long =
        (sAppContext?.let { Settings.getDelaySec(it) } ?: Settings.DEFAULT_DELAY_SEC) * 1000L
    private fun delayTrigger(): DelayTrigger =
        sAppContext?.let { Settings.getDelayTrigger(it) } ?: Settings.DEFAULT_DELAY_TRIGGER

    object CarIgnitionItem {
        const val OFF       = 0x0
        const val ACCESSORY = 0x1
        const val RUN       = 0x2
        const val CRANK     = 0x3
    }

    @Volatile private var sVsm: Any? = null
    @Volatile private var sCarGeneralClient: Any? = null
    @Volatile private var sCarStateClient: Any? = null
    @Volatile private var sVcmCallbackRegistered = false
    @Volatile private var sCarStateCallbackRegistered = false
    @Volatile private var sLastIgnition = -1
    @Volatile private var sLastGear = -1
    @Volatile private var sLastParkingBrake = -1
    @Volatile private var sLastDoorSensor = -1
    @Volatile private var sLastSpeed = 0f
    @Volatile var sHasBeenDriving = false
    @Volatile private var sLastTriggerTs = 0L

    // ── Door-close trigger tracking ──────────────────────────────────────────────
    /** Vrai si la porte conducteur a été ouverte pendant que le levier est en P */
    @Volatile private var sDriverDoorOpenedWhileParked = false

    // ── Cancellable close scheduling ─────────────────────────────────────────────
    private val sMainHandler = Handler(Looper.getMainLooper())
    @Volatile private var sPendingCloseRunnable: Runnable? = null
    @Volatile private var sCloseScheduled = false

    val logLines = CopyOnWriteArrayList<String>()
    val ignitionCallbacks     = CopyOnWriteArrayList<(Int) -> Unit>()
    val parkingStateCallbacks = CopyOnWriteArrayList<() -> Unit>()
    val gearChangeCallbacks   = CopyOnWriteArrayList<(Int) -> Unit>()
    val parkingBrakeCallbacks = CopyOnWriteArrayList<(Int) -> Unit>()
    var onLogUpdated: (() -> Unit)? = null

    fun init(context: Context) {
        sAppContext = context.applicationContext
        log("WindowHardware.init()")
        initKatman4(context.applicationContext)
        initKatman5(context.applicationContext)
        initCarState(context.applicationContext)
    }

    fun registerIgnitionListener(cb: (Int) -> Unit) { ignitionCallbacks.add(cb) }
    fun isVsmReady() = sVsm != null

    // ─────────────────────────────────────────────────────────────────────────────
    // Speed monitor
    // ─────────────────────────────────────────────────────────────────────────────

    @Volatile private var speedMonitorActive = false

    fun startSpeedMonitor(context: Context) {
        if (speedMonitorActive) return
        speedMonitorActive = true
        Thread {
            while (speedMonitorActive && sCarGeneralClient == null) {
                tryGetCarGeneralClient(context)
                Thread.sleep(500)
            }
            val gc = sCarGeneralClient ?: return@Thread
            val mSpeed = try { gc.javaClass.getMethod("getLatestSpeed") } catch (_: Exception) { return@Thread }
            while (speedMonitorActive) {
                try {
                    val speed = (mSpeed.invoke(gc) as? Float) ?: 0f
                    sLastSpeed = speed
                    if (!sHasBeenDriving && speed >= speedThresholdKmh()) {
                        sHasBeenDriving = true
                        log("hasBeenDriving=true (vitesse ${"%.1f".format(speed)} ≥ ${speedThresholdKmh()} km/h)")
                    }
                } catch (_: Exception) {}
                Thread.sleep(500)
            }
        }.start()
    }

    fun stopSpeedMonitor() { speedMonitorActive = false }

    private fun tryGetCarGeneralClient(context: Context) {
        if (sCarGeneralClient != null) return
        try {
            val launcherCtx = context.createPackageContext(
                LAUNCHER_PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            val adapterClass = launcherCtx.classLoader.loadClass(CAR_ADAPTER_CLASS)
            val generalClass = launcherCtx.classLoader.loadClass(CAR_GENERAL_CLASS)
            val adapter = adapterClass.getMethod("getInstance", Context::class.java).invoke(null, context) ?: return
            val ibinder = adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType!!)
                .invoke(adapter, BIND_CODE_GENERAL) ?: return
            sCarGeneralClient = generalClass.getConstructor(android.os.IBinder::class.java).newInstance(ibinder)
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Katman4 — CarVehicleSettingClient (contrôle des vitres)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun initKatman4(context: Context) {
        val launcherCtx: Context
        val adapterClass: Class<*>
        val clientClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER_PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            adapterClass = launcherCtx.classLoader.loadClass(CAR_ADAPTER_CLASS)
            clientClass  = launcherCtx.classLoader.loadClass(VSM_CLIENT_CLASS)
        } catch (e: Exception) {
            log("Katman4: classes err: ${e.message} — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4(context) }, 5_000)
            return
        }

        val adapter = try {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (e: Exception) { log("Katman4: getInstance err: ${e.message}"); return }
            ?: run { Handler(Looper.getMainLooper()).postDelayed({ initKatman4(context) }, 10_000); return }

        val listenerType = adapterClass.declaredClasses
            .firstOrNull { it.simpleName == "ServiceConnListener" }
        if (listenerType != null && listenerType.isInterface) {
            try {
                val proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.classLoader, arrayOf(listenerType)
                ) { _, method, args ->
                    if (method.name == "onResult" && (args?.getOrNull(0) as? Int) == 0)
                        tryInitVsm(adapter, adapterClass, clientClass)
                    null
                }
                adapterClass.getMethod("setConnListener", listenerType).invoke(adapter, proxy)
            } catch (_: Exception) {}
        }

        try { adapterClass.getMethod("start").invoke(adapter) } catch (_: Exception) {}
        tryInitVsm(adapter, adapterClass, clientClass)

        val h = Handler(Looper.getMainLooper())
        listOf(1_000L, 3_000L, 10_000L, 30_000L).forEach { delay ->
            h.postDelayed({ if (sVsm == null) tryInitVsm(adapter, adapterClass, clientClass) }, delay)
        }
    }

    private fun tryInitVsm(adapter: Any, adapterClass: Class<*>, clientClass: Class<*>) {
        if (sVsm != null) return
        try {
            val ibinder = adapterClass
                .getMethod("queryClient", Int::class.javaPrimitiveType!!)
                .invoke(adapter, VSM_SERVICE_CODE) ?: return
            sVsm = clientClass.getConstructor(android.os.IBinder::class.java).newInstance(ibinder)
            log("VSM prêt ✓")
        } catch (e: Exception) { Log.d(TAG, "tryInitVsm err: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Katman5 — CarGeneralClient (ignition)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun initKatman5(context: Context) {
        val launcherCtx: Context; val adapterClass: Class<*>; val generalClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER_PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            adapterClass = launcherCtx.classLoader.loadClass(CAR_ADAPTER_CLASS)
            generalClass = launcherCtx.classLoader.loadClass(CAR_GENERAL_CLASS)
        } catch (e: Exception) {
            log("Katman5: classes err: ${e.message} — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman5(context) }, 5_000)
            return
        }
        val adapter = try {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (e: Exception) { log("Katman5: getInstance err: ${e.message}"); return } ?: return

        try { adapterClass.getMethod("start").invoke(adapter) } catch (_: Exception) {}
        tryRegisterIgnitionListener(adapter, adapterClass, generalClass)

        val h = Handler(Looper.getMainLooper())
        listOf(2_000L, 5_000L, 10_000L, 30_000L).forEach { delay ->
            h.postDelayed({
                if (!sVcmCallbackRegistered)
                    tryRegisterIgnitionListener(adapter, adapterClass, generalClass)
            }, delay)
        }
    }

    private fun tryRegisterIgnitionListener(adapter: Any, adapterClass: Class<*>, generalClass: Class<*>) {
        if (sVcmCallbackRegistered) return
        try {
            val ibinder = adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType!!)
                .invoke(adapter, BIND_CODE_GENERAL) ?: return
            val client = generalClass.getConstructor(android.os.IBinder::class.java).newInstance(ibinder)
            if (sCarGeneralClient == null) sCarGeneralClient = client

            val registMethod = generalClass.methods
                .firstOrNull { it.name == "registListener" && it.parameterCount == 1 } ?: return
            val callbackType = registMethod.parameterTypes[0]
            if (!callbackType.isInterface) return

            val callbackBinder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == TX_IGNITION_CHANGE) {
                        try { data.enforceInterface(IGNITION_CALLBACK_DESCRIPTOR); dispatchIgnition(data.readInt()) } catch (_: Exception) {}
                    }
                    reply?.writeNoException(); return true
                }
            }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackType.classLoader, arrayOf(callbackType)
            ) { _, method, args ->
                when (method.name) {
                    "onIgnitionStateChange" -> (args?.getOrNull(0) as? Int)?.let { dispatchIgnition(it) }
                    "asBinder" -> return@newProxyInstance callbackBinder
                }; null
            }
            registMethod.invoke(client, proxy)
            sVcmCallbackRegistered = true
            log("Katman5 enregistré ✓")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val ignition = generalClass.getMethod("getIgnitionState").invoke(client) as? Int
                    if (ignition != null) dispatchIgnition(ignition)
                } catch (_: Exception) {}
            }, 500)
        } catch (e: Exception) { Log.d(TAG, "tryRegisterIgnitionListener err: ${e.message}") }
    }

    private fun armDrivingTimer() {
        val delay = timeThresholdMs()
        Handler(Looper.getMainLooper()).postDelayed({
            if (sLastIgnition == CarIgnitionItem.RUN && !sHasBeenDriving) {
                sHasBeenDriving = true
                log("hasBeenDriving=true (timer ${delay/60_000}min)")
            }
        }, delay)
    }

    private fun dispatchIgnition(state: Int) {
        if (state == sLastIgnition) return
        sLastIgnition = state
        log("ignition=$state driven=$sHasBeenDriving")
        when (state) {
            CarIgnitionItem.OFF -> {
                sHasBeenDriving = false
                sLastTriggerTs = 0L
                sDriverDoorOpenedWhileParked = false
                cancelPendingClose("ignition OFF")
            }
            CarIgnitionItem.RUN -> { if (!sHasBeenDriving) armDrivingTimer() }
        }
        val cbs = ignitionCallbacks.toList()
        sMainHandler.post { cbs.forEach { it(state) } }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CarStateClient — gear + door sensor
    // ─────────────────────────────────────────────────────────────────────────────

    private fun initCarState(context: Context) {
        val launcherCtx: Context; val adapterClass: Class<*>; val stateClass: Class<*>
        try {
            launcherCtx = context.createPackageContext(
                LAUNCHER_PKG, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            adapterClass = launcherCtx.classLoader.loadClass(CAR_ADAPTER_CLASS)
            stateClass   = launcherCtx.classLoader.loadClass(CAR_STATE_CLASS)
        } catch (e: Exception) {
            log("CarState: classes err: ${e.message} — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initCarState(context) }, 5_000)
            return
        }
        val adapter = try {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (e: Exception) { log("CarState: getInstance err: ${e.message}"); return } ?: return

        try { adapterClass.getMethod("start").invoke(adapter) } catch (_: Exception) {}
        tryRegisterCarStateListener(adapter, adapterClass, stateClass)

        val h = Handler(Looper.getMainLooper())
        listOf(2_000L, 5_000L, 10_000L, 30_000L).forEach { delay ->
            h.postDelayed({
                if (!sCarStateCallbackRegistered)
                    tryRegisterCarStateListener(adapter, adapterClass, stateClass)
            }, delay)
        }
    }

    private fun tryRegisterCarStateListener(adapter: Any, adapterClass: Class<*>, stateClass: Class<*>) {
        if (sCarStateCallbackRegistered) return
        try {
            val ibinder = adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType!!)
                .invoke(adapter, BIND_CODE_CAR_STATE) ?: return
            val client = stateClass.getConstructor(android.os.IBinder::class.java).newInstance(ibinder)
            sCarStateClient = client

            try {
                val g  = stateClass.getMethod("getGearState").invoke(client) as? Int ?: -1
                val pb = stateClass.getMethod("getParkingBrakeState").invoke(client) as? Int ?: -1
                sLastGear = g; sLastParkingBrake = pb
                log("CarState init: gear=$g  parkingBrake=$pb")
            } catch (e: Exception) { log("CarState init read err: ${e.message}") }

            val registMethod = stateClass.methods
                .firstOrNull { it.name == "registerListener" && it.parameterCount == 1 }
                ?: stateClass.methods.firstOrNull { it.name == "registListener" && it.parameterCount == 1 }
                ?: return
            val cbType = registMethod.parameterTypes[0]
            if (!cbType.isInterface) return

            val callbackBinder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    try {
                        when (code) {
                            TX_SERVICE_READY -> { data.enforceInterface(CAR_STATE_CALLBACK_DESCRIPTOR); log("CarState: onServiceReady") }
                            TX_GEAR_CHANGE   -> { data.enforceInterface(CAR_STATE_CALLBACK_DESCRIPTOR); dispatchGearChange(data.readInt()) }
                            TX_PARKING_BRAKE -> { data.enforceInterface(CAR_STATE_CALLBACK_DESCRIPTOR); dispatchParkingBrakeChange(data.readInt()) }
                            TX_DOOR_SENSOR   -> { data.enforceInterface(CAR_STATE_CALLBACK_DESCRIPTOR); dispatchDoorSensorChange(data.readInt()) }
                            else -> {
                                try {
                                    data.enforceInterface(CAR_STATE_CALLBACK_DESCRIPTOR)
                                    val v = try { data.readInt() } catch (_: Exception) { -999 }
                                    log("CarState: tx=$code v=$v")
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                    reply?.writeNoException(); return true
                }
            }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                cbType.classLoader, arrayOf(cbType)
            ) { _, method, args ->
                if (method.name == "asBinder") return@newProxyInstance callbackBinder
                when (method.returnType) {
                    Int::class.javaPrimitiveType     -> 0
                    Boolean::class.javaPrimitiveType -> false
                    Float::class.javaPrimitiveType   -> 0f
                    Long::class.javaPrimitiveType    -> 0L
                    Double::class.javaPrimitiveType  -> 0.0
                    else -> null
                }
            }
            registMethod.invoke(client, proxy)
            sCarStateCallbackRegistered = true
            log("CarState enregistré ✓")
        } catch (e: Exception) { Log.d(TAG, "tryRegisterCarStateListener err: ${e.message}") }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Trigger logic
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Déclenche la séquence de fermeture si les conditions sont remplies.
     * Le délai est annulable : tout événement porte pendant le délai annule la fermeture.
     */
    private fun maybeTriggerParkClose(reason: String) {
        if (sLastGear != GEAR_PARK) return
        if (!sHasBeenDriving) { log("$reason ignoré (pas armé)"); return }
        val now = System.currentTimeMillis()
        if (now - sLastTriggerTs <= TRIGGER_COOLDOWN_MS) { log("$reason ignoré (cooldown)"); return }
        if (sCloseScheduled) { log("$reason ignoré (déjà programmé)"); return }

        val delay = triggerDelayMs()
        log("$reason → TRIGGER dans ${delay / 1000}s (annulable)")
        sLastTriggerTs = now
        sHasBeenDriving = false
        sCloseScheduled = true
        armDrivingTimer()

        // Bip de prévention
        playBeepIfEnabled()

        val cbs = parkingStateCallbacks.toList()
        val run = Runnable {
            sPendingCloseRunnable = null
            sCloseScheduled = false
            log("→ Fermeture déclenchée")
            cbs.forEach { it() }
        }
        sPendingCloseRunnable = run
        sMainHandler.postDelayed(run, delay)
    }

    /** Annule la fermeture programmée si elle est en attente. */
    private fun cancelPendingClose(reason: String) {
        sPendingCloseRunnable?.let {
            sMainHandler.removeCallbacks(it)
            sPendingCloseRunnable = null
            sCloseScheduled = false
            log("Fermeture annulée ($reason)")
        }
    }

    private fun playBeepIfEnabled() {
        val ctx = sAppContext ?: return
        if (!Settings.isBeepEnabled(ctx)) return
        val vol = Settings.getBeepVolume(ctx)
        Thread {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, vol)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 600)
                Thread.sleep(800)
                tg.release()
            } catch (_: Exception) {}
        }.start()
    }

    private fun dispatchGearChange(gear: Int) {
        if (gear == sLastGear) return
        sLastGear = gear
        log("CarState: gear → $gear${if (gear == GEAR_PARK) " ← PARK" else ""}")
        if (gear != GEAR_PARK) {
            // Quitter P → reset flags, annuler tout trigger en cours
            sDriverDoorOpenedWhileParked = false
            cancelPendingClose("gear != PARK")
        } else {
            // Passer en P : déclencher si porte déjà ouverte ET mode DOOR_OPEN
            if (delayTrigger() == DelayTrigger.DOOR_OPEN && sLastDoorSensor == DOOR_OPEN) {
                maybeTriggerParkClose("gear→PARK (porte déjà ouverte)")
            }
        }
        val cbs = gearChangeCallbacks.toList()
        sMainHandler.post { cbs.forEach { it(gear) } }
    }

    private fun dispatchParkingBrakeChange(pb: Int) {
        if (pb == sLastParkingBrake) return
        sLastParkingBrake = pb
        val cbs = parkingBrakeCallbacks.toList()
        sMainHandler.post { cbs.forEach { it(pb) } }
    }

    private fun dispatchDoorSensorChange(v: Int) {
        if (v == sLastDoorSensor) return
        sLastDoorSensor = v

        if (v == DOOR_OPEN) {
            // ── Porte s'OUVRE ──────────────────────────────────────────────────
            val cancelMsg = if (sCloseScheduled) " [close pending → ANNULÉ]" else ""
            log("CarState: door→OUVERT$cancelMsg")

            // Tout mouvement de porte pendant le délai = annulation
            cancelPendingClose("porte ouverte pendant délai")

            // Mémoriser que la porte a été ouverte en mode P (pour trigger DOOR_CLOSE)
            if (sLastGear == GEAR_PARK) sDriverDoorOpenedWhileParked = true

            // Déclencher si mode DOOR_OPEN
            if (delayTrigger() == DelayTrigger.DOOR_OPEN) {
                maybeTriggerParkClose("door→OPEN")
            }

        } else {
            // ── Porte se FERME ─────────────────────────────────────────────────
            val cancelMsg = if (sCloseScheduled) " [close pending → ANNULÉ]" else ""
            log("CarState: door→FERMÉE (v=$v)$cancelMsg")

            // Tout mouvement de porte pendant le délai = annulation
            cancelPendingClose("porte fermée pendant délai")

            // Déclencher si mode DOOR_CLOSE et que la porte a été ouverte en mode P
            if (delayTrigger() == DelayTrigger.DOOR_CLOSE
                && sDriverDoorOpenedWhileParked
                && sLastGear == GEAR_PARK
            ) {
                sDriverDoorOpenedWhileParked = false
                maybeTriggerParkClose("door→CLOSE (conducteur sorti)")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Contrôle des vitres
    // ─────────────────────────────────────────────────────────────────────────────

    private val CMD_MOVE_UP = 1
    private val CMD_AUTO_UP = 3
    private val CMD_STOP    = 0

    fun closeAllWindowsPulsed(durationMs: Long = 3000L, pulseMs: Long = 120L) {
        val vsm = sVsm ?: run { log("close: VSM null"); return }
        val ctx = sAppContext ?: run { log("close: context null"); return }
        val m = try {
            vsm.javaClass.getMethod("setVehicleWindowStatus",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        } catch (_: Exception) { log("setVehicleWindowStatus introuvable"); return }

        val autoAreas   = mutableListOf<Int>()
        val pulsedAreas = mutableListOf<Int>()
        Settings.ALL_AREAS.forEach { area ->
            when (Settings.getWindowMode(ctx, area)) {
                WindowMode.AUTO   -> autoAreas.add(area)
                WindowMode.PULSED -> pulsedAreas.add(area)
                WindowMode.OFF    -> {}
            }
        }
        log("Close: AUTO=$autoAreas  PULSED=$pulsedAreas")

        autoAreas.forEach { area ->
            try { m.invoke(vsm, area, CMD_AUTO_UP) } catch (_: Exception) {}
        }
        if (pulsedAreas.isEmpty()) return

        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            pulsedAreas.forEach { area ->
                try { m.invoke(vsm, area, CMD_MOVE_UP) } catch (_: Exception) {}
            }
            try { Thread.sleep(pulseMs) } catch (_: InterruptedException) { return }
        }
        pulsedAreas.forEach { area ->
            try { m.invoke(vsm, area, CMD_STOP) } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Logging
    // ─────────────────────────────────────────────────────────────────────────────

    fun log(msg: String) {
        Log.i(TAG, msg)
        logLines.add(msg)
        while (logLines.size > 200) logLines.removeAt(0)
        onLogUpdated?.invoke()
    }

    fun clearLog() {
        logLines.clear()
        onLogUpdated?.invoke()
    }
}
