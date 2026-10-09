package com.mg4.winclose

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

object WindowHardware {

    private const val TAG = "WH"

    // ─────────────────────────────────────────────────────────────────────────────
    // Résolution du package SAIC hébergeant les classes carapi.
    //
    // Le package qui contient CarAdapterClient + CarVehicleSettingClient /
    // CarGeneralClient / CarStateClient varie selon le firmware (vérifié par
    // décompilation des firmwares) :
    //
    //   SWI69 / SWI131 / SWI132 / SWI173 → com.saicmotor.launcher
    //   SWI133 / SWI68 R71 (Trophy)       → com.saicmotor.voiceservice
    //   SWI68 R46                          → com.saicmotor.onlinemedia (app Musique)
    //
    // Note : sur SWI68 R46, en plus du déplacement de package, l'API de contrôle
    // des vitres (setVehicleWindowStatus/getVehicleWindowValue) n'existe plus
    // dans la carapi — le binding réussit mais la commande de fermeture échoue.
    //
    // On teste donc plusieurs packages candidats dans l'ordre, puis on scanne tous les
    // packages système « com.saicmotor.* », et enfin on retombe sur le classloader système.
    // ─────────────────────────────────────────────────────────────────────────────
    private val CAR_API_CANDIDATE_PACKAGES = listOf(
        "com.saicmotor.launcher",        // SWI69 / SWI131 / SWI132 / SWI173
        "com.saicmotor.voiceservice",    // SWI133 / SWI68 R71 (SaicVoiceService)
        "com.saicmotor.hmi.launcher",    // launcher renommé (SWI133 / SWI68 R71)
        "com.saicmotor.onlinemedia",     // SWI68 R46 (app Musique — binding seul)
        "com.saicmotor.carcontrol",
        "com.saicmotor.telematics",
        "com.saicmotor.hmi",
        "com.saicmotor.settings",
    )

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
    private const val GEAR_PARK_AOSP     = 4   // AOSP VehicleGear.GEAR_PARK (backend SWI133)
    private const val GEAR_REVERSE_AOSP  = 2   // AOSP VehicleGear.GEAR_REVERSE
    private const val GEAR_DRIVE_AOSP    = 8   // AOSP VehicleGear.GEAR_DRIVE
    // Nombre de lectures D/R consécutives avant de réellement quitter P sur SWI133.
    // Le signal brut fluctue (TCU éteint → NEUTRAL/UNKNOWN), on exige donc une
    // confirmation soutenue (~3 s à 500 ms/lecture) pour éviter une annulation erronée.
    private const val GEAR_MOVEMENT_POLLS = 6
    private const val TX_DOOR_SENSOR     = 6
    private const val DOOR_OPEN          = 0   // v=0 = porte ouverte (observé sur MG4)

    private const val TRIGGER_COOLDOWN_MS = 60_000L

    private class CarApiBind(
        val packageName: String,
        val classLoader: ClassLoader,
        val context: Context,
        val adapterClass: Class<*>,
    )

    @Volatile private var sCarApiBind: CarApiBind? = null
    @Volatile private var sCarApiResolved = false

    /**
     * Résout le package/classloader qui héberge [CAR_ADAPTER_CLASS].
     * Essaie les packages candidats, scanne les packages système « com.saicmotor.* »,
     * puis retombe sur le classloader système. Le résultat est mis en cache.
     */
    private fun resolveCarApi(context: Context): CarApiBind? {
        sCarApiBind?.let { return it }

        val candidates = linkedSetOf<String>()
        candidates.addAll(CAR_API_CANDIDATE_PACKAGES)
        try {
            for (pi in context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)) {
                pi.packageName?.let { if (it.startsWith("com.saicmotor")) candidates.add(it) }
            }
        } catch (e: Exception) {
            log("CarApi: PackageManager scan err: ${e.message}")
        }

        var bind: CarApiBind? = null
        for (pkg in candidates) {
            bind = tryLoadPackage(context, pkg, logProbe = !sCarApiResolved)
            if (bind != null) break
        }

        if (bind == null && !sCarApiResolved) {
            try {
                val cl = ClassLoader.getSystemClassLoader()
                val ac = cl.loadClass(CAR_ADAPTER_CLASS)
                bind = CarApiBind("system", cl, context, ac)
            } catch (e: Exception) {
                log("CarApi: ClassLoader.getSystemClassLoader() → ${e.javaClass.simpleName}")
            }
        }

