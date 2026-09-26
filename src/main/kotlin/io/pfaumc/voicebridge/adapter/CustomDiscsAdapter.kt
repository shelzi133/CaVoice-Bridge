package io.pfaumc.voicebridge.adapter

import io.pfaumc.voicebridge.BridgeMetrics
import io.pfaumc.voicebridge.VoiceBridgePlugin
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import space.subkek.customdiscs.api.CustomDiscsAPI
import space.subkek.customdiscs.api.LavaPlayerManager
import space.subkek.customdiscs.api.event.LavaPlayerStartPlayingEvent
import space.subkek.customdiscs.api.event.LavaPlayerStopPlayingEvent
import su.plo.slib.api.server.position.ServerPos3d
import su.plo.voice.api.encryption.EncryptionException
import su.plo.voice.api.server.audio.line.ServerSourceLine
import su.plo.voice.api.server.audio.source.ServerStaticSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

class CustomDiscsAdapter(
    private val plugin: VoiceBridgePlugin,
    private val pvAdapter: PvAdapter
) : Listener {

    private val logger = Logger.getLogger("VoiceBridge-Discs")

    private val api: CustomDiscsAPI? = CustomDiscsAPI.get()

    @Volatile
    private var discsLine: ServerSourceLine? = null

    private class DiscSource(val source: ServerStaticSource, val distance: Short) {
        val sequence = AtomicLong(0)
    }

    private val sources = ConcurrentHashMap<String, DiscSource>()

    val isActive: Boolean get() = api != null

    fun start() {
        if (api == null) {
            logger.info("CustomDiscs-SVC not found — music disc bridging disabled")
            return
        }

        Bukkit.getPluginManager().registerEvents(this, plugin)
        api.lavaPlayerManager.registerPacketHandler(plugin, this::onDiscFrame)
        createDiscsLine()
        logger.info("CustomDiscs-SVC detected — bridging music discs to Plasmo Voice")
    }

    fun shutdown() {
        api?.lavaPlayerManager?.unregisterPacketHandlers(plugin)
        LavaPlayerStartPlayingEvent.getHandlerList().unregister(this)
        LavaPlayerStopPlayingEvent.getHandlerList().unregister(this)
        for (key in sources.keys.toList()) {
            endSource(key)
        }
        discsLine = null
    }

    @EventHandler(ignoreCancelled = true)
    fun onDiscStart(event: LavaPlayerStartPlayingEvent) {
        registerSource(event.block)
    }

    @EventHandler
    fun onDiscStop(event: LavaPlayerStopPlayingEvent) {
        endSource(blockKey(event.block))
    }

    private fun registerSource(block: Block) {
        val api = api ?: return
        val line = discsLine ?: createDiscsLine() ?: return

        val channel = api.lavaPlayerManager.getAudioChannel(block) ?: run {
            logger.fine("No SVC audio channel found yet for disc block at ${block.location} — skipping")
            return
        }
        val distance = channel.distance.toInt().coerceIn(1, Short.MAX_VALUE.toInt()).toShort()

        val position = try {
            val world = pvAdapter.voiceServer.minecraftServer.getWorld(block.world)
            ServerPos3d(world, block.x + 0.5, block.y + 0.5, block.z + 0.5, 0f, 0f)
        } catch (e: Exception) {
            logger.warning("Could not resolve Plasmo Voice world for ${block.world.name}: ${e.message}")
            return
        }

        val staticSource = line.createStaticSource(position, true)
        sources[blockKey(block)] = DiscSource(staticSource, distance)
    }

    private fun onDiscFrame(
        @Suppress("UNUSED_PARAMETER") registration: LavaPlayerManager.HandlerRegistration,
        block: Block,
        data: ByteArray
    ): Boolean {
        val discSource = sources[blockKey(block)] ?: run {
            registerSource(block)
            sources[blockKey(block)]
        } ?: return true

        val encrypted = try {
            pvAdapter.voiceServer.defaultEncryption.encrypt(data)
        } catch (e: EncryptionException) {
            BridgeMetrics.droppedFrames.incrementAndGet()
            return true
        }

        val seq = discSource.sequence.incrementAndGet()
        discSource.source.sendAudioFrame(encrypted, seq, discSource.distance)
        BridgeMetrics.discFrames.incrementAndGet()
        return true
    }

    private fun endSource(key: String) {
        val discSource = sources.remove(key) ?: return
        discSource.source.sendAudioEnd(discSource.sequence.get(), discSource.distance)
        discSource.source.remove()
    }

    private fun createDiscsLine(): ServerSourceLine? {
        return try {
            pvAdapter.voiceServer.sourceLineManager.createBuilder(
                pvAdapter,
                "music_discs",
                "voicebridge.line.music_discs",
                "plasmovoice:textures/icons/speaker.png",
                2
            ).build().also { discsLine = it }
        } catch (e: Exception) {
            logger.warning("Failed to register Plasmo Voice source line for music discs: ${e.message}")
            null
        }
    }

    private fun blockKey(block: Block): String =
        "${block.world.uid}:${block.x}:${block.y}:${block.z}"
}
