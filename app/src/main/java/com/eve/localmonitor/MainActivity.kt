package com.eve.localmonitor

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity: ComponentActivity(){
    private lateinit var preview: PreviewView
    private lateinit var roi: RoiOverlay
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var btnLock: Button
    private var alarm: MediaPlayer? = null
    private var detected = false
    private var forced = false
    private var selectedSound: Uri? = null
    private var cameraInstance: Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var isLocked = false

    private val PREFS_NAME = "EveMonitorPrefs"

    override fun onCreate(b: Bundle?){
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        roi = findViewById(R.id.roi)
        zoomSeekBar = findViewById(R.id.zoom)
        btnLock = findViewById(R.id.btnLock)

        // 加载上次保存的配置
        loadPreferences()

        // 变焦控制
        zoomSeekBar.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean){
                if (!isLocked) {
                    cameraInstance?.cameraControl?.setLinearZoom(p / 100f)
                    savePreferences()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?){}
            override fun onStopTrackingTouch(s: SeekBar?){}
        })

        // 锁定 / 解除固定按钮
        btnLock.setOnClickListener {
            isLocked = !isLocked
            updateLockState()
            savePreferences()
        }

        findViewById<Button>(R.id.start).setOnClickListener{ forced = false; startCamera() }
        findViewById<Button>(R.id.stopAlarm).setOnClickListener{ forced = true; stopAlarm() }
        findViewById<Button>(R.id.sound).setOnClickListener{
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply{ 
                type = "audio/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(i, 88)
        }

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    private fun updateLockState() {
        // 同步给 RoiOverlay 禁止触摸
        roi.isLocked = isLocked
        roi.invalidate()

        if (isLocked) {
            btnLock.text = "解除固定"
            zoomSeekBar.isEnabled = false
            Toast.makeText(this, "已锁定红框和焦距", Toast.LENGTH_SHORT).show()
        } else {
            btnLock.text = "锁定红框焦距"
            zoomSeekBar.isEnabled = true
            Toast.makeText(this, "已解除固定，可调整", Toast.LENGTH_SHORT).show()
        }
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("isLocked", isLocked)
            putInt("zoomProgress", zoomSeekBar.progress)
            putString("selectedSoundUri", selectedSound?.toString())
            putFloat("roiLeft", roi.roi.left)
            putFloat("roiTop", roi.roi.top)
            putFloat("roiRight", roi.roi.right)
            putFloat("roiBottom", roi.roi.bottom)
            apply()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isLocked = prefs.getBoolean("isLocked", false)
        val zoomProgress = prefs.getInt("zoomProgress", 0)
        zoomSeekBar.progress = zoomProgress

        // 恢复声音 Uri
        val soundUriStr = prefs.getString("selectedSoundUri", null)
        if (soundUriStr != null) {
            try {
                selectedSound = Uri.parse(soundUriStr)
            } catch (_: Exception) {}
        }

        // 恢复红框位置
        val left = prefs.getFloat("roiLeft", -1f)
        if (left != -1f) {
            roi.roi.left = left
            roi.roi.top = prefs.getFloat("roiTop", 0.15f)
            roi.roi.right = prefs.getFloat("roiRight", 0.85f)
            roi.roi.bottom = prefs.getFloat("roiBottom", 0.85f)
            roi.invalidate()
        }

        updateLockState()
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?){
        super.onActivityResult(r, c, d)
        if(r == 88 && c == RESULT_OK && d != null) {
            val uri = d.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                selectedSound = uri
                savePreferences()
                Toast.makeText(this, "自定义报警音已保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCamera(){
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            val pv = Preview.Builder().build().also{ it.setSurfaceProvider(preview.surfaceProvider) }
            val a = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            a.setAnalyzer(exec){ img ->
                val hit = detect(img)
                img.close()
                runOnUiThread{ update(hit) }
            }
            p.unbindAll()
            cameraInstance = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, a)
            
            cameraInstance?.cameraControl?.setLinearZoom(zoomSeekBar.progress / 100f)
            
            findViewById<TextView>(R.id.status).text = "状态：监控中 (竖屏)"
        }, ContextCompat.getMainExecutor(this))
    }

    // 适配竖屏旋转角度的精准颜色检测与坐标映射
    private fun detect(im: ImageProxy): Boolean{
        val buf = im.planes[0].buffer
        val data = ByteArray(buf.remaining())
        buf.get(data)
        val w = im.width
        val h = im.height
        
        val rotation = im.imageInfo.rotationDegrees

        val l: Int
        val r: Int
        val t: Int
        val b: Int

        if (rotation == 90) {
            l = (roi.roi.top * w).toInt().coerceIn(0, w - 1)
            r = (roi.roi.bottom * w).toInt().coerceIn(l + 1, w)
            t = ((1f - roi.roi.right) * h).toInt().coerceIn(0, h - 1)
            b = ((1f - roi.roi.left) * h).toInt().coerceIn(t + 1, h)
        } else if (rotation == 270) {
            l = ((1f - roi.roi.bottom) * w).toInt().coerceIn(0, w - 1)
            r = ((1f - roi.roi.top) * w).toInt().coerceIn(l + 1, w)
            t = (roi.roi.left * h).toInt().coerceIn(0, h - 1)
            b = (roi.roi.right * h).toInt().coerceIn(t + 1, h)
        } else {
            l = (roi.roi.left * w).toInt().coerceIn(0, w - 1)
            r = (roi.roi.right * w).toInt().coerceIn(l + 1, w)
            t = (roi.roi.top * h).toInt().coerceIn(0, h - 1)
            b = (roi.roi.bottom * h).toInt().coerceIn(t + 1, h)
        }

        var hit = 0
        var total = 0
        val step = 2
        for(y in t until b step step){
            for(x in l until r step step){
                val i = (y * w + x) * 4
                if(i + 3 >= data.size) continue
                val rr = data[i].toInt() and 255
                val g = data[i+1].toInt() and 255
                val bb = data[i+2].toInt() and 255
                val mx = max(rr, max(g, bb))
                val mn = min(rr, min(g, bb))
                if(mx < 40 || mx - mn < 20) continue
                val hue = when(mx){
                    rr -> 60f * (g - bb) / (mx - mn)
                    g -> 60f * (2f + (bb - rr) / (mx - mn))
                    else -> 60f * (4f + (rr - g) / (mx - mn))
                }
                val hh = (hue + 360) % 360
                val isRedOrSimilar = (hh < 30 || hh > 330) && rr > g && rr > bb
                if(isRedOrSimilar) hit++
                total++
            }
        }
        return total > 0 && hit.toFloat() / total >= 0.001f
    }

    private fun update(hit: Boolean){
        if(hit && !detected){
            detected = true
            forced = false
            findViewById<TextView>(R.id.status).text = "状态：发现目标！报警中"
            if(!forced) startAlarm()
            
            val vibrateCb = findViewById<CheckBox>(R.id.vibrate)
            if(vibrateCb != null && vibrateCb.isChecked){
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(500)
                }
            }
        }else if(!hit && detected){
            detected = false
            forced = false
            stopAlarm()
            findViewById<TextView>(R.id.status).text = "状态：目标消失"
        }
    }

    private fun startAlarm(){
        if(alarm != null) return
        alarm = MediaPlayer().apply{
            try{
                if(selectedSound != null) setDataSource(this@MainActivity, selectedSound!!)
            }catch(_: Exception){}
            if(selectedSound == null) setDataSource(this@MainActivity, Settings.System.DEFAULT_ALARM_ALERT_URI)
            setAudioStreamType(AudioManager.STREAM_ALARM)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun stopAlarm(){
        alarm?.stop()
        alarm?.release()
        alarm = null
    }

    override fun onPause() {
        super.onPause()
        savePreferences()
    }

    override fun onDestroy(){
        super.onDestroy()
        stopAlarm()
        exec.shutdown()
    }
}
