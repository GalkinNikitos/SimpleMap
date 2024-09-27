package com.example.diploma
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.gson.annotations.SerializedName
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.LocationComponentOptions
import com.mapbox.mapboxsdk.location.engine.LocationEngineRequest
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.permissions.PermissionsListener
import com.mapbox.mapboxsdk.location.permissions.PermissionsManager
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.math.pow
@Serializable
data class OsrmRoute(
    @SerializedName("routes") val routes: List<RouteInfo>
)
@Serializable
data class RouteInfo(
    val legs: List<Leg>
)
@Serializable
data class Leg(
    @SerializedName("steps") val steps: List<Step>
)
@Serializable
data class Step(
    @SerializedName("geometry") val geometry: String
)
class MainActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mapView: MapView
    private lateinit var mapboxMap: MapboxMap
    private var markerCount = 0
    private val markers = mutableListOf<Point>()
    private lateinit var popupLayout: LinearLayout
    private lateinit var editText1: EditText
    private lateinit var editText2: EditText
    private var currentStyle: Style? = null
    private var pathSource: GeoJsonSource? = null
    private var locationComponent: LocationComponent? = null
    private var permissionsManager: PermissionsManager? = null
    private var lastLocation: Location? = null
    private val MAPTILER_API_KEY = "hLhaPLSIrELIBZAk08wF"
    private val mapId = "streets-v2"
    private val styleUrl = "https://api.maptiler.com/maps/$mapId/style.json?key=$MAPTILER_API_KEY"
    private var routeLineLayer: LineLayer? = null
    private var isMapInitializationPending = false
    private var isMapStyleLoaded = false
    @SuppressLint("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this)
        val inflater = LayoutInflater.from(this)
        val rootView = inflater.inflate(R.layout.activity_main, null)
        setContentView(rootView)
        popupLayout = rootView.findViewById(R.id.popupLayout)
        popupLayout.setBackgroundResource(R.drawable.blue_border)
        editText1 = rootView.findViewById(R.id.editText1)
        editText1.setBackgroundResource(R.drawable.green_border)
        editText1.setHintTextColor(Color.DKGRAY)
        editText1.setTextColor(Color.DKGRAY)
        editText1.setPadding(resources.getDimensionPixelSize(R.dimen.edit_text_padding_start), 0, 0, 0)
        editText2 = rootView.findViewById(R.id.editText2)
        editText2.setBackgroundResource(R.drawable.red_border)
        editText2.setHintTextColor(Color.DKGRAY)
        editText2.setTextColor(Color.DKGRAY)
        editText2.setPadding(resources.getDimensionPixelSize(R.dimen.edit_text_padding_start), 0, 0, 0)
        mapView = rootView.findViewById(R.id.mapView)
        showPopupMenu()
        checkPermissions()
        mapView.getMapAsync { map ->
            mapboxMap = map
            mapboxMap.setStyle(styleUrl) { style ->
                currentStyle = style
                isMapStyleLoaded = true
                initializeMapWithStyle(style)
                map.cameraPosition = CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(1.0).build()
                map.addOnCameraIdleListener(onCameraIdleListener)
            }
        }
        val resetButton = rootView.findViewById<ImageView>(R.id.resetButton)
        resetButton.setOnClickListener {
            resetMapAndMarkers()
        }
        val startButton = rootView.findViewById<ImageView>(R.id.startButton)
        startButton.setOnClickListener {
            drawRouteOnMapTextFields()
        }
    }
    override fun onMapReady(mapboxMap: MapboxMap) {
        this@MainActivity.mapboxMap = mapboxMap
        mapboxMap.setStyle(styleUrl) { style ->
            currentStyle = style
            isMapStyleLoaded = true
            setupMapWithStyle(mapboxMap, style)
        }
    }
    private fun initializeMapWithStyle(style: Style) {
        Log.d("MapDebug", "initializeMapWithStyle called")
        currentStyle = style
        Log.d("MapDebug", "currentStyle assigned")
        if (style.isFullyLoaded) {
            Log.d("MapDebug", "Style is fully loaded")
            val markerSourceId = "marker-source"
            Log.d("MapDebug", "Checking markerSource")
            var markerSource: GeoJsonSource? = style.getSourceAs(markerSourceId)
            if (markerSource == null) {
                markerSource = GeoJsonSource(markerSourceId)
                style.addSource(markerSource)
                Log.d("MapDebug", "markerSource initialized")
            } else {
                Log.d("MapDebug", "markerSource already exists")
            }
            val routeSourceId = "route-source"
            var routeSource: GeoJsonSource? = style.getSourceAs(routeSourceId)
            if (routeSource == null) {
                routeSource = GeoJsonSource(routeSourceId)
                style.addSource(routeSource)
                Log.d("MapDebug", "routeSource initialized")
            } else {
                Log.d("MapDebug", "routeSource already exists")
            }
            val existingRouteLayer = style.getLayer("route-line")
            if (existingRouteLayer != null) {
                style.removeLayer(existingRouteLayer)
            }
            routeLineLayer = LineLayer("route-line", routeSourceId)
                .withProperties(
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineColor(Color.BLUE)
                )
            style.addLayer(routeLineLayer!!)
            addMarkersToMap()
        } else {
            Log.d("MapDebug", "Style is not fully loaded")
            isMapInitializationPending = true
        }
    }
    private val onCameraIdleListener = object : MapboxMap.OnCameraIdleListener {
        override fun onCameraIdle() {
            if (isMapInitializationPending && currentStyle != null && currentStyle!!.isFullyLoaded) {
                Log.d("MapDebug", "Map became idle, initializing with style")
                initializeMapWithStyle(currentStyle!!)
                isMapInitializationPending = false
            }
        }
    }
    @SuppressLint("MissingPermission")
    private fun setupMapWithStyle(mapboxMap: MapboxMap, style: Style) {
        locationComponent = mapboxMap.locationComponent
        val locationComponentOptions =
            LocationComponentOptions.builder(this)
                .pulseEnabled(true)
                .build()
        val locationComponentActivationOptions =
            buildLocationComponentActivationOptions(style, locationComponentOptions)
        locationComponent!!.activateLocationComponent(locationComponentActivationOptions)
        locationComponent!!.isLocationComponentEnabled = true
        locationComponent!!.cameraMode = CameraMode.TRACKING
        locationComponent!!.forceLocationUpdate(lastLocation)
        addLongPressHandler()
    }
    private fun addLongPressHandler() {
        mapboxMap.addOnMapLongClickListener { latLng ->
            mapboxMap.addMarker(MarkerOptions().position(latLng))
            markers.add(Point.fromLngLat(latLng.longitude, latLng.latitude))
            markerCount++
            printMarkerCoordinates()
            true
        }
    }
    private fun addMarkersToMap() {
        Log.d("MyDebugTag", "Starting addMarkersToMap()")
        if (currentStyle == null || !currentStyle!!.isFullyLoaded) {
            Log.e("MyDebugTag", "Style not fully loaded")
            return
        }
        val markerIcons = listOf(
            R.drawable.tap_start,
            R.drawable.tap_end
        )
        for ((index) in markers.withIndex()) {
            val iconBitmap = BitmapFactory.decodeResource(resources, markerIcons[index.coerceIn(0, markerIcons.lastIndex)])
            val iconId = "marker-icon-$index"
            if (currentStyle!!.getImage(iconId) == null) {
                Log.d("MyDebugTag", "Adding image $iconId to style")
                currentStyle!!.addImage(iconId, iconBitmap)
            }
            val symbolLayer = SymbolLayer("markers-layer-$index", "markers-source")
                .withProperties(
                    PropertyFactory.iconImage(iconId),
                    PropertyFactory.iconSize(1.0f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconOffset(arrayOf(0.0f, -3.0f))
                )
            Log.d("MyDebugTag", "Adding layer 'markers-layer-$index' to style")
            currentStyle!!.addLayer(symbolLayer)
        }
        val features = markers.map { marker ->
            Feature.fromGeometry(Point.fromLngLat(marker.longitude(), marker.latitude()))
        }
        val markerSource = GeoJsonSource("markers-source", FeatureCollection.fromFeatures(features))
        Log.d("MyDebugTag", "Adding source 'markers-source' to style")
        currentStyle!!.addSource(markerSource)
        Log.d("MyDebugTag", "Finished addMarkersToMap()")
    }
    private fun showPopupMenu() {
        popupLayout.visibility = View.VISIBLE
    }
    private fun printMarkerCoordinates() {
        if (markers.size < 2) {
            Log.d("Coordinates", "Not enough markers to print coordinates")
            return
        }
        val marker1 = markers[0]
        val marker2 = markers[1]
        val longitude1 = marker1.longitude()
        val latitude1 = marker1.latitude()
        val longitude2 = marker2.longitude()
        val latitude2 = marker2.latitude()
        Log.d("Coordinates", "Marker 1 coordinates: $longitude1, $latitude1")
        Log.d("Coordinates", "Marker 2 coordinates: $longitude2, $latitude2")
        sendCoordinatesToServer(marker1, marker2)
    }
    private fun initKtorClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
    }
    private fun sendCoordinatesToServer(start: Point, end: Point) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("Coordinates", "Start coordinates: ${start.longitude()}, ${start.latitude()}")
                Log.d("Coordinates", "End coordinates: ${end.longitude()}, ${end.latitude()}")
                val startCoordinates =
                    withContext(Dispatchers.IO) {
                        URLEncoder.encode("${start.longitude()},${start.latitude()}", "UTF-8")
                    }
                val endCoordinates =
                    withContext(Dispatchers.IO) {
                        URLEncoder.encode("${end.longitude()},${end.latitude()}", "UTF-8")
                    }
                val client = initKtorClient()
                val url = "http://osrmgeo1.partoneclo.ru:5000/route/v1/driving/$startCoordinates;$endCoordinates?steps=true"
                Log.d("Coordinates", "Request URL: $url")
                val json = Json {
                    ignoreUnknownKeys = true
                }
                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                }
                if (response.status.isSuccess()) {
                    val responseBody = response.body<String>()
                    Log.d("Coordinates", "Server response: $responseBody")
                    val encodedRoute = json.decodeFromString<OsrmRoute>(responseBody)
                    val decodedRoute = osrmDecodeRoute(encodedRoute)
                    runOnUiThread {
                        drawPathOnMap(decodedRoute)
                    }
                } else {
                    val errorBody = response.body<String>()
                    Log.e("Coordinates1", "Server error: ${response.status.description} - $errorBody")
                }
            } catch (e: Exception) {
                if (e is IOException) {
                    Log.e("Coordinates", "Network error: ${e.message}")
                } else {
                    val errorBody = e.message ?: "Unknown error"
                    Log.e("Coordinates2", "Server error: $errorBody")
                }
            }
        }
    }
    private fun sendCoordinatesToServerOnTextFields(start: String, end: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("Coordinates", "Start coordinates: $start")
                Log.d("Coordinates", "End coordinates: $end")
                val url = "http://osrmgeo1.partoneclo.ru:5000/route/v1/driving/$start;$end?steps=true"
                Log.d("Coordinates", "Request URL: $url")
                val client = initKtorClient()
                val json = Json {
                    ignoreUnknownKeys = true
                }
                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                }
                if (response.status.isSuccess()) {
                    val responseBody = response.body<String>()
                    Log.d("Coordinates", "Server response: $responseBody")
                    val encodedRoute = json.decodeFromString<OsrmRoute>(responseBody)
                    val decodedRoute = osrmDecodeRoute(encodedRoute)
                    runOnUiThread {
                        drawPathOnMapOnTextFields(decodedRoute)
                        addStartAndEndMarkers(start, end)
                    }
                } else {
                    val errorBody = response.body<String>()
                    Log.e("Coordinates1", "Server error: ${response.status.description} - $errorBody")
                }
            } catch (e: Exception) {
                if (e is IOException) {
                    Log.e("Coordinates", "Network error: ${e.message}")
                } else {
                    val errorBody = e.message ?: "Unknown error"
                    Log.e("Coordinates2", "Server error: $errorBody")
                }
            }
        }
    }
    private fun addStartAndEndMarkers(startCoordinateString: String, endCoordinateString: String) {
        Log.d("addStartAndEndMarkers", "Adding start and end markers")
        Log.d("addStartAndEndMarkers", "Start coordinates: $startCoordinateString")
        Log.d("addStartAndEndMarkers", "End coordinates: $endCoordinateString")
        val startCoordinates = getCoordinatesFromString(startCoordinateString)
        val endCoordinates = getCoordinatesFromString(endCoordinateString)
        if (startCoordinates.size == 2 && endCoordinates.size == 2) {
            try {
                val startLon = startCoordinates[0].toDouble()
                val startLat = startCoordinates[1].toDouble()
                val endLon = endCoordinates[0].toDouble()
                val endLat = endCoordinates[1].toDouble()
                if (isValidCoordinate(startLon, startLat) && isValidCoordinate(endLon, endLat)) {
                    val start = Point.fromLngLat(startLon, startLat)
                    val end = Point.fromLngLat(endLon, endLat)
                    addMarker(start, R.drawable.tap_start_text_field)
                    addMarker(end, R.drawable.tap_end_text_field)
                    val markerSource = GeoJsonSource("markers-source", FeatureCollection.fromFeatures(listOf(
                        Feature.fromGeometry(Point.fromLngLat(start.longitude(), start.latitude())),
                        Feature.fromGeometry(Point.fromLngLat(end.longitude(), end.latitude()))
                    )))
                    currentStyle?.addSource(markerSource)
                } else {
                    Log.e("addStartAndEndMarkers", "Invalid coordinates: $startLon, $startLat, $endLon, $endLat")
                }
            } catch (e: NumberFormatException) {
                Log.e("addStartAndEndMarkers", "Invalid coordinates format: $e")
                Log.e("addStartAndEndMarkers", "Start coordinates: $startCoordinateString")
                Log.e("addStartAndEndMarkers", "End coordinates: $endCoordinateString")
            }
        } else {
            Log.e("addStartAndEndMarkers", "Invalid coordinates format")
            Log.e("addStartAndEndMarkers", "Start coordinates: $startCoordinateString")
            Log.e("addStartAndEndMarkers", "End coordinates: $endCoordinateString")
        }
    }
    private fun getCoordinatesFromString(coordinateString: String): List<String> {
        return when {
            coordinateString.contains(",") -> coordinateString.split(",")
            coordinateString.contains("%2C") -> coordinateString.split("%2C")
            else -> emptyList()
        }
    }
    private fun isValidCoordinate(longitude: Double, latitude: Double): Boolean {
        return longitude in -180.0..180.0 && latitude in -90.0..90.0
    }
    private fun addMarker(point: Point, iconResourceId: Int) {
        val iconDrawable = ContextCompat.getDrawable(this, iconResourceId)
        var iconBitmap: Bitmap? = null
        iconDrawable?.let {
            iconBitmap = Bitmap.createBitmap(it.intrinsicWidth, it.intrinsicHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(iconBitmap!!)
            it.setBounds(0, 0, canvas.width, canvas.height)
            it.draw(canvas)
        }
        mapboxMap.addMarker(
            MarkerOptions()
                .position(LatLng(point.latitude(), point.longitude()))
                .icon(iconBitmap?.let { IconFactory.getInstance(this).fromBitmap(it) })
        )
        Log.d("addStartAndEndMarkers", "Added marker at ${point.latitude()}, ${point.longitude()}")
    }
    private fun drawRouteOnMapTextFields() {
        Log.d("drawRouteOnMapTextFields", "***** START *****")
        Log.d("drawRouteOnMapTextFields", "Drawing route on map")
        val startLatLonStr = editText1.text.toString()
        val endLatLonStr = editText2.text.toString()
        Log.d("drawRouteOnMapTextFields", "Start coordinates: $startLatLonStr")
        Log.d("drawRouteOnMapTextFields", "End coordinates: $endLatLonStr")
        if (startLatLonStr.isNotEmpty() && endLatLonStr.isNotEmpty()) {
            var startLatLon = startLatLonStr.split(",")
            var endLatLon = endLatLonStr.split(",")
            if (startLatLon.size == 1 && endLatLon.size == 1) {
                startLatLon = startLatLonStr.split("%2C")
                endLatLon = endLatLonStr.split("%2C")
            }
            if (startLatLon.size == 2 && endLatLon.size == 2) {
                val startLat = startLatLon[1].trim().toDoubleOrNull() ?: return
                val startLon = startLatLon[0].trim().toDoubleOrNull() ?: return
                val endLat = endLatLon[1].trim().toDoubleOrNull() ?: return
                val endLon = endLatLon[0].trim().toDoubleOrNull() ?: return
                val startCoordinates = "$startLon,$startLat"
                val endCoordinates = "$endLon,$endLat"
                sendCoordinatesToServerOnTextFields(startCoordinates, endCoordinates)
            } else {
                Log.e("drawRouteOnMapTextFields", "Invalid coordinates entered")
                Log.e("drawRouteOnMapTextFields", "Start coordinates: $startLatLonStr")
                Log.e("drawRouteOnMapTextFields", "End coordinates: $endLatLonStr")
            }
        } else {
            Log.e("drawRouteOnMapTextFields", "Coordinates are empty")
        }
        Log.d("drawRouteOnMapTextFields", "***** END *****")
    }
    private fun drawPathOnMapOnTextFields(decodedRoute: FeatureCollection) {
        Log.d("drawPathOnMapOnTextFields", "Drawing path on map")
        val geoJson = decodedRoute
        if (pathSource == null) {
            Log.d("drawPathOnMapOnTextFields", "Creating new path source")
            pathSource = GeoJsonSource("path-source", geoJson)
            mapboxMap.style?.addSource(pathSource!!)
        } else {
            Log.d("drawPathOnMapOnTextFields", "Updating existing path source")
            pathSource?.setGeoJson(geoJson)
        }
        mapboxMap.style?.let { style ->
            Log.d("drawPathOnMapOnTextFields", "Removing old path layer")
            style.removeLayer("path-layer")
            val lineLayer = LineLayer("path-layer", pathSource?.id ?: "path-source").withProperties(
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineColor(Color.BLUE)
            )
            Log.d("drawPathOnMapOnTextFields", "Adding new path layer")
            style.addLayer(lineLayer)
        }
    }
    private fun drawPathOnMap(path: FeatureCollection) {
        val geoJson = path
        if (pathSource == null) {
            pathSource = GeoJsonSource("path-source", geoJson)
            mapboxMap.style?.addSource(pathSource!!)
        } else {
            pathSource?.setGeoJson(geoJson)
        }
        mapboxMap.style?.let { style ->
            style.removeLayer("path-layer")
            val lineLayer = LineLayer("path-layer", pathSource?.id ?: "path-source").withProperties(
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineColor(Color.BLUE)
            )
            style.addLayer(lineLayer)
        }
    }
    private fun resetMapAndMarkers() {
        markers.forEachIndexed { index, marker ->
            val symbolLayer = currentStyle?.getLayer("markers-layer-$index")
            if (symbolLayer != null) {
                currentStyle?.removeLayer(symbolLayer)
            }
            val markerIconId = "marker-icon-$index"
            currentStyle?.removeImage(markerIconId)
        }
        markers.clear()
        mapboxMap.style?.removeLayer("path-layer")
        pathSource?.let {
            mapboxMap.style?.removeSource(it.id)
        }
        mapboxMap.clear()
        pathSource = null
    }
    fun osrmDecodeRoute(encoded: OsrmRoute): FeatureCollection {
        val coordinates = mutableListOf<Point>()
        for (route in encoded.routes) {
            for (leg in route.legs) {
                for (step in leg.steps) {
                    coordinates.addAll(osrmDecode(step.geometry))
                }
            }
        }
        val lineString = LineString.fromLngLats(coordinates)
        val feature = Feature.fromGeometry(lineString)
        return FeatureCollection.fromFeatures(listOf(feature))
    }
    fun osrmDecode(encoded: String): List<Point> {
        val PRECISION = 5
        val len = encoded.length
        val points = mutableListOf<Point>()
        var index = 0
        var lat = 0.0
        var lng = 0.0
        val precision = 10.0.pow(-PRECISION)
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) -(result shr 1) else result shr 1
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) -(result shr 1) else result shr 1
            lng += dlng
            points.add(Point.fromLngLat(lng * precision, lat * precision))
        }
        return points
    }
    private fun checkPermissions() {
        if (PermissionsManager.areLocationPermissionsGranted(this)) {
            mapView.getMapAsync(this)
        } else {
            permissionsManager = PermissionsManager(object : PermissionsListener {
                override fun onExplanationNeeded(permissionsToExplain: List<String>) {
                    Toast.makeText(
                        this@MainActivity,
                        "You need to accept location permissions.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                override fun onPermissionResult(granted: Boolean) {
                    if (granted) {
                        mapView.getMapAsync(this@MainActivity)
                    } else {
                        finish()
                    }
                }
            })
            permissionsManager!!.requestLocationPermissions(this)
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsManager!!.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
    private fun buildLocationComponentActivationOptions(
        style: Style,
        locationComponentOptions: LocationComponentOptions
    ): LocationComponentActivationOptions {
        return LocationComponentActivationOptions
            .builder(this, style)
            .locationComponentOptions(locationComponentOptions)
            .useDefaultLocationEngine(true)
            .locationEngineRequest(
                LocationEngineRequest.Builder(750)
                    .setFastestInterval(750)
                    .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                    .build()
            )
            .build()
    }
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }
    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        pathSource = null
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}