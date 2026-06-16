package com.metallic.chiaki.session

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ControllerState

class StreamInput(val context: Context, val preferences: Preferences)
{
	var controllerStateChangedCallback: ((ControllerState) -> Unit)? = null

	// When true, external controller (DualSenseDriver) is providing input.
	// Skip phone sensors and virtual controller to avoid conflicts.
	var externalControllerActive: Boolean = false

	val controllerState: ControllerState get()
	{
		val controllerState = sensorControllerState or keyControllerState or motionControllerState


		if(!preferences.motionGamePadEnabled){
			val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
			@Suppress("DEPRECATION")
			when(windowManager.defaultDisplay.rotation)
			{
				Surface.ROTATION_90 -> {
					controllerState.accelX *= -1.0f
					controllerState.accelZ *= -1.0f
					controllerState.gyroX *= -1.0f
					controllerState.gyroZ *= -1.0f
					controllerState.orientX *= -1.0f
					controllerState.orientZ *= -1.0f
				}
				else -> {}
			}
		}
		// prioritize motion controller's l2 and r2 over key
		// (some controllers send only key, others both but key earlier than full press)
		if(motionControllerState.l2State > 0U)
			controllerState.l2State = motionControllerState.l2State
		if(motionControllerState.r2State > 0U)
			controllerState.r2State = motionControllerState.r2State

//		Log.i("axixi-sensor","accel:"+controllerState.accelX+","+controllerState.accelY+","+controllerState.accelZ
//				+" | gyro:"+controllerState.gyroX+","+controllerState.gyroY+","+controllerState.gyroZ
//		+ " | orient:"+controllerState.orientX+","+controllerState.orientY+","+controllerState.orientZ)

		return controllerState or touchControllerState
	}

	private val sensorControllerState = ControllerState() // from Motion Sensors
	private val keyControllerState = ControllerState() // from KeyEvents
	private val motionControllerState = ControllerState() // from MotionEvents
	var touchControllerState = ControllerState()
		set(value)
		{
			field = value
			controllerStateUpdated()
		}

	private val swapCrossMoon = preferences.swapCrossMoon
	private var gravity = FloatArray(3)
	private var gyroscopeData = FloatArray(3)
	private var rotationMatrix = FloatArray(9)
	private val orientationValues = FloatArray(3)

	private val sensorEventListener = object: SensorEventListener {
		override fun onSensorChanged(event: SensorEvent)
		{
			when(event.sensor.type)
			{
				Sensor.TYPE_ACCELEROMETER -> {
					if(preferences.motionGamePadEnabled){
						sensorControllerState.accelX = event.values[0] / SensorManager.GRAVITY_EARTH
						sensorControllerState.accelY = event.values[1] / SensorManager.GRAVITY_EARTH
						sensorControllerState.accelZ = event.values[2] / SensorManager.GRAVITY_EARTH
						val alpha = 0.8f
						gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
						gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
						gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
					}else{
						sensorControllerState.accelX = event.values[1] / SensorManager.GRAVITY_EARTH
						sensorControllerState.accelY = event.values[2] / SensorManager.GRAVITY_EARTH
						sensorControllerState.accelZ = event.values[0] / SensorManager.GRAVITY_EARTH
					}
				}
				Sensor.TYPE_GYROSCOPE -> {
					if(preferences.motionGamePadEnabled){
						sensorControllerState.gyroX = event.values[0]
						sensorControllerState.gyroY = event.values[1]
						sensorControllerState.gyroZ = event.values[2]
						gyroscopeData=event.values.clone()
					}else{
						sensorControllerState.gyroX = event.values[1]
						sensorControllerState.gyroY = event.values[2]
						sensorControllerState.gyroZ = event.values[0]
					}
				}
				Sensor.TYPE_ROTATION_VECTOR,Sensor.TYPE_GAME_ROTATION_VECTOR -> {
					val q = floatArrayOf(0f, 0f, 0f, 0f)
					SensorManager.getQuaternionFromVector(q, event.values)
					sensorControllerState.orientX = q[2]
					sensorControllerState.orientY = q[3]
					sensorControllerState.orientZ = q[1]
					sensorControllerState.orientW = q[0]
				}
				else -> return
			}
			// 当加速度计数据不为空时，更新旋转矩阵
			if (preferences.motionGamePadEnabled) {
				// 计算旋转矩阵
				SensorManager.getRotationMatrix(rotationMatrix, null, gravity, gyroscopeData)
				SensorManager.getOrientation(rotationMatrix, orientationValues)
				val q = floatArrayOf(0f, 0f, 0f, 0f)
				// 将旋转矩阵转换为四元数
				rotationMatrixToQuaternion(rotationMatrix, q)
				sensorControllerState.orientX = q[1]
				sensorControllerState.orientY = q[2]
				sensorControllerState.orientZ = q[3]
				sensorControllerState.orientW = q[0]
				Log.i("axixi-sensor","orient:"+controllerState.orientX+","+controllerState.orientY+","+controllerState.orientZ+","+controllerState.orientW)
			}

			controllerStateUpdated()
		}

		override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
	}


