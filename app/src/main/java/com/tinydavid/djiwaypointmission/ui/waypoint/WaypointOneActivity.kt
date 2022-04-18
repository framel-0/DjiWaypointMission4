package com.tinydavid.djiwaypointmission.ui.waypoint

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.*
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.maps.*
import com.mapbox.maps.extension.style.expressions.dsl.generated.interpolate
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorBearingChangedListener
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.tinydavid.djiwaypointmission.DJIApplication
import com.tinydavid.djiwaypointmission.R
import com.tinydavid.djiwaypointmission.databinding.ActivityWaypointOneBinding
import com.tinydavid.djiwaypointmission.ui.camera.CameraActivity
import com.tinydavid.djiwaypointmission.utils.LocationPermissionHelper
import dji.common.error.DJIError
import dji.common.mission.waypoint.*
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import dji.sdk.sdkmanager.DJISDKManager
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class WaypointOneActivity : AppCompatActivity(), TextureView.SurfaceTextureListener,
    View.OnClickListener {

    private lateinit var mBinding: ActivityWaypointOneBinding

    private lateinit var mDistanceTimeTextView: TextView
    private lateinit var mStatusTextView: TextView
    private lateinit var mLocateButton: Button
    private lateinit var mAddButton: Button
    private lateinit var mClearButton: Button
    private lateinit var configButton: Button
    private lateinit var mStartButton: Button
    private lateinit var mStopButton: Button

    private lateinit var mapView: MapView

    private lateinit var mVideoSurface: TextureView //Used to display the DJI product's camera video stream

    private var isAdd = false
    private var droneLocationLat: Double = 0.0
    private var droneLocationLng: Double = 0.0
    private var droneMarker: Marker? = null
    private val annotationApi = mapView.annotations
    private val dronePointAnnotationManager = annotationApi.createPointAnnotationManager(mapView)
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>()

    private lateinit var locationPermissionHelper: LocationPermissionHelper

    private lateinit var mMapboxMap: MapboxMap

    private val onIndicatorBearingChangedListener = OnIndicatorBearingChangedListener {
        mMapboxMap.setCamera(CameraOptions.Builder().bearing(it).build())
    }

    private val onIndicatorPositionChangedListener = OnIndicatorPositionChangedListener {
        mMapboxMap.setCamera(CameraOptions.Builder().center(it).build())
        mapView.gestures.focalPoint = mMapboxMap.pixelForCoordinate(it)
    }

    private val onMoveListener = object : OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) {
            onCameraTrackingDismissed()
        }

        override fun onMove(detector: MoveGestureDetector): Boolean {
            return false
        }

        override fun onMoveEnd(detector: MoveGestureDetector) {}
    }

    private val onMapClickListener = OnMapClickListener { point ->
        if (isAdd) { // if the user is adding waypoints
            markWaypoint(point) // this will mark the waypoint visually
            val waypoint = Waypoint(
                point.latitude(),
                point.longitude(),
                point.altitude().toFloat()
            ) // this will create the waypoint object to be added to the mission

            if (waypointMissionBuilder == null) {
                waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                    waypointList.add(waypoint) // add the waypoint to the list
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            } else {
                waypointMissionBuilder?.let { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            }
        } else {
            setResultToToast("Cannot Add Waypoint")
        }
        true
    }


    private var altitude = 100f
    private var speed = 10f

    private val waypointList = mutableListOf<Waypoint>()
    private var instance: WaypointMissionOperator? = null
    private var finishedAction = WaypointMissionFinishedAction.GO_HOME
    private var headingMode = WaypointMissionHeadingMode.AUTO

    private var receivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private var codecManager: DJICodecManager? =
        null //handles the encoding and decoding of video data


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityWaypointOneBinding.inflate(layoutInflater)
        val view = mBinding.root

        mapView = mBinding.mapView
        // this will get your mapbox instance using your access token
        setContentView(view)

        initUi() // initialize the UI

        locationPermissionHelper = LocationPermissionHelper(WeakReference(this))
        locationPermissionHelper.checkPermissions {
            onMapReady()
        }

        /*
       The receivedVideoDataListener receives the raw video data and the size of the data from the DJI product.
       It then sends this data to the codec manager for decoding.
       */
        receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
        }

        addListener() // will add a listener to the waypoint mission operator
    }


    private fun onMapReady() {
        mMapboxMap = mapView.getMapboxMap() // initialize the map

        mMapboxMap.setCamera(
            CameraOptions.Builder()
                .zoom(14.0)
                .build()
        )

        mMapboxMap.loadStyleUri(
            Style.MAPBOX_STREETS
        ) {
            initLocationComponent()
            setupGesturesListener()
        }


    }

    private fun addAnnotationToMap(lng: Double, lat: Double) {
        // Create an instance of the Annotation API and get the PointAnnotationManager.
        bitmapFromDrawableRes(
            this@WaypointOneActivity,
            R.drawable.red_marker
        )?.let {
//            val annotationApi = mapView.annotations
            val pointAnnotationManager = annotationApi.createPointAnnotationManager(mapView)
            // Set options for the resulting symbol layer.
            val pointAnnotationOptions: PointAnnotationOptions = PointAnnotationOptions()
                // Define a geographic coordinate.
                .withPoint(Point.fromLngLat(lng, lat))
                // Specify the bitmap you assigned to the point annotation
                // The bitmap will be added to map style automatically.
                .withIconImage(it)
            // Add the resulting pointAnnotation to the map.
            pointAnnotationManager.create(pointAnnotationOptions)
        }
    }

    private fun bitmapFromDrawableRes(context: Context, @DrawableRes resourceId: Int) =
        convertDrawableToBitmap(AppCompatResources.getDrawable(context, resourceId))

    private fun convertDrawableToBitmap(sourceDrawable: Drawable?): Bitmap? {
        if (sourceDrawable == null) {
            return null
        }
        return if (sourceDrawable is BitmapDrawable) {
            sourceDrawable.bitmap
        } else {
// copying drawable object to not manipulate on the same reference
            val constantState = sourceDrawable.constantState ?: return null
            val drawable = constantState.newDrawable().mutate()
            val bitmap: Bitmap = Bitmap.createBitmap(
                drawable.intrinsicWidth, drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    }


    private fun setupGesturesListener() {
        mapView.gestures.addOnMoveListener(onMoveListener)
        mapView.gestures.addOnMapClickListener(onMapClickListener)
    }

    private fun initLocationComponent() {
        val locationComponentPlugin = mapView.location
        locationComponentPlugin.updateSettings {
            this.enabled = true
            this.locationPuck = LocationPuck2D(
                bearingImage = AppCompatResources.getDrawable(
                    this@WaypointOneActivity,
                    R.drawable.aircraft,
                ),
                shadowImage = AppCompatResources.getDrawable(
                    this@WaypointOneActivity,
                    R.drawable.aircraft,
                ),
                scaleExpression = interpolate {
                    linear()
                    zoom()
                    stop {
                        literal(0.0)
                        literal(0.6)
                    }
                    stop {
                        literal(20.0)
                        literal(1.0)
                    }
                }.toJson()
            )
        }
//        locationComponentPlugin.
        locationComponentPlugin.addOnIndicatorPositionChangedListener(
            onIndicatorPositionChangedListener
        )
        locationComponentPlugin.addOnIndicatorBearingChangedListener(
            onIndicatorBearingChangedListener
        )
    }

    private fun onCameraTrackingDismissed() {
        Toast.makeText(this, "onCameraTrackingDismissed", Toast.LENGTH_SHORT).show()
        mapView.location
            .removeOnIndicatorPositionChangedListener(onIndicatorPositionChangedListener)
        mapView.location
            .removeOnIndicatorBearingChangedListener(onIndicatorBearingChangedListener)
        mapView.gestures.removeOnMoveListener(onMoveListener)
    }


    private fun markWaypoint(point: Point) {
        addAnnotationToMap(point.longitude(), point.latitude())
    }

    override fun onResume() {
        super.onResume()
        initFlightController()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeListener()
    }

    //Add Listener for WaypointMissionOperator
    private fun addListener() {
        getWaypointMissionOperator()?.addListener(eventNotificationListener)
    }

    private fun removeListener() {
        getWaypointMissionOperator()?.removeListener(eventNotificationListener)
    }

    private val eventNotificationListener: WaypointMissionOperatorListener =
        object : WaypointMissionOperatorListener {
            override fun onDownloadUpdate(downloadEvent: WaypointMissionDownloadEvent) {}
            override fun onUploadUpdate(uploadEvent: WaypointMissionUploadEvent) {}
            override fun onExecutionUpdate(executionEvent: WaypointMissionExecutionEvent) {}
            override fun onExecutionStart() {}
            override fun onExecutionFinish(error: DJIError?) {
                setResultToToast("Execution finished: " + if (error == null) "Success!" else error.description)
            }
        }

    private fun getWaypointMissionOperator(): WaypointMissionOperator? { // returns the mission operator
        if (instance == null) {
            if (DJISDKManager.getInstance().missionControl != null) {
                instance = DJISDKManager.getInstance().missionControl.waypointMissionOperator
            }
        }
        return instance
    }

    private fun initUi() {
        mDistanceTimeTextView = mBinding.textWaypointDistanceTime
        mStatusTextView = mBinding.textStatus
        mLocateButton = mBinding.buttonLocate
        mAddButton = mBinding.buttonAddWaypoint
        mClearButton = mBinding.buttonClearWaypoint

        configButton = mBinding.buttonConfigWaypoint
        mStartButton = mBinding.buttonStartWaypoint
        mStopButton = mBinding.buttonStopWaypoint

        mVideoSurface = mBinding.videoPreviewerSurfaceD

        /*
        Giving videoSurface a listener that checks for when a surface texture is available.
        The videoSurface will then display the surface texture, which in this case is a camera video stream.
        */
        mVideoSurface.surfaceTextureListener = this

        mStartButton.isEnabled = false
        mStopButton.isEnabled = false

        mLocateButton.setOnClickListener(this)
        mAddButton.setOnClickListener(this)
        mClearButton.setOnClickListener(this)

        configButton.setOnClickListener(this)
        mStartButton.setOnClickListener(this)
        mStopButton.setOnClickListener(this)
    }

    private fun initFlightController() {
        // this will initialize the flight controller with predetermined data
        DJIApplication.getFlightController()?.let { flightController ->
            flightController.setStateCallback { flightControllerState ->
                // set the latitude and longitude of the drone based on aircraft location
                droneLocationLat = flightControllerState.aircraftLocation.latitude
                droneLocationLng = flightControllerState.aircraftLocation.longitude
                runOnUiThread {
                    updateDroneLocation() // this will be called on the main thread
                    cameraUpdate()
                }
            }
        }
    }


    private fun updateDroneLocation() { // this will draw the aircraft as it moves
        //Log.i(TAG, "Drone Lat: $droneLocationLat - Drone Lng: $droneLocationLng")
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) {
            return
        }

        val pos = Point.fromLngLat(droneLocationLng, droneLocationLat)
        // the following will draw the aircraft on the screen
        bitmapFromDrawableRes(
            this@WaypointOneActivity,
            R.drawable.aircraft
        )?.let {

            // Set options for the resulting symbol layer.
            val pointAnnotationOptions: PointAnnotationOptions = PointAnnotationOptions()
                // Define a geographic coordinate.
                .withPoint(pos)
                // Specify the bitmap you assigned to the point annotation
                // The bitmap will be added to map style automatically.
                .withIconImage(it)
            // Add the resulting pointAnnotation to the map.

            runOnUiThread {
                dronePointAnnotationManager.deleteAll()
                if (checkGpsCoordination(droneLocationLat, droneLocationLng)) {
                    dronePointAnnotationManager.create(pointAnnotationOptions)
                }
            }
        }
    }

    private fun cameraUpdate() { // update where you're looking on the map
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) {
            return
        }
        val point = Point.fromLngLat(droneLocationLng, droneLocationLat)
        val zoomLevel = 18.0
/*
        mMapboxMap.flyTo(
            cameraOptions {
                center(point) // Sets the new camera position on click point
                zoom(zoomLevel) // Sets the zoom
                bearing(180.0) // Rotate the camera
                pitch(60.0) // Set the camera pitch
            },
            mapAnimationOptions {
                duration(7000)
            }
        )
*/

        val mapAnimationOptions = MapAnimationOptions.Builder().duration(1500L).build()
        mapView.camera.easeTo(
            CameraOptions.Builder()
                // Centers the camera to the lng/lat specified.
                .center(point)
                // specifies the zoom value. Increase or decrease to zoom in or zoom out
                .zoom(zoomLevel)
                // specify frame of reference from the center.
                .padding(EdgeInsets(500.0, 0.0, 0.0, 0.0))
                .build(),
            mapAnimationOptions
        )
    }

    private fun showSettingsDialog() {
        val wayPointSettings =
            layoutInflater.inflate(R.layout.dialog_waypointsetting, null) as LinearLayout

        val wpAltitudeTV = wayPointSettings.findViewById<View>(R.id.altitude) as TextView
        val speedRG = wayPointSettings.findViewById<View>(R.id.speed) as RadioGroup
        val actionAfterFinishedRG =
            wayPointSettings.findViewById<View>(R.id.actionAfterFinished) as RadioGroup
        val headingRG = wayPointSettings.findViewById<View>(R.id.heading) as RadioGroup

        speedRG.setOnCheckedChangeListener { _, checkedId -> // set the speed to the selected option
            Log.d(TAG, "Select speed")
            when (checkedId) {
                R.id.lowSpeed -> {
                    speed = 3.0f
                }
                R.id.MidSpeed -> {
                    speed = 5.0f
                }
                R.id.HighSpeed -> {
                    speed = 10.0f
                }
            }
        }

        actionAfterFinishedRG.setOnCheckedChangeListener { _, checkedId -> // set the action after finishing the mission
            Log.d(TAG, "Select finish action")

            when (checkedId) {
                R.id.finishNone -> {
                    finishedAction = WaypointMissionFinishedAction.NO_ACTION
                }
                R.id.finishGoHome -> {
                    finishedAction = WaypointMissionFinishedAction.GO_HOME
                }
                R.id.finishAutoLanding -> {
                    finishedAction = WaypointMissionFinishedAction.AUTO_LAND
                }
                R.id.finishToFirst -> {
                    finishedAction = WaypointMissionFinishedAction.GO_FIRST_WAYPOINT
                }
            }
        }

        headingRG.setOnCheckedChangeListener { _, checkedId -> // changes the heading

            Log.d(TAG, "Select heading")
            when (checkedId) {
                R.id.headingNext -> {
                    headingMode = WaypointMissionHeadingMode.AUTO
                }
                R.id.headingInitDirec -> {
                    headingMode = WaypointMissionHeadingMode.USING_INITIAL_DIRECTION
                }
                R.id.headingRC -> {
                    headingMode = WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER
                }
                R.id.headingWP -> {
                    headingMode = WaypointMissionHeadingMode.USING_WAYPOINT_HEADING
                }

            }
        }

        AlertDialog.Builder(this) // creates the dialog
            .setTitle("")
            .setView(wayPointSettings)
            .setPositiveButton("Finish") { dialog, id ->
                val altitudeString = wpAltitudeTV.text.toString()
                altitude = nullToIntegerDefault(altitudeString).toInt().toFloat()
                Log.e(TAG, "altitude $altitude")
                Log.e(TAG, "speed $speed")
                Log.e(TAG, "mFinishedAction $finishedAction")
                Log.e(TAG, "mHeadingMode $headingMode")
                configWayPointMission()
            }
            .setNegativeButton("Cancel") { dialog, id -> dialog.cancel() }
            .create()
            .show()
    }

    private fun nullToIntegerDefault(value: String): String {
        var newValue = value
        if (!isIntValue(newValue)) newValue = "0"
        return newValue
    }

    private fun isIntValue(value: String): Boolean {
        try {
            val newValue = value.replace(" ", "")
            newValue.toInt()
        } catch (e: Exception) {
            return false
        }
        return true
    }

    private fun enableDisableAdd() { // toggle for adding or not
        if (!isAdd) {
            isAdd = true
            mAddButton.text = "Exit"
        } else {
            isAdd = false
            mAddButton.text = "Add"
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.button_locate -> { // will draw the drone and move camera to the position of the drone on the map
                updateDroneLocation()
                cameraUpdate()
            }
            R.id.button_add_waypoint -> { // this will toggle the adding of the waypoints
                enableDisableAdd()
            }
            R.id.button_clear_waypoint -> { // clear the waypoints on the map
                runOnUiThread {
//                    mapboxMap?.clearData()
                }
            }
            R.id.button_config_waypoint -> { // this will show the settings
                showSettingsDialog()
            }

            R.id.button_start_waypoint -> { // this will let the drone start navigating to the waypoints
                uploadWaypointMission()
            }
            R.id.button_stop_waypoint -> { // this will immediately stop the waypoint mission
                stopWaypointMission()
            }
            R.id.button_camera_view -> { // this will immediately stop the waypoint mission
                startActivity(this@WaypointOneActivity, CameraActivity::class.java)
            }
            else -> {}
        }
    }


    private fun configWayPointMission() {
        if (waypointMissionBuilder == null) {
            waypointMissionBuilder = WaypointMission.Builder()
                .finishedAction(finishedAction) // initialize the mission builder if null
                .headingMode(headingMode)
                .autoFlightSpeed(speed)
                .maxFlightSpeed(speed)
                .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
        }

        waypointMissionBuilder?.let { builder ->
            builder.finishedAction(finishedAction)
                .headingMode(headingMode)
                .autoFlightSpeed(speed)
                .maxFlightSpeed(speed)
                .flightPathMode(WaypointMissionFlightPathMode.NORMAL)

            if (builder.waypointList.size > 0) {
                for (i in builder.waypointList.indices) { // set the altitude of all waypoints to the user defined altitude
                    builder.waypointList[i].altitude = altitude
                }
                setResultToToast("Set Waypoint attitude successfully")
            }
            getWaypointMissionOperator()?.let { operator ->
                val error = operator.loadMission(builder.build()) // load the mission
                if (error == null) {
                    mStartButton.isEnabled = true
                    setResultToToast("loadWaypoint succeeded")
                } else {
                    setResultToToast("loadWaypoint failed " + error.description)
                }
            }
        }
    }

    private fun uploadWaypointMission() { // upload the mission
        getWaypointMissionOperator()!!.uploadMission { error ->
            if (error == null) {
                val msg = "Mission upload successfully!"
                mStatusTextView.text = msg
                setResultToToast(msg)

                var batPercentage = 0


                DJIApplication.getProductInstance()?.battery?.setStateCallback {
                    batPercentage = it.chargeRemainingInPercent
                }


                val totalDistanceMeters = waypointMissionBuilder?.calculateTotalDistance()
                val totalTimeSecs = waypointMissionBuilder?.calculateTotalTime()

                val time: String = if (totalTimeSecs != null) {
                    val time = splitToComponentTimes(totalTimeSecs)

                    val hours = time[0]
                    val minutes = time[1]
                    val seconds = time[2]
                    String.format("%02d hrs:%02d mins:%02d secs", hours, minutes, seconds)
                } else
                    ""

                mDistanceTimeTextView.text =
                    "Total Distance: ${totalDistanceMeters ?: ""} Meters, Total time: $time smart Battery: $batPercentage%"

                if (batPercentage > 18)
//                    if ((totalTimeSecs!!.div(60)) > 20)
                    startWaypointMission()
//                    else
//                        setResultToToast("Mission start failed: total time exceeded")
                else
                    setResultToToast("Mission start failed, error: Low Battery")

            } else {
                setResultToToast("Mission upload failed, error: " + error.description + " retrying...")
                getWaypointMissionOperator()?.retryUploadMission(null)
            }
        }
    }

    fun splitToComponentTimes(seconds: Float): IntArray {
        val longVal: Long = seconds.toLong()
        val hours = longVal.toInt() / 3600
        var remainder = longVal.toInt() - hours * 3600
        val minutes = remainder / 60
        remainder -= minutes * 60
        val secs = remainder
        return intArrayOf(hours, minutes, secs)
    }

    private fun startWaypointMission() { // start mission


        getWaypointMissionOperator()?.startMission { error ->
            if (error == null) {
                val msg = "Mission Start successfully!"
                mStatusTextView.text = msg
                setResultToToast("Mission Start: Successfully")
                mStopButton.isEnabled = true
            } else {
                setResultToToast("Mission Start: " + error.description)
            }

        }

    }

    private fun stopWaypointMission() { // stop mission
        getWaypointMissionOperator()?.stopMission { error ->
            setResultToToast("Mission Stop: " + if (error == null) "Successfully" else error.description)
        }
    }


    private fun setResultToToast(string: String) {
        runOnUiThread { Toast.makeText(this, string, Toast.LENGTH_SHORT).show() }
    }

    private fun startActivity(context: Context, activity: Class<*>?) {
        // this will start the activity
        val intent = Intent(context, activity)
        context.startActivity(intent)
    }


    //When a TextureView's SurfaceTexture is ready for use, use it to initialize the codecManager
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(this, surface, width, height)
        }
    }

    //when a SurfaceTexture's size changes...
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    //when a SurfaceTexture is about to be destroyed, uninitialize the codedManager
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        codecManager?.cleanSurface()
        codecManager = null
        return false
    }

    //When a SurfaceTexture is updated...
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locationPermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
        // this will check if your gps coordinates are valid
        return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
    }

    companion object {
        const val TAG = "WaypointOneActivity"

        private var waypointMissionBuilder: WaypointMission.Builder? = null
        // you will use this to add your waypoints


    }
}