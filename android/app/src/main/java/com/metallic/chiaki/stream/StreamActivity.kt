// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Matrix
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.get
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.DualSenseDriver
import com.metallic.chiaki.lib.RumbleEvent
import com.metallic.chiaki.lib.TriggerEffectsEvent
import com.metallic.chiaki.fsr.FsrVideoProcessor
import com.metallic.chiaki.fsr.NisVideoProcessor
import com.metallic.chiaki.fsr.VideoProcessingGLSurfaceView
import com.metallic.chiaki.lib.VideoUpscaler
import com.metallic.chiaki.session.StreamState
import com.metallic.chiaki.session.StreamStateConnected
import com.metallic.chiaki.session.StreamStateConnecting
import com.metallic.chiaki.session.StreamStateCreateError
import com.metallic.chiaki.session.StreamStateLoginPinRequest
import com.metallic.chiaki.session.StreamStateQuit
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import kotlin.math.min


private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity(), View.OnSystemUiVisibilityChangeListener
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
		private const val ACTION_USB_PERMISSION = "com.metallic.chiaki.USB_PERMISSION"
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding

	private val uiVisibilityHandler = Handler()

	private var dualSenseDriver: DualSenseDriver? = null

	// Upscaler
	private var upscalerView: VideoProcessingGLSurfaceView? = null
	private var fsrProcessor: FsrVideoProcessor? = null
	private var nisProcessor: NisVideoProcessor? = null
	private var upscalerType: VideoUpscaler = VideoUpscaler.OFF

	private val usbReceiver = object : BroadcastReceiver() {
		override fun onReceive(ctx: Context, intent: Intent) {
			if (ACTION_USB_PERMISSION == intent.action) {
				synchronized(this) {
					if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						initDualSense()
					}
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)

		val connectInfo = intent.getParcelableExtra<ConnectInfo>(EXTRA_CONNECT_INFO)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		window.decorView.setOnSystemUiVisibilityChangeListener(this)

		//填充刘海区域
		if(Preferences(this).screenCutoutModeEnabled){
			// Allow the activity to layout under notches if the fill-screen option
			// was turned on by the user or it's a full-screen native resolution
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				window.attributes.layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				window.attributes.layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
			}
		}

		if(Preferences(this).screenPortraitEnabled){
			requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
			var params=binding.aspectRatioLayout.layoutParams as FrameLayout.LayoutParams
			params.gravity=Gravity.TOP and Gravity.CENTER_HORIZONTAL
			binding.aspectRatioLayout.layoutParams=params
		}else{
			// For regular displays, we always request landscape
			requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
		}

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		//显示虚拟控制器
		if (Preferences(this).onScreenControlsEnabled) {
			viewModel.setOnScreenControlsEnabled(true)
			//是否仅显示触控板
			viewModel.setTouchpadOnlyEnabled(Preferences(this).touchpadOnlyEnabled)
		}

		//viewModel.session.attachToTextureView(textureView)
		setupUpscaler()
		if (upscalerView != null) {
			// Insert upscaler GL view into layout
			val params = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT)
			binding.mainStreamLayout.addView(upscalerView, 0, params)
			upscalerView?.setDesiredAspectRatio(
				viewModel.session.connectInfo.videoProfile.width.toDouble() /
				viewModel.session.connectInfo.videoProfile.height.toDouble())
		} else {
			viewModel.session.attachToSurfaceView(binding.surfaceView)
		}

		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		adjustStreamViewAspect()

		// Register USB permission receiver for DS5Dongle
		registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))

		// Wire DualSense feedback callbacks (bypass LiveData for lower latency)
		viewModel.session.rumbleCallback = { event ->
			dualSenseDriver?.sendRumble(event.left, event.right)
		}
		viewModel.session.triggerEffectsCallback = { event ->
			dualSenseDriver?.sendTriggerEffects(
				event.typeLeft, event.dataLeft,
				event.typeRight, event.dataRight)
		}

		// Try to init DS5Dongle if plugged in
		initDualSense()

		var vibrator:Vibrator ?=null

		//震动反馈 (phone vibrator fallback when DS5Dongle not connected)
		if(Preferences(this).rumbleEnabled)
		{
			viewModel.session.rumbleState.observe(this, Observer {
				// If DualSenseDriver is active, skip phone vibrator - it handles rumble directly
				if (dualSenseDriver?.active == true)
					return@Observer

				//手柄震动马达
				if(Preferences(this).rumbleGamePadEnabled){
					val deviceIds = InputDevice.getDeviceIds()
					deviceIds.forEach { deviceId ->
						InputDevice.getDevice(deviceId)?.apply {
							val hasJoyMotion = getMotionRange(MotionEvent.AXIS_X) != null && getMotionRange(MotionEvent.AXIS_Y) != null
							if (hasJoyMotion&&(sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
										sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)) {
								if(this.vibrator.hasVibrator()){
									vibrator=this.vibrator
									return@forEach
								}
							}
						}
					}
				}
				if(vibrator==null){
					vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
				}
				val simulatedAmplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator?.cancel()
				if(simulatedAmplitude == 0)
					return@Observer

				val pwmPeriod: Long = 20
				val onTime = (simulatedAmplitude / 255.0 * pwmPeriod).toLong()
				val offTime = pwmPeriod - onTime
//				Log.i("axixiLogY", "onTime->"+onTime)
//				Log.i("axixiLogY", "offTime->"+offTime)
//				if(it.type.toInt().toUInt() ==1U){
//					Log.i("axixiLogY", "自适应扳机")
//					if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
//						vibrator!!.vibrate(VibrationEffect.createOneShot(onTime, simulatedAmplitude))
//					else
//						vibrator!!.vibrate(onTime)
//
//					return@Observer
//				}
//				Log.i("axixiLogY", "触觉反馈")
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					val vibrationAttributes = VibrationAttributes.Builder()
//						.setUsage(VibrationAttributes.USAGE_MEDIA)
						.setUsage(VibrationAttributes.USAGE_TOUCH)
						.build()
					vibrator!!.vibrate(
						VibrationEffect.createWaveform(
							longArrayOf(
								0,
								onTime,
								offTime
							), -1
						), vibrationAttributes
					)
				} else {
					val audioAttributes = AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_GAME)
						.build()
					vibrator!!.vibrate(longArrayOf(0, onTime, offTime), -1, audioAttributes)
				}
			})
		}
	}

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			fragment.controllerStateCallback = { state ->
				viewModel.input.touchControllerState = state
			}
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		viewModel.session.resume()
	}

	override fun onPause()
	{
		super.onPause()
		viewModel.session.pause()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		dualSenseDriver?.stop()
		fsrProcessor?.release()
		nisProcessor?.release()
		try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		viewModel.session.resume()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	override fun onSystemUiVisibilityChange(visibility: Int)
	{
		//此时系统栏是显示的
		if(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0)
			showOverlay()
		else
			hideOverlay()
	}

	private fun showOverlay()
	{
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
				or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				or View.SYSTEM_UI_FLAG_FULLSCREEN)
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private var lastNetDataNum: Long = 0
	private fun stateChanged(state: StreamState)
	{
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE

		when(state)
		{
			is StreamStateConnected ->{
				if(Preferences(this).screenPortraitEnabled){
					val view=binding.mainStreamLayout[2]
					val params= view.layoutParams as FrameLayout.LayoutParams
					val marginTop=window.decorView.height-binding.surfaceView.height
					params.topMargin= min(marginTop,window.decorView.height/2)
//					Log.i("surfaceView.height",params.topMargin.toString())
					view.layoutParams=params
				}
			}
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reasonString?.contains("AxiDecoded|") == true){
						if(!binding.fpsText.isVisible){
							return
						}
						val datas =state.reasonString.split("|")
						val sb = StringBuilder()
						if (TrafficStatsHelper.getPackageRxBytes(android.os.Process.myUid()).toInt() !== TrafficStats.UNSUPPORTED) {
							val netData: Long =
								TrafficStatsHelper.getPackageRxBytes(android.os.Process.myUid()) + TrafficStatsHelper.getPackageTxBytes(
									android.os.Process.myUid()
								)
							if (lastNetDataNum != 0L) {
								sb.append("带宽：")
								val realtimeNetData: Float = (netData - lastNetDataNum) / 1024f
								if (realtimeNetData >= 1000) {
									sb.append(
										String.format(
											"%.2f",
											realtimeNetData / 1024f
										) + "M/s"
									)
								} else {
									sb.append(String.format("%.2f", realtimeNetData) + "K/s")
								}
							}
							lastNetDataNum = netData
						}
						sb.append("\t ")
						sb.append(if (viewModel.session.connectInfo.ps5) "PS5" else "PS4")
						sb.append("\t ")
						sb.append(viewModel.session.connectInfo.videoProfile.width)
						sb.append("x")
						sb.append(viewModel.session.connectInfo.videoProfile.height)
						sb.append(if (viewModel.session.connectInfo.videoProfile.codec==Codec.CODEC_H265_HDR) " HDR" else "")
						sb.append("\t 解码："+datas[1])
						sb.append("\t FPS："+datas[2])
						binding.fpsText.text=sb.toString()
						return
					}

					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}

			else -> {}
		}
	}

	override fun onBackPressed() {
		val items = arrayOf("退出串流", "切换控件图层", "切换性能图层","取消")
		val builder =  MaterialAlertDialogBuilder(this)
			builder.setTitle("操作菜单")  // 设置对话框标题
			.setItems(items) { dialog, which ->
				// 用户选择的选项索引（which）
				dialog.dismiss()
				if(which==0){
					super.onBackPressed()
					return@setItems
				}
				if(which==1){
					if(binding.overlay.isVisible){
						hideOverlay()
					}else{
						showOverlay()
					}
					return@setItems
				}
				if(which==2){
					if(binding.fpsText.isVisible){
						binding.fpsText.visibility=View.GONE
					}else{
						binding.fpsText.visibility=View.VISIBLE
					}
					return@setItems
				}
			}
		builder.create().show()

	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		return viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	}
	override fun onGenericMotionEvent(event: MotionEvent) =
		viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

	private fun setupUpscaler() {
		val prefs = Preferences(this)
		val typeKey = prefs.upscaler
		upscalerType = VideoUpscaler.fromKey(typeKey)

		if (upscalerType == VideoUpscaler.OFF) return

		val processor: VideoProcessingGLSurfaceView.VideoProcessor = when (upscalerType) {
			VideoUpscaler.FSR1 -> {
				val fp = FsrVideoProcessor(this)
				fp.setFsrEnabled(true)
				fp.setSharpness(prefs.upscalerSharpness / 100.0f * 2.0f) // 0-100 → 0-2
				fsrProcessor = fp
				fp
			}
			VideoUpscaler.NIS -> {
				val np = NisVideoProcessor(this)
				np.setEnabled(true)
				np.setSharpness(prefs.upscalerSharpness / 100.0f)
				nisProcessor = np
				np
			}
			VideoUpscaler.OFF -> return
		}

		upscalerView = VideoProcessingGLSurfaceView(this, false, false, processor,
			object : VideoProcessingGLSurfaceView.SurfaceListener {
				override fun onInputSurfaceAvailable(surfaceTexture: android.graphics.SurfaceTexture) {
					viewModel.session.session?.setSurface(android.view.Surface(surfaceTexture))
				}
				override fun onInputSurfaceDestroyed() {
					viewModel.session.session?.setSurface(null)
				}
			})
	}

	private fun initDualSense() {
		val driver = DualSenseDriver(
			context = this,
			onInputChanged = { state ->
				viewModel.session.session?.setControllerState(state)
			},
			onDeviceStatusChanged = { connected ->
				if (connected) {
					viewModel.input.externalControllerActive = true
					viewModel.setOnScreenControlsEnabled(false)
				} else {
					viewModel.input.externalControllerActive = false
				}
			}
		)

		val dev = driver.findDevice()
		if (dev != null) {
			val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
			if (usbManager.hasPermission(dev)) {
				if (driver.start(dev)) {
					dualSenseDriver = driver
				}
			} else {
				val pi = PendingIntent.getBroadcast(
					this, 0, Intent(ACTION_USB_PERMISSION),
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
					else PendingIntent.FLAG_UPDATE_CURRENT
				)
				usbManager.requestPermission(dev, pi)
			}
		}
	}
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