	// Function to convert rotation matrix to quaternion
	private fun rotationMatrixToQuaternion(matrix: FloatArray, quaternion: FloatArray) {
		if (matrix.size == 9) {
			quaternion[0] = Math.sqrt((1.0 + matrix[0] + matrix[4] + matrix[8])).toFloat() / 2
			val w4 = 4.0 * quaternion[0]
			quaternion[1] = ((matrix[7] - matrix[5]) / w4).toFloat()
			quaternion[2] = ((matrix[2] - matrix[6]) / w4).toFloat()
			quaternion[3] = ((matrix[3] - matrix[1]) / w4).toFloat()
		} else if (matrix.size == 16) {
			quaternion[0] = Math.sqrt((1.0 + matrix[0] + matrix[5] + matrix[10])).toFloat() / 2
			val w4 = 4.0 * quaternion[0]
			quaternion[1] = ((matrix[9] - matrix[6]) / w4).toFloat()
			quaternion[2] = ((matrix[2] - matrix[8]) / w4).toFloat()
			quaternion[3] = ((matrix[4] - matrix[1]) / w4).toFloat()
		}
	}


	private lateinit var sensorManagerA :SensorManager

	private val motionLifecycleObserver = object: LifecycleObserver {
		@OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
		fun onResume()
		{
			sensorManagerA = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

			if(preferences.motionGamePadEnabled){
				val deviceIds = InputDevice.getDeviceIds()
				deviceIds.forEach { deviceId ->
					InputDevice.getDevice(deviceId)?.apply {
						val hasJoyMotion = getMotionRange(MotionEvent.AXIS_X) != null && getMotionRange(MotionEvent.AXIS_Y) != null
						if (hasJoyMotion&&sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) {
							//android 12
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
								if(this.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!=null
									&&this.sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null){
									sensorManagerA=this.sensorManager
								}
							}
							return@forEach
						}
					}
				}
			}
			val samplingPeriodUs = 4000

			listOfNotNull(
				sensorManagerA.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
				sensorManagerA.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
				if(preferences.motionGameVectorEnabled){
					sensorManagerA.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
				} else {
					sensorManagerA.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
				}
			).forEach {
				sensorManagerA.registerListener(sensorEventListener, it, samplingPeriodUs)
			}
		}