        sCarApiResolved = true
        if (bind != null) {
            log("CarApi: résolu via ${bind.packageName}")
            sCarApiBind = bind
        }
        return bind
    }

    private fun tryLoadPackage(context: Context, pkg: String, logProbe: Boolean): CarApiBind? {
        return try {
            val ctx = context.createPackageContext(
                pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
            val cl = ctx.classLoader
            val adapterClass = cl.loadClass(CAR_ADAPTER_CLASS)
            CarApiBind(pkg, cl, ctx, adapterClass)
        } catch (e: Exception) {
            if (logProbe) log("CarApi: $pkg → ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

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
    @Volatile private var sGearMovementCount = 0
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

    // ── Repeating beep ───────────────────────────────────────────────────────────
    @Volatile private var sBeepRunnable: Runnable? = null

    // ── Surveillance vitres pendant le countdown (action manuelle) ───────────────
    @Volatile private var sWindowMonitorActive = false

    // ── Verbose mode — log every raw CarStateClient event ───────────────────────
    @Volatile var verboseMode = false

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
        Swi133Controller.init(context.applicationContext)
        startSwi133SensorPoller()
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

    // ─────────────────────────────────────────────────────────────────────────────
    // Poller capteurs SWI133+ (marcia / porte / accensione / vitesse)
    //
    // Sur SWI133 les capteurs CarStateClient/CarGeneralClient (queryClient) sont morts,
    // donc on lit les états via le backend VehicleService (IVehicleConditionService +
    // IVehiclePropertyService) en polling. Le trigger porte+park nécessite ces capteurs
    // même véhicule éteint, donc le poller tourne en continu tant que le service vit.
    // ─────────────────────────────────────────────────────────────────────────────
    @Volatile private var swi133PollerActive = false

    // Dernières valeurs loggées en mode verbose (évite d'inonder le log)
    @Volatile private var sVbGear = Int.MIN_VALUE
    @Volatile private var sVbIgn  = Int.MIN_VALUE
    @Volatile private var sVbDoor = Int.MIN_VALUE
    @Volatile private var sVbSpeed = Float.NaN

    private fun startSwi133SensorPoller() {
        if (swi133PollerActive) return
        swi133PollerActive = true
        Thread {
            while (swi133PollerActive) {
                try {
                    if (Swi133Controller.isReady()) pollSwi133Sensors()
                } catch (_: Exception) {}
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }.start()
    }

    private fun pollSwi133Sensors() {
        // Marcia : AOSP VehicleGear → modèle interne GEAR_PARK=1.
        // Seuls PARK (4), REVERSE (2) et DRIVE (8) sont significatifs : NEUTRAL (1)
        // et UNKNOWN (0) sont du bruit (le TCU éteint les renvoie après stationnement)
        // et ne doivent pas faire quitter l'état PARK.
        val gear = Swi133Controller.getGear()
        if (gear >= 0) {
            if (verboseMode && gear != sVbGear) {
                sVbGear = gear
                log("VERBOSE SWI133 gear raw=$gear ${gearLabel(gear)}")
            }
            when (gear) {
                GEAR_PARK_AOSP -> dispatchGearChange(GEAR_PARK)
                GEAR_REVERSE_AOSP, GEAR_DRIVE_AOSP -> dispatchGearChange(0)
                else -> { /* NEUTRAL / UNKNOWN / autre : bruit → ignorer */ }
            }
        }

        // Accensione : encodage identique à CarIgnitionItem (0=OFF, 2=RUN)
        val ign = Swi133Controller.getIgnition()
        if (ign >= 0) {
            if (verboseMode && ign != sVbIgn) {
                sVbIgn = ign
                log("VERBOSE SWI133 ignition raw=$ign")
            }
            dispatchIgnition(ign)
        }

        // Vitesse
        val speed = Swi133Controller.getSpeed()
        if (speed >= 0f) {
            if (verboseMode && kotlin.math.abs(speed - sVbSpeed) >= 1f) {
                sVbSpeed = speed
                log("VERBOSE SWI133 speed raw=${"%.1f".format(speed)} km/h")
            }
            sLastSpeed = speed
            if (!sHasBeenDriving && speed >= speedThresholdKmh()) {
                sHasBeenDriving = true
                log("hasBeenDriving=true (vitesse ${"%.1f".format(speed)} ≥ ${speedThresholdKmh()} km/h)")
            }
        }

        // Porte : 0=fermée, 1/2/3=ouverte → DOOR_OPEN=0 dans le modèle interne
        val door = Swi133Controller.getDoor()
        if (door >= 0) {
            if (verboseMode && door != sVbDoor) {
                sVbDoor = door
                log("VERBOSE SWI133 door raw=$door ${if (door == 0) "(fermée)" else "(ouverte/ajar)"}")
            }
            dispatchDoorSensorChange(if (door == 0) 1 else DOOR_OPEN)
        }
    }

    private fun tryGetCarGeneralClient(context: Context) {
        if (sCarGeneralClient != null) return
        try {
            val bind = resolveCarApi(context) ?: return
            val adapterClass = bind.adapterClass
            val generalClass = bind.classLoader.loadClass(CAR_GENERAL_CLASS)
            val adapter = adapterClass.getMethod("getInstance", Context::class.java).invoke(null, bind.context) ?: return
            val ibinder = adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType!!)
                .invoke(adapter, BIND_CODE_GENERAL) ?: return
            sCarGeneralClient = generalClass.getConstructor(android.os.IBinder::class.java).newInstance(ibinder)
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Katman4 — CarVehicleSettingClient (contrôle des vitres)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun initKatman4(context: Context) {
        val bind = resolveCarApi(context)
        if (bind == null) {
            log("Katman4: aucun package SAIC hébergeant $CAR_ADAPTER_CLASS — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4(context) }, 5_000)
            return
        }
        val adapterClass = bind.adapterClass
        val clientClass: Class<*>
        try {
            clientClass = bind.classLoader.loadClass(VSM_CLIENT_CLASS)
        } catch (e: Exception) {
            log("Katman4: classe $VSM_CLIENT_CLASS introuvable dans ${bind.packageName} (${e.message}) — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman4(context) }, 5_000)
            return
        }

        val adapter = try {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, bind.context)
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
            val queryMethod = adapterClass.getMethod("queryClient", Int::class.javaPrimitiveType!!)
            val ibinder = queryMethod.invoke(adapter, VSM_SERVICE_CODE)
            if (ibinder == null) {
                log("Katman4: queryClient(0x$VSM_SERVICE_CODE) → null (service CarAdapterService non connecté ?)")
                return
            }
            val vsm = clientClass.getConstructor(android.os.IBinder::class.java).newInstance(ibinder)
            sVsm = vsm
            log("VSM prêt ✓")
        } catch (e: Exception) {
            log("Katman4: tryInitVsm err: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Katman5 — CarGeneralClient (ignition)
    // ─────────────────────────────────────────────────────────────────────────────

    private fun initKatman5(context: Context) {
        val bind = resolveCarApi(context)
        if (bind == null) {
            log("Katman5: aucun package SAIC hébergeant $CAR_ADAPTER_CLASS — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman5(context) }, 5_000)
            return
        }
        val adapterClass = bind.adapterClass
        val generalClass: Class<*>
        try {
            generalClass = bind.classLoader.loadClass(CAR_GENERAL_CLASS)
        } catch (e: Exception) {
            log("Katman5: classe $CAR_GENERAL_CLASS introuvable dans ${bind.packageName} (${e.message}) — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initKatman5(context) }, 5_000)
            return
        }
        val adapter = try {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, bind.context)
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
        log("ignition=$state driven=$sHasBeenDriving gear=$sLastGear")
        when (state) {
            CarIgnitionItem.OFF -> {
                // Sur un VE, le conducteur éteint la voiture AVANT d'ouvrir la porte.
                // Si on est en PARK, on conserve sHasBeenDriving et sDriverDoorOpenedWhileParked
                // pour que le trigger porte-fermée puisse encore fonctionner après l'extinction.
                // On ne reset que si on n'est PAS en PARK (état inattendu).
                sLastTriggerTs = 0L
                cancelPendingClose("ignition OFF")
                if (sLastGear != GEAR_PARK) {
                    sHasBeenDriving = false
                    sDriverDoorOpenedWhileParked = false
                }
            }
            CarIgnitionItem.RUN -> {
                // Nouveau trajet : on repart de zéro
                sHasBeenDriving = false
                sDriverDoorOpenedWhileParked = false
                armDrivingTimer()
            }
            CarIgnitionItem.CRANK -> {
                // Démarrage / présence active du conducteur (freno/accensione) :
                // on annule immédiatement la fermeture programmée, sans attendre.
                if (sCloseScheduled) {
                    log("VERBOSE SWI133: Countdown annullato da Pressione Freno / Accensione")
                    cancelPendingClose("Pressione Freno / Accensione")
                }
            }
        }
        val cbs = ignitionCallbacks.toList()
        sMainHandler.post { cbs.forEach { it(state) } }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CarStateClient — gear + door sensor
    // ─────────────────────────────────────────────────────────────────────────────

    private fun initCarState(context: Context) {
        val bind = resolveCarApi(context)
        if (bind == null) {
            log("CarState: aucun package SAIC hébergeant $CAR_ADAPTER_CLASS — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initCarState(context) }, 5_000)
            return
        }
        val adapterClass = bind.adapterClass
        val stateClass: Class<*>
        try {
            stateClass = bind.classLoader.loadClass(CAR_STATE_CLASS)
        } catch (e: Exception) {
            log("CarState: classe $CAR_STATE_CLASS introuvable dans ${bind.packageName} (${e.message}) — retry 5s")
            Handler(Looper.getMainLooper()).postDelayed({ initCarState(context) }, 5_000)
            return
        }
        val adapter = try {
            adapterClass.getMethod("getInstance", Context::class.java).invoke(null, bind.context)
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
                        data.enforceInterface(CAR_STATE_CALLBACK_DESCRIPTOR)
                        // En mode verbose : lire et logger TOUS les ints du parcel pour tous les codes
                        if (verboseMode) {
                            val pos0 = data.dataPosition()
                            val vals = mutableListOf<Int>()
                            repeat(4) { try { vals.add(data.readInt()) } catch (_: Exception) {} }
                            log("VERBOSE tx=$code vals=${vals.joinToString(",")}")
                            data.setDataPosition(pos0)   // rebobiner pour la dispatch normale
                        }
                        when (code) {
                            TX_SERVICE_READY -> log("CarState: onServiceReady")
                            TX_GEAR_CHANGE   -> dispatchGearChange(data.readInt())
                            TX_PARKING_BRAKE -> dispatchParkingBrakeChange(data.readInt())
                            TX_DOOR_SENSOR   -> {
                                val v = data.readInt()
                                if (verboseMode) log("VERBOSE door raw=$v last=$sLastDoorSensor")
                                dispatchDoorSensorChange(v)
                            }
                            else -> {
                                val v = try { data.readInt() } catch (_: Exception) { -999 }
                                log("CarState: tx=$code v=$v")
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
        // Le countdown (et son beep) ne doit pas démarrer si le toggle
        // « auto-closing » est désactivé dans l'UI.
        val ctx = sAppContext
        if (ctx == null || !ctx.getSharedPreferences(LocaleHelper.PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(WindowService.PREF_AUTO_CLOSE, false)) {
            log("$reason ignoré (toggle auto-closing désactivé)")
            return
        }
        if (sLastGear != GEAR_PARK) return
        if (!sHasBeenDriving) { log("$reason ignoré (pas armé)"); return }
        val now = System.currentTimeMillis()
        if (now - sLastTriggerTs <= TRIGGER_COOLDOWN_MS) { log("$reason ignoré (cooldown)"); return }
        if (sCloseScheduled) { log("$reason ignoré (déjà programmé)"); return }

        // ── Contrôle préventif : toutes les vitres déjà fermées ? ────────────────
        // Avant le beep et le compte à rebours, on vérifie que la fermeture est
        // réellement nécessaire : si les 4 vitres sont confirmées fermées par une
        // lecture fiable, on saute beep + timer (aucun bruit, aucune action).
        if (allWindowsReliablyClosed()) {
            log("VERBOSE SWI133: Countdown ignorato (tutti i finestrini già chiusi)")
            return
        }

        val delay = triggerDelayMs()
        log("$reason → TRIGGER dans ${delay / 1000}s (annulable)")
        sLastTriggerTs = now
        sHasBeenDriving = false
        sCloseScheduled = true
        armDrivingTimer()

        // Bips répétitifs pendant le compte à rebours (1/s)
        startRepeatingBeepIfEnabled()

        // Surveillance des tasti fisici finestrini : si le conducteur actionne une
        // vitre pendant l'attente (position qui bouge), on annule immédiatement.
        startWindowManualMonitor()

        val cbs = parkingStateCallbacks.toList()
        val run = Runnable {
            sPendingCloseRunnable = null
            sCloseScheduled = false
            stopRepeatingBeep()
            stopWindowManualMonitor()
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
        stopRepeatingBeep()
        stopWindowManualMonitor()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Moniteur « Tasto Finestrino » — annule le countdown si une vitre bouge
    //
    // Le seul moyen de détecter une pression sur les boutons physiques des vitres
    // est d'observer la position des vitres : si l'une d'elles change pendant la
    // fenêtre de 5 s, le conducteur est en train d'actionner une vitre → annulation
    // immédiate du beep et du timer (log diagnostic dédié).
    // ─────────────────────────────────────────────────────────────────────────────

    private fun startWindowManualMonitor() {
        if (sWindowMonitorActive) return
        sWindowMonitorActive = true
        val baseline = Settings.ALL_AREAS.map { getWindowPosition(it) }
        Thread {
            while (sWindowMonitorActive) {
                try {
                    Settings.ALL_AREAS.forEachIndexed { i, area ->
                        val now = getWindowPosition(area)
                        val base = baseline[i]
                        if (now >= 0f && base >= 0f && kotlin.math.abs(now - base) > 2.0f) {
                            log("VERBOSE SWI133: Countdown annullato da Tasto Finestrino")
                            cancelPendingClose("Tasto Finestrino")
                            return@Thread
                        }
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
        }.start()
    }

    private fun stopWindowManualMonitor() {
        sWindowMonitorActive = false
    }

    // ── Bip répétitif (1 bip/seconde) pendant le compte à rebours ───────────────

    private fun startRepeatingBeepIfEnabled() {
        val ctx = sAppContext ?: return
        if (!Settings.isBeepEnabled(ctx)) return
        stopRepeatingBeep()
        val vol = Settings.getBeepVolume(ctx)
        lateinit var run: Runnable
        run = Runnable {
            if (sBeepRunnable == null) return@Runnable
            Thread {
                try {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    @Suppress("DEPRECATION")
                    am.requestAudioFocus(null, AudioManager.STREAM_NOTIFICATION,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, vol)
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    Thread.sleep(300)
                    tg.release()
                } catch (_: Exception) {}
            }.start()
            sMainHandler.postDelayed(run, 1_000L)
        }
        sBeepRunnable = run
        sMainHandler.post(run)
    }

    private fun stopRepeatingBeep() {
        sBeepRunnable?.let { sMainHandler.removeCallbacks(it); sBeepRunnable = null }
    }

    private fun gearLabel(raw: Int): String = when (raw) {
        GEAR_PARK_AOSP    -> "(PARK)"
        GEAR_REVERSE_AOSP -> "(REVERSE)"
        GEAR_DRIVE_AOSP   -> "(DRIVE)"
        1                 -> "(NEUTRAL)"
        0                 -> "(UNKNOWN)"
        else              -> "(autre)"
    }

    private fun dispatchGearChange(gear: Int) {
        // Sur SWI133 le signal de marcia brut est bruyant : après stationnement le
        // TCU éteint renvoie NEUTRAL/UNKNOWN (filtré en amont dans le poller), et des
        // blips transitoires de REVERSE/DRIVE peuvent apparaître. On ne quitte donc P
        // qu'après GEAR_MOVEMENT_POLLS lectures D/R consécutives (~3 s), pour ne pas
        // annuler à tort la fermeture programmée.
        var effective = gear
        if (gear == GEAR_PARK) {
            sGearMovementCount = 0
        } else if (Swi133Controller.isReady()) {
            sGearMovementCount++
            if (sGearMovementCount <= GEAR_MOVEMENT_POLLS) effective = GEAR_PARK
        }
        if (effective == sLastGear) return
        sLastGear = effective
        log("CarState: gear → $effective${if (effective == GEAR_PARK) " ← PARK" else ""}")
        if (effective != GEAR_PARK) {
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
        sMainHandler.post { cbs.forEach { it(effective) } }
    }

    private fun dispatchParkingBrakeChange(pb: Int) {
        if (pb == sLastParkingBrake) return
        sLastParkingBrake = pb
        val cbs = parkingBrakeCallbacks.toList()
        sMainHandler.post { cbs.forEach { it(pb) } }
    }

    private fun dispatchDoorSensorChange(v: Int) {
        if (v == sLastDoorSensor) return
        val prev = sLastDoorSensor
        sLastDoorSensor = v

        if (v == DOOR_OPEN) {
            // ── Porte s'OUVRE ──────────────────────────────────────────────────
            // Annuler si l'utilisateur revient (porte qui s'ouvre = mouvement humain)
            val cancelMsg = if (sCloseScheduled) " [close pending → ANNULÉ]" else ""
            log("CarState: door→OUVERT$cancelMsg")
            cancelPendingClose("porte ouverte")

            // Mémoriser que la porte a été ouverte en mode P (pour trigger DOOR_CLOSE)
            if (sLastGear == GEAR_PARK) sDriverDoorOpenedWhileParked = true

            // Déclencher si mode DOOR_OPEN
            if (delayTrigger() == DelayTrigger.DOOR_OPEN) {
                maybeTriggerParkClose("door→OPEN")
            }

        } else {
            // ── Porte se FERME / verrouille ────────────────────────────────────
            // On NE cancel PAS ici : la porte envoie plusieurs valeurs non-nulles
            // en séquence pendant la fermeture (ex : v=1 ajar, v=2 loquet engagé).
            // Annuler sur ces transitions tuerait le compte à rebours au verrouillage.
            // On annule UNIQUEMENT si la porte re-s'ouvre (v=0, branche ci-dessus).
            log("CarState: door v=$prev→$v${if (sCloseScheduled) " [close en cours]" else ""}")

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
    // Lecture de position des vitres via sVsm.getVehicleWindowValue(area)
    // Échelle 0–255 : 0.0 = fermée, 255.0 = complètement ouverte.
    // Diagnostic (vsm.getVehicleWindowValue(0..3) loggé au démarrage) a confirmé
    // que cette méthode est disponible directement sur CarVehicleSettingClient.
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Retourne la position de la vitre.
     * Ancienne carapi : getVehicleWindowValue(area), échelle 0–255 (0.0 = fermée).
     * SWI133+ : getXxxWindow() via IVehicleControlService, échelle 0–100 (0.0 = fermée).
     * -1 = indisponible.
     * Note : sur SWI133, 0.0 peut être renvoyé pour des vitres ouvertes sans capteur ;
     * closeAllWindowsPulsed ne s'y fie donc pas pour sauter la fermeture.
     */
    private fun getWindowPosition(area: Int): Float {
        // getVehicleWindowValue() ne remonte une vraie position que pour les vitres
        // équipées d'un capteur CAN (typiquement les versions avec fermeture AUTO).
        // Les vitres en mode PULSED (version standard sans capteur) retournent des
        // sentinelles fixes — on ne les appelle donc que si le mode est AUTO.
        val vsm = sVsm
        if (vsm != null) {
            return try {
                (vsm.javaClass
                    .getMethod("getVehicleWindowValue", Int::class.javaPrimitiveType!!)
                    .invoke(vsm, area) as? Number)?.toFloat() ?: -1f
            } catch (_: Exception) { -1f }
        }
        if (Swi133Controller.isReady()) return Swi133Controller.getPosition(area)
        return -1f
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Contrôle préventif pré-countdown
    //
    // L'hardware ne fournit PAS d'état binaire « fermée » par vitre : il n'expose que
    // la position (échelle 0..255 via l'ancienne carapi, 0..100 via IVehicleControlService
    // SWI133+). On ne saute donc le countdown que si les 4 positions sont confirmées ~0
    // par une lecture jugée fiable :
    //   - position illisible (< 0)         → non fiable → countdown
    //   - vitre en mode PULSED (légacy)    → pas de capteur CAN → sentinelle → countdown
    //   - SWI133 : seules FL est fiable ; 0.0 sur FR/RL/RR est une sentinelle (vitre
    //     ouverte sans capteur) → non fiable → countdown
    // Ce comportement « ne saute que si certain » évite les faux « toutes fermées »
    // (bug v1.3) et correspond à la règle : au moindre doute → beep + countdown.
    // ─────────────────────────────────────────────────────────────────────────────
    private fun allWindowsReliablyClosed(): Boolean {
        val ctx = sAppContext ?: return false
        for (area in Settings.ALL_AREAS) {
            if (Swi133Controller.isReady() && area != Settings.AREA_FL) return false
            if (sVsm != null && Settings.getWindowMode(ctx, area) != WindowMode.AUTO) return false
            val pos = getWindowPosition(area)
            if (pos < 0f) return false
            if (pos > 0.5f) return false
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Contrôle des vitres
    // ─────────────────────────────────────────────────────────────────────────────

    private val CMD_MOVE_UP = 1
    private val CMD_AUTO_UP = 3
    private val CMD_STOP    = 0

    @Volatile private var sWindowSetMethod: java.lang.reflect.Method? = null

    private fun windowSetMethod(): java.lang.reflect.Method? {
        sWindowSetMethod?.let { return it }
        val vsm = sVsm ?: return null
        val m = try {
            vsm.javaClass.getMethod("setVehicleWindowStatus",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        } catch (_: Exception) { null }
        sWindowSetMethod = m
        return m
    }

    /** Envoie une commande de vitre via l'ancienne carapi ou le contrôleur SWI133+. */
    private fun setWindowCmd(area: Int, cmd: Int) {
        val vsm = sVsm
        if (vsm != null) {
            val m = windowSetMethod() ?: return
            try { m.invoke(vsm, area, cmd) } catch (_: Exception) {}
        } else if (Swi133Controller.isReady()) {
            Swi133Controller.setWindow(area, cmd)
        }
    }

    fun closeAllWindowsPulsed(durationMs: Long = 3000L, pulseMs: Long = 120L) {
        val vsm = sVsm
        if (vsm == null && !Swi133Controller.isReady()) {
            log("close: VSM null (aucun contrôle fenêtre disponible)")
            return
        }
        if (vsm != null && windowSetMethod() == null) {
            log("close: setVehicleWindowStatus introuvable")
            return
        }
        val ctx = sAppContext ?: run { log("close: context null"); return }

        val areaNames = mapOf(
            Settings.AREA_FL to "FL", Settings.AREA_FR to "FR",
            Settings.AREA_RL to "RL", Settings.AREA_RR to "RR"
        )
        val autoAreas   = mutableListOf<Int>()
        val pulsedAreas = mutableListOf<Int>()
        Settings.ALL_AREAS.forEach { area ->
            val name = areaNames[area] ?: "A$area"
            val mode = Settings.getWindowMode(ctx, area)
            // Lire la position uniquement pour les vitres en mode AUTO :
            // seules celles-là ont un capteur CAN actif (versions avec fermeture automatique).
            // Les vitres PULSED (version standard) n'ont pas de capteur → -1f systématique.
            val pos = if (mode == WindowMode.AUTO) getWindowPosition(area) else -1f
            val posStr = if (pos < 0f) "pos=?" else "pos=${"%.1f".format(pos)}"
            when (mode) {
                WindowMode.AUTO -> {
                    // Sur SWI133 les vitres sans capteur renvoient pos=0.0 alors qu'elles
                    // sont ouvertes (seul FL remonte une vraie position). On ne fait donc
                    // PAS confiance à 0.0 ni à une lecture manquante pour sauter la fermeture :
                    // en mode AUTO on envoie toujours la commande native de fermeture.
                    log("close: $name $posStr → AUTO${if (pos == 0f) " (pos=0 non fiable → forcée)" else ""}")
                    autoAreas.add(area)
                }
                WindowMode.PULSED -> { log("close: $name $posStr → PULSED"); pulsedAreas.add(area) }
                WindowMode.OFF    ->   log("close: $name $posStr → OFF (désactivée)")
            }
        }
        log("Close: AUTO=$autoAreas  PULSED=$pulsedAreas")

        // SWI133 : seule FL (conducteur) honore la commande native AUTO_UP. L'envoyer
        // aussi à FR/RL/RR peut empêcher leur fermeture (retour terrain : RR en AUTO ne
        // se ferme pas). Sur SWI133 on ne l'envoie donc qu'à FL ; les autres vitres AUTO
        // sont fermées par la tenue UP ci-dessous (même mécanisme que PULSE, vérifié).
        if (sVsm == null) {
            if (Settings.AREA_FL in autoAreas) setWindowCmd(Settings.AREA_FL, CMD_AUTO_UP)
        } else {
            autoAreas.forEach { area -> setWindowCmd(area, CMD_AUTO_UP) }
        }

        // Fallback PULSED : si Katman4 / CarAdapterService est déconnecté (sVsm == null),
        // la commande native AUTO_UP n'est pas fiable (sur SWI133 seule FL l'honore).
        // On applique donc l'impulsion UP à TOUTES les vitres AUTO pour garantir leur
        // fermeture physique, exactement comme les vitres PULSED.
        val holdAreas = pulsedAreas.toMutableList()
        if (sVsm == null) {
            if (autoAreas.isNotEmpty()) {
                log("Close: fallback PULSED (Katman4 null) → vitres AUTO maintenues UP: $autoAreas")
                holdAreas.addAll(autoAreas)
            }
        }
        if (holdAreas.isEmpty()) return

        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            holdAreas.forEach { area -> setWindowCmd(area, CMD_MOVE_UP) }
            try { Thread.sleep(pulseMs) } catch (_: InterruptedException) { return }
        }
        holdAreas.forEach { area -> setWindowCmd(area, CMD_STOP) }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SWI133+ — contrôle des vitres via com.saicmotor.service.vehicle.VehicleService
    //
    // Sur SWI133 (et SWI68 R71) l'ancienne pile CarAdapterClient/CarVehicleSettingClient
    // est obsolète : la classe CarAdapterClient hébergée par com.saicmotor.voiceservice
    // tente de se lier à com.saicmotor.caradapter.CarAdapterService, package qui n'existe
    // plus sur SWI133 (le binding échoue → queryClient() → null → « VSM null »).
    //
    // Le contrôle véhicule est désormais dans com.saicmotor.service.vehicle.VehicleService
    // (exported), qui expose un IHubService dont getService("vehiclecontrol") renvoie un
    // IVehicleControlService avec des appels par vitre :
    //   setDriverWindow / setPassengerWindow / setLeftRearWindow / setRightRearWindow
    // (float : 0 = STOP, 1 = UP, 2 = DOWN, 3 = AUTO_UP, 4 = AUTO_DOWN) et les getters
    // correspondants (position 0..100, 0 = fermée).
    // ─────────────────────────────────────────────────────────────────────────────
    private object Swi133Controller {
        private const val HUB_DESC   = "com.saicmotor.sdk.vehiclesettings.IHubService"
        private const val CTRL_DESC  = "com.saicmotor.sdk.vehiclesettings.IVehicleControlService"
        private const val COND_DESC  = "com.saicmotor.sdk.vehiclesettings.IVehicleConditionService"
        private const val PROP_DESC  = "com.saicmotor.sdk.vehiclesettings.IVehiclePropertyService"
        private const val PACKAGE    = "com.saicmotor.service.vehicle"
        private const val ACTION     = "com.saicmotor.service.vehicle.VehicleService"
        private const val KEY_CTRL   = "vehiclecontrol"
        private const val KEY_COND   = "vehiclecondition"
        private const val KEY_PROP   = "vehicleproperty"

        private const val TX_HUB_GET_SERVICE = 1

        // IVehicleControlService (fenêtres)
        private const val TX_GET_DRIVER     = 5
        private const val TX_SET_DRIVER     = 6
        private const val TX_GET_PASSENGER  = 7
        private const val TX_SET_PASSENGER  = 8
        private const val TX_GET_LEFT_REAR  = 9
        private const val TX_SET_LEFT_REAR  = 10
        private const val TX_GET_RIGHT_REAR = 11
        private const val TX_SET_RIGHT_REAR = 12

        // IVehicleConditionService (vitesse / accensione / marcia)
        private const val TX_GET_SPEED    = 3   // float (km/h)
        private const val TX_GET_IGNITION = 4   // int (0=OFF,1=ACC,2=RUN,3=CRANK)
        private const val TX_GET_GEAR     = 5   // int (AOSP VehicleGear : PARK=4)

        // IVehiclePropertyService (porte conducteur)
        private const val TX_PROP_GET_INT    = 6
        private const val SIGNAL_DRIVER_DOOR = 100859990  // 0=fermée,1=ouverte,2=ajar,3=grande ouverte

        @Volatile private var ctrl: IBinder? = null
        @Volatile private var cond: IBinder? = null
        @Volatile private var prop: IBinder? = null
        @Volatile private var bound = false
        private const val MAX_ATTEMPTS = 6

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) return
                ctrl = getService(service, KEY_CTRL, "vehiclecontrol")
                cond = getService(service, KEY_COND, "vehiclecondition")
                prop = getService(service, KEY_PROP, "vehicleproperty")
                bound = true
                log("Swi133: control=${ctrl != null} condition=${cond != null} property=${prop != null}")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                ctrl = null; cond = null; prop = null
                bound = false
                log("Swi133: VehicleService déconnecté")
            }
        }

        private fun getService(hub: IBinder, key: String, label: String): IBinder? {
            return try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(HUB_DESC)
                    data.writeString(key)
                    hub.transact(TX_HUB_GET_SERVICE, data, reply, 0)
                    reply.readException()
                    reply.readStrongBinder()
                } finally { data.recycle(); reply.recycle() }
            } catch (e: Exception) {
                log("Swi133: getService($label) err: ${e.message}")
                null
            }
        }

        fun init(context: Context) {
            init(context, 0)
        }

        private fun init(context: Context, attempt: Int) {
            if (bound) return
            if (attempt >= MAX_ATTEMPTS) {
                log("Swi133: abandon (package $PACKAGE absent ?)")
                return
            }
            try {
                val intent = Intent(ACTION).setPackage(PACKAGE)
                val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                log("Swi133: bindService($PACKAGE) tentative ${attempt + 1} → $ok")
                if (!ok) {
                    Handler(Looper.getMainLooper()).postDelayed(
                        { init(context, attempt + 1) }, 5_000
                    )
                }
            } catch (e: Exception) {
                log("Swi133: bind err: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun isReady() = ctrl != null

        private fun setTx(area: Int): Int = when (area) {
            Settings.AREA_FL -> TX_SET_DRIVER
            Settings.AREA_FR -> TX_SET_PASSENGER
            Settings.AREA_RL -> TX_SET_LEFT_REAR
            else             -> TX_SET_RIGHT_REAR
        }

        private fun getTx(area: Int): Int = when (area) {
            Settings.AREA_FL -> TX_GET_DRIVER
            Settings.AREA_FR -> TX_GET_PASSENGER
            Settings.AREA_RL -> TX_GET_LEFT_REAR
            else             -> TX_GET_RIGHT_REAR
        }

        fun setWindow(area: Int, cmd: Int) {
            val b = ctrl ?: return
            try {
                val data = Parcel.obtain()
                try {
                    data.writeInterfaceToken(CTRL_DESC)
                    data.writeFloat(cmd.toFloat())
                    b.transact(setTx(area), data, null, IBinder.FLAG_ONEWAY)
                } finally { data.recycle() }
            } catch (_: Exception) {}
        }

        fun getPosition(area: Int): Float {
            val b = ctrl ?: return -1f
            return try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(CTRL_DESC)
                    b.transact(getTx(area), data, reply, 0)
                    reply.readException()
                    reply.readFloat()
                } finally { data.recycle(); reply.recycle() }
            } catch (_: Exception) { -1f }
        }

        // ── Capteurs (marcia / vitesse / accensione / porte) ─────────────────────

        private fun transactInt(b: IBinder, desc: String, tx: Int): Int {
            return try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(desc)
                    b.transact(tx, data, reply, 0)
                    reply.readException()
                    reply.readInt()
                } finally { data.recycle(); reply.recycle() }
            } catch (_: Exception) { -1 }
        }

        private fun transactFloat(b: IBinder, desc: String, tx: Int): Float {
            return try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(desc)
                    b.transact(tx, data, reply, 0)
                    reply.readException()
                    reply.readFloat()
                } finally { data.recycle(); reply.recycle() }
            } catch (_: Exception) { -1f }
        }

        /** Marcia (AOSP VehicleGear, PARK=4) ou -1. */
        fun getGear(): Int {
            val b = cond ?: return -1
            return transactInt(b, COND_DESC, TX_GET_GEAR)
        }

        /** Vitesse (km/h) ou -1. */
        fun getSpeed(): Float {
            val b = cond ?: return -1f
            return transactFloat(b, COND_DESC, TX_GET_SPEED)
        }

        /** Accensione (0=OFF, 1=ACC, 2=RUN, 3=CRANK) ou -1. */
        fun getIgnition(): Int {
            val b = cond ?: return -1
            return transactInt(b, COND_DESC, TX_GET_IGNITION)
        }

        /** Porte conducteur : 0=fermée, 1=ouverte, 2=ajar, 3=grande ouverte ; -1 = indispo. */
        fun getDoor(): Int {
            val b = prop ?: return -1
            return try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(PROP_DESC)
                    data.writeInt(SIGNAL_DRIVER_DOOR)
                    b.transact(TX_PROP_GET_INT, data, reply, 0)
                    reply.readException()
                    reply.readInt()
                } finally { data.recycle(); reply.recycle() }
            } catch (_: Exception) { -1 }
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
