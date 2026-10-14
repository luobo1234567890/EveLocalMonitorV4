package com.eve.localmonitor

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
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
    private var alarm: MediaPlayer? = null
    private var detected = false
    private var forced = false
    private var selectedSound: android.net.Uri? = null
    private var cameraInstance: Camera? = null
    private val exec = Executors.newSingleThreadExecutor()

    override fun onCreate(b: Bundle?){
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        roi = findViewById(R.id.roi)

        // 变焦控制
        findViewById<SeekBar>(R.id.zoom).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean){
                cameraInstance?.cameraControl?.setLinearZoom(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar?){}
            override fun onStopTrackingTouch(s: SeekBar?){}
        })

        findViewById<Button>(R.id.start).setOnClickListener{ forced = false; startCamera() }
        findViewById<Button>(R.id.stopAlarm).setOnClickListener{ forced = true; stopAlarm() }
        findViewById<Button>(R.id.sound).setOnClickListener{
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply{ type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(i, 88)
        }

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7)
        }
    }

    override fun onActivityResult(r: Int, c: Int, d: Intent?){
        super.onActivityResult(r, c, d)
        if(r == 88 && c == RESULT_OK) selectedSound = d?.data
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
            findViewById<TextView>(R.id.status).text = "状态：监控中 (竖屏)"
        }, ContextCompat.getMainExecutor(this))
    }

    // 修复后的竖屏颜色检测与坐标对齐逻辑
    private fun detect(im: ImageProxy): Boolean{
        val buf = im.planes[0].buffer
        val data = ByteArray(buf.remaining())
        buf.get(data)
        val w = im.width
        val h = im.height
        
        // 核心修复：将竖屏 UI 上的红框相对坐标，精准映射到相机的实际像素坐标系中
// 修正水平方向偏移（将检测范围向左平移对齐红框）
        val l = ((1f - roi.roi.bottom) * w).toInt().coerceIn(0, w - 1)
        val r = ((1f - roi.roi.top) * w).toInt().coerceIn(l + 1, w)
        val t = ((1f - roi.roi.right) * h).toInt().coerceIn(0, h - 1)
        val b = ((1f - roi.roi.left) * h).toInt().coerceIn(t + 1, h)

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
                // 固定检测红、橙红、粉红等相近颜色
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
            
            // 震动触发
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

    override fun onDestroy(){
        super.onDestroy()
        stopAlarm()
        exec.shutdown()
    }
}