		@OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
		fun onPause()
		{
//			val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
			sensorManagerA.unregisterListener(sensorEventListener)
		}
	}

	fun observe(lifecycleOwner: LifecycleOwner)
	{
		if(preferences.motionEnabled)
			lifecycleOwner.lifecycle.addObserver(motionLifecycleObserver)
	}

	private fun controllerStateUpdated()
	{
		if (externalControllerActive) return
		controllerStateChangedCallback?.let { it(controllerState) }
	}

	fun dispatchKeyEvent(event: KeyEvent): Boolean
	{
		//Log.i("StreamSession", "key event $event")
		if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
			return false

		when(event.keyCode)
		{
			KeyEvent.KEYCODE_BUTTON_L2 -> {
				keyControllerState.l2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
			KeyEvent.KEYCODE_BUTTON_R2 -> {
				keyControllerState.r2State = if(event.action == KeyEvent.ACTION_DOWN) UByte.MAX_VALUE else 0U
				return true
			}
		}

		val touchPadKey= Preferences(context).touchPadKey.toString().toInt()

		val buttonMask: UInt = when(event.keyCode)
		{
			// dpad handled by MotionEvents
			//KeyEvent.KEYCODE_DPAD_LEFT -> ControllerState.BUTTON_DPAD_LEFT
			//KeyEvent.KEYCODE_DPAD_RIGHT -> ControllerState.BUTTON_DPAD_RIGHT
			//KeyEvent.KEYCODE_DPAD_UP -> ControllerState.BUTTON_DPAD_UP
			//KeyEvent.KEYCODE_DPAD_DOWN -> ControllerState.BUTTON_DPAD_DOWN
			touchPadKey -> ControllerState.BUTTON_TOUCHPAD
			KeyEvent.KEYCODE_BUTTON_A -> if(swapCrossMoon) ControllerState.BUTTON_MOON else ControllerState.BUTTON_CROSS
			KeyEvent.KEYCODE_BUTTON_B -> if(swapCrossMoon) ControllerState.BUTTON_CROSS else ControllerState.BUTTON_MOON
			KeyEvent.KEYCODE_BUTTON_X -> if(swapCrossMoon) ControllerState.BUTTON_PYRAMID else ControllerState.BUTTON_BOX
			KeyEvent.KEYCODE_BUTTON_Y -> if(swapCrossMoon) ControllerState.BUTTON_BOX else ControllerState.BUTTON_PYRAMID
			KeyEvent.KEYCODE_BUTTON_L1 -> ControllerState.BUTTON_L1
			KeyEvent.KEYCODE_BUTTON_R1 -> ControllerState.BUTTON_R1
			KeyEvent.KEYCODE_BUTTON_THUMBL -> ControllerState.BUTTON_L3
			KeyEvent.KEYCODE_BUTTON_THUMBR -> ControllerState.BUTTON_R3
			KeyEvent.KEYCODE_BUTTON_SELECT -> ControllerState.BUTTON_SHARE
			KeyEvent.KEYCODE_BUTTON_START -> ControllerState.BUTTON_OPTIONS
			KeyEvent.KEYCODE_BUTTON_C -> ControllerState.BUTTON_PS
			KeyEvent.KEYCODE_BUTTON_MODE -> ControllerState.BUTTON_PS
			else -> return false
		}

		keyControllerState.buttons = keyControllerState.buttons.run {
			when(event.action)
			{
				KeyEvent.ACTION_DOWN -> this or buttonMask
				KeyEvent.ACTION_UP -> this and buttonMask.inv()
				else -> this
			}
		}

		controllerStateUpdated()
		return true
	}

	fun onGenericMotionEvent(event: MotionEvent): Boolean
	{
//		if(preferences.touchMouseEnabled&&(event.source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE)){
////			Log.i("axixi","类型："+event.action)
//			if(event.action==MotionEvent.ACTION_HOVER_ENTER||event.action==MotionEvent.ACTION_HOVER_MOVE){
//				keyControllerState.buttons=keyControllerState.buttons.run {
//					this or ControllerState.BUTTON_TOUCHPAD
//				}
//				controllerStateUpdated()
//				Handler().postDelayed({
//					keyControllerState.buttons=keyControllerState.buttons.run {
//						this and ControllerState.BUTTON_TOUCHPAD.inv()
//					}
//					controllerStateUpdated()
//				}, 100)
//
//			}
//			return true
//		}
		if(event.source and InputDevice.SOURCE_CLASS_JOYSTICK != InputDevice.SOURCE_CLASS_JOYSTICK)
			return false
		fun Float.signedAxis() = (this * Short.MAX_VALUE).toInt().toShort()
		fun Float.unsignedAxis() = (this * UByte.MAX_VALUE.toFloat()).toUInt().toUByte()
		motionControllerState.leftX = event.getAxisValue(MotionEvent.AXIS_X).signedAxis()
		motionControllerState.leftY = event.getAxisValue(MotionEvent.AXIS_Y).signedAxis()
		motionControllerState.rightX = event.getAxisValue(MotionEvent.AXIS_Z).signedAxis()
		motionControllerState.rightY = event.getAxisValue(MotionEvent.AXIS_RZ).signedAxis()
		motionControllerState.l2State = event.getAxisValue(MotionEvent.AXIS_LTRIGGER).unsignedAxis()
		motionControllerState.r2State = event.getAxisValue(MotionEvent.AXIS_RTRIGGER).unsignedAxis()
		motionControllerState.buttons = motionControllerState.buttons.let {
			val dpadX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
			val dpadY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
			val dpadButtons =
				(if(dpadX > 0.5f) ControllerState.BUTTON_DPAD_RIGHT else 0U) or
						(if(dpadX < -0.5f) ControllerState.BUTTON_DPAD_LEFT else 0U) or
						(if(dpadY > 0.5f) ControllerState.BUTTON_DPAD_DOWN else 0U) or
						(if(dpadY < -0.5f) ControllerState.BUTTON_DPAD_UP else 0U)
			it and (ControllerState.BUTTON_DPAD_RIGHT or
					ControllerState.BUTTON_DPAD_LEFT or
					ControllerState.BUTTON_DPAD_DOWN or
					ControllerState.BUTTON_DPAD_UP).inv() or
					dpadButtons
		}
		//Log.i("StreamSession", "motionEvent => $motionControllerState")
		controllerStateUpdated()
		return true
	}
}