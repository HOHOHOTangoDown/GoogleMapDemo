package com.example.demomap

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.unit.TextUnit

import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.volley.BuildConfig
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.OnMyLocationButtonClickListener
import com.google.android.gms.maps.GoogleMap.OnMyLocationClickListener
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import org.json.JSONObject
import java.util.Objects

class MainActivity : AppCompatActivity(), OnMyLocationButtonClickListener,
    OnMyLocationClickListener, OnMapReadyCallback, GoogleMap.OnMapClickListener {
    companion object {
        const val LOCATION_PERMISSION_REQUEST_CODE = 1001

        fun applyInsets(container: View) {
            ViewCompat.setOnApplyWindowInsetsListener(
                container,
                 { view: View?, insets: WindowInsetsCompat? ->
                    val innerPadding =
                        insets!!.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                    view!!.setPadding(
                        innerPadding.left,
                        innerPadding.top,
                        innerPadding.right,
                        innerPadding.bottom
                    )
                    insets
                }
            )
        }
    }

    private var destinationMarker: Marker? = null
    private var currentRoute: Polyline? = null
    private var startLocation: LatLng? = null
    private var mainRouteStartPostion: LatLng? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isNavigating = false
    private var startTime: Long = 0
    private var totalDistance = 0f
    private var lastLocation: Location? = null
    private var isPermissionDialogShowing = false
    private lateinit var map: GoogleMap
    private var mStartButton: Button? = null

    private var assistRoute: Polyline? = null   // 当前位置到起点的辅助路径
    private val DASH_LENGTH_PX = 20f           // 虚线线段长度
    private val GAP_LENGTH_PX = 10f            // 虚线间隔长度

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.my_location_demo)
        if (checkLocationPermission()) {
            initMap()

            // 初始化定位服务
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            mStartButton = findViewById(R.id.button_start)
            mStartButton?.setOnClickListener{
                startNavigation()
            }

        } else {
            showPermissionDialog()
        }

    }

    private fun initMap() {
        val mapFragment =
            supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        mapFragment?.getMapAsync(this)
        applyInsets(findViewById<View>(R.id.map_container))
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionDialog() {
        if (isPermissionDialogShowing) return

        AlertDialog.Builder(this)
            .setTitle("定位权限请求")
            .setMessage("为了使用导航功能，需要获取您的位置信息")
            .setPositiveButton("确定") { dialog, _ ->
                isPermissionDialogShowing = false
                requestLocationPermission()
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                isPermissionDialogShowing = false
                dialog.dismiss()
                Toast.makeText(this, "没有定位权限将无法使用导航功能", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener { isPermissionDialogShowing = false }
            .show()

        isPermissionDialogShowing = true
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onMyLocationButtonClick(): Boolean {
        return false
    }

    override fun onMyLocationClick(p0: Location) {

    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.setOnMyLocationButtonClickListener(this)
        googleMap.setOnMyLocationClickListener(this)
        googleMap.setOnMapClickListener(this)
        enableMyLocation()

        // 监听 My Location 变化
        map.setOnMyLocationChangeListener { location ->
            startLocation = LatLng(location.latitude, location.longitude)
            updateAssistRouteIfNeeded()
        }
    }

    // 新增：更新辅助路径（当前位置到起点）
    private fun updateAssistRouteIfNeeded() {
        if (isNavigating && destinationMarker != null && startLocation != null && mainRouteStartPostion != null) {
            // 绘制当前位置到起点的虚线（实际为同一点，仅显示当前位置标记）
            drawAssistRoute(listOf(listOf(startLocation!!, LatLng(mainRouteStartPostion!!.latitude,
                mainRouteStartPostion!!.longitude))))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予
                initMap()
            } else {
                // 权限被拒绝
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                ) {
                    // 用户拒绝但未选择"不再询问"，再次显示提示
                    showPermissionDialog()
                } else {
                    // 用户拒绝并选择"不再询问"，引导至设置页面
                    showSettingsDialog()
                }
            }
        }
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要定位权限")
            .setMessage("您已拒绝授予定位权限且选择不再询问，" +
                    "请在设置中手动开启定位权限以使用导航功能")
            .setPositiveButton("设置") { dialog, _ ->
                dialog.dismiss()
                openAppSettings()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "没有定位权限将无法使用导航功能", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.fromParts("package", packageName, null)
        }
        startActivityForResult(intent, LOCATION_PERMISSION_REQUEST_CODE)
    }

    override fun onMapClick(point: LatLng) {
        // 移除之前的目的地标记
        destinationMarker?.remove()

        // 添加新的目的地标记
        destinationMarker = map.addMarker(
            MarkerOptions()
                .position(point)
                .title("destination")
        )
    }

    /**
     * Enables the My Location layer if the fine location permission has been granted.
     */
    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {

        // [START maps_check_location_permission]
        // 1. Check if permissions are granted, if so, enable the my location layer
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            map.isMyLocationEnabled = true
            // 配置地图设置
            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isMyLocationButtonEnabled = true

            // 移动到当前位置
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        val currentLatLng = LatLng(location.latitude, location.longitude)
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                    }
                }
            return
        }

        // 2. If if a permission rationale dialog should be shown
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) || ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        ) {
            showPermissionDialog()
            return
        }

        // 3. Otherwise, request permission
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
        // [END maps_check_location_permission]
    }

    private var currentZoomLevel = 15f // 默认缩放级别
    private val NAVIGATION_ZOOM_LEVEL = 17f // 导航时的缩放级别

    private fun zoomToCurrentLocation() {
        lastLocation?.let { location ->
            val currentLatLng = LatLng(location.latitude, location.longitude)

            // 创建一个相机更新，同时设置位置和缩放级别
            val cameraUpdate = CameraUpdateFactory.newLatLngZoom(currentLatLng, NAVIGATION_ZOOM_LEVEL)

            // 应用相机更新，添加动画效果
            map.animateCamera(cameraUpdate, 1000, object : GoogleMap.CancelableCallback {
                override fun onFinish() {
                    // 动画完成后的回调
                    currentZoomLevel = NAVIGATION_ZOOM_LEVEL
                }

                override fun onCancel() {
                    // 动画取消时的回调
                }
            })
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startNavigation() {
        if (destinationMarker == null) {
            Toast.makeText(this, "请先选择目的地", Toast.LENGTH_SHORT).show()
            return
        }

        isNavigating = true
        startTime = System.currentTimeMillis()
        totalDistance = 0f

        // 获取当前位置并开始导航
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                lastLocation = location

                // 1. 先请求当前位置到起点的辅助路径（通常是同一点，但保留逻辑）
                requestDirections(location, destinationMarker!!.position)

                startLocation?.let {
                    updateAssistRouteIfNeeded()
                }

                // 2. 开始位置更新
                startLocationUpdates()

                // 3. 放大地图到当前位置
                zoomToCurrentLocation()
            }
            }
    }


    // 新增：绘制辅助路径（虚线）
    private fun drawAssistRoute(routes: List<List<LatLng>>) {
        // 移除之前的辅助路径
        assistRoute?.remove()

        if (routes.isNotEmpty()) {
            var lineOptions = PolylineOptions()
            lineOptions.color(Color.GRAY)
            lineOptions.width(6f)
            lineOptions.addAll(routes[0])
            lineOptions.pattern( listOf(
                Dash(DASH_LENGTH_PX),
                Gap(GAP_LENGTH_PX)
            ))

            assistRoute = map.addPolyline(lineOptions)
        }
    }


    private fun requestDirections(origin: Location, destination: LatLng) {
        val url = getUrl(origin.latitude, origin.longitude, destination.latitude, destination.longitude)
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, "获取路线失败: 参数错误", Toast.LENGTH_SHORT).show()
            return
        }
        // 使用Retrofit或Volley发送HTTP请求
        val queue = Volley.newRequestQueue(this)
        val stringRequest = StringRequest(
            Request.Method.GET, url,
            { response ->
                // 解析路线数据
                val result = parseDirectionResponse(response)
                if (result.size > 0 && result[0].isNotEmpty()) {
                    mainRouteStartPostion = result[0][0]
                }

                drawRoute(result)
            },
            { error ->
                Toast.makeText(this, "获取路线失败: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        )

        queue.add(stringRequest)
    }

    private fun parseDirectionResponse(response: String): List<List<LatLng>> {
        val routes = ArrayList<List<LatLng>>()
        val jObject = JSONObject(response)

        if (jObject.getString("status") == "OK") {
            val jRoutes = jObject.getJSONArray("routes")

            for (i in 0 until jRoutes.length()) {
                val jRoute = jRoutes.getJSONObject(i)
                val jLegs = jRoute.getJSONArray("legs")
                val jSteps = jLegs.getJSONObject(0).getJSONArray("steps")

                val path = ArrayList<LatLng>()

                for (j in 0 until jSteps.length()) {
                    val polyline = jSteps.getJSONObject(j).getJSONObject("polyline")
                    val points = polyline.getString("points")
                    path.addAll(decodePolyline(points))
                }

                routes.add(path)
            }
        }

        return routes
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0

            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)

            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0

            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)

            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val p = LatLng(
                lat.toDouble() / 1E5,
                lng.toDouble() / 1E5
            )
            poly.add(p)
        }

        return poly
    }

    private fun drawRoute(routes: List<List<LatLng>>) {
        // 移除之前的路线
        currentRoute?.remove()

        // 绘制新路线
        if (routes.isNotEmpty()) {
            val lineOptions = PolylineOptions()
            lineOptions.color(Color.BLUE)
            lineOptions.width(10f)
            lineOptions.addAll(routes[0])

            currentRoute = map.addPolyline(lineOptions)
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,  // 优先级：高精度
            1000L  // 最小更新间隔（毫秒）
        ).apply {
            setMinUpdateIntervalMillis(500)  // 最快更新间隔
            setMaxUpdateDelayMillis(2000)   // 最大延迟时间
        }.build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { newLocation ->
                    // 更新总距离
                    if (lastLocation != null) {
                        totalDistance += lastLocation!!.distanceTo(newLocation)
                    }
                    lastLocation = newLocation

                    // 更新地图上的位置
                    updateMapLocation(newLocation)

                    // 检查是否到达目的地
                    if (isNearDestination(newLocation)) {
                        stopNavigation()
                        showTripSummary()
                    }
                }
            }
        }

        if (checkLocationPermission()) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    // 在位置更新时应用 3D 视角
    private fun updateMapLocation(location: Location) {
        val currentLatLng = LatLng(location.latitude, location.longitude)

        // 创建带倾斜和方向的相机位置
        val cameraPosition = CameraPosition.Builder()
            .target(currentLatLng)           // 目标位置
            .zoom(17f)                     // 缩放级别
            .tilt(60f)                     // 倾斜角度（0-90度）
            .bearing(location.bearing)     // 设备朝向（方向角）
            .build()

        // 应用相机位置，添加平滑动画
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(cameraPosition),
            1000,
            null
        )
    }

    private fun isNearDestination(location: Location): Boolean {
        destinationMarker?.let { marker ->
            val destinationLatLng = marker.position
            val destinationLocation = Location("").apply {
                latitude = destinationLatLng.latitude
                longitude = destinationLatLng.longitude
            }
            return location.distanceTo(destinationLocation) < 50 // 假设50米内为到达
        }
        return false
    }

    private fun stopNavigation() {
        isNavigating = false
        // 停止位置更新
        fusedLocationClient.removeLocationUpdates(object : LocationCallback() {})
    }

    private fun showTripSummary() {
        // 计算行程时间
        val elapsedTime = System.currentTimeMillis() - startTime
        val timeString = formatTime(elapsedTime)

        // 创建并显示行程摘要对话框
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_trip_summary)

        // 设置时间和距离
        dialog.findViewById<TextView>(R.id.trip_time).text = "行程时间: $timeString"
        dialog.findViewById<TextView>(R.id.trip_distance).text =
            "总距离: ${String.format("%.2f", totalDistance/1000)} 公里"

        // 在摘要对话框中显示路线地图
        // 可以使用MapView或重新绘制路线

        dialog.show()
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}小时${minutes % 60}分${seconds % 60}秒"
            minutes > 0 -> "${minutes}分${seconds % 60}秒"
            else -> "${seconds}秒"
        }
    }

    private fun getUrl(originLat: Double, originLng: Double, destLat: Double, destLng: Double): String {
        val strOrigin = "origin=$originLat,$originLng"
        val strDest = "destination=$destLat,$destLng"
        val mode = "mode=driving"
        val parameters = "$strOrigin&$strDest&$mode"
        val output = "json"
        val appInfo =
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val bundle = Objects.requireNonNull(appInfo.metaData)

        val apiKey =
            bundle.getString("com.google.android.geo.API_KEY") // Key name is important!

        if (apiKey == null || apiKey.isBlank() || apiKey == "DEFAULT_API_KEY") {
            Toast.makeText(
                this,
                "API Key was not set in secrets.properties",
                Toast.LENGTH_LONG
            ).show()
            return ""
        }

        return "https://maps.googleapis.com/maps/api/directions/$output?$parameters&key=${apiKey}"
    }


}