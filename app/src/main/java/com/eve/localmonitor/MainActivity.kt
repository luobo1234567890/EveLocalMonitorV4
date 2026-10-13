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
    private var strength = 90
    private var selectedSound: android.net.Uri? = null
    private var cameraInstance: Camera? = null // 新增：用来保存相机实例以便控制缩放
    private val exec = Executors.newSingleThreadExecutor()

    override fun onCreate(b: Bundle?){
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        roi = findViewById(R.id.roi)

        findViewById<SeekBar>(R.id.strength).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean){ strength = p }
            override fun onStartTrackingTouch(s: SeekBar?){}
            override fun onStopTrackingTouch(s: SeekBar?){}
        })

        // 修复 1：使用保存的 cameraInstance 来代替预览控件本身获取 cameraControl
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
            
            // 修复 2：将绑定的相机实例赋值给全局变量 cameraInstance
            cameraInstance = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, a)
            
            findViewById<TextView>(R.id.status).text = "状态：监控中"
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detect(im: ImageProxy): Boolean{
        val buf = im.planes[0].buffer
        val data = ByteArray(buf.remaining())
        buf.get(data)
        val w = im.width
        val h = im.height
        val l = (roi.roi.left * w).toInt().coerceIn(0, w - 1)
        val r = (roi.roi.right * w).toInt().coerceIn(l + 1, w)
        val t = (roi.roi.top * h).toInt().coerceIn(0, h - 1)
        val b = (roi.roi.bottom * h).toInt().coerceIn(t + 1, h)
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
                if(mx < 40 || mx - mn < 25) continue
                val hue = when(mx){
                    rr -> 60f * (g - bb) / (mx - mn)
                    g -> 60f * (2f + (bb - rr) / (mx - mn))
                    else -> 60f * (4f + (rr - g) / (mx - mn))
                }
                val hh = (hue + 360) % 360
                val red = (hh < 25 || hh > 335) && rr > g * 1.15 && rr > bb * 1.15
                val orange = (hh in 15f..45f) && rr > g * 1.1
                val yellow = (hh in 40f..75f) && rr > bb * 1.2
                val green = (hh in 75f..165f) && g > rr * 1.15
                val cyan = (hh in 165f..205f) && g > rr * 1.1 && bb > rr * 1.1
                val blue = (hh in 205f..260f) && bb > rr * 1.15
                val pink = (hh in 315f..345f) && rr > g * 1.2 && bb > g * 1.05
                if(red || orange || yellow || green || cyan || blue || pink) hit++
                total++
            }
        }
        return total > 0 && hit.toFloat() / total >= (0.0005f + (100 - strength) / 10000f)
    }

    private fun update(hit: Boolean){
        if(hit && !detected){
            detected = true
            forced = false
            findViewById<TextView>(R.id.status).text = "状态：发现目标！报警中"
            if(!forced) startAlarm()
            if(findViewById<CheckBox>(R.id.vibrate).isChecked){
                (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(500, Vibrator.DEFAULT_AMPLITUDE))
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
