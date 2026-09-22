package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerProtocolContractTest {
    private val channel = File(
        "src/main/java/io/github/trvny/wambridge/mobile/SamsungWamChannel.kt",
    ).readText()
    private val remote = File(
        "src/main/java/io/github/trvny/wambridge/mobile/SpeakerRemote.kt",
    ).readText()

    @Test
    fun persistentChannelUsesMeasuredCommandAndReplySpelling() {
        assertTrue(channel.contains("fun setSleepTimer(seconds: Int)"))
        assertTrue(channel.contains("\"SetSleepTimer\""))
        assertTrue(channel.contains("Argument(\"option\", command.option, Kind.STR)"))
        assertTrue(channel.contains("Argument(\"sleeptime\", command.seconds.toString(), Kind.DEC)"))
        assertTrue(channel.contains("fun requestSleepTimer()"))
        assertTrue(channel.contains("\"GetSleepTimer\""))
        assertTrue(channel.contains("method.equals(\"SleepTime\", ignoreCase = true)"))
        assertTrue(channel.contains("onSleepTimerChanged"))
    }

    @Test
    fun sleepCommandsNeverWakeTheSpeaker() {
        val start = channel.indexOf("fun setSleepTimer(seconds: Int)")
        val end = channel.indexOf("fun ", start + 4)
        val block = channel.substring(start, end)
        assertFalse(block.contains("powerOn = true"))
    }

    @Test
    fun idleRemoteCanSetAndReadTheSameState() {
        assertTrue(remote.contains("fun setSleepTimer("))
        assertTrue(remote.contains("fun readSleepTimer("))
        assertTrue(remote.contains("\"SetSleepTimer\""))
        assertTrue(remote.contains("\"GetSleepTimer\""))
        assertTrue(remote.contains("return readSleepTimer(context, speakerIp)"))
        assertTrue(remote.contains("sleepTimerState("))
    }
}
