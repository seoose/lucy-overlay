package com.syseo.lucyoverlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

@SuppressLint("ClickableViewAccessibility")
class FloatingPetWindow(
    context: Context,
    private val windowManager: WindowManager,
    val petId: String,
    private val sheet: SpriteSheet,
    startX: Int,
    startY: Int,
    edgeOnly: Boolean
) {
    private enum class Edge { TOP, RIGHT, BOTTOM, LEFT }

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setImageBitmap(sheet.frame(SpriteSheet.Motion.IDLE, 0))
    }

    // Spritesheets are loaded at 1/2 res; scale ~1.0 keeps on-screen size near original.
    private val displayScale = 1.0f
    private val displayW = (sheet.cellW * displayScale).toInt()
    private val displayH = (sheet.cellH * displayScale).toInt()

    private val layoutParams = WindowManager.LayoutParams(
        displayW,
        displayH,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = startX
        y = startY
    }

    private var edgeOnlyMode = edgeOnly
    private var edge = Edge.BOTTOM
    private var posX = startX.toFloat()
    private var posY = startY.toFloat()
    private var vx = 0f
    private var vy = 0f
    private var speed = 2.2f
    private var clockwise = true
    private var motion = SpriteSheet.Motion.IDLE
    private var frameIndex = 0
    private var frameTimerMs = 0L
    private var stateTimerMs = 0L
    private var nextDecisionMs = Random.nextLong(900, 2200)
    private var walking = true
    private var dragging = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var paramStartX = 0
    private var paramStartY = 0

    private var screenW = context.resources.displayMetrics.widthPixels
    private var screenH = context.resources.displayMetrics.heightPixels
    @Volatile private var destroyed = false

    private var lastLayoutX = Int.MIN_VALUE
    private var lastLayoutY = Int.MIN_VALUE
    private var lastDrawnMotion: SpriteSheet.Motion? = null
    private var lastDrawnFrame = -1

    init {
        if (edgeOnlyMode) {
            snapToNearestEdge(posX, posY)
            walking = true
            motion = facingMotion()
        } else {
            commitPos(force = true)
            walking = true
            val angle = Random.nextDouble(0.0, Math.PI * 2)
            val spd = Random.nextDouble(1.4, 2.8)
            vx = (kotlin.math.cos(angle) * spd).toFloat()
            vy = (kotlin.math.sin(angle) * spd).toFloat()
            motion = facingMotion()
        }
        drawFrame(force = true)

        imageView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    walking = false
                    vx = 0f
                    vy = 0f
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    paramStartX = layoutParams.x
                    paramStartY = layoutParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    posX = (paramStartX + (event.rawX - touchStartX)).coerceIn(0f, maxX().toFloat())
                    posY = (paramStartY + (event.rawY - touchStartY)).coerceIn(0f, maxY().toFloat())
                    commitPos(force = true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (edgeOnlyMode) snapToNearestEdge(posX, posY)
                    if (hypot(
                            (event.rawX - touchStartX).toDouble(),
                            (event.rawY - touchStartY).toDouble()
                        ) < 12
                    ) {
                        walking = false
                        motion = SpriteSheet.Motion.WAVE
                        frameIndex = 0
                        stateTimerMs = 0
                        nextDecisionMs = 1200
                        drawFrame(force = true)
                    } else {
                        walking = true
                        if (!edgeOnlyMode) {
                            vx = Random.nextDouble(-2.0, 2.0).toFloat()
                            vy = Random.nextDouble(-2.0, 2.0).toFloat()
                        }
                        motion = facingMotion()
                        nextDecisionMs = Random.nextLong(1600, 3600)
                        drawFrame(force = true)
                    }
                    commitPos(force = true)
                    true
                }
                else -> false
            }
        }
        windowManager.addView(imageView, layoutParams)
    }

    fun setEdgeOnly(enabled: Boolean) {
        if (edgeOnlyMode == enabled) return
        edgeOnlyMode = enabled
        if (enabled) {
            vx = 0f
            vy = 0f
            snapToNearestEdge(posX, posY)
            walking = false
            motion = SpriteSheet.Motion.IDLE
            drawFrame(force = true)
        }
    }

    fun updateScreenSize(w: Int, h: Int) {
        screenW = w
        screenH = h
        posX = posX.coerceIn(0f, maxX().toFloat())
        posY = posY.coerceIn(0f, maxY().toFloat())
        if (edgeOnlyMode) snapToNearestEdge(posX, posY) else commitPos(force = true)
    }

    fun tick(dtMs: Long) {
        if (destroyed || dragging) return

        stateTimerMs += dtMs
        frameTimerMs += dtMs

        val frameDuration = when (motion) {
            SpriteSheet.Motion.RUN_RIGHT, SpriteSheet.Motion.RUN_LEFT -> 70L
            SpriteSheet.Motion.JUMP -> 90L
            SpriteSheet.Motion.WAVE -> 120L
            else -> 140L
        }
        var frameChanged = false
        if (frameTimerMs >= frameDuration) {
            frameTimerMs = 0
            frameIndex++
            frameChanged = true
        }

        if (stateTimerMs >= nextDecisionMs) {
            pickNextBehavior()
            frameChanged = true
        }

        if (walking) {
            if (edgeOnlyMode) moveAlongEdge(dtMs) else moveFree(dtMs)
            val nextMotion = facingMotion()
            if (nextMotion != motion) {
                motion = nextMotion
                frameChanged = true
            }
        }

        if (frameChanged) drawFrame(force = false)
        commitPos(force = false)
    }

    private fun drawFrame(force: Boolean) {
        if (destroyed) return
        val shownIndex = frameIndex.mod(motion.frames)
        if (!force && lastDrawnMotion == motion && lastDrawnFrame == shownIndex) return
        lastDrawnMotion = motion
        lastDrawnFrame = shownIndex
        imageView.setImageBitmap(sheet.frame(motion, shownIndex))
    }

    private fun moveFree(dtMs: Long) {
        val scale = dtMs / 16f
        posX += vx * scale
        posY += vy * scale
        val maxXf = maxX().toFloat()
        val maxYf = maxY().toFloat()
        if (posX < 0f) {
            posX = 0f
            vx = abs(vx)
        } else if (posX > maxXf) {
            posX = maxXf
            vx = -abs(vx)
        }
        if (posY < 0f) {
            posY = 0f
            vy = abs(vy)
        } else if (posY > maxYf) {
            posY = maxYf
            vy = -abs(vy)
        }
    }

    private fun moveAlongEdge(dtMs: Long) {
        val dist = (speed * (dtMs / 16f)).coerceAtLeast(0.5f)
        val nudge = dist.coerceAtLeast(1f)
        val maxXf = maxX().toFloat()
        val maxYf = maxY().toFloat()

        when (edge) {
            Edge.TOP -> {
                posY = 0f
                posX += if (clockwise) dist else -dist
                when {
                    posX >= maxXf -> {
                        posX = maxXf
                        edge = Edge.RIGHT
                        posY = nudge
                        clockwise = true
                    }
                    posX <= 0f -> {
                        posX = 0f
                        edge = Edge.LEFT
                        posY = nudge
                        clockwise = false
                    }
                }
            }
            Edge.RIGHT -> {
                posX = maxXf
                posY += if (clockwise) dist else -dist
                when {
                    posY >= maxYf -> {
                        posY = maxYf
                        edge = Edge.BOTTOM
                        posX = (maxXf - nudge).coerceAtLeast(0f)
                        clockwise = true
                    }
                    posY <= 0f -> {
                        posY = 0f
                        edge = Edge.TOP
                        posX = (maxXf - nudge).coerceAtLeast(0f)
                        clockwise = false
                    }
                }
            }
            Edge.BOTTOM -> {
                posY = maxYf
                posX += if (clockwise) -dist else dist
                when {
                    posX <= 0f -> {
                        posX = 0f
                        edge = Edge.LEFT
                        posY = (maxYf - nudge).coerceAtLeast(0f)
                        clockwise = true
                    }
                    posX >= maxXf -> {
                        posX = maxXf
                        edge = Edge.RIGHT
                        posY = (maxYf - nudge).coerceAtLeast(0f)
                        clockwise = false
                    }
                }
            }
            Edge.LEFT -> {
                posX = 0f
                posY += if (clockwise) -dist else dist
                when {
                    posY <= 0f -> {
                        posY = 0f
                        edge = Edge.TOP
                        posX = nudge.coerceAtMost(maxXf)
                        clockwise = true
                    }
                    posY >= maxYf -> {
                        posY = maxYf
                        edge = Edge.BOTTOM
                        posX = nudge.coerceAtMost(maxXf)
                        clockwise = false
                    }
                }
            }
        }
    }

    private fun facingMotion(): SpriteSheet.Motion {
        if (!walking) return motion
        if (!edgeOnlyMode) {
            return if (vx >= 0f) SpriteSheet.Motion.RUN_RIGHT else SpriteSheet.Motion.RUN_LEFT
        }
        return when (edge) {
            Edge.TOP -> if (clockwise) SpriteSheet.Motion.RUN_RIGHT else SpriteSheet.Motion.RUN_LEFT
            Edge.BOTTOM -> if (clockwise) SpriteSheet.Motion.RUN_LEFT else SpriteSheet.Motion.RUN_RIGHT
            Edge.RIGHT -> if (clockwise) SpriteSheet.Motion.RUN_RIGHT else SpriteSheet.Motion.RUN_LEFT
            Edge.LEFT -> if (clockwise) SpriteSheet.Motion.RUN_LEFT else SpriteSheet.Motion.RUN_RIGHT
        }
    }

    private fun pickNextBehavior() {
        stateTimerMs = 0
        frameIndex = 0
        frameTimerMs = 0

        when (Random.nextInt(100)) {
            in 0..62 -> {
                walking = true
                speed = Random.nextDouble(1.6, 3.0).toFloat()
                if (edgeOnlyMode) {
                    if (Random.nextFloat() < 0.25f) clockwise = !clockwise
                } else {
                    val angle = Random.nextDouble(0.0, Math.PI * 2)
                    val spd = Random.nextDouble(1.4, 3.0)
                    vx = (cos(angle) * spd).toFloat()
                    vy = (sin(angle) * spd).toFloat()
                }
                motion = facingMotion()
                nextDecisionMs = Random.nextLong(1800, 4500)
            }
            in 63..78 -> {
                walking = false
                vx = 0f
                vy = 0f
                motion = SpriteSheet.Motion.IDLE
                nextDecisionMs = Random.nextLong(900, 2400)
            }
            in 79..90 -> {
                walking = false
                vx = 0f
                vy = 0f
                motion = SpriteSheet.Motion.WAVE
                nextDecisionMs = Random.nextLong(900, 1600)
            }
            else -> {
                walking = false
                vx = 0f
                vy = 0f
                motion = SpriteSheet.Motion.JUMP
                nextDecisionMs = Random.nextLong(700, 1200)
            }
        }
    }

    private fun snapToNearestEdge(x: Float, y: Float) {
        val maxXf = maxX().toFloat()
        val maxYf = maxY().toFloat()
        val dTop = y
        val dBottom = abs(maxYf - y)
        val dLeft = x
        val dRight = abs(maxXf - x)
        edge = when (minOf(dTop, dBottom, dLeft, dRight)) {
            dTop -> Edge.TOP
            dBottom -> Edge.BOTTOM
            dLeft -> Edge.LEFT
            else -> Edge.RIGHT
        }
        when (edge) {
            Edge.TOP -> {
                posY = 0f
                posX = x.coerceIn(0f, maxXf)
            }
            Edge.BOTTOM -> {
                posY = maxYf
                posX = x.coerceIn(0f, maxXf)
            }
            Edge.LEFT -> {
                posX = 0f
                posY = y.coerceIn(0f, maxYf)
            }
            Edge.RIGHT -> {
                posX = maxXf
                posY = y.coerceIn(0f, maxYf)
            }
        }
        when (edge) {
            Edge.TOP, Edge.BOTTOM -> {
                if (posX <= 0f) posX = 1f
                if (posX >= maxXf) posX = (maxXf - 1f).coerceAtLeast(0f)
            }
            Edge.LEFT, Edge.RIGHT -> {
                if (posY <= 0f) posY = 1f
                if (posY >= maxYf) posY = (maxYf - 1f).coerceAtLeast(0f)
            }
        }
        commitPos(force = true)
    }

    private fun commitPos(force: Boolean) {
        if (destroyed) return
        val x = posX.toInt().coerceIn(0, maxX())
        val y = posY.toInt().coerceIn(0, maxY())
        if (!force && x == lastLayoutX && y == lastLayoutY) return
        layoutParams.x = x
        layoutParams.y = y
        lastLayoutX = x
        lastLayoutY = y
        runCatching { windowManager.updateViewLayout(imageView, layoutParams) }
    }

    private fun maxX() = (screenW - displayW).coerceAtLeast(0)
    private fun maxY() = (screenH - displayH).coerceAtLeast(0)

    fun destroy() {
        if (destroyed) return
        destroyed = true
        imageView.setOnTouchListener(null)
        imageView.setImageDrawable(null)
        runCatching {
            if (imageView.isAttachedToWindow) {
                windowManager.removeViewImmediate(imageView)
            }
        }
        runCatching { sheet.recycle() }
    }
}
